# SuretySeven Document Processing Pipeline — Implementation Plan

This document is the finalized design for the backend. Implement exactly what's described here — do not introduce SQS, Lambda, S3, or other infrastructure not mentioned below; this is a deliberate scope decision for a 15-20 hour take-home.

## Stack
- Java, Spring Boot
- MySQL
- Local disk for file storage (see rationale below)

## Storage decision (read this before writing any code)
Files are stored on **local disk**, not as a MySQL BLOB, not on S3.
- File bytes are written **synchronously**, on the request thread, named by content hash (e.g. `./uploaded-documents/{contentHash}.pdf`). This is fast, local I/O — no reason to make it async.
- Only the **mock processing step** (extraction + validation + retries) runs asynchronously, because that's the part that's slow/unreliable (simulated timeouts, errors, retries).
- File storage must sit behind a `FileStorage` interface (`write(hash, bytes) -> path`, `read(path) -> bytes`) so the backing implementation (disk vs. S3 vs. DB) is swappable without touching business logic. Only one implementation (`LocalDiskFileStorage`) needs to actually exist for this assignment.

## Database schema

```sql
CREATE TABLE documents (
    document_id      VARCHAR(50) PRIMARY KEY,       -- e.g. "DOC-<uuid>" or "DOC-<sequence>"
    filename          VARCHAR(255) NOT NULL,
    document_type     VARCHAR(50) NOT NULL,          -- e.g. "FINANCIAL_STATEMENT"
    content_hash      VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 hex of file bytes
    file_path         VARCHAR(500) NOT NULL,         -- path on local disk
    status            VARCHAR(20) NOT NULL,          -- UPLOADED, PROCESSING, PROCESSED, FAILED
    retry_count       INT DEFAULT 0,
    extracted_result  JSON,                          -- populated only when status = PROCESSED
    failure_reason    VARCHAR(500),                  -- e.g. "TIMEOUT", "ERROR", "INVALID_RESULT",
                                                       -- or "VALIDATION_FAILED: companyName is required"
    file_size_bytes   BIGINT,                         -- from optional upload metadata, or computed server-side if absent
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL,

    INDEX idx_document_type (document_type),
    INDEX idx_status (status)
    -- content_hash is already indexed via the UNIQUE constraint
);

CREATE TABLE document_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id   VARCHAR(50) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    reason        VARCHAR(500),                       -- populated only on FAILED entries
    timestamp     TIMESTAMP NOT NULL,

    FOREIGN KEY (document_id) REFERENCES documents(document_id),
    INDEX idx_document_id (document_id)
);
```

Do NOT select `file_content`/large fields with `SELECT *` on list endpoints — there is no large blob column in this schema (file lives on disk), but keep list/status DTOs projected to only the columns they need regardless.

## Retryability rule (do not add a separate enum for this)

Retryability is derived from `failure_reason`, not stored as its own field:
- `failure_reason` starts with `TIMEOUT` or `ERROR` → **retryable** (processor/network flakiness — a different attempt on the same file can succeed).
- `failure_reason` starts with `INVALID_RESULT` or `VALIDATION_FAILED` → **not retryable** (deterministic outcome for a given file — the mock processor always extracts the same fields for the same file, so re-running produces the identical failure).

```java
private boolean isRetryable(Document doc) {
    if (doc.getStatus() != Status.FAILED || doc.getFailureReason() == null) return false;
    return doc.getFailureReason().startsWith("TIMEOUT") || doc.getFailureReason().startsWith("ERROR");
}
```

## Request-level validation (at upload time, before duplicate check)

