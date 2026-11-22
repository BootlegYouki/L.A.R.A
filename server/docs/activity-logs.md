# Hub activity logs

Implements the local admin console and operational history described in
[`contracts/activity_logs.md`](../../contracts/activity_logs.md), issue #368.
The implementation is local on `contract/hub-activity-logs` at the owner's request.
The published contract PR remains draft and post-defense. The local development
Hub has been rebuilt and reopened with the user-approved request logging extension;
these changes have not been pushed or deployed to other Hubs.

## Finding the page

Sign in to the Hub window with the admin PIN, then select **Activity logs**.
Overview, Accounts and Activity logs share the authenticated navigation. Accounts
supports name/identifier search and Teacher/Learner filters. Activity logs supports
catalog search, severity, inclusive local date filters, expandable safe details,
pause/resume and loading older entries. Pausing stops display polling; recording
continues. Navigation away stops activity polling. Sign-out discards loaded history.

The page uses a compact table with Time, Severity, Request / event, Status and
Duration columns. Each row has a keyboard-accessible details button. Expanded
details stay open during polling. Search, severity and dates share one toolbar;
Clear filters resets them together. Refresh updates immediately, and the header
shows Live, Paused or Offline. A scroll wrapper keeps the table inside narrow
windows. Wording is brief and describes the current state without extra guidance.

## Key files and data flow

* `backend/src/services/request_activity.rs`: safe request middleware and approved endpoint/method catalogs.
* `backend/src/db/migrations/007_http_request_activity.sql`: preserves history and the sequence high-water mark while adding constrained request details.
* `backend/src/services/activity.rs`: typed producer catalog, nonblocking bounded
  queue, SQLite writer, retention, gap accounting and clean shutdown marker.
* `backend/src/db/migrations/006_activity_history.sql`: private event/state tables
  matching the canonical master schema. These tables never create sync revisions.
* `backend/src/routes/activity.rs`: ADMIN plus loopback-only read API. Tunnel
  forwarding headers are rejected using the existing local-admin guard.
* `backend/src/lib.rs`, `discovery/udp.rs`, `websocket/mod.rs`,
  `routes/{admin,materials,quiz_control,mod}.rs`: lifecycle, discovery/network,
  account, document and quiz producers. Only committed successful changes are
  recorded; failed operations record fixed reasons after authorization.
* `ui/src/pages/ActivityLogs.tsx`: in-memory history and cursor chains; aborts stale
  requests, drains catch-up pages at a fixed snapshot, polls every five seconds
  only while active, visible and unpaused, and preserves loaded rows during failure.
* `ui/src/pages/AdminUsers.tsx`, `ui/src/i18n/en.json`, `ui/src/index.css`: console
  navigation, account search and resource-based wording using existing tokens.

Producers accept enums and bounded numeric measurements, with no free-text error
or payload field. Successful queue writes return immediately. The writer batches
at most 100 entries or 500 ms, retains at most 20,000 events for seven days, and
suppresses identical warnings/errors for 30 seconds. Lost/suppressed events create
`LOG_GAP` entries and cumulative loss metadata. Write failures retry with bounded
backoff without stopping account/classroom service. Shutdown grants the writer
two seconds to drain; an incomplete shutdown leaves the dirty marker for restart.

Reads use bound SQL values, an exclusive sequence cursor and a UUID history ID.
The high-water sequence includes previously pruned events. Catch-up selects the
oldest unseen page, returning each page newest first, so a burst larger than 100
does not skip the middle. History changes return `ACTIVITY_LOG_RESET`; unavailable
tables return the safe `ACTIVITY_LOG_UNAVAILABLE` error.

## Limits and gotchas

This is best-effort operational history, not an audit ledger or raw tracing viewer.
No names, account IDs/LRNs, PINs, tokens, file names/paths, addresses or document
contents are stored in these events. It is not included in client sync or exports.
It does not add OCR: an uploaded scan may have no extracted lesson text.
An empty PDF/DOCX extraction currently uses the generic `NO_TEXT_LAYER` reason;
the existing parser does not expose a richer typed failure result.

The GUI now also shows every REST/portal HTTP request using method, allowlisted endpoint template, response status and time to response headers. Request recording wraps CORS, authentication, routing and handlers, so preflight, rejected requests and activity-page polling are covered. Request details never include raw URL parameters, bodies or credentials. Request events bypass repeated-warning suppression but retain bounded queue/gap behavior.

The GUI observes events emitted by implemented services; it is not a general
collector of every tracing line. Discovery recovery refers to UDP beacon delivery.
Activity-log database recovery also emits a fixed service recovery event.
The native lockfile includes existing backend document-parser/upload dependencies
required to compile the current staging backend into the window.

## Verification

`cargo check --all-targets -j 1`, `cargo test -j 1` in `server/backend`,
`npm run build` in `server/ui`, shared Python contract tests and the invariant
guardrail cover the implementation. The activity integration suite tests local
admin access, invalid filters, literal search, 350-event catch-up, history reset,
private account outcomes, clean/dirty restart, retention, queue overflow, loss
reporting, bounded producer calls and service continuity when logging is unavailable.

The real-backend browser test is `ui/tests/activity-logs.cjs`. Start the isolated
preview with `cargo run --example activity_preview -- <new-temp-directory>`;
it refuses an existing directory, uses synthetic accounts and free ports, and writes
`preview.json`. Serve the built UI using `vite preview --host 127.0.0.1 --port 5175`.
Set `LARA_ACTIVITY_PREVIEW` to that JSON path and `LARA_PLAYWRIGHT_PACKAGE` to an
installed Playwright package (optionally `LARA_CHROMIUM_EXECUTABLE`), then run the
test with Node. It redirects loopback API calls to that real test Hub and blocks
external requests. Write a `stop` file in the preview directory to shut it down.
Screenshots and verification notes are in [`evidence/368`](evidence/368/verification.md).

HTTP request verification uses `ui/tests/request-activity.cjs` with the same preview
environment variables. Current request-log screenshots and results are in
[`evidence/request-activity`](evidence/request-activity/verification.md).
