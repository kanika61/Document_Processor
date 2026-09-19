# SuretySeven Document Processing Pipeline

A monolithic Spring Boot service that accepts document uploads, stores the file on local disk,
and runs a simulated (mock) extraction + validation pipeline asynchronously with automatic
retries. Implements the design in `IMPLEMENTATION_PLAN.md`.

## Stack

- Java 21, Spring Boot 3.3 (Web, Data JPA, Validation)
- MySQL 8+
- Local disk file storage behind a `FileStorage` interface

## Prerequisites

- JDK 21 — `brew install openjdk@21` on macOS if you don't have it
- Maven 3.9+ — `brew install maven`
- MySQL 8+ running locally — `brew install mysql && brew services start mysql`

## 1. Create the database

The app creates its own tables on startup (via `schema.sql`), but the **database itself**
must exist first:

```bash
mysql -u root -e "CREATE DATABASE IF NOT EXISTS document_processor;"
```

If your MySQL root user has a password, or you use a different user, set it via environment
variables before running the app (see Configuration below).

## 2. Build

```bash
# If JDK 21 isn't your default `java`, point Maven at it for this shell:
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS/Homebrew path

mvn clean package
```

This compiles the code, runs the unit test suite, and produces
`target/document-processor-1.0.0.jar`.

## 3. Run

```bash
java -jar target/document-processor-1.0.0.jar
```

The API listens on `http://localhost:8080`. On first boot it creates the `documents` and
`document_history` tables (see `src/main/resources/schema.sql`) and the
`./uploaded-documents/` directory for stored files, both relative to wherever you run the jar
from.

Or run directly with Maven during development:

```bash
mvn spring-boot:run
```

## Configuration

Everything is externalized in `src/main/resources/application.yml`, overridable via
environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `DB_HOST` | `localhost` | MySQL host |
| `DB_PORT` | `3306` | MySQL port |
| `DB_NAME` | `document_processor` | MySQL database name |
| `DB_USERNAME` | `root` | MySQL username |
| `DB_PASSWORD` | *(empty)* | MySQL password |

Processing behavior (retry count, thread pool sizing, storage path) is bound from the
`document-processing.*` keys in `application.yml` via `@ConfigurationProperties` — no Java
constants to hunt down if you want to change them:

```yaml
document-processing:
  max-retry-attempts: 3
  retry-backoff-ms: 1000
  executor:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 100
  storage:
    base-path: ./uploaded-documents
```

## API

### `POST /documents` — upload

`multipart/form-data` with:
- `file` — the PDF to upload (only `.pdf` is accepted)
- `documentType` — must be `FINANCIAL_STATEMENT` (the only value currently supported)
- `metadata` *(optional)* — JSON string, e.g. `{"sizeBytes": 204800}`; cross-checked against
  the actual upload size with a logged warning on mismatch, never a hard failure

```bash
curl -X POST http://localhost:8080/documents \
  -F "file=@statement.pdf;type=application/pdf" \
  -F "documentType=FINANCIAL_STATEMENT" \
  -F 'metadata={"sizeBytes":204800}'
```

Responses:
- `202 Accepted` — `{ "documentId": "DOC-...", "status": "UPLOADED" }` for a new document, or
  `{ "documentId": "DOC-...", "status": "PROCESSING" }` if this re-uploads a file that
  previously failed with a retryable reason (`TIMEOUT`/`ERROR`) — processing is re-triggered
  on the **same** `documentId`, no new row is created.
- `409 Conflict` — the file's content hash already exists and is `UPLOADED`/`PROCESSING`/
  `PROCESSED`, or is a `FAILED` document whose failure is deterministic
  (`VALIDATION_FAILED`/`INVALID_RESULT` — re-processing the identical bytes always produces the
  identical result, so it's rejected instead of silently no-op'ing).
- `400 Bad Request` — unknown `documentType` or non-`.pdf` file. No row is created, no file is
  written to disk, in either case.

### `POST /documents/{id}/retry` — explicitly retry a failed document

