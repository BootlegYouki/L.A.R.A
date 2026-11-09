# Desktop #40: Hub connection

Issue #40 builds on merged discovery #5. It verifies a selected LAN Hub, remembers its nonsecret REST/WebSocket addresses and reconnects without hiding cached Home. Reachable does not mean signed in; accounts remain #45.

## Files and flow
- `src/services/api.ts`: official Tauri HTTP plugin; GET `/api/classrooms` on a validated private/loopback IPv4 and port. A 200 or unauthenticated 401 proves reachability; 3xx, other errors and timeouts do not. No token, cookies or account data are supplied. Redirects are disabled; response streams/deadline/listeners are cleaned up.
- `src/services/settings.ts`: nonsecret `lara-desktop-hub` preference, validated on read. Stores `rest_base` and `websocket_url` separately, preserving advertised WS port. No new SQL preference table. Storage failure leaves a usable session and visible warning.
- `src/services/connection.ts`: initial saved-Hub reconnect, otherwise first discovery candidate. One probe at a time; connected checks every three seconds, failures retry after 1/2/4/8/10 seconds. Selecting another Hub cancels the old probe and ignores late results. Discovery disappearance alone never proves a saved/manual Hub is unreachable.
- `src/pages/ConnectionPage.tsx`: discovered candidates and manual address form. It emits selections, performs no network/SQL work, and keeps the fallback visible during loading, empty and error states.
- `src/components/HubStatusBanner.tsx`, `src/App.tsx`: truthful connecting/connected/offline status within the existing Home shell. Saved classes and DB retry stay accessible. This flow uses the English dictionary regardless of old language preferences; legacy language infrastructure is removed by #225; see [english-only.md](english-only.md).
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


## Normal Windows-session follow-up (2026-11-04)

The user launched the built Desktop executable from their own PowerShell, confirmed the Classes empty state without the database error, and reported automatic connection after closing/reopening. Discovery was available, so this verifies automatic connection after restart, not saved-only recovery with discovery blocked.

The user then ran `work/check-desktop-migration.py` from normal PowerShell. Their supplied terminal screenshot reports the actual AppData database, migrations `(1, client_offline_schema, 1)` and `(2, sync_rejections, 1)`, and `Migration 2 complete: True`. The script opens SQLite read-only and additionally checks `sync_reject_reason` exists on all four contracted tables. This establishes that native migration 2 completed in the normal user session. The earlier managed-process readonly error/snapshot is an environment limitation and is superseded for this normal-session upgrade check; it is not evidence of a production migration failure.

Still unverified for #40: saved-only cold launch with discovery unavailable, the production installer/CSP and physical two-PC/WAN-disconnected connection checks. Unit tests and the earlier #5 discovery run do not replace these checks.

## Saved-address restart with discovery unavailable (2026-11-04)

The user closed Desktop. Before relaunch, the debug endpoint was confirmed closed and an independent socket successfully occupied UDP 8888. The native app was then started with the existing stable WebView2 test profile on G:. No address was typed and no Connect action was used. It displayed `Connected to Local Hub: Grade 4 - Science (Mock Hub)` and selected saved `192.168.100.2:8080`. Native `get_discovered_hubs` returned an empty array while the blocker was held. The existing validated preference contained REST `http://192.168.100.2:8080` and WebSocket `ws://192.168.100.2:8081`. Thus saved-address reconnection passed without discovery assistance. The temporary socket was stopped afterward; firewall/router settings were unchanged.

The first combined test additionally waited for the Classes empty state and failed that separate assertion: this managed launch again displayed the database error. A focused follow-up asserted only saved reconnect, selected address, empty discovery list and visible Classes heading; it passed and explicitly recorded `databaseError: true`. Do not interpret this as a successful managed-profile DB open. The user's normal-session migration-2 verification above remains distinct. Screenshot: `screenshots/connection-native-saved.png` includes the managed database error rather than concealing it.

