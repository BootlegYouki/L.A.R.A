# Hub integration into staging — 22 November 2026

PR #399 already merged the sign-in refresh, sign-out confirmation and scrollable
account pagination. This follow-up includes the remaining user-approved changes:

| Work | Files / behavior |
|---|---|
| Activity console | Private durable recorder, admin-only route, compact request table, filters and safe clickable details |
| Request coverage | HTTP method/template/status/header duration, including public/rejected requests; no private bodies, IDs or credentials |
| Polling exclusions | Activity-viewer requests and GET/OPTIONS account-list requests are omitted from new recording and read history; polling and account writes continue |
| Resource usage | Whole computer vs Hub CPU/RAM, network connection rates, storage and uptime; local authenticated native IPC |
| Charts | Independent Line/Area/Bars choices, resource colors, labels, numbered axes and bounded ten-minute history |
| Learner import | CSV/Excel validation, generated PINs, duplicate handling, interrupted-request reconciliation, credential export and native Save dialog |
| Demonstration file | `server/docs/examples/Demo learners.xlsx`, 20 fictional learners validated by the importer |
| Launcher | Root `lara.cmd` / `lara.ps1` build and start this checkout's Hub |

The source worktree's earlier changes were applied as patches onto current
staging, preserving its announcement-author, sync and contract updates. Existing
canonical migration `006_announcement_author_hub_activity.sql` is unchanged;
only migration 007 is added for request metadata. Newer contracts are retained.

## Merge dependency

Contract PR #400 must merge before this dependent implementation. Both PRs target
staging. The feature branch includes the contract commits so its combined schema
and runtime can be tested; the feature diff will shrink after the contract merges.
Staging currently fails its route-registration test because the contracted activity
endpoint is missing. This implementation supplies the handler.

## Existing development database

Read-only inspection of the user's running Hub found development migration 006
`activity history` and migration 007 `http request activity`; the announcements
table has no author_id column. This differs from canonical staging migration 006.
SQLx checksum validation will reject that database on the integrated branch.
Fresh databases and upgrades from canonical staging are covered by the tests.

Do not replace migration files, reset history, edit the live migration ledger or
delete the user's database to bypass the mismatch. The PR stays draft pending a
reviewed upgrade path for that development database. No live data was modified.

## Practical limits

Network monitoring measures all programs on the selected adapter. Hub process
metrics exclude managed AI child processes; those are included in whole-computer
totals. Request duration ends at headers; document processing has separate events.
The recorder uses the documented bounded queue, retention and visible gap policy.
Monitoring/browser metrics and account tests use synthetic fixtures. Native Save
dialog interaction and a physical disconnected classroom router were not automated.

## Combined verification

On the staging-based feature branch, Windows, 22 November 2026:

- Backend `cargo check --all-targets` and the full `cargo test` suite passed,
  including route registration, migration/schema parity and history preservation.
- Native `cargo test --features tauri/custom-protocol` compiled the production UI
  assets and passed four tests for real CPU/RAM/network collection, admin-only
  metrics access, counter handling and import export restrictions.
- UI TypeScript/Vite build and 14 monitoring/import logic tests passed. The
  committed demo workbook passes the real importer with all 20 text LRNs intact.
- All 89 shared contract/mock/schema tests and offline invariants passed.
- Account/sign-in and monitoring browser suites passed with synthetic responses.
- Request logging and learner import browser suites passed against isolated real
  Rust Hubs with canonical migrations. No real user accounts were modified.

Browser checks cover graph types/axes, account table scrolling/pagination,
sign-out confirmation, stale/offline recovery, row details and import interruption.
Screenshots are under the corresponding `server/docs/evidence/` folders.

GitHub CI follow-up: the strict Clippy job rejected the recorder's nested map key
type. A named EventKey alias keeps the same representation and behavior while
satisfying the type-complexity lint; no lint suppression is added.
