# Hub account console (#43)

The Hub window lets the school admin set up `ADMIN-0001`, sign in, list accounts, create teachers and learners, and reset their PINs. The Lead approved these five flows on #43. Deactivation is deferred to a contract-change and follow-up issue. The console is English only and uses desktop targets.

## Key files and data flow

- `ui/src/pages/AdminUsers.tsx`: setup/login, account table, create/reset dialogs, one-time PIN receipt, and connection states.
- `ui/src/admin.ts`: REST calls to `http://127.0.0.1:8080`, five-second timeouts, wire types, and the setup navigation hint.
- `ui/src/i18n/en.json`: all console wording. The existing Nunito, Phosphor and Tailwind token imports supply its appearance.
- `backend/src/routes/admin.rs`: existing contract routes, guarded by `AuthUser` and `require_role(Role::Admin)`. Setup/login retain the Hub-PC and tunnel checks implemented in #42.
- `backend/src/db/users.rs`: explicit `User` projections, account inserts, and PIN updates. The model cannot serialize a PIN/hash.
- `backend/tests/admin_users.rs`: account login, reset durability, input validation, concurrent duplicates, role guards, response redaction and CORS.
- `ui/tests/admin-console.cjs`: browser acceptance checks against an isolated mock or real Hub.

The window uses REST directly, as TECH_SPEC section 2 requires. It adds no Tauri account commands, REST routes, events, columns or migrations. `src-tauri/tauri.conf.json` permits the loopback connection and Tauri IPC. CORS allows the Tauri origins and the two local Vite origins; it grants no authentication. Other origins receive no CORS permission. The existing `tower-http` dependency enables its `cors` feature.

Creation and reset hash PINs through the existing bounded `PinHasher` before taking a write lock. A short `BEGIN IMMEDIATE` transaction commits the account change and its non-admin `sync_revisions` entry together. The admin gets no sync revision. Reset retains existing sessions, matching the mock; it does not add session revocation behavior.

The real create/reset handlers validate four ASCII PIN digits as the contract requires; the mock is more permissive for malformed create/reset requests. The native window lockfile now also includes the backend's existing hashing dependencies, which were missing from that lockfile.

## State and failure handling

The table holds the last successful account list in memory while signed in. A failed refresh keeps that list visible, marks the Hub unavailable and disables creation/reset. Open forms retain their inputs. Refresh retries every five seconds, with a manual refresh button. Failed writes never retry automatically: the outcome may be uncertain if a connection drops after commit. The banner tells the admin to refresh before retrying.

PIN inputs are four masked boxes with digit advance, backspace and whole-PIN paste. A successful create/reset shows the typed PIN once in a dialog. Dismissal, Escape, sign-out and session expiry remove it. PINs, tokens and account lists are never written to browser storage. Logout forgets the local session even if the Hub cannot be reached; any server session that could not be revoked retains its normal expiry.

The sole browser-storage value, `lara:admin-configured`, is a boolean navigation hint. A fresh window profile shows setup; a successful setup/login or `ADMIN_EXISTS` switches to login. If the Hub data directory or window profile changes, the explicit setup/sign-in link handles the mismatch. This avoids inventing an admin-status API or opening the database from the window. The Hub remains the authority for setup and authentication. Reopening the window requires sign-in; account data is not exposed before authentication when the Hub is unavailable.

The page uses the existing Hub address panel above the account table. Dialogs use native modal focus containment, Escape and focus restoration. Filled actions use Dark Green for readable white text; all buttons are at least 40px. Inputs use the documented 52px minimum and PIN boxes are 52 × 60px. No new visual assets or packages are needed. `vite.config.ts` isolates PostCSS config so a parent-folder Tailwind installation cannot override the Hub's Tailwind 4 setup.

## How to test without programming

Ask a developer to start the Hub window with a temporary, empty Hub data folder. Do not use a school database for these checks.

