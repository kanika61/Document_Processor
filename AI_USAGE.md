# AI Usage

## Tools
- Claude Code (Claude Sonnet 5), in the Claude desktop app.

## What I did vs. what AI did
- **Me:** proposed the backend design (`backend/IMPLEMENTATION_PLAN.md`: stack, schema, endpoints, async processing, retry rules) and wrote the frontend plan, made the design decisions, reviewed the output, and directed the corrections below.
- **Error handling:** I specified the requirements in my plan (clean `400`/`409`/`500` responses, no raw backend errors shown to users), edited the duplicate-upload error message myself, and asked for errors to show in red and successes in green. The AI wrote the exception-handling code from that.
- **Testing:** I tested the flow end to end myself and created the Postman API collection (`Document-Processor.postman_collection.json`).
- **Docker:** I gave the requirement (run the whole project with `docker compose up`) and reviewed and approved the plan; the AI proposed the design and created the files.
- **AI:** wrote most of the code, tests and docs from those plans.

## Significant AI-generated code
- Frontend: three pages, toast, confirm dialog, spinner, dashboard tiles, retry button.
- Unit tests (Mockito), the README / API contract docs, and the Docker setup (`docker-compose.yml` and the two Dockerfiles).

## Changed or rejected
- Added `POST /documents/{id}/retry`, which the original plan excluded.
- Dashboard stats were refetched on every filter change; changed to once per page visit.
- Kept React StrictMode; rejected removing it and rejected an AbortController workaround.
- Tried, then reverted, dropping the client `metadata` and adding an `uploadedBy` field.
- Edited the duplicate-upload message text myself.
- The AI's processing loop reloaded the document from the DB on every status change. I questioned why it hit the DB each time, and had it reuse the already-loaded entity instead. I also chose not to batch all the history writes into one save at the end, since that would lose the live timeline and crash safety.
- Rejected the AI's suggestion to merge the list and detail DTOs into one with `@JsonInclude(NON_NULL)`. I pointed out that dropping nulls would also drop fields that are legitimately null but required by the contract (`failureReason`, `extractedResult`), so the two DTOs stay.
- Decided to keep the DTOs as records rather than getter/setter classes: they are response objects that are built once and never changed, so they don't need setters.

## What I learned
**Designing the backend**
- **Idempotent uploads.** Hashing the file content (SHA-256) makes a duplicate detectable no matter what the file is called. It also gives a clean retry rule: the same bytes always give the same result, so a re-upload is only allowed when the earlier failure was flaky rather than deterministic.
- **Not every failure deserves a retry.** A timeout is the processor's fault, so a retry can succeed. A validation failure is the document's fault, so a retry can only fail again. A timeout can also hide a document that will always fail validation, which is why a retry sometimes ends in a different error.
- **Where transactions belong.** `@Transactional` on the upload makes the document row and its first history row succeed or fail together. The async worker deliberately has none, so each status change commits immediately and the UI can show progress live. It only covers DB writes, so a failed insert can still leave an orphaned file on disk.
- **`@Async` needs its own bean.** Calling an async method from inside the same class bypasses Spring's proxy and silently runs it synchronously. A named thread pool (`documentProcessingExecutor`) also keeps this work off the shared default.
- **Ask why the code hits the DB.** The processing loop reloaded the same row on every status change. Reusing the loaded entity removed a call per change. Batching every write into one save would have been fewer calls but would lose the live timeline and crash safety, so cutting DB calls has to be weighed against what the feature needs.
- **DTOs are a contract, not a convenience.** Two response shapes stay separate because dropping nulls would also drop fields that are legitimately null but promised by the API. Records fit because a response is built once and never changed.
- **Hide the storage behind an interface.** `FileStorage` means moving from local disk to S3 changes one class.

**Frontend and the browser**
- **StrictMode** double-mounts effects in dev, so it doubles API calls there only. A production build makes each call once.
- **Polling vs. push.** Polling every 3 seconds while a document is in flight is simple and enough here. Server-Sent Events is the scale-up path, and it is written into the docs.
- **CORS** exists because the UI and API run on different origins. Without an explicit allow-list, the browser blocks every call.

**Running it anywhere**
- **Images, containers and Compose.** An image is a template, a container is a running copy, and Compose wires several together on a private network. Pods are a Kubernetes idea, not part of Compose.
- **One process per container** keeps restarts, data and logs independent. Multi-stage builds mean nobody needs a JDK, Maven or Node to run the project.
- **Volumes** are why uploaded files and database rows survive a restart. A relative storage path is fragile, so the container uses an absolute one.

**Working with AI**
- The AI made small mistakes I caught by running things: a bulk edit broke two tests, and it wrongly said no commits existed. Checking its claims against the code and the tests was necessary.
- It also accepted correct pushback. When I argued against merging the DTOs or batching the writes, it changed its position.
- The automated tests are Mockito unit tests only. I did the integration testing myself by running the full flow end to end (Postman, real MySQL, real disk). There is no automated integration test in the repo.

- **Observability:** structured logs, metrics and tracing, so "why did DOC-123 fail" can be answered without reading code.

