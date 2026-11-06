# Teacher classrooms and roster (#47)

Teachers can create a class, copy its generated `XXX-XXX` code, and open People to review join requests. The current prototype/design rules supply the Home, class bar, 40px controls, 68px roster rows and Radix dialogs. Home cards never show class codes. All copy is in `src/i18n/en.json`.

## Files and data flow

- `CreateClassroomDialog.tsx`: required trimmed name, optional section, canonical creation receipt and truthful clipboard feedback. A successful Hub POST followed by a failed local save still shows its code and a cache warning; it never offers another creation POST. A subsequent safe classroom GET can recover the saved metadata.
- `TopAppBar.tsx` and `RosterPage.tsx`: selected class/code, semantic name/status/action table, empty/loading/error states, manual Refresh and retained rejected/removed rows. Pending rows allow Accept/Decline; Active rows allow Remove through a confirmation that starts on Cancel and explains that scores/submissions remain. Offline rows are read only.
- `classrooms.ts`/`api.ts`: exact contract routes, private IPv4 Hub validation, active teacher credentials, owned/co-taught class checks, strict UUIDs and response parsers. Mutations run once; their result is followed by a canonical roster GET. Failures never optimistically grant/remove access. HTTP 401 expires the session; 429 respects cooldown; 501 stays an explicit unsupported-Hub error.
- `realtime.ts`: after validated ACK, teacher `EVENT_JOIN_REQUEST` is only a refresh hint for known class codes. Event names/LRNs are discarded; counts come from fetched Pending rows, never increments guessed from events. Reconnect refreshes canonical classes and teacher rosters. The existing learner approval flow remains intact.
- `App.tsx`: bounded roster reads (four concurrent), per-class cancellation and account/Hub generations. Account updates synchronously publish the latest reference before React renders, preventing concurrent class reads from overwriting another roster. Switching to a learner cannot restore a delayed teacher result.
- `account.rs`: `save_roster_snapshot` and `clear_roster_snapshot` use the existing account-scoped SQLite `sync_state.account_rosters`. Only server enrollment/student UUIDs, full name, status and optional joined time are retained. No LRN, PIN, token, invented enrollment, schema change or sync cursor. Native commands independently require the active teacher and an owned/co-taught cached class. Confirmed GET 403 drops that cached snapshot; it is absent, not a fabricated empty roster. Transactions preserve queued children and unrelated classes. Learner restoration always returns no rosters.

## Selected-Hub WebSocket policy (lead #245 follow-up)

`main.tsx` installs an additional, immutable document CSP before React starts. Its `connect-src` permits only the validated saved Hub's exact private/loopback WebSocket origin, local IPC and same-origin assets (plus loopback Vite HMR in development). Realtime checks that policy before reading a token. The Tauri static transport template still contains `ws:`, but the intersecting document policy blocks public and nonselected sockets. A different successfully saved Hub starts a fresh document rather than adding more allowed hosts. Failed preference persistence leaves live updates unavailable, with explicit feedback and manual REST refresh available.