No file upload needed — reuses the file already on disk via `FileStorage.read`. `404` if the
`documentId` doesn't exist; `400 { "message": "This document cannot be retried." }` if
`status` isn't `FAILED` or `failureReason` isn't retryable; otherwise `202 Accepted` with
`{ "documentId": "...", "status": "PROCESSING" }`, reusing the same `documentId`.

```bash
curl -X POST http://localhost:8080/documents/DOC-.../retry
```

This is in addition to (not instead of) the re-upload path above — re-uploading the identical
file also re-triggers a retryable `FAILED` document on its existing `documentId`. Use `/retry`
when you don't have the original file bytes on hand client-side.

### `GET /documents/{id}` — detail

```json
{
  "documentId": "DOC-...",
  "status": "PROCESSED",
  "documentType": "FINANCIAL_STATEMENT",
  "filename": "statement.pdf",
  "fileSizeBytes": 204800,
  "createdAt": "...",
  "updatedAt": "...",
  "extractedResult": { "companyName": "...", "registrationNumber": "...", "...": "..." },
  "failureReason": null
}
```

### `GET /documents/{id}/history` — full state-transition timeline, oldest first

```json
[
  { "status": "UPLOADED", "reason": null, "timestamp": "..." },
  { "status": "PROCESSING", "reason": null, "timestamp": "..." },
  { "status": "FAILED", "reason": "TIMEOUT", "timestamp": "..." },
  { "status": "PROCESSING", "reason": null, "timestamp": "..." },
  { "status": "PROCESSED", "reason": null, "timestamp": "..." }
]
```

A `FAILED` row is terminal only if it's the *last* row — a `FAILED` followed by a `PROCESSING`
row is an automatic retry in progress.

### `GET /documents` — paginated list

Query params, all optional and combinable: `status`, `documentType`, `uploadDate`
(`YYYY-MM-DD`, matches documents created on that calendar day in UTC), `page`, `size` (default
20). Returns the standard Spring Data `Page` envelope (`content`, `totalElements`,
`totalPages`, `number`, `size`, ...).

## How processing works

1. Upload is fully synchronous: hash the bytes (SHA-256), check for a duplicate, write to disk
   (`./uploaded-documents/{contentHash}.pdf`), insert the `documents` + `document_history`
   rows.
2. The controller then calls `DocumentProcessingService.processDocumentAsync(documentId)` — a
   `@Async` method on its own Spring bean, backed by a dedicated `documentProcessingExecutor`
   thread pool (core 4 / max 8 / queue 100, all configurable).
3. Each attempt calls the mock `PdfProcessor`, which randomly simulates `SUCCESS` / `TIMEOUT` /
   `ERROR` / `INVALID_RESULT`. On `SUCCESS`, the extracted fields are deterministic for a given
   file's bytes (not random per attempt) — this is what makes the retryability rule below hold.
4. Retry rule, derived from `failure_reason` rather than a separate enum:
   - `TIMEOUT` / `ERROR` → transient, retried up to `max-retry-attempts` (default 3) with a
     fixed backoff (default 1s) between attempts.
   - `VALIDATION_FAILED: ...` / `INVALID_RESULT` → deterministic for that file, never retried
     automatically, and rejected with `409` if the same file is re-uploaded.
5. Every transition (`UPLOADED`, `PROCESSING`, `FAILED`, `PROCESSED`) is written to
   `document_history` as it happens (not buffered until the end), so `GET /history` reflects
   in-flight processing in real time.

## Testing

```bash
mvn test
```

38 tests, all unit-level (Mockito-mocked repositories/collaborators, no live database
required):

- `DocumentServiceTest` — the synchronous upload flow: valid upload, invalid `documentType`,
  non-PDF rejection, all three duplicate-content-hash branches (active status, retryable
  `FAILED`, non-retryable `FAILED`), and the four `POST /retry` outcomes (not found, wrong
  status, non-retryable reason, retryable reason).
- `DocumentProcessingServiceTest` — the async retry loop: successful processing, a `SUCCESS`
  extraction that fails validation (no retry), `TIMEOUT` exhausting all 3 attempts, a
  `TIMEOUT`/`TIMEOUT`/`SUCCESS` sequence ending `PROCESSED`, and `INVALID_RESULT` (no retry).
