# SuretySeven Frontend

React + Vite frontend for the document processing pipeline. No state management library, no
CSS framework, no test framework — plain fetch, plain CSS, plain React hooks, as specified in
the frontend plan.

## Stack

- React 19 + Vite
- react-router-dom (client-side routing across three pages)
- Plain CSS (`src/index.css`) — no Tailwind/CSS-in-JS

## Prerequisites

- Node 18+ (developed against Node 26)
- The backend running at `http://localhost:8080` (see `../backend/README.md`) — the frontend
  makes no sense without it, and the backend must have CORS enabled for `http://localhost:5173`
  (already configured in `WebConfig.java`).

## Setup

```bash
npm install
```

Optionally copy `.env.example` to `.env` if the backend isn't at the default
`http://localhost:8080`:

```bash
cp .env.example .env
# then edit VITE_API_BASE_URL
```

## Run

```bash
npm run dev
```

Opens at `http://localhost:5173`. Make sure the backend (and its MySQL database) is already
running — see the backend README for that setup.

## Build

```bash
npm run build
```

Outputs a static production bundle to `dist/`.

## Pages

- `/upload` — upload form. Selecting a file and clicking Upload opens a confirmation dialog
  (`Upload "<filename>" as <type>?`) before the request fires. Result surfaces as a toast:
  green success (with a "View" link to the new document) on `202`, blue info on `409`
  (duplicate), red error on `400`/`500`.
- `/documents` — a small stats dashboard (Total / In progress / Processed / Failed, computed
  client-side from four `GET /documents?status=X&size=1` calls — no dedicated backend endpoint),
  filters (status, document type, upload date), and a paginated table. Empty states distinguish
  "no documents at all" (with an Upload CTA) from "no documents match these filters."
- `/documents/:id` — full detail: metadata, extracted fields (when `PROCESSED`), failure reason
  parsed into a readable list (when `VALIDATION_FAILED`), full history timeline, and a Retry
  button shown only when the failure is retryable (`TIMEOUT`/`ERROR`). Polls every 3s while the
  document is in flight (`UPLOADED`/`PROCESSING`) — silently, no spinner flash; the spinner only
  appears on the very first load.

## Components

| Component | Purpose |
|---|---|
| `Toast.jsx` | `ToastContext` + `ToastProvider` + `useToast()`. Bottom-right stack, auto-dismiss ~4s, manual `×`, 3 variants. |
| `ConfirmDialog.jsx` | Generic confirm modal (title, message, onConfirm, onCancel) — currently only wired into upload. |
| `Spinner.jsx` | Small CSS `@keyframes` spinner, `inline` variant for buttons. |
| `StatCard.jsx` | One dashboard tile. |
| `StatusBadge.jsx` | Colored status pill. |
| `HistoryTimeline.jsx` | Renders a document's `document_history` rows as a vertical timeline. |

## Notes on scope (matches the frontend plan)

- No skeleton loaders — plain spinner only, and only on true initial loads (list fetch,
  dashboard counts, detail page's first load). Background polling on the detail page never
  shows a spinner.
- No stacked mobile card layout — under 720px the table becomes horizontally scrollable
  (`.table-scroll`) instead of a second layout.
- No frontend automated tests — covered by the backend's test suite per the assignment split.
- Retry is available two ways, both hitting the same backend behavior: re-uploading the
  identical file (duplicate-hash detection re-triggers processing), or the dedicated
  `POST /documents/{id}/retry` button on the detail page. The button is hidden entirely for
  non-retryable failures (`VALIDATION_FAILED`/`INVALID_RESULT`) since the backend would reject
  those with `400` anyway.
