# CloudWatch-style overview verification

22 November 2026, Windows x64.

- Production UI build: passed in the feature worktree and the copy used by
  the local launcher.
- Eight monitoring logic tests: passed, including one-hour retention, grouping,
  missing/nonfinite readings, continuous warning windows, recovery, validation
  and allowlisted CSV output.
- Extended browser suite: passed with synthetic native/REST replies. It checked
  shared history, period and statistic controls; pause/manual refresh/resume;
  native-save CSV content; three warning transitions after a real 30-second span;
  saved/reset thresholds; graph expansion and all graph types; gauges and the
  statistics table; 720/960/1280px layouts; adapter loss and recovery; sign-out
  resets and session expiry. No page errors or nonlocal requests were observed.
- Five native release tests: passed. This covers real Windows collection,
  warm-up/rate resets, admin monitoring authorization, storage inventory and
  the file export filename/size allowlist.
- All 91 shared contract/mock/schema tests: passed.
- Offline invariant guardrail and whitespace check: passed.

The Windows package is built from the updated local development source.
The existing running Hub and its database were not stopped or modified.
The local launcher now reuses the release profile so it does not recreate the
debug cache cleared earlier to recover disk space. Its PowerShell syntax parsed.

Screenshots: [1280px](overview-1280.png), [960px](overview-960.png),
[720px](overview-720.png), [warning states](resource-warnings.png),
[unavailable readings](unavailable.png).

Browser checks do not automate the OS save dialog or verify a physical
classroom load. Linux and installation on another PC were not tested.