- `FinancialStatementValidatorTest` — each validation rule individually and in combination.
- `LocalDiskFileStorageTest` — write/read round-trip.
- `PdfProcessorTest` — same file bytes always produce the same extracted fields on `SUCCESS`
  (the invariant the retry rule depends on), while different files can differ.

The full end-to-end flow (real MySQL, real disk, real `@Async` thread pool) was also manually
verified against a running instance: valid upload → `PROCESSED` with extraction; duplicate
upload → `409`; a document that failed with `TIMEOUT` twice then a `VALIDATION_FAILED` →
history showed `UPLOADED → PROCESSING → FAILED(TIMEOUT) → PROCESSING → FAILED(VALIDATION_FAILED)`;
re-uploading that file → `409` non-retryable; and a document that failed `ERROR`/`TIMEOUT`/
`TIMEOUT` and hit the terminal `FAILED` state → re-uploading it re-triggered processing on the
same `documentId` (verified against the DB — still exactly one row) and it reached `PROCESSED`.

## Class structure

- `DocumentController` — REST endpoints only, no business logic.
- `DocumentService` — the synchronous upload flow (hashing, duplicate detection, request-level
  validation, disk write, initial insert) plus the read-side queries (`GET` endpoints).
- `DocumentProcessingService` — the asynchronous flow only: the retry loop, calling the
  processor, dispatching to the validator, and all `document_history` writes during processing.
  Lives on a separate bean from `DocumentService`; the controller calls it directly to avoid
  Spring's self-invocation proxy pitfall with `@Async`.
- `FileStorage` (interface) + `LocalDiskFileStorage` — swappable storage backend.
- `DocumentProcessor` (interface) + `PdfProcessor` + `DocumentProcessorRegistry` — one
  implementation per file **format**.
- `Validator` (interface) + `FinancialStatementValidator` + `ValidatorRegistry` — one
  implementation per document **type**.
- `DocumentRepository`, `DocumentHistoryRepository` — plain Spring Data JPA repositories.

## Engineering questions (things described here, not built)

**Scaling to ~1M documents/day.** The in-process `@Async` executor and thread pool would be
replaced with a real queue (SQS, or RabbitMQ/Kafka) and a separate worker/consumer process
pulling from it. The HTTP contract (`POST /documents`, the `GET` endpoints) would not need to
change — only what sits behind the async trigger. File storage would move to S3, referenced by
object key instead of a local filesystem path, behind the same `FileStorage` interface — that
interface exists specifically so this swap doesn't touch business logic.

**Status updates at scale.** The detail page currently polls every 3s while a document is in
flight. At that scale, polling would be replaced with Server-Sent Events: the worker publishes
each status change (e.g. via the queue or Redis pub/sub) and a streaming endpoint (Spring
`SseEmitter`) pushes it to subscribed browsers, so idle viewers cost nothing. The existing
`GET /documents/{id}` and `/history` endpoints stay for the initial load.

**Known limitation — orphaned files.** If the disk write in the upload flow succeeds but the
subsequent `documents` row insert fails, the file is left on disk with no corresponding row.
This is treated as acceptable for this scope; a production system would run a periodic
reconciliation job (list files on disk / in S3, diff against `documents.content_hash`, and
clean up unreferenced ones).

**Known limitation — no horizontal scaling of the app tier.** Local disk storage means
multiple app instances wouldn't share a filesystem; this is exactly why `FileStorage` is an
interface rather than direct file I/O calls scattered through the code — swapping in
`S3FileStorage` is a contained, single-class change.

## Out of scope (by design)

No SQS/Lambda/S3, no separate `FailureCategory` enum (retryability is derived from
`failure_reason` text), no filesystem-write rollback/retry loop, no second file-format
processor — matching the scope boundaries in `IMPLEMENTATION_PLAN.md`.

> Note: `IMPLEMENTATION_PLAN.md` originally specified no dedicated retry endpoint (retry only
> via re-upload). `POST /documents/{id}/retry` was added afterward as an explicit follow-up
> request, reusing the same `isRetryable` check and `processDocumentAsync` call as the
> re-upload path — no new processing logic.
