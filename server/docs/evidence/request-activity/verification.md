# HTTP request activity verification

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
