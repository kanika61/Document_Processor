# API Contracts — SuretySeven Document Processing Pipeline

Base URL (local): `http://localhost:8080`

All responses are `application/json` unless noted. All timestamps are ISO-8601 UTC instants
(e.g. `2026-09-18T16:07:22Z`).

---

## 1. `POST /documents` — Upload a document

Uploads a file for async extraction + validation. Duplicate detection is by SHA-256 content
hash, not filename.

### Request

`Content-Type: multipart/form-data`

| Part | Type | Required | Notes |
|---|---|---|---|
| `file` | file | Yes | Only `.pdf` is accepted. |
| `documentType` | string (form field) | Yes | Must exactly match a `DocumentType` enum constant. Currently only `FINANCIAL_STATEMENT`. |
| `metadata` | string (form field, JSON) | No | e.g. `{"sizeBytes": 204800}`. Cross-checked against the actual upload size; mismatch is logged as a warning, never a hard failure. If omitted, the server computes and stores the actual byte length. |

```bash
curl -X POST http://localhost:8080/documents \
  -F "file=@statement.pdf;type=application/pdf" \
  -F "documentType=FINANCIAL_STATEMENT" \
  -F 'metadata={"sizeBytes":204800}'
```

### Responses

#### `202 Accepted` — new document created

```json
{
  "documentId": "DOC-3f6a1c9e-...",
  "status": "UPLOADED"
}
```

#### `202 Accepted` — retryable failure re-triggered (same file re-uploaded)

Returned when the content hash matches an existing row whose `status` is `FAILED` with a
retryable `failureReason` (`TIMEOUT` or `ERROR`). Processing is re-triggered on the **existing**
`documentId` — no new row is created.

```json
{
  "documentId": "DOC-3f6a1c9e-...",
  "status": "PROCESSING"
}
```

#### `400 Bad Request` — unknown `documentType`

No row created, no file written to disk.

```json
{
  "message": "Unknown documentType 'NOT_A_TYPE'. Supported types: [FINANCIAL_STATEMENT]"
}
```

#### `400 Bad Request` — unsupported file format

No row created, no file written to disk.

```json
{
  "message": "Unsupported file format for 'statement.xlsx'. Only .pdf is supported."
}
```

#### `400 Bad Request` — missing required part

```json
{
  "message": "Missing required part 'file'"
}
```

#### `409 Conflict` — duplicate content, currently active

Returned when the content hash matches an existing row with `status` = `UPLOADED`,
`PROCESSING`, or `PROCESSED`.

```json
{
  "message": "A document with identical content already exists.",
  "documentId": "DOC-3f6a1c9e-...",
  "filename": "statement.pdf",
  "status": "PROCESSED",
  "uploadedAt": "2026-09-18T16:07:22Z"
}
```

#### `409 Conflict` — duplicate content, non-retryable failure

Returned when the content hash matches an existing `FAILED` row whose `failureReason` starts
with `VALIDATION_FAILED` or `INVALID_RESULT` (deterministic for this file — reprocessing would
produce the identical result).

```json
{
  "message": "This document already failed and reprocessing will not change the result",
  "documentId": "DOC-3f6a1c9e-...",
  "filename": "statement.pdf",
  "status": "FAILED",
  "uploadedAt": "2026-09-18T16:07:22Z",
  "failureReason": "VALIDATION_FAILED: companyName is required"
}
```

#### `500 Internal Server Error` — disk write failure

```json
{
  "message": "Upload failed, please try again"
}
```

#### `500 Internal Server Error` — any other unexpected failure

```json
{
  "message": "Something went wrong. Please try again later."
}
```

---

## 2. `POST /documents/{id}/retry` — Explicitly retry a failed document

Re-triggers processing on an existing document without a new file upload. Reuses the file
already on disk (`FileStorage.read(doc.filePath)`, same as the internal retry loop) — the
`file` part is not sent.

```bash
curl -X POST http://localhost:8080/documents/DOC-3f6a1c9e-.../retry
```

### Responses

#### `202 Accepted` — retryable, processing re-triggered on the same `documentId`

Returned only when `status` is `FAILED` **and** `failureReason` starts with `TIMEOUT` or
`ERROR`.

```json
{
  "documentId": "DOC-3f6a1c9e-...",
  "status": "PROCESSING"
}
```

#### `400 Bad Request` — not retryable

Returned when `status` is not `FAILED` (e.g. `UPLOADED`/`PROCESSING`/`PROCESSED`), or `status`
is `FAILED` but `failureReason` is deterministic (`VALIDATION_FAILED`/`INVALID_RESULT`).

```json
{
  "message": "This document cannot be retried."
}
```

#### `404 Not Found`

```json
{
  "message": "No document found with id DOC-does-not-exist"
}
```

> Uploading the same file again (`POST /documents`) still also re-triggers a retryable
> `FAILED` document on its existing `documentId` — the two paths are equivalent for that case.
> `POST /documents/{id}/retry` additionally covers retrying without having the original file
> bytes on hand client-side.

---

## 3. `GET /documents/{id}` — Document detail

```bash
curl http://localhost:8080/documents/DOC-3f6a1c9e-...
```

