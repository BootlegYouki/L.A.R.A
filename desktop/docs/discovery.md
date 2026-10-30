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

Not verified: physical Wi-Fi disable/reconnect, WAN-unplugged router, another machine, Linux/macOS, production installer, release CSP/HTTP/WS/media permissions (owned by later issues), or a normal window-close lifecycle trace. The test processes were stopped explicitly; process exit releasing sockets is distinct from observing graceful scanner cancellation. Manual entry here only validates/selects an address; #40 must check and save a responding endpoint before calling it connected.
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

**Submission status: held.** Real-Hub mDNS and physical Wi-Fi reconnect/WAN-unplugged checks remain required. The old native-window screenshots still document the unchanged UI; the new core cancellation result does not replace a normal-window-close or fresh native-window check. No #5 PR has been published and #5 must remain open.
