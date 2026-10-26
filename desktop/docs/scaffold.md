# Desktop scaffold — issue #29

## Purpose and scope
The Tauri 2 desktop window hosts a React 19 / strict TypeScript / Tailwind 4 shell. It opens the canonical client SQLite database without contacting a Hub, reads the current account's saved classes, and supports English and Filipino. This is the foundation for later desktop steps, not a working sign-in or connection screen.

The developer relayed the Lead's direction to start the scaffold while the desktop technical spec is being prepared. `TECH_SPEC.md` is deliberately left for that work; this note records the implementation choices for review and reconciliation.

## Key files and entry points

| File | Responsibility |
|---|---|
| `src-tauri/src/main.rs`, `lib.rs` | Native entry point; SQL plugin and versioned migration registration |
| `src-tauri/migrations/001_client_offline.sql` | Initial client schema, mirrored from `contracts/schema/client_offline.sql` |
| `src-tauri/tauri.conf.json` | Native window, Vite dev server, local-only CSP |
| `src-tauri/capabilities/default.json` | Only SQL load, select and execute in the main window |
| `src/services/db.ts` | Retryable, shared database initialization and current-account class query |
| `src/store/app.ts` | Zustand loading/error/ready state; language preference persistence |
| `src/App.tsx` | Top app bar, offline notice, loading skeleton, empty/cached/error content |
| `src/i18n/` | Matching dictionaries and typed access |
| `src/index.css` | Tailwind 4, shared theme, local fonts/icons, reduced-motion skeleton |
| `tests/` | Schema, real SQLite persistence and account filtering; initialization retry and store behavior |

## Data flow and persistence
1. React asks the Zustand store to load saved classes. Concurrent startup calls share the same pending read.
2. `Database.load('sqlite:lara_client.db')` opens the SQL plugin database under the OS app-config directory for `org.lara.desktop`. It applies registered migrations transactionally. A failed initialization is not cached permanently; Try again can reopen it.
3. Foreign keys are enabled explicitly on open. The locked SQL plugin's SQLx SQLite driver also defaults foreign keys to ON on every pooled connection. A JavaScript PRAGMA alone must not be taken as proof about every pooled connection.
4. The query reads only classes owned/co-taught by `sync_state.current_user_id`, or with that learner's ACTIVE enrollment. With no current user it returns no classes. Class codes and other users' personal information are not selected. These local filters do not replace future authentication or Hub authorization.
5. Classroom data stays in SQLite. Only the language preference goes to localStorage; a denied/unavailable preference store does not stop the interface. Persisted state cannot hydrate classroom rows or a stale ready/error status.
6. There are no Hub calls, tokens, fake accounts, demo data or background discovery in this step. The Hub notice remains disconnected until the connection service lands. Cards are read-only placeholders, not links to nonexistent pages.

## Design decisions
- Import `design-system/desktop/theme.css`, the existing Tailwind 4 mirror, directly. Font/icon assets and their licenses are copied into `src/assets/design-system/` as the desktop README requests; Vite bundles them locally.
- Use the Classroom-style top bar and Home content area. This scaffold does not reproduce the complete Home prototype or add its unimplemented controls.
- Desktop controls use the canonical 40px target, overriding the older phone-sized wording in #29. Focus rings, icon-plus-text statuses and reduced-motion loading are provided. Purple remains unused because there is no AI surface.
- Keep the OS title bar and default OS database location. Bundle creation is disabled for the scaffold; the temporary green icon is copied from the Hub scaffold.
- Register one immutable, LF-normalized migration. Future schema changes must be additive migrations after a contract change; do not edit an applied migration.
- SQL permissions are local to the main window; there is no remote-origin capability, filesystem, shell, HTTP, updater or sidecar permission. SQL permissions have no per-database scope in this plugin: load/select/execute are intentionally limited to this trusted local webview.
- Authentication, Hub networking/CORS decisions, discovery (#5), the connection UI (#40), synchronization and AI remain later issues. In particular, do not implement atomic sync with separate JavaScript BEGIN/COMMIT calls across a pool. The desktop skill requires a proven single native transaction for that later step.

## Run and check
Prerequisites: Node 22.13+ (the SQLite tests use Node's built-in SQLite), Rust stable, and Tauri's platform prerequisites. On Windows this includes the Visual Studio C++ build tools and WebView2. No runtime internet dependency is introduced.

From `desktop/`:
```sh
npm ci
npm run lint
npm test
npm run build
npm run tauri dev
```

From the repository root (Python with PyYAML):
```sh
python -m unittest discover tests
python scripts/verify_invariants.py
```

### How to test as an end user
1. Start the desktop app with `npm run tauri dev`. No account or running Hub is required for this scaffold. On a new database, the window shows Home, the Hub-disconnected notice, and an empty classes message.
2. Select Filipino in the top bar. All interface text changes; the button becomes English. Close and reopen the app: the chosen language is remembered when preference storage is writable.
3. Leave the Hub stopped. The app remains usable; no connection dialog blocks it. Starting/stopping the mock Hub should not change this notice yet, because discovery and sign-in are outside #29.
4. The database is created at the app-config path for `org.lara.desktop` as `lara_client.db`. It contains the 16 canonical client tables plus SQLx's migration bookkeeping. Do not delete an existing database to retest.
5. `npm test` uses temporary databases to verify reopening preserves queued comments, foreign keys reject an orphan, and another account's or pending/removed learner's classes stay out of the result. No real learner data is used.
6. After sign-in/sync are implemented, a saved current user's classes appear here from SQLite. This scaffold deliberately does not provide a manual fixture-import or account-switch UI.

## Verification limits and follow-up
- The initial migration is exercised with real SQLite and compared to the canonical SQL. Native launch must also be checked to exercise Tauri permissions and the SQL plugin.
- Physical school-router testing with the WAN cable unplugged is an integration-day check, not implied by a local build or unit test.
- Windows verification does not establish Linux/macOS launch or packaging.
- Have a Filipino-speaking teammate review the interface wording.
- Align file names and state/service decisions with Kiervy's completed tech spec before adding further desktop features.

