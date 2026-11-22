# Endpoint request activity proposal

Accepted by the user and implemented locally on 22 November 2026. See
[`activity-logs.md`](activity-logs.md) for the implementation and
[`evidence/request-activity/verification.md`](evidence/request-activity/verification.md)
for verification. The sections below record the proposal that preceded the change.

Later user-approved exception: `/api/admin/activity-logs` no longer records its own
requests, and existing viewer-request rows are omitted from reads without deletion.
GET/OPTIONS `/api/admin/users` is also excluded from new recording and returned history;
polling remains enabled and account creation/PIN-reset requests remain recorded.
Other REST/portal endpoint coverage remains as described below.

Requested behavior: show HTTP requests to every Hub endpoint on the existing
admin-only Activity logs page, alongside operational events.

## Contract changes required

`contracts/activity_logs.md` currently specifies "no per-request traffic logging",
excludes request URLs, and says not to log every unauthenticated probe or rejected
credential. Its event catalog and the SQL/OpenAPI schemas do not represent HTTP
method, endpoint or response status. These definitions must change before runtime
implementation, under the repository's contract-first workflow.

## Proposed behavior

- Add a typed HTTP request event and approved details for HTTP method, endpoint
  template and HTTP status. Reuse the existing timestamp and duration field.
- Capture all HTTP requests centrally, including successful responses, validation
  errors, rejected authentication/authorization, unknown routes, unsupported
  methods, CORS preflight, media requests and activity-page polling.
- Use only registered endpoint templates such as `/api/materials/{id}`. Unknown
  paths use a fixed unmatched label. Never store actual resource/account IDs,
  raw paths, query strings, headers, bodies, PINs, LRNs, tokens or response content.
- Map methods to an approved enum; unfamiliar methods use a fixed OTHER value.
  Use INFO for successful responses, WARNING for client errors and ERROR for
  server errors. Record redirects without mislabeling them as failures.
- Measure elapsed time until response headers are available; do not buffer or
  consume upload/download bodies to produce a log entry.
- Show method, endpoint template, status and duration in the existing page using
  English dictionary strings. Preserve search, severity/date filtering, pause,
  pagination, empty/loading/unavailable states and Hub-local admin authorization.
- Keep existing queue, batching, retention and loss reporting. Request entries
  must bypass repeated-failure suppression; capacity exhaustion still produces
  visible gaps. This remains best-effort history, not a lossless audit ledger.
- Use a new migration preserving existing history; never edit migration 006.
  Keep request history out of client sync and exports.

Logging page polls adds one safe event per poll and can shorten retained history
under the existing 20,000-event limit. Pausing the display continues to pause only
polling, not server recording.

## Verification

Test representative GET/POST requests, dynamic routes, 4xx/5xx, authentication
failure, preflight, unknown paths/methods, activity reads, bounded enqueue behavior,
retention and history-preserving migration. Assert that credentials, LRNs,
resource IDs and query/body content never appear in stored or returned entries.
Build the UI and native Hub, run backend and shared contract/invariant checks,
then verify entries on the running Hub's Activity logs page.
