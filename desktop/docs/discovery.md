# Desktop 1.2: Hub discovery

Issue: #5. Discovery identifies candidate addresses; it does not authenticate a Hub or user. Connection verification, saved addresses, HTTP transport permissions and the connection screen remain #40. No account, schema, credential or network contract changes are introduced.

## Files and flow

- `src-tauri/src/discovery.rs`: native Tokio UDP listener, mdns-sd browser, bounded source registry, `HubInfo`, `get_discovered_hubs` and local `hub-found` event.
- `src-tauri/src/lib.rs`: registers state/IPC, starts the scanner at setup and signals it to stop on app exit. main.rs remains thin.
- `src/services/discovery.ts`: validates native payloads and canonical local manual addresses; polls snapshots every second to remove expired rows and listens for first-discovery events. Cleanup handles late listener/IPC completion and prevents overlapping reads.
- `src/components/HubDiscovery.tsx`: nearby Hub cards and always-visible manual entry within Home. Selecting an address is session-only and explicitly unverified; it does not send a request or change the connection pill. This is the address-selection part of discovery, not the #40 connection flow.
- `src/App.tsx`: subscribes once for the mounted Home; cached classes and database retry remain independent of discovery. Also applies the Lead's next-UI follow-ups from #165: To-do row, Enrolled caret, Filipino Home wording.
- Paired dictionaries, token-based styles, parser/service/SSR tests and native screenshots accompany the feature.

The prototype does not define a discovery screen. The additional Home panel follows its existing content sheet, Nunito, Phosphor and design tokens. It preserves the existing shell and offline banner. Desktop buttons are at least 40px and the labelled address input is at least 52px. No new modal dependency is needed.

## Protocol and lifecycle

UDP listens on wildcard IPv4 port 8888. Each datagram is bounded to 4096 bytes before JSON parsing. Required fields are `app`, `version`, `name`, `ip`, `http_port`, `ws_port`. The app must be `lara`; version/name must be nonempty; names are at most 160 characters without control characters; ports must be numeric integers in 1..65535. Only canonical private/loopback IPv4 addresses are accepted, matching Mobile's current LAN policy. Unknown fields are ignored. No credentials are attached to advertisements.

mDNS browses `_lara._tcp.local.`. The native library supplies the trailing root dot; the on-wire service is the required `_lara._tcp.local`. Host and HTTP port come from resolved SRV/A records, with WS defaulting to the contracted 8081 because the Hub defines no mDNS TXT fields. Unsupported/public addresses are ignored. Source records are separate, with a maximum of 128 leases and 64 visible endpoints; rows deduplicate by IPv4 plus HTTP port. Live UDP metadata takes precedence because it supplies explicit HTTP and WebSocket ports; mDNS supplies only HTTP and uses the conventional WS default. Once the UDP lease expires, a live mDNS source remains available. Within the same source priority, most recently refreshed metadata wins.

A fresh mDNS browse is started every four timer ticks, stopping and shutting down the old daemon first. This renews leases from actual PTR responses rather than replaying a daemon cache as if a peer were live. The daemon also checks interface changes every two seconds. UDP bind failures are retried on the next one-second tick; mDNS failure does not stop UDP or manual entry. Entries expire after ten seconds without a fresh advertisement. Service removal drops only that mDNS source, preserving a live UDP source. A scan remains local; it never attempts tunnel discovery.

The React snapshot poll removes stale cards within another second. The three-second initial skeleton becomes a useful empty state; scanning continues. Losing a discovery advertisement does not establish loss of an authenticated connection. Until #40 verifies reachability, the existing disconnected pill/banner remain truthful. The selected address is neither a saved setting nor proof of connectivity.

## Verification (Windows, 2026-10-30)