1. On **Set the admin PIN**, enter your name and a four-digit test PIN. Select **Set admin PIN**. The Accounts screen opens with the admin listed.
2. Select **Sign out**, close and reopen the window, and enter the admin PIN. Accounts must remain hidden until sign-in succeeds. A wrong PIN must show a readable error.
3. Select **Create account**. Enter a test teacher's full name and unique ID, choose **Teacher**, enter a PIN with a leading zero, and save. Record the PIN privately, then dismiss the confirmation. It must disappear.
4. Sign in to a test learner/teacher app with that teacher ID and PIN. It should sign in as a teacher. Repeat account creation with **Learner** and a test LRN.
5. On the Hub, select the teacher's **Reset PIN** button. Enter a different PIN and save. The old PIN must fail at the app's sign-in screen; the new PIN must work. Restarting the Hub must preserve the change.
6. Try creating the same ID again. The console must explain that it already exists and keep the account list intact.
7. Open a creation form and type a test name. Ask the developer to stop the backend while leaving the window open, or disconnect its loopback requests in the development browser. Within the refresh/timeout period, **Hub unavailable** must appear. The draft and last loaded accounts remain; saving is disabled. Restore the backend connection and refresh. The draft should still be present.
8. Navigate with Tab and Enter. Escape must close a form or PIN confirmation and return focus to its trigger. Sign out; accounts and PINs must disappear.

For the final classroom check, disconnect the router's WAN cable and repeat setup, account creation, app login and PIN reset. The browser checks below block nonlocal HTTP requests but do not replace this physical check.

## Developer verification

From `server/backend/`: `cargo check --all-targets` and `cargo test`.
From `server/ui/`: `npm run build`.
From the repository root: `python3 -m unittest discover tests` and `python3 scripts/verify_invariants.py`.
On Windows, use the installed Python executable and set `PYTHONUTF8=1` for the suite and its subprocesses.

For browser checks, first build the backend binary and start the UI on `127.0.0.1:5173`. Keep ports 8080/8081 free. From `server/ui/`, run `node tests/admin-console.cjs mock`, then `node tests/admin-console.cjs real`. The runner starts/stops its own Hub and creates a fresh real data directory under `backend/target/`. It refuses an occupied HTTP port.

The runner requires an available Playwright installation and browser. Set `LARA_PLAYWRIGHT_PACKAGE` to its package path if it is not locally installed, `LARA_CHROMIUM_EXECUTABLE` for an existing Chromium executable, and `LARA_PYTHON` if the mock needs an explicit Python path. These are development tools, not runtime dependencies. It simulates only the `lan_address` OS command; every account request uses the selected Hub.

## Acceptance evidence

| Criterion | Evidence |
| --- | --- |
| Admin creates a teacher that can sign in | Browser flows against mock and real Hub; Rust `created_accounts_sign_in_and_reset_pin_survives_restart` also checks learner creation, leading-zero PINs and old/new PIN login. |
| Only the admin reaches account routes | Rust `account_routes_require_admin_and_do_not_change_data_for_other_roles`; browser-created teachers/learners receive 403 on the admin list. Existing setup/login tests cover LAN/tunnel restrictions. |
| Hub unavailable preserves local screen data | Browser checks abort loopback account requests, retain the table and open draft, disable saving and recover after refresh. Delayed list responses verify the loading skeleton; an empty response verifies the empty state. See the screenshots. |
| English, design tokens, desktop targets | English dictionary and existing token/font/icon imports; browser checks assert visible buttons are at least 40px. Text contrast: Dark Green on white 6.58:1; Secondary text on white 4.97:1. |
| Team checks pass | `cargo check --all-targets`, `cargo test`, UI build, all 66 repository tests and the invariant scanner passed. Output excerpts below. |

```text
cargo check --all-targets
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 49.27s

cargo test
    Finished `test` profile [unoptimized + debuginfo] target(s) in 10m 09s
    tests/admin_setup.rs: 13 passed; 0 failed
    tests/admin_users.rs: 4 passed; 0 failed
    All remaining unit, integration and doc-test groups passed.

npm run build
    tsc -b && vite build
    24 modules transformed; built successfully

cargo check (server/src-tauri/)
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 10m 29s

python -m unittest discover tests (PYTHONUTF8=1)
    Ran 66 tests in 96.390s
    OK

python -X utf8 scripts/verify_invariants.py
    All L.A.R.A offline invariants PASSED.
```

Screenshots use synthetic test accounts, with the PIN confirmations dismissed:

- [Mock Hub accounts](evidence/issue-43-mock-accounts.png)
- [Mock Hub loading skeleton](evidence/issue-43-mock-loading.png)
- [Mock Hub unavailable with retained draft](evidence/issue-43-mock-offline.png)
- [Real Hub accounts](evidence/issue-43-real-accounts.png)
- [Real Hub loading skeleton](evidence/issue-43-real-loading.png)
- [Real Hub unavailable with retained draft](evidence/issue-43-real-offline.png)

Browser verification covers the actual React UI and REST behavior. Native WebView interaction, physical WAN disconnection and a second classroom device still need the manual check above.
