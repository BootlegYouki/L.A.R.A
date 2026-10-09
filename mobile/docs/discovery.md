# LAN Hub Discovery (`#4`, MOBILE 1.2)

How the Android client finds the classroom Hub on the local Wi-Fi, and the manual fallback for when
the router blocks that. Behavior source of truth: `rules/networking-and-lan.md` §2 and §4.

## Purpose

The app opens straight to the discovery screen and must surface the classroom Hub within a few
seconds, with no typing, on a plain classroom Wi-Fi. Two passive discovery paths run at once so a
Hub is found even when one path is filtered:

- **mDNS / DNS-SD** — the Hub registers `_lara._tcp.local` on its HTTP port (8080).
- **UDP beacon** — the Hub broadcasts a JSON heartbeat to `255.255.255.255:8888` every 3 seconds.

When a sub-₱1,500 router has **AP / client isolation** on, both paths are dropped. For that case the
screen always offers a **manual IP entry** so the pupil can connect by unicast.

## Key files

All discovery infrastructure lives in `app/src/main/java/org/lara/app/data/remote/discovery/`:

| File | Responsibility |
|---|---|
| `DiscoveredHub.kt` | Normalized Hub model + `DiscoverySource` (MDNS / UDP_BEACON / MANUAL). Identity is `ip:httpPort` (`key`), used to de-duplicate the two sources. |
| `MulticastLockManager.kt` | Reference-counted `WifiManager.MulticastLock`. Acquired before any broadcast/multicast listening, released when discovery stops. |
| `NsdScanner.kt` | `NsdManager` discovery of `_lara._tcp.`, resolves each service to IP + port. Cold `Flow<DiscoveredHub>`. |
| `UdpScanner.kt` | Binds a `DatagramSocket` on `:8888`, parses `UdpBeacon` JSON. Cold `Flow<DiscoveredHub>` on `Dispatchers.IO`. |
| `UdpBeacon.kt` | `@Serializable` DTO mirroring the contract beacon fields (`app`, `version`, `name`, `ip`, `http_port`, `ws_port`). |
| `NetworkMonitor.kt` | `ConnectivityManager.NetworkCallback` → `Flow<Boolean>` of Wi-Fi availability for auto-reconnect. |
| `HubDiscoveryService.kt` | Facade: `discover()` merges NSD + UDP; `manualHub()` / `isValidManualEntry()` validate manual input against the issue regex. |

UI lives in `app/src/main/java/org/lara/app/ui/screens/discovery/`:

| File | Responsibility |
|---|---|
| `DiscoveryViewModel.kt` | MVI `AndroidViewModel`. Exposes `uiState: StateFlow<DiscoveryUiState>` and `hubs: StateFlow<List<DiscoveredHub>>`. De-duplicates, restarts the scan on Wi-Fi availability, drives the manual dialog. |
| `DiscoveryScreen.kt` | Compose screen: searching / empty / offline states, a card per Hub, always-present manual-entry + rescan actions. |
| `ManualIpDialog.kt` | Elementary-friendly manual IP/port dialog with inline validation. |

## Data flow

```
NsdScanner.discover() ─┐                              ┌─ DiscoveryViewModel ─ uiState ─┐
                       ├─ HubDiscoveryService.discover ┤  (merge + de-dup by key)       ├─ DiscoveryScreen
UdpScanner.listen() ───┘                              └─ hubs: StateFlow<List<Hub>>    ─┘
NetworkMonitor.wifiAvailability() ───────────────────── restart scan on `true`
ManualIpDialog ── submitManualEntry() ── HubDiscoveryService.manualHub() ── merge into list
```

Both scanners share one `MulticastLockManager`. The lock is held only while the merged flow is
collected (i.e. while the screen is on), then released, so the Wi-Fi radio is not kept warm needlessly.

## Manual IP validation

The entry is validated against the exact issue pattern:

```
^([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?$
```

The regex only bounds digit counts, so `HubDiscoveryService` additionally rejects octets above 255
and ports outside `1..65535`. A missing port defaults to 8080 (HTTP) with 8081 (WebSocket) by
convention. Covered by `HubDiscoveryServiceTest`.

## Gotchas

- **MulticastLock is mandatory.** Without it, budget Transsion (Infinix, TECNO, itel) and realme
  phones silently drop the UDP beacon and mDNS multicast to save battery (`rules/networking-and-lan.md`
  §4.3), and discovery appears to "not work" with no error.
- **Do not require `NET_CAPABILITY_INTERNET`.** The classroom LAN has no uplink; the network request
  in `NetworkMonitor` asks only for `TRANSPORT_WIFI`, or the Hub would never be considered reachable.
- **mDNS resolve is single-shot on older APIs.** `NsdScanner` queues services and resolves one at a
  time to avoid `FAILURE_ALREADY_ACTIVE` across minSdk 26 … targetSdk 35.
- **The UDP socket blocks.** It runs on a daemon thread with a 1 s `soTimeout` so a cancelled flow
  tears the loop down promptly; the socket is closed and the lock released in `awaitClose`.
- **Manual entry is never hidden.** Per §4.2 it must always be available on the connection screen,
  including while discovery is still searching or has found nothing.

## Verify

Run the mock Hub (`python3 scripts/mock_hub.py`) on the same LAN, then:

1. Open the app — the beaconed Hub (`Grade 4 - Science (Mock Hub)`) appears and connects.
2. Stop the beacon / enable AP isolation — enter the Hub IP manually; it connects immediately.
3. `./gradlew test` runs `HubDiscoveryServiceTest` (manual-IP regex + beacon mapping) on the JVM.