- `npm run lint`: passes without warnings.
- `npm test`: service/manual-boundary, cleanup, late-result, SSR state/escaping tests plus existing SQLite/store/shell tests.
- `npm run build`: strict TypeScript and production Vite build.
- `cargo test --manifest-path src-tauri/Cargo.toml --lib`: beacon validation, source deduplication/expiry, bounded registry.
- Native Tauri window against `python scripts/mock_hub.py`: real UDP, command IPC and event permission path; invalid manual address rejected; valid selection remains explicitly unverified. Stopping the mock removes its row after lease expiry, leaving cached Home available.
- EN/FIL, 720/1200px widths, 100/200% base font: eight native layout checks pass with no horizontal document overflow. See [English](screenshots/discovery-native-en.png) and [Filipino](screenshots/discovery-native-fil.png).
- Real headless Hub built from staging with an isolated data directory on G: advertises its actual address and is discovered over UDP.
- Separate mDNS-only protocol fixture using mdns-sd 0.21.6 advertises the same service/port without UDP or HTTP. With port 8888 occupied by an independent test socket, the native scanner resolves that fixture and stays populated for a 20-second observation. This proves the mDNS scanner independently of UDP; it is not a real-Hub integration pass.
- Shared suite: 66 tests passed; offline/localization guardrail and final diff checks are required before submission.

## Outstanding integration checks

**Real-Hub mDNS remains unresolved on this Windows host.** The actual Hub logs incoming `_lara._tcp.local` questions, but the client does not receive a resolved Hub service. A scoped wire check observes the queries without corresponding L.A.R.A response packets. The same native scanner resolves the mDNS-only fixture. This narrows the discrepancy to the real responder/Windows network path, but does not establish its root cause. No firewall, other-team code or third-party dependency source was modified to make the test pass. Keep this limitation visible for the Lead/server team and repeat on separate LAN devices.

As of October 31, two Windows machines and Hub-side physical Wi-Fi disable/reconnect have been exercised (see below). Not verified: WAN-unplugged router, Desktop-side interface reconnection, Linux/macOS, production installer, release CSP/HTTP/WS/media permissions (owned by later issues), or a normal window-close lifecycle trace. The test processes were stopped explicitly; process exit releasing sockets is distinct from observing graceful scanner cancellation. Manual entry here only validates/selects an address; #40 must check and save a responding endpoint before calling it connected.
## Initial native-window check results (before history migration)

```text
npm run lint: exit 0
npm test: 6 files, 20 tests passed
npm run build: 44 modules transformed; built in 2.60s
cargo test --lib: 3 passed, 0 failed
cargo build: finished dev profile
python -m unittest discover tests: 66 tests in 26.089s, OK
python scripts/verify_invariants.py: PASSED
git diff --check: PASSED
```

Node reports its existing experimental SQLite warning. Rust reports the G: filesystem's unsupported incremental hard-link operation and copies files instead; no Rust code warning was reported. The Windows sandbox denied the automated WM_CLOSE message (Win32 error 5), so graceful-close behavior remains unverified. Forced termination of the test process released UDP 8888, confirmed by binding it again. The real native `hub-found` subscription received an independently sent valid beacon. No debug logging or testing permissions were added to the product.


## History migration and follow-up verification (October 30)

Following Discussion #185, the feature was recreated from rewritten staging `2390e21` as `feat/desktop-1.2-discovery-v2`. Only our own feature commit was cherry-picked (`f8a4629` became `f439c19`); the old branch was retained. No old staging merge was imported, no protected branch was pushed and no AI co-author trailers were added.

Review found an explicit UDP WebSocket port could be replaced by mDNS's default. The registry now prefers live UDP metadata for the same endpoint. A regression test checks explicit port 8085 is retained while the UDP lease is live and falls back to mDNS only after expiry.

The scanner core now takes the same shared registry and a notification callback; the Tauri adapter emits the existing `hub-found` event. This permits exercising the real scanner loop without a GUI or fake sockets. There is no new product command, payload or dependency.

Run the opt-in network test alone on a local network, with UDP 8888 free and no other L.A.R.A. advertiser running, from desktop/:

```powershell
$env:LARA_TEST_MDNS_IP = '<this-PC-LAN-IPv4>'
cargo test --locked --manifest-path src-tauri/Cargo.toml --lib native_scanner_network_lifecycle -- --ignored --nocapture
```

The ignored-by-default marker prevents normal CI from depending on host multicast/socket access; this test WAS explicitly run. It passed in 43.78 seconds. It checks actual UDP reception and notification, expiry after a stopped advertiser, rediscovery, cooperative scanner cancellation and UDP port release. It then occupies UDP 8888, advertises an independent mDNS-only fixture, checks twenty seconds of stable discovery, removes the publisher, checks expiry and cancels the scanner. This is a protocol stop/return test, not physical Wi-Fi reconnection and not a normal-window-close test.

Current automated results:

```text
npm run lint: exit 0
npm test: 20 passed
npm run build: 44 modules, built in 1.95s
cargo test --locked --lib: 4 passed, 1 opt-in network test ignored by default
explicit native_scanner_network_lifecycle run: 1 passed in 43.78s
cargo check --locked --all-targets: PASSED
python -m unittest discover tests: 66 passed in 21.392s
python scripts/verify_invariants.py: PASSED
```

A second scratch executable directly included the actual Hub `server/backend/src/discovery/mdns.rs` and used its unchanged `start` function with libmdns 0.10.1; a plain mdns-sd 0.21.6 browser did not resolve that service during a twelve-second observation on this Windows host. The failure reproduces outside our scanner's lease/restart logic and outside the full Hub's HTTP/database runtime. The independent mdns-sd publisher succeeds. This narrows the responder/querier interoperability or local Windows network-path discrepancy; it does not prove the exact cause or justify silently changing server code or firewall settings.

**Submission status: prepared for review with incomplete integration checks.** See the October 31 evidence below. Real-Hub mDNS-only and WAN-disconnected operation remain unverified. No #5 PR has been published and #5 remains open; the Lead decides whether the disclosed gaps block review or merge.


## Two-PC integration evidence (October 31, 2026)

The local feature branch was rebased onto rewritten staging `e829179`; its two implementation commits are now `67238cf` and `6717c4c`. The intervening upstream changes affect Mobile only.

A fresh native Desktop build ran on the primary Windows PC (Ethernet, 192.168.100.2). The unchanged real headless Hub was built from that staging source and copied to a second Windows PC (Wi-Fi, 192.168.100.6), using an isolated synthetic test-data directory and the name `Desktop Integration Test Hub`. This was a development executable, not an installer or signed release. No server source was changed.

Observed evidence, with its provenance:

- User-supplied Hub terminal photos show HTTP listening on wildcard :8080, WebSocket :8081, UDP beacon startup and mDNS registration startup. Startup logging alone does not prove receipt of mDNS responses.
- Initial cross-PC TCP :8080 probes timed out and the Desktop list was empty. The user allowed the Hub through its Windows firewall prompt and changed the second PC's trusted network profile to Private. This session does not isolate the exact cause of the initial timeout or establish a later successful TCP probe.
- A user-supplied Desktop screenshot subsequently shows the real second-PC candidate `Desktop Integration Test Hub`, `192.168.100.6:8080`. This establishes real-Hub auto-discovery across two machines, but does not distinguish UDP from mDNS or establish an authenticated connection.
- After the user disabled the Hub PC's Wi-Fi, their screenshot shows the candidate removed while Home, the empty state and manual entry remain visible.
- The user first recovered discovery after a Hub restart. In a separate follow-up, they confirmed the candidate returned after toggling the Hub PC's Wi-Fi off/on with both applications left running. This is user-reported Hub-side network recovery; it does not establish Desktop-side interface recovery or an instrumented recovery latency.
- The native header remains `Not connected to Hub`: address discovery/selection does not perform #40 connection verification.

