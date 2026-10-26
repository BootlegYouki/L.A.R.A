# Desktop scaffold verification — #29

Verified on Windows on 2026-10-26. Uses synthetic test data only.

## Automated checks
```text
npm run lint
> eslint . --max-warnings 0
(exit 0)

npm test
Test Files  3 passed (3)
     Tests  9 passed (9)

npm run build
> tsc --noEmit && vite build
vite v6.4.5 building for production...
41 modules transformed.
built in 1.28s

cargo check --all-targets
Checking tao v0.37.2
Checking webview2-com v0.39.1
Finished dev profile [unoptimized + debuginfo] target(s) in 1m 37s

python -m unittest discover tests
Ran 66 tests in 11.909s
OK

python scripts/verify_invariants.py
All L.A.R.A offline invariants and localization rules PASSED.
```

The native check used Rust 1.99.1 and Visual Studio 2022 C++ tools. JavaScript checks used Node 22.19.0.
Node's built-in SQLite prints an ExperimentalWarning during tests; it is test-only and does not replace the native SQL plugin.

## Native launch and database evidence
`npm run tauri dev` started Vite on loopback port 1420, compiled the native client and launched the L.A.R.A. window. A first run under restricted filesystem permissions exposed a denied app-config directory; after granting the app's own directory, relaunch completed with the real SQL plugin.

Read-only inspection of the database created by that window:
```text
Tables:
_sqlx_migrations
ai_chat_messages
announcement_comments
announcements
assignment_submissions
assignments
classrooms
enrollments
material_chunks
materials
private_comments
quiz_attempts
quiz_questions
quizzes
sync_state
topics
users

Applied migrations:
[(1, 'client_offline_schema', 1)]
```

Observed in the real native window with no Hub running:
- Top app bar, Hub-not-connected notice and empty-class content; no blocking connection dialog.
- Selecting Filipino changed the headings, notice, empty state, help text and switch label.
- Reloading retained Filipino and returned to the empty-class content.
- No fake classroom or fake connected status.

A stalled native database invocation now times out after 15 seconds and permits retry; the timeout/retry regression test passes. This does not cancel a native operation already running: the migration remains managed transactionally by the SQL plugin.

## Not verified
- Physical classroom Wi-Fi/router with its WAN cable unplugged.
- Linux/macOS native launch, release installers or hardware performance.
- Hub authentication, discovery, connectivity, sync, materials, quizzes and AI: outside #29.
- Cached-class cards in the native window with a signed-in account (sign-in/sync are not implemented). The class query is exercised against real SQLite in tests.
- Full application-close/reopen preference test; preference persistence was checked by webview reload.
- Filipino wording still needs a fluent teammate's review.
- Kiervy's completed technical spec has not yet been supplied or reconciled with this scaffold.

Project source, Rust, npm dependencies and build caches are on G:. Windows resolves the SQL plugin's small runtime database and WebView state to the standard app-data folders for `org.lara.desktop` on C:.
