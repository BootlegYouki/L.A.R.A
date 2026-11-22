# HTTP request activity verification

## Account-list read exclusion

Follow-up: the user's remaining entries were OPTIONS browser preflight requests
every five seconds, confirmed directly in the running Hub's safe event metadata.
Both GET and OPTIONS for `/api/admin/users` are now excluded from new recording
and returned history. Actual POST account creation and PIN-reset requests remain
recorded. The regression test includes existing GET/OPTIONS rows, fresh preflights
for GET/POST, and successful account creation/reset; legacy rows stay stored.

GET and OPTIONS `/api/admin/users` are excluded from new HTTP_REQUEST recording
and returned history for all statuses. Stored historical rows are preserved;
those read/preflight rows are hidden. Polling behavior is unchanged. Account
creation and PIN resets remain logged.

The preflight follow-up passed the full backend check/test suite, all four focused
request/migration tests (including preserved legacy OPTIONS rows), offline
invariants, scoped whitespace checks, and the native production-asset build.

Passed: 88 shared contract/mock/schema tests, offline invariants, backend
`cargo check --all-targets` and full `cargo test`, and the native production-asset
build. Targeted tests include seven history tests and four request/migration tests.
The new real-Hub test makes successful/unauthenticated account reads, creates a
synthetic teacher and resets its PIN; only the two POST requests appear in the
filtered history, while the historical GET row remains stored.
The request-activity browser suite also passed against the rebuilt isolated Hub:
searching `/api/admin/users` returns no entries, while an authenticated teacher
GET `/api/classrooms` appears with status 200. Existing privacy, row expansion,
filtering, offline recovery and responsive checks passed. Temporary preview
processes were stopped; restart the user's Hub to load the rebuilt executable.

## Activity viewer exclusion

The user-approved exception excludes `/api/admin/activity-logs` from new request
events for all methods/statuses. The read API hides older viewer-request entries
without deleting them or changing history identity/sequence high-water marks.

Verified: all 87 shared contract/mock/schema tests, offline invariants, backend
`cargo check --all-targets`, full backend `cargo test`, native production-asset
build and the scoped whitespace check passed. Targeted Rust coverage includes
seven history tests and three HTTP request/migration tests; the new test checks
successful reads, rejected authentication, unsupported methods, CORS preflight,
preserved legacy rows and exclusion from returned/searchable history.

The running user Hub was preserved. Restart it to load the rebuilt executable.

## Clickable rows

The latest UI pass makes the complete event row clickable and keyboard focusable.
The request-activity browser suite passed against an isolated real Hub with
synthetic accounts: clicking the endpoint cell opens details, clicking the time
cell closes them, Enter/Space on the row toggle details, and Enter on the existing
button toggles once. Expanded details include the entry number. Existing privacy,
filtering, unavailable-Hub recovery and responsive-control checks also passed.
UI and native production-asset builds and `git diff --check` passed. The running
user Hub was preserved; restart it to load the rebuilt executable.

Verified locally on Windows, 22 November 2026, on `contract/hub-activity-logs`.
The user approved extending the operational-history contract to HTTP requests.

## Checks passed

- `npm run build` in `server/ui`: TypeScript and Vite production build passed.
- `cargo check --all-targets -j 1 --target-dir ../src-tauri/target` in
  `server/backend`: passed, with development debug information and incremental
  compilation disabled to reuse the local native build cache.
- `cargo test -j 1 --target-dir ../src-tauri/target`: full backend unit,
  integration and documentation suite passed.
- Focused recorder/activity run: 116 unit tests, seven existing history tests
  and two new HTTP request/migration integration tests passed.
- Shared Python suite: 86 tests passed using existing temporary test dependencies.
- `scripts/verify_invariants.py`: all offline invariants passed.
- Native `cargo build -j 1 --features tauri/custom-protocol`: passed. The local
  Hub was reopened, and its `/download` endpoint returned HTTP 200.
- `git diff --check`: passed.

## Request coverage and privacy

Real-backend tests cover public requests, dynamic endpoint templates, rejected
authentication, malformed JSON, repeated identical failures, unknown paths,
unsupported methods, CORS preflight, activity-page reads, redirects and server
errors. A response with a never-ending body confirms the recorder returns at
response headers instead of consuming streaming content.

Assertions verify that LRNs, PINs, tokens, headers, bodies, private methods,
resource IDs and query values are absent from returned history. SQL constraints
reject unapproved paths/methods/statuses. Contract tests check that the approved
endpoint catalog covers every registered route. The migration test preserves
existing history, history identity and the sequence high-water mark after pruning.

## Browser verification

`ui/tests/request-activity.cjs` passed against an isolated real Rust
`activity_preview` Hub with synthetic accounts, using installed Edge headlessly.
The Tauri status bridge was stubbed; REST, SQLite and request recording were real.
The test covered method/endpoint/status/details, search, severity, retained rows
when the Hub is unreachable, retry, 720/960/1280px layouts, 40px controls, no private
inputs in the page, no external HTTP requests and no uncaught browser errors.

- [Request details](request-details.png)
- [Unavailable Hub with retained requests](request-offline.png)

Preview processes were stopped after verification. The user's local Hub remains
open. Native login/Activity navigation with the user's real admin PIN and a
physical classroom network were not automated; the page was verified using the
isolated synthetic-account Hub.

History remains best-effort under the existing bounded queue, retention and gap
reporting policy. HTTP logging covers the REST/portal listener, not individual
WebSocket frames on the separate classroom connection.
