# Zero-Internet LAN Networking & Protocol Rules

This rule document governs all network protocols, ports, discovery beacons, and file streaming across the L.A.R.A ecosystem.

All AI agents and contributors must follow these rules.

---

## 1. Non-Negotiable Zero-Internet Invariant

* The system operates exclusively within an isolated local area network (router or teacher laptop hotspot).
* **Never add cloud dependencies:**
  * No Firebase (Firestore, Auth, Messaging).
  * No Google Play Services / In-App Billing.
  * No external CDNs (cdnjs, unpkg, jsdelivr).
  * No Google Fonts web links (fonts must be bundled locally).
  * No remote telemetry, crashlytics, or analytics.

---

## 2. Port & Protocol Standards

* **HTTP REST & File Engine (Port 8080):**
  * Serves the Captive Download Portal at `http://<hub-ip>:8080/download`.
  * Handles binary downloads (APKs, desktop installers, GGUF models, PDFs).
  * Handles homework photo uploads (`POST /api/assignments/:id/submit`).
* **Realtime Event Broker (Port 8081):**
  * WebSocket channel for low-latency state synchronization.
  * Handles live quiz starts, countdowns, enrollment notifications, stream pushes, and Hub-assisted AI token streaming.
* **Network Discovery:**
  * **mDNS / Zeroconf:** Register service as `_lara._tcp.local` on port 8080.
  * **UDP Broadcast Beacon:** Broadcast JSON heartbeat packet every 3 seconds to subnet address `255.255.255.255:8888`:
    ```json
    {"app": "lara", "version": "1.2.0", "name": "Grade 4 - Science", "ip": "192.168.1.50", "http_port": 8080, "ws_port": 8081}
    ```
  * **Manual Fallback:** All clients must always expose an elementary-friendly input dialog allowing users to type the host IP manually if router client isolation blocks broadcast.

---

## 3. Video Streaming & Bandwidth Throttling

To prevent 40 connected devices from freezing cheap classroom Wi-Fi routers:

1. **HTTP Range Requests (`206 Partial Content`):**
   * Video endpoints must support standard HTTP range headers (`Range: bytes=start-end`).
   * Never require clients to download the entire MP4 file before starting playback.
2. **Bandwidth Ceiling:**
   * The Local Hub must enforce a per-client token-bucket transfer cap (maximum **2.0 MB/s per stream**).
3. **File Size Limit:**
   * Teacher video uploads are capped at **250MB per video** (recommended 720p H.264).

---

## 4. Firewall Invariants & Router AP Isolation Workarounds

Operating a multi-client classroom server over local Wi-Fi introduces OS firewall and router hardware friction. All components must adhere to the following countermeasures:

### 4.1 Windows Defender Firewall Auto-Whitelist (Server Host)
* When the teacher runs the Local Hub on Windows, Windows Firewall defaults to blocking inbound TCP ports (8080, 8081) on networks categorized as "Public".
* **Installer Requirement:** The Windows installer (Tauri NSIS `.exe`) must automatically register inbound firewall rules:
  ```cmd
  netsh advfirewall firewall add rule name="LARA Local Hub (HTTP)" dir=in action=allow protocol=TCP localport=8080
  netsh advfirewall firewall add rule name="LARA Local Hub (WebSocket)" dir=in action=allow protocol=TCP localport=8081
  netsh advfirewall firewall add rule name="LARA Discovery Beacon (UDP)" dir=in action=allow protocol=UDP localport=8888
  ```
* **In-App Health Check:** The Hub dashboard must verify socket bind state and display a clear warning with an auto-fix button if inbound traffic is restricted.

### 4.2 Router AP Isolation (Client Isolation) Workarounds
* Sub-₱1,500 commercial routers or portable pocket Wi-Fi units may ship with "AP Isolation / Client Isolation" enabled, which blocks device-to-device communication and drops UDP broadcast packets (`:8888`).
* **Countermeasure 1 (Manual IP Fallback):** The mobile and desktop clients must always feature an elementary-friendly manual IP input box so pupils can connect directly via unicast TCP (`http://<hub-ip>:8080`).
* **Countermeasure 2 (Laptop Hotspot Mode):** If a physical router strictly isolates clients and settings cannot be changed, the teacher must use **Windows/Linux Mobile Hotspot** directly from their laptop. Hotspot mode eliminates router client isolation and operates 100% offline.

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