### Responses

#### `200 OK`

```json
{
  "documentId": "DOC-3f6a1c9e-...",
  "status": "PROCESSED",
  "documentType": "FINANCIAL_STATEMENT",
  "filename": "statement.pdf",
  "fileSizeBytes": 204800,
  "createdAt": "2026-09-18T16:07:22Z",
  "updatedAt": "2026-09-18T16:07:23Z",
  "extractedResult": {
    "companyName": "Acme Corp",
    "registrationNumber": "REG-123456",
    "address": "123 Simulated St, Unit 42",
    "annualRevenue": 1500000,
    "documentDate": "2024-01-15"
  },
  "failureReason": null
}
```

| Field | Type | Notes |
|---|---|---|
| `status` | enum | `UPLOADED` \| `PROCESSING` \| `PROCESSED` \| `FAILED` |
| `documentType` | enum | `FINANCIAL_STATEMENT` |
| `extractedResult` | object \| `null` | Populated only when `status` = `PROCESSED`. |
| `failureReason` | string \| `null` | Populated only when `status` = `FAILED`. One of `TIMEOUT`, `ERROR`, `INVALID_RESULT`, or `VALIDATION_FAILED: <comma-separated reasons>`. |

#### `404 Not Found`

```json
{
  "message": "No document found with id DOC-does-not-exist"
}
```

---

## 4. `GET /documents/{id}/history` — State-transition timeline

Ordered oldest → newest.

```bash
curl http://localhost:8080/documents/DOC-3f6a1c9e-.../history
```

### Responses

#### `200 OK`

```json
[
  { "status": "UPLOADED", "reason": null, "timestamp": "2026-09-18T16:07:22Z" },
  { "status": "PROCESSING", "reason": null, "timestamp": "2026-09-18T16:07:22Z" },
  { "status": "FAILED", "reason": "TIMEOUT", "timestamp": "2026-09-18T16:07:22Z" },
  { "status": "PROCESSING", "reason": null, "timestamp": "2026-09-18T16:07:23Z" },
  { "status": "PROCESSED", "reason": null, "timestamp": "2026-09-18T16:07:23Z" }
]
```

A `FAILED` row is terminal only if it is the **last** row in the array — a `FAILED` row
followed by a `PROCESSING` row represents an automatic retry (or a manual retry via
re-upload) already in progress.

#### `404 Not Found`

```json
{
  "message": "No document found with id DOC-does-not-exist"
}
```

---

## 5. `GET /documents` — Paginated / filtered list

```bash
curl "http://localhost:8080/documents?status=PROCESSED&documentType=FINANCIAL_STATEMENT&uploadDate=2026-09-18&page=0&size=20"
```

### Query parameters (all optional, combinable)

| Param | Type | Notes |
|---|---|---|
| `status` | enum | `UPLOADED` \| `PROCESSING` \| `PROCESSED` \| `FAILED` |
| `documentType` | enum | `FINANCIAL_STATEMENT` |
| `uploadDate` | date (`YYYY-MM-DD`) | Matches documents created on that calendar day, UTC. |
| `page` | int | 0-indexed. Default `0`. |
| `size` | int | Default `20`. |

An invalid enum value for `status`/`documentType` (e.g. `?status=NOT_REAL`) returns:

```json
{ "message": "Invalid value for parameter 'status'" }
```
with `400 Bad Request`.

### Response — `200 OK`

Standard Spring Data `Page` envelope. `content[]` items are the projected list view
(`extractedResult` is intentionally excluded — use `GET /documents/{id}` for that):

```json
{
  "content": [
    {
      "documentId": "DOC-3f6a1c9e-...",
      "filename": "statement.pdf",
      "documentType": "FINANCIAL_STATEMENT",
      "status": "PROCESSED",
      "fileSizeBytes": 204800,
      "failureReason": null,
      "createdAt": "2026-09-18T16:07:22Z",
      "updatedAt": "2026-09-18T16:07:23Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "offset": 0,
    "paged": true,
    "unpaged": false,
    "sort": { "sorted": false, "unsorted": true, "empty": true }
  },
  "totalPages": 1,
  "totalElements": 1,
  "last": true,
  "first": true,
  "numberOfElements": 1,
  "size": 20,
  "number": 0,
  "sort": { "sorted": false, "unsorted": true, "empty": true },
  "empty": false
}
```

---

## Error response shape (generic)

Every non-2xx response not otherwise specified above returns:

```json
{ "message": "<human-readable, non-technical message>" }
```

Stack traces, exception class names, and raw DB errors are never included in any response body.

## Status code summary

| Code | Meaning |
|---|---|
| `202 Accepted` | Upload accepted (new document or retryable-duplicate re-trigger), or `POST /retry` accepted. |
| `200 OK` | Successful read (`GET`). |
| `400 Bad Request` | Invalid `documentType`, unsupported file format, missing multipart part, bad query-param value, or `POST /retry` on a non-retryable document. |
| `404 Not Found` | No document with the given `documentId`. |
| `409 Conflict` | Duplicate content hash on upload — either still active or a non-retryable failure. |
| `500 Internal Server Error` | Disk write failure or any other unexpected error. |
