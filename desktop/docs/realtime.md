# Joining classes and live approval (#46)

Learners enter the teacher's six-character class code in a Radix dialog. Six boxes support capitalization, whole-code paste, arrows and backspace. The service removes separators and validates the contract alphabet. English resources and existing design tokens supply the UI; the approved Home layout remains intact.

## Files and flow

- `components/JoinClassModal.tsx`: modal, entry, loading/error/offline/cooldown feedback. No automatic join retry. Closing does not cancel a server request already accepted.
- `services/classrooms.ts`: authenticated fixed-path join POST, strict receipt parser and native persistence; reuses the selected-Hub HTTP boundary, timeouts, redirect rejection and 401/429 handling.
- `services/realtime.ts`: selected validated Hub's separate WS port; native active-account token, EVENT_HELLO, validated ACK before approval hints, learner-ID filtering, reconnect at 1/2/4/8/10 seconds. 4401 or UNAUTHORIZED ends the session. Disposal ignores delayed credential results.
- `src-tauri/src/account.rs`: `save_join_status({hub,userId,receipt}) -> CachedAccount`, now including `join_requests: {classroom_id,status}[]`. Independently validates learner, active context, UUID and status.
- `App.tsx`, Home and Sidebar: Pending/Rejected/Removed are status cards, never class entry points. ACTIVE requires canonical authenticated classroom GET confirmation. NonACTIVE hints lock an open class immediately. Safe failed GETs retry after five seconds; server cooldowns take precedence.

Existing per-account SQLite `sync_state` stores exact receipts in `account_join_requests` and canonical learner statuses in `account_class_statuses`. Snapshots update metadata/statuses/visible IDs atomically. Existing #45 caches remain readable; their previously validated active IDs have the legacy ACTIVE fallback. No enrollment UUID, teacher name, cursor, new table or migration is invented. Pending metadata/receipt-only requests survive restart; queued children and old metadata remain when access is removed.

GET includes only ACTIVE/PENDING learner classes. A received rejection/removal is saved. Missing classes disappear from accessible metadata after the next GET. An omitted old Pending receipt retains its last known status rather than being falsely relabelled Rejected. Full sync, cursor/epoch reconciliation and exact missed-decision recovery remain #48. No quiz recovery or sign-out is included in #46. Teacher roster/create-class UI is now implemented by [#47](roster.md).

## Permissions and safety

Only `/api/classrooms/join` is added to existing private-IPv4 native HTTP patterns. The static transport CSP includes `ws:` for dynamic LAN addresses. The #47 follow-up adds an intersecting immutable document policy limited to the exact selected saved Hub; changing Hub reloads the document. See [roster security notes](roster.md#selected-hub-websocket-policy-lead-245-follow-up). Realtime independently validates selected private/loopback IP/ports before reading credentials or opening a socket. No public HTTP, remote Hub or script source is enabled.

Tokens stay in OS credentials/session memory. Session invalidation advances the operation generation, aborts outstanding work and clears the account reference so a delayed native receipt cannot restore an expired account. Hub/account switching tears down realtime. ACTIVE hints alone never grant access.

