# Activity logs UI verification

Verified locally on Windows on 22 November 2026.

The Activity logs page now uses a table with aligned time, level, request/event,
status and duration columns. Details expand below each row. Filters share a
toolbar, with Refresh, Pause updates and Clear filters available as labelled
keyboard controls. Copy was shortened to factual labels and status messages.
Existing L.A.R.A tokens, Nunito and bundled Phosphor icons remain in use.

Checks passed:

- UI production build (`npm run build`).
- Native Hub build (`cargo build -j 1 --features tauri/custom-protocol`).
- `tests/activity-logs.cjs`: real Hub login, paging, expanded details, pause/resume,
  filters, unavailable Hub/retry, navigation, account search and sign-out.
- `tests/request-activity.cjs`: request fields, privacy, filters, clear filters,
  empty state, retained rows during connection failure, keyboard expansion,
  720/960/1280px layouts, 40px controls, no external requests and no page errors.
- Offline invariant scan and `git diff --check`.

The browser checks used synthetic accounts in an isolated real Rust Hub and
installed Edge. Native status IPC was stubbed only in the browser; REST, request
recording and SQLite were real. Preview processes were stopped after verification.
The rebuilt user's Hub was reopened and `/download` returned HTTP 200.

- [Table](request-table.png)
- [Expanded request](request-details.png)
- [No matching entries](request-empty.png)
- [Unavailable Hub with retained entries](request-offline.png)
- [720px layout](activity-720.png)
- [1280px layout](activity-1280.png)

Earlier issue #368 screenshots were preserved separately. Backend behavior was
unchanged in this UI pass; the preceding request-logging implementation passed
the full Rust suite and 86 shared tests.
