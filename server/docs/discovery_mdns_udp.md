# Discovery: mDNS responder and UDP beacon

> Step 1.1 (issue #1). Area rule: [`rules/networking-and-lan.md`](../../rules/networking-and-lan.md) §2.

## 1. Purpose
Let learner and teacher devices find the Hub on the classroom network with no configuration. Two
independent mechanisms run side by side so one failing does not leave the Hub invisible:

* **UDP subnet beacon** — a JSON heartbeat broadcast every 3 s to `255.255.255.255:8888`.
* **mDNS / DNS-SD responder** — advertises the service `_lara._tcp.local` on the HTTP port (8080).

Neither is required for a device to connect: every client also keeps a manual IP box, which is the
fallback when a cheap router has AP / client isolation turned on and drops both broadcast and
multicast (`rules/networking-and-lan.md` §4.2).

## 2. Key files and entry points
| File | Responsibility |
| :--- | :--- |
| `backend/src/discovery/mod.rs` | `lan_ip() -> Option<IpAddr>`: the one reachable LAN address (skips loopback/unspecified). Declares the two submodules. |
| `backend/src/discovery/udp.rs` | `run(BeaconInfo, watch::Receiver<bool>)`: the beacon loop. `BeaconInfo { name, http_port, ws_port }`. Consts `BEACON_PORT = 8888`, `BEACON_INTERVAL = 3 s`. |
| `backend/src/discovery/mdns.rs` | `start(name, http_port, lan_ip) -> Option<MdnsResponder>`: registers `_lara._tcp` via `libmdns`. The returned guard unregisters on drop. |
| `backend/src/lib.rs` | `serve()` spawns the beacon as a task and holds the mDNS guard in `HubHandle`; both stop on `shutdown()`. |
| `backend/src/config.rs` | `HubConfig.hub_name` (the beacon `name`), default `DEFAULT_HUB_NAME`. |
| `backend/src/main.rs` | Headless runs may override the name with the `LARA_HUB_NAME` env var. |

## 3. Data flow and state
No database, no files, no events on the Broker. Discovery is read-only network output.

**Beacon packet** (keys exactly as `scripts/mock_hub.py`, verified by a unit test in `udp.rs`):

```json
{"app": "lara", "version": "<CARGO_PKG_VERSION>", "name": "<hub name>", "ip": "<lan ip>", "http_port": 8080, "ws_port": 8081}
```

* `ip`, `http_port` and `ws_port` are re-read every tick, so a Hub PC plugged into the router *after*
  startup begins advertising a reachable address within one 3 s interval without a restart.
* `serve()` advertises the **actually bound** ports, not the configured ones, so a test hub on
  ephemeral ports still broadcasts a correct address.
* mDNS advertises only the single `lan_ip()` address (empty IP list when there is no LAN), so a
  client never resolves an unreachable VirtualBox / Hyper-V / VPN address.

## 4. Gotchas and edge cases
* **Best-effort, never fatal.** A router that blocks broadcast or a busy `:5353` must not stop the
  Hub. `udp::run` logs once and returns; `mdns::start` returns `None`; serving continues. This is why
  the manual IP box is mandatory on every client.
* **No network yet.** When `lan_ip()` is `None` the beacon skips that tick quietly (an address-less
  packet would only mislead). mDNS still starts and advertises whatever interfaces it finds.
* **`libmdns` runs its own OS thread** with a current-thread runtime; it does not borrow the Hub's
  Tokio runtime. The `MdnsResponder` guard owns the `Service` and `Responder`; field order drops the
  registration before its host so no stale record lingers after a restart.
* **Dropped packets are normal** (AP isolation, transient routing). A send error is logged at
  `debug` only, never at warn, so a flaky adapter does not spam the console every 3 seconds.
* **Android clients** must hold a `WifiManager.MulticastLock` to receive these UDP packets at all
  (`rules/networking-and-lan.md` §4.3); that is the mobile team's responsibility, not the Hub's.
* **Firewall:** inbound UDP 8888 (and TCP 8080/8081) is opened by the installer / dashboard auto-fix
  (step 1.4); without it Windows "Public" profile blocks the beacon. See `network/firewall_check.rs`.

## 5. Tests
* `discovery/udp.rs` unit tests: the beacon JSON has exactly the six mock-hub keys and values, and
  the port and interval match the rules file.
* `tests/scaffold.rs::hub_with_discovery_starts_and_stops_cleanly`: `serve()` with the beacon task
  and mDNS responder still serves HTTP and shuts down cleanly.

Receiving a live broadcast packet is not asserted in CI: loopback broadcast reception and a fixed
`:8888` collide across parallel test hubs and vary by OS. The serialization unit test is the
authoritative check on the wire shape.
