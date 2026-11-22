# Overview monitoring verification

Verified on Windows, 22 November 2026, on `contract/hub-activity-logs`.

Passed:

- UI TypeScript/Vite production build.
- Four UI logic tests for ten-minute retention, bounded memory, graph positions,
  zero usage, missing/nonfinite samples, gaps after sleep, area closure across
  missing segments and ten-second network bar averaging.
- Browser suite in installed Edge: metric values, three graphs, automatic/manual
  adapters, adapter disappearance, sampling across page switches, unavailable
  readings/recovery, sign-out stopping/clearing, new-session reset and expiry.
- Browser widths 720, 960 and 1280px; no horizontal page overflow, adapter control
  at least 40px, no external requests and no uncaught page errors. The visual
  pass checks line, area and paired-bar rendering with token-based card colors.
- The friendly-label pass verifies This computer and L.A.R.A Hub in the processor
  card, both memory values (8 GiB / 256 MiB in the fixture), matching legends and
  the Network connection selector. Updated screenshots show the comparison layout.
- Graph options: all three resources switch independently among Line, Area and
  Bars. The browser suite checks all nine combinations, resource colors, correct
  bar-tooltip units, 40px selectors, selection retention across pages and default
  restoration on a new session. Switching preserves collected history.
- Axis labels: every graph displays five scaled row ticks with units, a measure
  name and Time label, plus 10 min ago / 5 min ago / Now column markers. The
  browser suite checks processor percentages, memory capacity and network-speed
  ticks, and the 720/960/1280px screenshots verify spacing.
- Native Rust tests: real Windows CPU/RAM/network/data-drive collection, first
  sample warm-up, rate calculation and reset handling; real backend sessions
  for admin/teacher/learner, unknown, expired and revoked-token checks.
- Native production-asset build (`cargo build -j1 --features tauri/custom-protocol`).
- Backend `cargo check --all-targets` and full `cargo test`.
- Shared Python contract/mock/schema suite (86 tests), offline invariant scan
  and `git diff --check`.

The initial monitoring build was reopened with the user's existing data directory;
`/download` returned HTTP 200. The later color/chart pass passed another UI build,
four logic tests and the browser suite. The running Hub did not exit on a graceful
close request, so its executable was preserved as
`target/debug/lara-hub-window-before-chart-colors.exe` and the updated executable
was built at the normal launch path. Restart the running Hub to load that version.
The temporary Vite preview was stopped.

Browser screenshots use synthetic metrics and REST responses, not school data:

- [960px Overview](overview-960.png)
- [720px Overview](overview-720.png)
- [1280px Overview](overview-1280.png)
- [Unavailable readings](unavailable.png)

OS readings are verified by native tests. Browser automation stubs Tauri IPC, so
it does not independently verify the native IPC bridge or the user's actual
authenticated window. No Linux machine or physical classroom load was tested.
