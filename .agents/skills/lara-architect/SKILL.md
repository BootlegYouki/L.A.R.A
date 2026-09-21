---
name: lara-architect
description: Architectural rules, networking protocols, SQLite delta-sync schemas, Material 3 accessibility guidelines, and MiniCPM5-2B Socratic prompt guardrails for developing the L.A.R.A offline LAN classroom ecosystem (mobile, desktop, and server). Use whenever implementing, modifying, or testing components across L.A.R.A applications.
---

# L.A.R.A System Architecture & Implementation Guidelines

Use this skill whenever writing, modifying, or testing code in the **L.A.R.A** project (`mobile/`, `desktop/`, and `server/`).

---

## 1. Zero-Internet LAN Networking Rules

All three applications operate strictly within an isolated local area network (router or teacher hotspot). Never introduce external cloud dependencies (no Firebase, Google Play APIs, CDN scripts, or external telemetry).

### Ports & Protocol Standards
* **HTTP REST & File Server (Port 8080):**
  * Serves the Captive Download Portal at `http://<hub-ip>:8080/download`.
  * Serves educational videos via HTTP Byte-Range requests (`Range: bytes=X-`, response `206 Partial Content`).
  * Enforces a per-client streaming rate limit (max 2.0 MB/s) to protect classroom router throughput.
* **Realtime Event Broker (Port 8081):**
  * WebSocket channel for live quiz timers, active quiz lock signals, enrollment approvals, announcements, and Hub-assisted AI token streaming.
* **Network Discovery:**
  * **mDNS / Zeroconf:** Register service as `_lara._tcp.local` on port 8080.
  * **UDP Subnet Beacon:** Broadcast lightweight JSON heartbeat every 3 seconds to `255.255.255.255:8888`:
    `{"app": "lara", "version": "1.2.0", "name": "Grade 4 - Science", "ip": "192.168.1.50", "http_port": 8080, "ws_port": 8081}`
  * **Manual Fallback:** Always provide an elementary-friendly input box to type the host IP manually if router client isolation blocks broadcast.

---

## 2. Offline-First Delta-Sync & SQLite Rules

Both clients (Android Room and Desktop SQLite) mirror classroom data locally for home study.

### Sync Lifecycle
1. **Pull Handshake (`POST /api/sync/pull`):** Client sends `{ student_id, last_synced_at }`. Hub queries records modified after `last_synced_at` and returns delta JSON. Client writes within a single atomic SQLite transaction.
2. **Push Handshake (`POST /api/sync/push`):** Client uploads queued offline records (completed quiz attempts, homework camera photos, class enrollment requests). Hub responds with ACK confirmation.
3. **Authority Model:**
   * **Server is authoritative:** Classrooms, announcements, published quizzes, rosters, and official graded scores.
   * **Client is authoritative:** Locally initiated draft answers, queued quiz attempts, and compressed homework photos.

---

## 3. Pluggable Socratic AI Tutor & Experimental SLM Guardrails

The AI tutor (**L.A.R.A AI**) is a pedagogical guide for Filipino elementary students (Grades 1 to 6), not an answer engine. The inference runtime is **pluggable and model-agnostic via GGUF and `llama.cpp`**, benchmarking baseline candidate **MiniCPM5-2B (Int4)** alongside **Qwen2.5**, **Llama 3.2**, and **SmolLM2**.

### Hardware Routing Decision
* **Android Phones with < 6GB physical RAM:** Must route inference to the Local Hub over WebSockets (port 8081). Client heap must stay < 250MB to prevent Android Low Memory Killer (OOM) crashes on 3GB/4GB budget devices (Infinix, TECNO, itel, realme).
* **Phones with ≥ 6GB RAM & Laptops:** Can execute supported candidate GGUF models (MiniCPM5-2B, Qwen2.5, Llama 3.2) 100% locally via `llama.cpp` (JNI on Android, sidecar binary on Desktop).

* **Hub Queue:** Hub manages concurrent low-RAM requests using a FIFO queue with 2 to 4 parallel `llama-server` slots, pushing queue estimates (`"Pangalawa ka sa pila - est. 4s"`) over WebSockets.

### Pedagogical System Prompt Directives
1. **Never provide direct answers:** If asked "What is the answer to #3?" or "Ano ang sagot?", politely decline:
   *"Hindi ko maibibigay ang mismong sagot, pero tutulungan kitang tuklasin ito! Balikan natin ang binasa mo. Ano ang unang hakbang?"*
2. **Context Binding:** Always ground hints exclusively in the teacher's uploaded lesson module text chunks.
3. **Step-by-Step Questioning:** Offer one small hint followed by a guiding question.
4. **Bilingual:** Detect and reply in the pupil's preferred language (English or natural Filipino/Taglish).
5. **Quiz Lockout:** While a quiz is active, the AI tutor floating button is completely removed from the UI, and the Hub server rejects any inference calls with `HTTP 403 / QUIZ_IN_PROGRESS`.

---

## 4. Paperless Assessment (Quiz) Engine Rules

Designed to replace paper test printing for DepEd teachers.

1. **Global Time Limit:** Countdowns run on overall quiz time (e.g., 20 mins for 15 items), not per-question timers.
2. **Visual Countdown:** Prominent, friendly timer (Green -> Yellow at 5 mins -> Red at 2 mins).
3. **Auto-Submit on Timeout:** When `00:00` is reached, answers lock and auto-submit immediately.
4. **Network Disconnect Resilience:** If Wi-Fi cuts out during an active test, the timer continues locally on device hardware. Upon finish, answers are stored as `QUEUED_SYNC` and flush to the Hub the moment Wi-Fi reconnects.
5. **Auto-Grading:** Instant scoring for Multiple Choice, True/False, and Identification.

---

## 5. UI/UX Accessibility (Elementary Grades 1 to 6)

* **Design System:** Google Material Design 3 (Material You).
* **Touch Targets:** Minimum 48dp (preferred 56dp) for young children's touch precision.
* **Typography & Visuals:** Large, high-legibility fonts, high-contrast surface colors, simple iconography with text labels.
* **Camera Capture:** CameraX with framing guides and automatic JPEG compression (<800KB) for handwritten worksheets.
* **DepEd Export:** Local Hub desktop app provides one-click export of student grades to `.xlsx` / `.csv` formatted for official DepEd Class Records directly to plugged-in USB flash drives.
