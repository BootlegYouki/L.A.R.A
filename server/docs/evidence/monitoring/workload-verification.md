# Workload monitoring verification — 2026-11-22

- UI production build passed in the feature worktree and the development copy
  used by the existing launcher.
- Four monitoring history/graph logic tests passed.
- Browser suite passed: five graphs, each graph type, workload values, storage
  breakdown, missing AI queue, adapter loss, unavailable/recovered readings,
  sign-out resets, session expiry and 720/960/1280px layouts without overflow.
- Backend full suite passed (394 tests); the additional real-HTTP workload test
  passed, confirming that background polls count toward workload while staying
  excluded from Activity logs. Strict backend Clippy passed with warnings denied.
- Five native tests passed: real Windows readings, rate reset handling, active
  admin authorization, and metadata-only storage totals. Strict native Clippy
  passed with warnings denied.
- All 91 shared contract/mock/schema tests and offline invariant checks passed.
- `git diff --check` passed.

Updated screenshots in this folder use synthetic readings. Native tests collect
real OS values, but no Linux or physical classroom load was tested. AI queue and
disk latency have no measurement source; queue remains explicitly unavailable.
Only Hub-descendant llama-server processes contribute to AI CPU/RAM.

The monitoring source patch was also applied to the existing development copy
without changing its migration files or opening/modifying the user's database.
The old running executable was preserved as
`lara-hub-window-before-workload-20261122.exe` before rebuilding the usual launch
path. Close the old Hub window and run `lara start` to load the new overview.
