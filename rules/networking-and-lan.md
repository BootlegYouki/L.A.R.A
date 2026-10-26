# Zero-Internet LAN Networking & Protocol Rules

This rule document governs all network protocols, ports, discovery beacons, and file streaming across the L.A.R.A ecosystem.

All AI agents and contributors must follow these rules.

---

## 1. Non-Negotiable Zero-Internet Invariant

* The system operates exclusively within an isolated local area network: the classroom router, or the Hub PC's own hotspot as a fallback. The **one** sanctioned exception is Remote/Suspension Mode (section 5): a Hub-run Cloudflare Tunnel, opt-in per Hub, used only while classes are suspended. Nothing in this app ever requires an uplink to function in class.
* **Never add cloud dependencies:**
  * No Firebase (Firestore, Auth, Messaging).
  * No Google Play Services / In-App Billing.
  * No external CDNs (cdnjs, unpkg, jsdelivr).
  * No Google Fonts web links (fonts must be bundled locally).
  * No remote telemetry, crashlytics, or analytics.
  * No third party in the data path beyond the Cloudflare Tunnel itself, which only relays encrypted bytes to the Hub and never stores, inspects or re-serves them.

---

## 2. Port & Protocol Standards

* **HTTP REST & File Engine (Port 8080):**
  * Serves the Captive Download Portal at `http://<hub-ip>:8080/download`.
  * Handles binary downloads (APKs, desktop installers, GGUF models, PDFs).
  * Handles homework photo uploads (`POST /api/assignments/{id}/submit`) and the opt-in AI model download (`GET /api/model/file`, resumable with HTTP Range).
* **Realtime Event Broker (Port 8081):**
  * WebSocket channel for low-latency state synchronization.
  * Handles live quiz starts, countdowns, enrollment notifications, stream pushes, and Hub-assisted AI token streaming.
* **Network Discovery:**
  * **mDNS / Zeroconf:** Register service as `_lara._tcp.local` on port 8080.
  * **UDP Broadcast Beacon:** Broadcast JSON heartbeat packet every 3 seconds to subnet address `255.255.255.255:8888`:
    ```json
    {"app": "lara", "version": "<hub app version>", "name": "<school or room name set by the admin>", "ip": "192.168.1.50", "http_port": 8080, "ws_port": 8081}
    ```
  * **Manual Fallback:** All clients must always expose an easy-to-use input dialog allowing users to type the host IP manually if router client isolation blocks broadcast. The same field accepts a saved Hub remote URL (the Cloudflare Tunnel hostname) when the device is off the school LAN; see section 5.

---

### 2.1 Authentication and the Realtime Handshake
* Every REST route except `/download`, `POST /api/auth/register` and `POST /api/auth/login` requires `Authorization: Bearer <token>`. Media routes that players cannot send headers to (`/api/materials/{id}/stream`, `/download`, `/api/submissions/{id}/file`) also accept `?token=`.
* WebSocket: the first frame must be `EVENT_HELLO` with the token. The Hub answers `EVENT_HELLO_ACK` (`server_time`, `hub_id`, `sync_epoch`) or closes with code `4401`. Reconnect with backoff 1 s, 2 s, 4 s, up to 10 s, and never show a blocking error while offline.
* Errors always use `{ "error": { "code", "message" } }`. See `contracts/events/README.md` and `contracts/openapi.yaml`.

### 2.2 Where the Hub Runs
The pilot Hub is a **dedicated, always-on school PC wired to the router** running the same Hub app (Windows or Linux). Do not design around a teacher's personal laptop that sleeps, moves or changes networks. Teachers may use laptop hotspot mode only as a fallback, and the Hub should then warn about sleep and lid-close.

---

## 3. Video Streaming & Bandwidth Throttling

To prevent 40 connected devices from freezing cheap classroom Wi-Fi routers:

1. **HTTP Range Requests (`206 Partial Content`):**
   * Video endpoints must support standard HTTP range headers (`Range: bytes=start-end`).
   * Never require clients to download the entire MP4 file before starting playback.
2. **Bandwidth Ceiling:**
   * The Local Hub must enforce a per-client token-bucket transfer cap (maximum **2.0 MB/s per client**, counted per signed-in user across all their streams and downloads, not per connection).
3. **File Size Limit:**
   * Teacher video uploads are capped at **250MB per video** (recommended 720p H.264).

---

## 4. Firewall Invariants & Router AP Isolation Workarounds

Operating a multi-client classroom server over local Wi-Fi introduces OS firewall and router hardware friction. All components must adhere to the following countermeasures:

### 4.1 Firewall Rules on the Hub PC
* **Linux (the pilot Hub):** the `.deb` post-install step opens the same three ports when a firewall is active (`ufw allow 8080/tcp`, `ufw allow 8081/tcp`, `ufw allow 8888/udp`, or the `firewalld` equivalent) and does nothing when none is running. It never needs the internet.
* **Windows:** Windows Firewall defaults to blocking inbound TCP ports (8080, 8081) on networks categorized as "Public".
* **Installer Requirement:** The Windows installer (Tauri NSIS `.exe`) must automatically register inbound firewall rules:
  ```cmd
  netsh advfirewall firewall add rule name="LARA Local Hub (HTTP)" dir=in action=allow protocol=TCP localport=8080
  netsh advfirewall firewall add rule name="LARA Local Hub (WebSocket)" dir=in action=allow protocol=TCP localport=8081
  netsh advfirewall firewall add rule name="LARA Discovery Beacon (UDP)" dir=in action=allow protocol=UDP localport=8888
  ```
* **In-App Health Check:** The Hub dashboard must verify socket bind state and display a clear warning with an auto-fix button if inbound traffic is restricted.

### 4.2 Router AP Isolation (Client Isolation) Workarounds
* Sub-₱1,500 commercial routers or portable pocket Wi-Fi units may ship with "AP Isolation / Client Isolation" enabled, which blocks device-to-device communication and drops UDP broadcast packets (`:8888`).
* **Countermeasure 1 (Manual IP Fallback):** The mobile and desktop clients must always feature an easy-to-use manual IP input box so learners can connect directly via unicast TCP (`http://<hub-ip>:8080`).
* **Countermeasure 2 (Laptop Hotspot Mode):** If a physical router strictly isolates clients and settings cannot be changed, turn on the **Windows/Linux Mobile Hotspot of the Hub PC** and have devices join it. Hotspot mode eliminates router client isolation and operates 100% offline.

### 4.3 Android MulticastLock Requirement (Mobile Client)
* Android OS power management disables the Wi-Fi chip from processing UDP broadcast packets by default.
* **Mobile Requirement:** The discovery service in `mobile/` must explicitly acquire a `WifiManager.MulticastLock` before listening on UDP port 8888 and release it when discovery terminates:
  ```kotlin
  val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  val multicastLock = wifi.createMulticastLock("lara_discovery_lock").apply {
      setReferenceCounted(true)
      acquire()
  }
  ```

---

## 5. Remote/Suspension Mode (Cloudflare Tunnel)

The one sanctioned exception to section 1's zero-internet invariant. See `AGENTS.md` section 3.5 for the product-level decision; this section is the networking detail.

* **What it's for:** letting a Hub's own accounts reach it from outside the school network while classes are suspended (e.g. bad weather), so homework submission, the stream, materials, grades, private comments, the AI tutor and quizzes keep working from home. It changes transport only — never authorization, never the contract.
* **Mechanism:** the Hub PC runs `cloudflared` as a managed child process (like `llama-server`), pointed at the Hub's own `:8080` and `:8081`. Cloudflare terminates TLS at its edge and proxies to the tunnel; the Hub never opens an inbound port on the public internet itself (no router port-forwarding, no dynamic DNS to the Hub's own IP). This keeps the Hub PC off the open internet even while reachable through the tunnel.
* **Opt-in and admin-controlled:** disabled by default. An admin enables it from the Hub dashboard, which then shows the assigned remote URL (e.g. `hub-<school>.lara.app`) for the admin to share with teachers and learners. Disabling it tears down the tunnel; nothing else about the Hub changes.
* **Discovery stays LAN-first:** mDNS and the UDP beacon (section 2) are never attempted over the tunnel — they are broadcast/multicast and do not route off the LAN. A client on the school network always finds the Hub locally first; the remote URL is only used by a client that already has it saved and cannot reach the Hub locally.
* **Auth is unchanged:** the same bearer token and `EVENT_HELLO` handshake (section 2.1) apply byte-for-byte over the tunnel. The tunnel does not add, remove or weaken any authorization check.
* **Rate and size limits still apply:** the per-client **2.0 MB/s** video cap and the **250 MB** upload cap (section 3) hold over the tunnel exactly as on the LAN; home internet connections will often be the actual bottleneck, which is expected and not a bug.
* **Quiz timing over the tunnel:** `EVENT_QUIZ_START`'s live tick-by-tick sync assumes classroom-LAN latency and is not relied upon remotely. A remote client instead runs its own local countdown from the quiz's duration, anchored to `started_at`, and the Hub still validates `submitted_at - started_at <= limit + 60s` as the authority (`rules/quiz-and-anti-cheat.md`).
* **Never:** expose the Hub's ports directly to the internet by any means other than the Cloudflare Tunnel (no port-forwarding, no other reverse-proxy, no dynamic DNS pointed at the Hub). The tunnel is the single sanctioned path off the LAN so there is exactly one thing to audit and one thing that can be disabled.