Refreshed checks: `npm run lint` exit 0, 20 tests in 6 files passed, `npm run build` passed (44 modules, 1.38 seconds), and `cargo build --locked` passed. Earlier Rust/shared-suite results above remain historical; they were not rerun for this documentation-only update. The same-PC real-Hub mDNS compatibility reproduction still exited 2 (`actual_hub_responder_resolved=false`).

WAN-only test: NOT PERFORMED. The user's fiber modem/router has fiber, power and a LAN Ethernet connection, with no separate Ethernet WAN uplink to unplug. No fiber cable was disconnected and no router setting was changed. Turning off the Hub PC's Wi-Fi is loss of the local network, not loss of the WAN. Repeat using a suitable isolated test router before claiming WAN-disconnected operation.

Remaining review limitations: real-Hub mDNS-only discovery on separate devices; WAN-only isolation; Desktop-side network-adapter recovery; normal native window-close lifecycle; Linux/macOS and release packaging. The independent mDNS fixture and scanner cancellation tests remain useful evidence but do not replace these checks. No completion or merge claim is made.

## Real-Hub mDNS-only attempt (October 31)

With the second-PC Hub running at 192.168.100.6 and Wi-Fi connected, a standalone mdns-sd 0.21.6 browser (no UDP beacon listener) resolved `Desktop Integration Test Hub._lara._tcp.local.`, host `DESKTOP-1MA0V9G.local.`, HTTP 8080, address 192.168.100.6. This proves the available network can carry real-Hub mDNS responses.

The actual Desktop scanner was then exercised by the opt-in `real_hub_mdns_without_udp` test. A separate socket holds UDP 8888 for the entire test, so the scanner cannot receive beacon datagrams. It waits up to 25 seconds for the expected Hub, then would require 20 seconds of stable visibility and cancel/join the scanner. This run FAILED: the candidate did not resolve within 25 seconds.

A one-variable experiment temporarily disabled periodic mDNS daemon restarts. It failed with the same 25-second resolution timeout; that production-code experiment was reverted. A subsequent standalone browser also timed out without resolving the service. The user confirmed the second-PC Hub and Wi-Fi remained running and no new firewall prompt appeared. No firewall/security changes were made during these attempts. These mixed results do not establish root cause or a reliable integration pass.

The opt-in test is retained for reproducible investigation, ignored by default because it requires another real LAN device. Close Desktop and all other UDP 8888 listeners, keep the second-PC Hub running, and run from desktop/:

```powershell
$env:LARA_TEST_REAL_HUB_IP = '<real-second-PC-Hub-IPv4>'
cargo test --locked --manifest-path src-tauri/Cargo.toml --lib real_hub_mdns_without_udp -- --ignored --nocapture
```

Default Rust suite after restoring production code: 4 passed, 2 integration tests ignored. The explicit real-Hub test above failed and must not be represented as passing by the default suite. Product scanner behavior was not changed. WAN connectivity remained available throughout; this was not a WAN-isolation test. Real-Hub mDNS reliability remains unresolved.

## Review handoff (October 31)

Scope remains Desktop only. The server team owns any responder change. A scratch diagnostic copy of libmdns reproduced the local failure; selecting its outgoing IPv4 multicast LAN interface passed, reverting that one change failed, and restoring it passed again. No server repository source or cached dependency was changed. This isolates an outbound-interface issue in that same-PC reproduction; it does not establish that the second-PC failure has the identical cause or that a server fix has shipped. The server team should validate independently. No workaround was added to Desktop.

Final review-preparation checks on this branch: lint passed, 20 Desktop tests passed, production build passed (44 modules, 1.31 seconds), shared suite 66 tests passed in 12.286 seconds, invariant check passed. Default Rust suite most recently passed 4 tests with 2 explicit integration tests ignored. The explicit real-Hub mDNS-only test failed as recorded above; default tests do not waive that failure. Diff scope and whitespace checked. Keep #5 open and submit as draft with these limitations.
