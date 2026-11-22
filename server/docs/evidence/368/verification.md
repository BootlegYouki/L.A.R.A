# Activity logs verification

Verified locally on Windows, 21 November 2026, against synthetic data in an
isolated real Rust Hub. The existing running Hub and its database were untouched.

* UI: `npm run build` passed (TypeScript and Vite production assets).
* Backend: `cargo check --all-targets -j 1` passed.
* Backend: final full `cargo test -j 1` passed: 384 tests, zero failures.
* Native window: `cargo check -j 1` passed in `server/src-tauri` using cached
  dependencies in offline mode.
* Activity suite: all seven integration tests passed, including queue overflow,
  clean/dirty restart, retention, privacy, local authorization and burst pagination.
* Shared contracts: 83 tests passed, with PyYAML/jsonschema supplied from a
  temporary test dependency directory; no project dependency changes.
* Contract snapshot fixtures: ten tests passed. Offline invariant guardrail and
  `git diff --cached --check` passed with all implementation files staged.
* Browser: `ui/tests/activity-logs.cjs` passed against the real preview Hub:
  login, details, loading older than 100 rows, search/severity filters,
  pause/resume, offline/retry, no polling when hidden by navigation, account
  search, clearing history on sign-out, 40 px controls, no horizontal overflow
  at 720/960/1280 px, and no external requests or uncaught page errors.

Screenshots: [960 px](activity-960.png), [720 px](activity-720.png),
[1280 px](activity-1280.png), [unavailable Hub](activity-offline.png).
Nunito and Phosphor are bundled locally. The native Tauri bridge was stubbed
in the browser test to supply status; the REST API and SQLite recorder were real.

Build resource recovery: an initial parallel build exhausted memory/disk.
Generated Rust debug symbols were removed and builds rerun with one compilation
job and debug information disabled. The final browser run used installed Edge.
Native interactive window behavior and installer packaging were not exercised.
