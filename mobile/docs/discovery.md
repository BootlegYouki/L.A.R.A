# MOBILE 1.2 — Hub discovery (#4)

## Purpose and entry points

The startup route is now Discovery. Home/About remain accessible through Back to
home, with a Find classroom Hub action to return. No Room, authentication, role
graphs, sync, remote URLs, or fake classroom records are introduced.

`MainActivity -> AppContainer.discovery -> HubDiscovery -> DiscoveryViewModel ->
DiscoveryScreen`. UI imports domain models/interfaces only. The application
container owns the Android implementation; ViewModel factories use manual DI.

## Files and data flow

- `domain/discovery/HubDiscovery.kt`: pure Kotlin discovery interface, Hub metadata,
  snapshots, and connection outcomes.
- `data/remote/discovery/HubEndpoint.kt`: private constructor and validated LAN/manual
  factories. Reuses MOBILE 1.1 `isAllowedHubUrl()` for REST and WebSocket addresses.
- `BeaconParser.kt`: strict contract DTO parsing and source leases in `HubRegistry`.
- `NsdScanner.kt`: Android NSD discovery, serialized resolution, timeout, service loss,
  and listener cleanup. Android takes `_lara._tcp.`; the protocol name remains
  `_lara._tcp.local`. A resolved IPv4 literal and advertised HTTP port are validated;
  WebSocket defaults to the contract's 8081 because no mDNS TXT fields are defined.
- `UdpScanner.kt`: IO-dispatched wildcard UDP :8888 socket, reuse/broadcast enabled,
  bounded 4096-byte payload, receive timeout 500ms, and socket cleanup with `use`.
- `MulticastLockManager.kt`: non-reference-counted idempotent acquire/release with
  safe logging (no tokens, account identifiers, or message bodies).
- `WifiMonitor.kt`: one Wi-Fi `NetworkCallback` per active observation, no INTERNET
  or VALIDATED requirement; unregisters on cancellation. Tracks alternative Wi-Fi
  networks and restarts the scan session after loss/switching.
- `AndroidHubDiscovery.kt`: coordinates scanners, main-thread registry ownership,
  lifecycle cleanup, selected Wi-Fi routing for HTTP, and endpoint persistence.
- `HubProbe.kt`: bounded GET of existing public `/download`, redirects disabled,
  response checked before accepting reachability, always disconnects.
- `HubEndpointStore.kt`: private preferences contain only a user-selected,
  responding endpoint; reads revalidate stored addresses. No automatic connection
  to newly discovered Hubs. Stored endpoints are available to future auth work.
- `ui/screens/discovery/*`: lifecycle-aware StateFlows, scanning skeletons,
  empty/offline/warning feedback, retry, discovered rows, and always-visible manual
  field. Primary controls are 56dp; secondary controls at least 52dp. All copy is
  in matching English/Filipino string resources; bundled Phosphor Info, Warning Circle,
  and Check Circle icons use the documented design tokens.

## Protocol, identity, expiry, and network behavior

UDP requires `app`, `version`, `name`, `ip`, `http_port`, `ws_port` as documented in
PRD 3.1 and networking rules section 2. Unknown future properties are ignored;
missing fields, wrong app, invalid ports, disallowed addresses, malformed/truncated
JSON, and oversized packets are ignored. Numeric JSON ports must be numbers.
No fabricated Hub ID is used: discovery has none. Rows deduplicate by canonical
IPv4 plus HTTP port across sources. Source leases expire after ten seconds of no
refresh; mDNS resolution refreshes every four seconds. Losing mDNS removes that
source only, leaving a still-live UDP advertisement intact.

NSD resolution has a three-second timeout and is serialized to avoid old Android's
already-active resolver failure. On API 34+, pending resolution is stopped on
cancellation. Older Android cannot cancel an in-flight platform resolve: late
results are ignored and another resolve is not started while it remains pending.
Retry or a returned Wi-Fi network restarts discovery; there is no battery-draining
connection retry loop. An OEM resolver that never calls back can stall that NSD
session on API 26–33; UDP/manual remain available.

`DiscoveryScreen` starts observation at STARTED and stops at STOP/disposal;
ViewModel cancellation also closes the flow. Network changes clear old results,
cancel pending connection checks, release locks, stop NSD, close UDP (within its
bounded receive timeout), and start a fresh session on returning Wi-Fi. A generation
counter prevents cancellation of an old check from changing a newer check's state.

Manual entry accepts canonical IPv4 with optional `:port`, trims surrounding
whitespace, checks each octet through the existing policy helper, and requires a
port in 1..65535 (default HTTP 8080, WS 8081). No hostname, IPv6, URL, public cleartext
IP, or alternate octal-like spelling is accepted. Endpoint creation is the shared
security boundary for manual, NSD, UDP, and persisted destinations.

## Reachability versus authentication