- `documentType` must be one of a **fixed enum** (`DocumentType`), starting with `FINANCIAL_STATEMENT`. If the submitted value doesn't match a known enum constant, reject immediately with `400 Bad Request` — do not create a row, do not attempt processing. This also means there is always a `Validator` available for any `documentType` that made it past this check; the processing step never needs to handle an "unknown type" case.
- File format: only `.pdf` is accepted for now. Reject any other file extension/content-type with `400 Bad Request` at upload time (do not write to disk, do not insert a row). Keep the file-format check as its own small, isolated piece (e.g. a single method/strategy check) so adding a second format later doesn't require touching unrelated code — but do not build a second format's processor now.
- Optional metadata: accept a `metadata` field on the upload request representing the **document's file size** (e.g. `{ "sizeBytes": 204800 }` or similar). Store it as a column on `documents` (e.g. `file_size_bytes BIGINT`). If provided, it can optionally be cross-checked against the actual uploaded file's byte length as a sanity check (mismatch doesn't need to hard-fail the upload for this scope — logging a warning is enough). If not provided, compute and store the actual byte length server-side regardless, so the field is always populated for display in the UI.

## Upload endpoint — `POST /documents`

All steps below run **synchronously on the request thread** except step 6.

1. Read file bytes from the multipart request. Compute `contentHash = SHA-256(bytes)`.
2. Query `documents` by `content_hash`.
3. **If a row is found:**
   - `status` is `UPLOADED`, `PROCESSING`, or `PROCESSED` → return `409 Conflict` with `{ message, documentId, filename, status, uploadedAt }` (do not create a new row, do not touch disk).
   - `status` is `FAILED` and `isRetryable(doc)` is true → skip file write and insert (file is already on disk from the original upload); go directly to step 6 using the **existing** `documentId`. Return `202 Accepted` with `{ documentId, status: "PROCESSING" }`.
   - `status` is `FAILED` and not retryable → return `409 Conflict` with `{ message: "This document already failed and reprocessing will not change the result", documentId, status, failureReason }`.
4. **If no row is found:** write the file to disk via `FileStorage.write(contentHash, bytes)`.
   - If this throws, return `500` with a clean, non-technical error message (e.g. "Upload failed, please try again"). Do not insert anything into the DB in this case — there is nothing to clean up.
5. Insert a new `documents` row: `documentId` (generate fresh, format `DOC-<uuid>`), `filename`, `documentType`, `contentHash`, `filePath`, `status = UPLOADED`, timestamps. If this insert fails, return `500` with a clean error message (the file may be orphaned on disk in this rare case — acceptable for this scope; document it in README as a known limitation, do not build cleanup logic for it).
6. Call `processingService.processDocumentAsync(documentId)` — this must return immediately (see Async section). Return `202 Accepted` with `{ documentId, status: "UPLOADED" }`.

Also insert a `document_history` row with `status = UPLOADED` at the same time as the step-5 insert.

## Async processing — `DocumentProcessingService.processDocumentAsync`

Must be annotated `@Async` on a **separate Spring bean** from whatever calls it (never call it via `this.` from within the same class — that silently bypasses the async proxy). Configure a dedicated `ThreadPoolTaskExecutor` bean for this (core size ~4, max ~8, queue capacity ~100) and reference it by name in `@Async("documentProcessingExecutor")`.

```
processDocumentAsync(documentId):
    update status -> PROCESSING, append document_history row (status=PROCESSING, reason=null)
    fetch the Document row (need documentType, filePath)
    attempt = 0
    while attempt < 3:
        attempt += 1
        result = mockProcessor.process(documentId)   # randomly returns SUCCESS / TIMEOUT / ERROR / INVALID_RESULT
        if result.outcome == SUCCESS:
            validation = validatorRegistry.get(documentType).validate(result.extractedData)
            if validation.isValid():
                update status -> PROCESSED, extracted_result = result.extractedData
                append document_history row (status=PROCESSED, reason=null)
            else:
                update status -> FAILED, failure_reason = "VALIDATION_FAILED: " + validation.errors.join(", ")
                append document_history row (status=FAILED, reason=<that string>)
            return
        if result.outcome == INVALID_RESULT:
            update status -> FAILED, failure_reason = "INVALID_RESULT"
            append document_history row (status=FAILED, reason="INVALID_RESULT")
            return   # do not retry
        if result.outcome in (TIMEOUT, ERROR):
            # IMPORTANT: log FAILED on every failed attempt, not just the final one — this matches
            # the assignment's own section 7 example (UPLOADED -> PROCESSING -> FAILED -> PROCESSING -> PROCESSED)
            # and is what lets the UI timeline show a failure occurring BEFORE a retry, not only at the end.
            update status -> FAILED, failure_reason = result.outcome.name()   # "TIMEOUT" or "ERROR"
            append document_history row (status=FAILED, reason=result.outcome.name())
            if attempt == 3:
                return   # terminal — status stays FAILED, no further row is written
            else:
                update status -> PROCESSING
                append document_history row (status=PROCESSING, reason=null)   # about to retry
                # optional: sleep a short fixed/backoff delay before looping (e.g. 1s, 2s)
```

Note on reading this back for the timeline UI: a `FAILED` row is **terminal** if and only if it is the *last* row in that document's history (no `PROCESSING` row after it). A `FAILED` row followed by a `PROCESSING` row represents an internal retry that the system already attempted automatically — both an in-loop retry and a later manual `/retry` call or re-upload produce the same shape (`FAILED` → `PROCESSING` → ...), so the frontend does not need to distinguish between them.

Wrap the body in try/catch for unexpected exceptions (treat as `ERROR` outcome) — **do not let exceptions escape this method uncaught**, since exceptions thrown inside a `void @Async` method are silently swallowed by Spring and the document would be stuck in `PROCESSING` forever with no failure ever recorded.

## Mock processor — pluggable by file type

Introduce a `DocumentProcessor` interface, with one implementation per **file format** (not per document type — those are separate axes, see the earlier note on this distinction):

```java
public interface DocumentProcessor {
    ProcessResult process(byte[] fileBytes, String documentId);
}
```

- `PdfProcessor implements DocumentProcessor` — the only real implementation needed for this assignment (PDF is the only supported format).
- A `Map<FileType, DocumentProcessor>` (or a simple `if/switch` on file extension for now, since only one format exists) resolves which processor to use, based on the uploaded file's extension/content-type — **not** based on `documentType`. This keeps the door open for an `ExcelProcessor` later without touching `DocumentType`, `Validator`, or any business-rule code at all.
- `DocumentProcessingService.processDocumentAsync` looks up the processor by file type once, then calls `.process(...)` inside the retry loop — the retry/outcome-handling logic (SUCCESS/TIMEOUT/ERROR/INVALID_RESULT, retry rules) stays exactly as already specified and does not change based on which processor is used.
- Each `process(...)` call still randomly returns one of `SUCCESS`/`TIMEOUT`/`ERROR`/`INVALID_RESULT`, and on `SUCCESS` still deterministically extracts the same fields for the same file (required for the retryability rule to hold — see above). Extracted field *shape* stays tied to `documentType` (via `Validator`), not to the processor — the processor's job is just "did extraction succeed," the validator's job is "is what was extracted acceptable for this document type."

Do not build `ExcelProcessor` or any second implementation now — only wire the interface + registry so adding one later is a new class + one registry entry, no changes to `DocumentProcessingService`.

`PdfProcessor`'s simulated extraction, for `FINANCIAL_STATEMENT` documents, should produce: `companyName`, `registrationNumber`, `address`, `annualRevenue`, `documentDate`. It's fine to occasionally hardcode/simulate one of these as missing or invalid (e.g. negative `annualRevenue`) so validation failures are actually reachable in testing.

## Validation

- One `Validator` (or similar) per `documentType`, resolved via a registry/map keyed by `DocumentType`.
- `FinancialStatementValidator` rules: `companyName` required; `registrationNumber` required; `annualRevenue >= 0`; `documentDate` must parse as a valid date.
- Validate against the extracted data as a `Map<String, Object>` or `JsonNode` — no dedicated POJO class per document type is required for this scope (keeps the design simpler; a POJO + Bean Validation annotations is a reasonable alternative but not necessary).
- On failure, collect all violated rules into a single readable string stored in `failure_reason` (e.g. `"VALIDATION_FAILED: companyName is required, annualRevenue must be >= 0"`).

## Retry — no dedicated endpoint

There is **no `POST /documents/{id}/retry` endpoint**. Retrying happens only through the existing upload flow: the user re-uploads the same file, the duplicate-check-by-content-hash finds the existing `FAILED` row, and if it's retryable, `processDocumentAsync` is re-triggered on the existing `documentId` (see step 3 of the upload endpoint above). If the UI wants a "Retry" button, it should call `POST /documents` again with the same file (or store the original file client-side and resubmit it) rather than calling a separate retry-specific endpoint.

## Status/history/list endpoints

- `GET /documents/{id}` → full detail: `documentId, status, documentType, filename, fileSizeBytes, createdAt, updatedAt, extractedResult (nullable), failureReason (nullable)`.
- `GET /documents/{id}/history` → all `document_history` rows for that `documentId`, ordered by timestamp ascending.
- `GET /documents?status=&documentType=&uploadDate=&page=&size=` → all filters optional and combinable; **return a Spring Data `Page<DocumentSummary>`** (the standard `content`/`totalElements`/`totalPages`/`number`/`size` envelope — do not build a custom pagination wrapper). Default page size 20. Do not include a `documentId` filter param here (that's what the single-resource `GET /documents/{id}` is for). Implement with a single parameterized JPA `@Query` using `(:param IS NULL OR field = :param)` per filter — do not use the Criteria API/`Specification` for this; three-to-four optional filters don't justify that complexity.
- Add DB indexes on `status` and `document_type` (already in the schema above) to keep these filters fast.

## Class structure (build to this shape — don't collapse layers or invent extra ones)

- `DocumentController` — REST endpoints only (`POST /documents`, `GET /documents`, `GET /documents/{id}`, `GET /documents/{id}/history`). No business logic here; delegates to the services below and maps results to HTTP responses/status codes.
- `DocumentService` — owns the **synchronous** upload-time flow: hashing, duplicate lookup, request-level validation (document type enum, PDF-only check), disk write via `FileStorage`, and the initial DB insert/duplicate-branch logic described above.
- `DocumentProcessingService` — owns the **asynchronous** flow only: `@Async processDocumentAsync(documentId)`, the retry loop, calling `MockProcessor`, dispatching to the right `Validator` via the type→validator registry, and all `document_history` writes that happen during processing.
- `FileStorage` (interface) + `LocalDiskFileStorage` (implementation) — as already specified.
- `Validator` (interface) + `FinancialStatementValidator` (implementation) + a registry/map (`Map<DocumentType, Validator>`, injected as a Spring bean) — as already specified.
- `DocumentProcessor` (interface) + `PdfProcessor` (implementation) + a registry/map (`Map<FileType, DocumentProcessor>` or equivalent) — as specified below.
- Repositories: `DocumentRepository`, `DocumentHistoryRepository` (plain Spring Data JPA repositories).

`DocumentController` must depend on `DocumentService` and `DocumentProcessingService` as **separate beans** — the controller calls `documentProcessingService.processDocumentAsync(...)`, never a method on itself or on `DocumentService` that then calls the async method internally, to avoid the Spring self-invocation proxy issue.

## Error handling

Use a single `@ControllerAdvice`/`@ExceptionHandler` class for anything **unexpected** (DB connectivity issues, uncaught exceptions, etc.) — return a generic `500` with a consistent body shape, e.g. `{ "message": "Something went wrong. Please try again later." }`. Never leak stack traces, exception class names, or raw DB error text to the client (per assignment section 9: "do not expose raw backend errors directly to users"). This is distinct from the **expected** business-flow responses already specified above (`409` for duplicates, `400` for invalid document type/file format), which should return their own specific, clear messages rather than the generic fallback.

## Logging (Observability, assignment section 12)

Log, at minimum, on every state transition: `documentId`, attempt number (when applicable), old status → new status, failure reason (when applicable), timestamp. Never log the file's raw content/extracted PII fields — only structural/status information. A developer reading the logs must be able to answer "why did DOC-12345 fail" from logs alone.

## Testing (assignment section 13 — minimum required cases)

1. Valid document upload → returns `202`, row created with `status = UPLOADED`.
2. Invalid extracted data → ends in `status = FAILED`, `failure_reason` starts with `VALIDATION_FAILED`, not retried further.
3. Successful processing → ends in `status = PROCESSED` with `extracted_result` populated.
4. Processor failure (`TIMEOUT`/`ERROR`) exhausting retries → ends in `status = FAILED`, `failure_reason` is `TIMEOUT` or `ERROR`, exactly 3 attempts logged in `document_history`.
5. Failure followed by a successful retry → first attempt(s) log `PROCESSING`→(retry)→ eventually `PROCESSED`; verify `document_history` shows the full sequence.
6. Uploading the same document twice → second upload returns `409` with the original `documentId` and no second row created; also test the sub-case of re-uploading a document that previously failed with a retryable reason (should re-trigger processing on the same `documentId`, not create a new row).

## Configuration (externalize to `application.yml`, do not hardcode)

```yaml
document-processing:
  max-retry-attempts: 3
  retry-backoff-ms: 1000        # optional fixed/incremental delay between internal retry attempts
  executor:
    core-pool-size: 4
    max-pool-size: 8
    queue-capacity: 100
  storage:
    base-path: ./uploaded-documents
```
Bind these with `@ConfigurationProperties` rather than scattering `@Value` annotations or literal constants through the code, so retry count, thread pool sizing, and storage path can be changed without touching Java source.

## Explicitly out of scope for this implementation
- No SQS, no Lambda, no S3 — a single monolithic Spring Boot service, matching the assignment's own statement that "a simple monolithic application is completely acceptable."
- No separate `FailureCategory` enum — retryability is derived from `failure_reason` text as described above.
- No filesystem-write-failure retry loop with rollback — a disk write failure at upload time fails the request synchronously and cleanly; nothing is left in an ambiguous state.
- No shared library between services — there is only one service.

## What to mention in the README / engineering-questions write-up (do not build these, just describe them)
- If this had to scale to ~1M documents/day: replace the in-process `@Async` executor with a real queue (SQS or RabbitMQ/Kafka) and a separate worker/consumer; the API contract for upload/status/history would not need to change, only what sits behind the async trigger. File storage would move to S3, referenced by path/key instead of a local filesystem path, behind the same `FileStorage` interface.
- Known limitation: a DB insert failure immediately after a successful disk write can leave an orphaned file on disk with no corresponding row; a production system would run a periodic reconciliation job to detect and clean these up.
- Known limitation: local disk storage does not support horizontal scaling across multiple app instances (they wouldn't share a filesystem) — this is why the `FileStorage` interface exists, to make an S3 migration a contained change.