Remaining #40 verification: production installer/CSP and physical two-PC/WAN-disconnected connection checks; Linux/macOS are not established by Windows tests. Saved-only startup is no longer an outstanding connection check for this Windows development profile.

## Production packaging attempt (2026-11-04)

`npm run tauri build -- --bundles nsis` compiled the optimized release executable and embedded frontend successfully. Default tool-cache extraction was denied in the managed process. A build-only override `work/tauri-local-tools.json` (`bundle.useLocalToolsDir: true`) moved tool caching to G: under the target directory; extraction/hash validation succeeded. NSIS then failed to start with Windows error 623, `Illegal System DLL Relocation`. No installer was produced, installed or verified. No endpoint-protection/ASLR/firewall settings were altered to bypass this. `work/Build-Desktop-Installer.cmd` reproduces the build in the user's normal session with process-local caches/temp on G:. It does not install or publish anything.

Second-PC Hub was started at 192.168.100.6. Initial cross-PC HTTP timed out with the Hub PC on Public; user switched that trusted network to Private and confirmed the download page then loaded in their main browser. The browser's ordinary HTTP "Not secure" label is not a failed page load. Managed HTTP probe still timed out; do not represent that probe as successful or count the installed two-PC connection test before it is actually run.

## Installed Windows build and real-Hub follow-up (2026-11-04)

This follow-up supersedes the packaging and two-PC pending results above. The user ran `work/Build-Desktop-Installer.cmd` in their normal Windows session: the production frontend built (49 modules, 1.11s), release compilation finished (28.17s), and NSIS produced `L.A.R.A._0.1.0_x64-setup.exe` (5.20 MiB). The user installed and launched that build, first connecting to the local mock and then the real Hub on a second physical Windows PC. This exercises embedded production assets and native HTTP capability in the installed app; it does not establish signed-in classroom access.

Topology: main Windows PC Ethernet `192.168.100.2`, second Windows PC Wi-Fi `192.168.100.6:8080`, same router. Device models were not recorded. The real Hub answered `/api/classrooms` locally with Unauthorized (expected unauthenticated 401) and listened on `0.0.0.0:8080`. Initial cross-PC TCP tests failed despite a Private network and an enabled inbound Allow rule. Two enabled Private inbound Block rules named `lara-hub.exe` were found. The user disabled only those Hub Block rules; the Codex sandbox block rule was left untouched. TCP 8080 then passed, followed by installed Desktop real-Hub connection. A temporary Private inbound TCP 8080 Allow rule scoped to main PC `192.168.100.2` was also created during diagnosis; successful results were obtained with that rule present. Do not claim the app allowance alone was sufficient or that the temporary rule was removed.

User-supplied physical test evidence:
- Real Hub connects at `192.168.100.6:8080`, with Home and empty Classes visible: `screenshots/connection-installed-real-hub.png`.
- User closed/reopened installed Desktop and confirmed automatic reconnect without clicking Connect.
- User closed the second-PC Hub terminal. Desktop became Not connected without a modal, retained the selected endpoint, and kept Home/Classes visible: `screenshots/connection-installed-real-offline.png`.
- User restarted the Hub and confirmed Desktop automatically reconnected without interaction.

These verify installed Windows real-Hub connection, persisted startup reconnect, outage detection and Hub-process recovery. They do not prove Wi-Fi-adapter recovery or a WAN-only outage: WAN remained connected, and stopping the Hub is a different test. Physical WAN-disconnected integration remains unverified (the home fiber gateway has no separate Ethernet WAN cable); do not disconnect fiber or represent the sprint WAN-unplugged criterion as passed. Linux/macOS, device models, signed-in class joining/sync, and later WebSocket/media features are also not established by this run. The earlier saved-only mock test is separate evidence for discovery-independent startup.

Pre-submission rerun (2026-11-04): 31 Desktop tests pass, lint passes, production build passes (49 modules, 1.14s); five Rust library tests pass with two opt-in physical #5 tests ignored; 66 shared tests pass in 12.782s; repository invariants and git diff --check pass. Only documentation/screenshots changed since the installed application tests.