Selecting a row or Check Hub probes `/download` on IO using the Wi-Fi `Network` when
available, including on Wi-Fi without WAN. HTTP 200 with HTML is reported as
"Hub responding" with an explicit download-page explanation, then saved. A
redirect, timeout, wrong content type, or failed status is never reported as success.
No response body is loaded, and redirects cannot escape the validated target.
This proves portal reachability only, not a cryptographically authenticated Hub or
a user session; another HTML service could respond at that path. Account login and
the authenticated WebSocket handshake belong to MOBILE 2.2, not this issue.

## Permissions and platform considerations

Manifest adds INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, and
CHANGE_WIFI_MULTICAST_STATE. The target remains 35 and minimum 26. These APIs use
normal permissions on that target; no location scan, SSID access, nearby-device
runtime prompt, or new device setting is requested. Target-SDK upgrades must
reassess Android local-network protections. No `usesCleartextTraffic` attribute:
the approved XML base config and centralized URL policy are preserved. The first
lint run caught API-30-only `clearCapabilities`; it was replaced with the API-26
compatible request builder before final validation.

The emulator cannot prove physical multicast/broadcast reception. Router AP
isolation and Windows firewall can block discovery; manual entry helps only when
unicast is permitted. Do not change firewall/device security settings automatically.
An IPv6-only mDNS service is omitted because this sprint's LAN policy is IPv4.

## Offline behavior and verification

No network blocks startup. Disconnected status, manual entry, retry, and Home/About
remain usable. There is no local-data read yet: MOBILE 1.3 owns Room. All discovery
results are temporary; only an explicitly checked endpoint persists.

Run `gradlew.bat assembleDebug test lint` from mobile (the current development cache
is `.gradle-user-home`). The test-only coroutine dependency provides deterministic
ViewModel tests, with no sleeps or multicast dependency. Tests cover manual ranges,
ports, malformed input, URL policy integration, contract parsing, malformed packets,
deduplication, expiry, source removal, lifecycle cleanup, loss/return/switch of Wi-Fi
snapshots, cancelled checks, portal status/type/redirect handling, and connection
cleanup. See [review evidence](evidence/4/README.md).

The repository mock emits the documented UDP beacon and serves `/download`; it
does not advertise mDNS and has no public health route. Test mDNS with the real Hub
on integration day. Physical procedure:

1. PC wired to router, phone on the same router Wi-Fi; start the mock. If 8080 is
   occupied, instantiate `MockHubServer` with alternate HTTP/WS ports, keeping 8888.
2. Cold-open the app and measure time to the first row (target approximately 3s).
3. Select that row, then manually check the PC IPv4 and its actual test port.
4. Try empty input, invalid octets/ports, a public cleartext IP, and an unused port.
5. Turn Wi-Fi off/on manually; confirm rows clear, lock releases/reacquires, scan
   returns, and there is no stale success. Background/return and repeat scanning.
6. Repeat with WAN physically unplugged; capture screenshots and timestamps. This
   requires human confirmation; airplane mode with Wi-Fi off is a separate test.
7. Repeat on Transsion/realme and against real Hub, including mDNS and observed
   timing. These cannot be inferred from passing unit tests.

Platform references checked during implementation:
[Android NSD API](https://developer.android.com/reference/android/net/nsd/NsdManager)
and [network callbacks](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).

## PR #164 design review corrections (2026-10-28)

Discovery lists nearby Hubs first, followed by rescan and the manual fallback.
The scrollable screen uses the 4dp-grid spacing tokens, with separate label,
input, validation feedback, helper text and primary action. Inputs have a 2dp
outline (Primary when focused, Danger when invalid), a 12dp radius and at least
52dp height. The ASCII keyboard exposes both dots and colons; Done dismisses it
and invokes the same validated check as the button.

A white Hub pill pairs a 10dp dot with localized Searching, Connected or Not
connected text. `DiscoveryFeedback.kt` derives its state from existing snapshots,
checks and results. Only RESPONDING produces Connected; this still means the
public portal responded, not an authenticated account session. Existing network
session/lifecycle changes invalidate results. This issue adds no continuous
reachability polling.

One feedback banner uses the warning, success or info palette. Warning uses the
Phosphor Warning Circle, amber border/fill and the canonical banner text color
from design-system section 1.3. Success uses Check Circle and green tokens. Titles
are bold through the colon; descriptions contain one sentence. Invalid addresses
instead show the icon and error under the field. Empty results have an encouraging
localized line under the nearby-Hubs heading, including during scanning. Discovery
roadmap copy and its unused scope string were removed.

The showcase has some off-grid CSS spacing (banner 14/18 and pill 6/14). Android
uses the mandatory section 3.1 spacing grid instead: banner 16, pill 4/12, gap 8.
The documented icon, dot and border dimensions are preserved. The existing local
color mirror now names the already-approved warning banner ink; shared tokens
were not edited.

No discovery, endpoint validation, security policy, storage, navigation or
reconnection implementation changed. See [review-fix evidence](evidence/4/review-fixes/README.md)
for screenshots, checks, remaining device coverage and the reviewer response.