Production console testing exposed Radix's generated scroll-lock style being refused by CSP. `index.html` now carries Tauri's style nonce placeholder in a meta element; Tauri replaces it per document and adds that nonce to the policy. Before React renders, `main.tsx` passes the replaced nonce to the existing `get-nonce` singleton used by Radix's stylesheet helper. Production `style-src` stays strict: no `unsafe-inline`, static nonce, script change or disabled CSP injection. The final packaged test verified the actual replaced nonce, stylesheet acceptance, computed body overflow lock and unlock, and zero CSP console errors. This follows Tauri's installed `STYLE_NONCE_TOKEN`/`replace_csp_nonce` implementation and [CSP mechanism](https://v2.tauri.app/security/csp/).

Lead #240 follow-ups: visibly muted disabled controls without hover feedback, and cleared server errors on login/register navigation. Home cards still omit class codes and invented progress/counts.

## Verification so far

- Frontend: 57 tests/15 files; lint passes. Includes normalization, exact receipts, event filtering/backoff/disposal/private-Hub validation, status UI gates and actual React effects for delayed-receipt/401 and failed-GET recovery. Both review regressions failed before fixes and passed afterward.
- Native: 11 pass, three existing opt-in OS/network tests ignored. Covers pending restart, legacy compatibility, rollback, queued retention, no invented enrollments and canonical ACTIVE confirmation.
- Shared: 67 contract/schema/mock tests pass; offline invariants pass.
- TypeScript/Vite and optimized native executable builds pass; no installer test claimed.
- Follow-up source review found no further actionable issue.

Radix implements the required house dialog. Development-only `react-test-renderer` exercises security-sensitive App races and emits React's deprecation notice. Node emits the existing experimental SQLite notice. npm audit reports existing development-tool advisories including Vitest/tinypool; no blanket major-version audit fix was applied in this feature.

## Production native interaction evidence

On the physical Windows PC (B450M Steel Legend / Windows 11 Pro), the optimized executable used embedded `tauri.localhost` assets, actual native HTTP/SQLite/Windows credentials and browser WebSocket. The user launched/restarted the test executable; loopback inspection used an isolated WebView profile on G: and process-only arguments. No debugging capability or launch argument was added to the shipped app.

Against the mock on test ports 8180/8181: empty Home, keyboard entry, lowercase/grouped paste, Escape/focus restoration, invalid-code feedback, saved Pending, live approval, live removal while the class was open, live rejection and rejoining all passed. Pending never exposed Open or Sidebar navigation. Stopping the mock kept the Pending card visible; the offline dialog disabled submission. The user closed and reopened the entire app while the Hub remained stopped. A changed browser-process endpoint confirmed the cold restart, and Pending reopened without a PIN or class access.

The latest real Rust backend was built unchanged and run with `LARA_TEST_SEED=1` and isolated data at `G:/Codex/LARA/work/real-hub-46-data`. Test setup used the existing teacher API to create two synthetic classrooms (not a claim of Desktop create-class UI). Production Desktop signed in as joining seed learner `123456789013`, joined each through the dialog, and received actual `EVENT_HELLO_ACK`, `ACTIVE` and `REJECTED` frames. Approval unlocked without reload; rejection stayed locked. With the real Hub stopped, its cached approved class and rejected receipt remained without a blocking login. Restarting the same durable Hub data reconnected without a PIN. The full app was subsequently reopened for the final production nonce/scroll-lock check, retaining its real-Hub account.

At 720/1200px and 100/200% root text, Home/dialog had no horizontal overflow. Login/register navigation cleared both wrong-PIN and admin-refusal feedback. No renderer error was captured. Screenshots `screenshots/join-*.png` cover dialog, empty, invalid, Pending, approved, rejected, removed, offline dialog/Home/restart, and real-Hub Pending/approved/rejected/offline/recovered; `join-production-csp.png` captures the final nonce-enabled dialog.

The final optimized build completed in 37.34 seconds. No Server source, shared contract/rule/design file, original database or queued work was changed or deleted.

## Repeat the checks

1. Start the mock from repo root. Run Desktop with native prerequisites; connect to the mock.
2. Sign in as seed joining learner `123456789013` / PIN `1234`. Open Join class, enter `K7M-4QX`, and expect Waiting for approval with no Open class.
3. From a separate teacher/API session (`T-0001` / `1234`), approve that learner. Expect the card to unlock without a refresh. Remove/reject through the existing teacher API to check status lockout; teacher UI is #47.
4. Rejoin to Pending, stop the mock, close Desktop fully and reopen: Pending stays and no PIN or blocking offline dialog appears.
5. Repeat join/approve/reject against a seeded real Hub with isolated test data. Create the fixture class through the API. Confirm actual WebSocket frames, stop/restart the Hub, and inspect the production console when opening the dialog.

## Not verified

WAN-disconnected classroom operation, second-PC firewall behavior, Linux/macOS #46 runtime, a new installer, full screen-reader audit and low-spec performance are not claimed. Full sync and exact missed-rejection recovery remain #48, as above. Native operations can complete after a UI timeout; generation/context guards prevent stale visible-account restoration. npm's pre-existing development advisories remain (the baseline and final Vitest/tinypool versions are unchanged).
