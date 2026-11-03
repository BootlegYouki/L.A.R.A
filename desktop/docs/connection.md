# Desktop #40: Hub connection

Issue #40 builds on merged discovery #5. It verifies a selected LAN Hub, remembers its nonsecret REST/WebSocket addresses and reconnects without hiding cached Home. Reachable does not mean signed in; accounts remain #45.

## Files and flow
- `src/services/api.ts`: official Tauri HTTP plugin; GET `/api/classrooms` on a validated private/loopback IPv4 and port. A 200 or unauthenticated 401 proves reachability; 3xx, other errors and timeouts do not. No token, cookies or account data are supplied. Redirects are disabled; response streams/deadline/listeners are cleaned up.
- `src/services/settings.ts`: nonsecret `lara-desktop-hub` preference, validated on read. Stores `rest_base` and `websocket_url` separately, preserving advertised WS port. No new SQL preference table. Storage failure leaves a usable session and visible warning.
- `src/services/connection.ts`: initial saved-Hub reconnect, otherwise first discovery candidate. One probe at a time; connected checks every three seconds, failures retry after 1/2/4/8/10 seconds. Selecting another Hub cancels the old probe and ignores late results. Discovery disappearance alone never proves a saved/manual Hub is unreachable.
- `src/pages/ConnectionPage.tsx`: discovered candidates and manual address form. It emits selections, performs no network/SQL work, and keeps the fallback visible during loading, empty and error states.
- `src/components/HubStatusBanner.tsx`, `src/App.tsx`: truthful connecting/connected/offline status within the existing Home shell. Saved classes and DB retry stay accessible. This flow uses the English dictionary regardless of old language preferences; removal of legacy language infrastructure remains #225.
- `src-tauri/src/lib.rs`, Cargo lock/config/capability: registers native HTTP. CSP stays IPC-only for REST; no unrestricted WebView HTTP/WS or global Tauri object is needed.

## Trust boundaries and permissions
The HTTP capability permits only GET probe URLs under private/loopback IPv4 `/api/classrooms`; the service fixes method/path and never accepts arbitrary URLs. Host matching uses URLPattern regular expressions: partial numeric wildcard hosts do not match IPs as expected. A Rust test uses the plugin's URLPattern parser to allow private ranges/ports and reject public addresses, spoofed domain suffixes, other paths and HTTPS. No off-LAN/tunnel address or auth transport is introduced here. The probe is not server identity verification.

## Current schema prerequisite
Staging commit `0971ce3` added `REJECTED` plus nullable `sync_reject_reason` to four uploadable client tables. Migration 1 is unchanged. Migration 2 now adopts those exact contract changes by rebuilding leaf tables and retaining existing columns/queued payloads; indexes are restored. Tests compare the resulting columns/foreign keys with the current canonical schema and exercise preserved queued work/rejection storage. This adds no sync-engine behavior.

Kiervy's proposed `002_hub_connections.sql` was on this unmerged feature branch, not a released migration; it is replaced rather than shipping the unapproved settings table. Any developer who already installed that intermediate branch may have migration-2 history with a different checksum. Preserve/back up their data and use a separate disposable test profile; do not delete an existing DB to hide that mismatch. Production migration 1 and user data are not erased.

## Design
Existing prototype Home/nav preserved. The prototype has no connection screen; the connection controls remain an inline Home panel (rather than a blocking dialog), retaining manual access and saved classes together. Existing bundled Nunito/Phosphor and design tokens; 40px buttons, 52px input. The connected success surface uses canonical primary-light/dark, not nonexistent success-light/dark tokens. All new UI wording is English and dictionary-based.

## Test commands
In desktop/: `npm run lint`, `npm test`, `npm run build`, `cargo test --locked --manifest-path src-tauri/Cargo.toml --lib`, `cargo check --locked --manifest-path src-tauri/Cargo.toml --all-targets`.
From root: `python scripts/verify_invariants.py`, `python -m unittest discover tests`.
Native: start `python scripts/mock_hub.py` then `npm run tauri dev`. Confirm automatic connection, stop/restart Hub, invalid/manual entry with discovery unavailable, saved endpoint on restart and offline cached Home.

## Evidence (2026-11-04, Windows)
- 31 Desktop tests pass, lint and strict production build pass (49 modules, 1.38 seconds).
- Five default Rust tests pass, including permission-pattern regression; two #5 physical-network tests ignored by default. All-target check and native dev build pass.
- Actual Tauri window used native HTTP against the mock: unauthenticated 401 requests observed, connected status shown, invalid manual address rejected, loopback manual selection verified. Real localStorage preference contains separate REST/WS bases. No renderer page errors in the checked flow.
- Stopping the mock switches to offline and retains Home, empty classes and enabled manual input; restarting resumes probes automatically. Four 720/1200px and 100/200% font combinations show no document horizontal overflow.
- Screenshots: `screenshots/connection-native-online.png`, `screenshots/connection-native-offline.png`.
- Cold startup exposed an environment restriction: the existing AppData database returned SQLite code 8 (readonly) while running migration 2. Retry then read the prior schema because plugin-sql consumes its pending migrations on that first attempt. The old DB was preserved; do not interpret retry success as a completed upgrade. Unit tests prove the upgrade on writable SQLite databases; native upgrade of this protected existing profile remains unverified. WebView2 test data was explicitly directed to `G:/Codex/LARA/work/webview2-connection-tests` for a stable test browser profile. A normal Close/relaunch was exercised and the preference was present afterward, but automatic discovery was still available, so this does not independently prove saved-only startup recovery. The first attempted saved-only restart failed with the environment-limited profile and remains disclosed.

Not yet verified for this #40 change: production installer/native production CSP, physical two-device LAN/WAN-disconnected run, Linux/macOS, browser WS/media (later issues), and signed-in classroom fetch (accounts #45). #189's previous discovery evidence does not automatically establish these new connection behaviors. No PR published by this local review task yet.


Final repo checks: 66 shared tests passed in 13.676 seconds; offline invariants passed. No shared/server/mobile files were edited. The feature must still be checked outside the managed test process for native database upgrade, saved-only cold launch, production bundle permissions and two-PC network behavior before calling every #40 acceptance criterion verified. No test result here overrides that limit.