CSP policies intersect and a processed meta policy cannot be relaxed by changing/removing its element: [CSP specification](https://www.w3.org/TR/2026/WD-CSP3-20260306/) and [HTML CSP meta rules](https://html.spec.whatwg.org/multipage/semantics.html). Native tests confirmed both public and alternate local socket attempts were blocked; switching from the mock to the real Hub replaced the allowed origin. No permissive inline styles or script sources were added; the existing per-document Radix style nonce remains.

## Verification (2026-11-07)

- `npm test`: **76 passed / 19 files**; `npm run lint`: zero warnings/errors.
- Native `cargo test --manifest-path desktop/src-tauri/Cargo.toml`: **13 passed**, three existing opt-in OS/network tests ignored. Covers roster rollback/reopen, context/role isolation, field whitelist, confirmed-forbidden invalidation, queued retention and exact HTTP permission routes.
- Shared `python -m unittest discover tests`: **67 passed**; `python scripts/verify_invariants.py`: passed.
- Final production `npm run tauri -- build --no-bundle --config G:/Codex/LARA/work/tauri-local-tools.json`: TypeScript/Vite and optimized executable passed; native release phase **40.36s**. No new dependencies.
- Regression tests reproduced the concurrent roster merge race before its fix. Service tests cover successful create + failed cache write without repeating POST, invalid payloads, learner refusal, decisions and 403 snapshot invalidation. Cache-write fault injection is unit-tested; no actual disk-failure test is claimed.

The physical Windows 11 Pro / B450M Steel Legend PC ran embedded `http://tauri.localhost` assets, actual native HTTP/SQLite/Windows credentials and browser WebSocket. An isolated WebView profile on G: and process-only loopback inspection were used; no debugging flag or capability ships with the app. Test accounts/data were synthetic. Original databases and queued work were not erased.

### Mock Hub

On isolated test ports 8280/8281, the production window created a class and copied its code, showed its empty roster, received a second session's live join request and exact Pending count, accepted it and unlocked the learner via actual approval event, declined another request, cancelled then confirmed removal and observed the learner's access disappear. Rejected/Removed records stayed visible. A third Pending row, cached read-only rows and count survived stopping the Hub and a full process restart without a PIN. At 720/1200px and 100/200% root text there was no horizontal overflow. No renderer exception was captured. Dialog focus restoration/default Cancel and Escape were checked.

### Real Hub from staging `f5f5cfd`

The unchanged Rust backend ran with `LARA_TEST_SEED=1` and isolated `G:/Codex/LARA/work/real-hub-47-data`. The production Desktop created/copied a class and displayed its empty roster. A second authenticated session joined through the existing API; actual teacher ACK/JOIN_REQUEST frames refreshed the Pending row/count. Actual UI Accept produced the learner's ACTIVE event and canonical class access; Decline produced REJECTED. A learner account in Desktop exposed no teacher People view or native roster cache. Switching back restored the teacher's canonical roster.

**Real removal returned HTTP 501 NOT_IMPLEMENTED.** The UI reported it and kept the learner Active; removal success is mock-only pending Server #121/#232. No backend workaround or Server source change was made.

Stopping the real Hub retained the two cached rows with disabled actions. Closing and reopening the entire final executable produced a different browser-process endpoint, restored those rows without a PIN, and remained read only. Restarting the durable Hub reconnected without a PIN. A subsequent real creation also completed. No renderer exception was captured in the main-flow runs.

Screenshots in `screenshots/roster-*.png` cover create/code/empty, live Pending, accepted, remove confirmation, decisions, learner access, offline/cold restart/recovered and real unsupported-removal error. `roster-real-pending.png` shows the final red pending badge. Early mock screenshots precede that badge color adjustment; behavior is unchanged.

## Repeat

1. Start `python scripts/mock_hub.py`, or the real staging Hub with `LARA_TEST_SEED=1` and a new isolated `LARA_DATA_DIR`. Connect Desktop and sign in as `T-0001` / seed PIN `1234`.
2. Create a class from Home. Check its generated code, Copy feedback, empty People view, and absence of codes on Home cards.
3. In a second authenticated learner/API session (`123456789013` / seed PIN), join that code. Without reloading Desktop, expect Pending and count 1. Accept: expect Active and learner access. Repeat with another synthetic learner for Decline.
4. For Active, open Remove, cancel/Escape first, then confirm. The mock marks Removed and locks learner access; current real staging returns the documented 501 and keeps Active.
5. Stop the Hub, close Desktop fully, reopen it: saved rows remain read only without a PIN. Restart the same Hub data and check recovery. Switch to a learner and confirm no teacher roster is exposed.

## Not verified / limits

Real-Hub removal remains 501 as above. WAN-unplugged operation, a second physical PC for #47 live pushes, low-spec performance, Linux/macOS runtime, a new installer and full screen-reader audit are not claimed. These checks do not establish classroom integration across several devices. Full sync/cursor/epoch reconciliation and exact recovery of missed decisions remain #48; sign-out and class settings remain their ordered issues. Concurrent native operations may finish after a UI timeout, but generation/context guards prevent stale visible-account restoration.

Node's experimental SQLite and React test-renderer deprecation notices, Cargo's cross-drive hardlink fallback and inherited development-tool advisories are environment/baseline limits, not suppressed checks. Hosted CI status is reported separately in the PR; local passes do not establish hosted CI success.
