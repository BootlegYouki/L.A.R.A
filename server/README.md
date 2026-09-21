# L.A.R.A Local Hub Server Specification

## 1. Overview
The **L.A.R.A Local Hub Server** is a standalone host application engineered to run on a teacher’s laptop or a school desktop computer connected to the classroom local area network (LAN). It acts as the single source of truth for the offline classroom, providing captive installer distribution, real-time WebSocket orchestration, paperless quiz grading, video streaming, and a centralized Small Language Model (SLM) inference engine.

---

## 2. Technical Stack & Architecture

* **Host Application Framework:** Tauri 2.x Desktop GUI (Rust core + React/TS dashboard).
* **Backend Runtime:** Rust (Tokio async runtime) or Node.js embedded service.
* **Database Engine:** Central SQLite embedded database managed via SQLx / better-sqlite3 with ACID compliance and automatic schema migrations.
* **Networking & Protocols:**
  * **HTTP File & API Server (Port 8080):** Axum / Actix-Web / Fastify serving REST endpoints, static files, and HTTP Byte-Range video streaming (`206 Partial Content`).
  * **Realtime Event Broker (Port 8081):** WebSockets (`tokio-tungstenite` / `ws`) for low-latency live events, quiz timers, and token streaming.
  * **Discovery Beacon:** mDNS service responder registering `_lara._tcp.local` on port 8080 + UDP broadcast service sending JSON heartbeats to `255.255.255.255:8888`.
* **Embedded SLM Engine:** Pluggable `llama-server` process hosting candidate GGUF models (benchmarking **MiniCPM5-2B (Int4)** alongside **Qwen2.5**, **Llama 3.2**, and **SmolLM2**) with continuous batching (2 to 4 parallel slots) and a FIFO request queue.

* **Target Host OS:** Windows 10/11, Linux (Ubuntu/Debian), macOS. Minimal dependencies; runs self-contained without requiring internet access.

---

## 3. Core Functional Modules

### 3.1 Teacher Control & Status Dashboard
* Displays current host LAN IP address (e.g., `192.168.1.50`) and active ports in large text for classroom projection or board writing.
* Live metric tiles: Total Enrolled Pupils, Currently Connected (WebSocket active), and CPU/RAM load.
* One-click server controls: Start Server, Stop Server, and Restart Discovery Beacon.

### 3.2 Captive Download Web Portal
* Lightweight HTML web portal hosted at `http://<hub-ip>:8080/download`.
* Allows pupils and teachers to bootstrap their devices over classroom Wi-Fi without Google Play or internet access.
* Hosts:
  * `LARA-Student.apk` (Android client)
  * `LARA-Desktop-Setup.exe` and `.deb` (Desktop client)
  * `minicpm-2b-q4.gguf` (Quantized model weights for optional on-device AI)
* Includes a 3-step illustrated guide showing how to enable "Allow from this source" for sideloading on Android.

### 3.3 Class Enrollment & Teacher Approval Gate
* Generates unique 6-character Class Codes for created subjects.
* Live pending approval queue: Alerts the teacher whenever a pupil enters a Class Code.
* Teacher has one-click actions to **Accept** or **Decline** students, preventing unauthorized devices from accessing the class.

### 3.4 Media & Document Streaming Service
* **Automated Text Extraction:** When a teacher uploads a PDF or text module, the server extracts plain text and splits it into structured chunks, attaching this metadata to the file download to save pupil phone batteries.
* **Byte-Range Video Streaming:** Serves uploaded MP4/WebM videos via HTTP `206 Partial Content` with support for instant seeking.
* **Bandwidth Throttling:** Enforces a per-client transfer ceiling (max 2.0 MB/s) to prevent classroom Wi-Fi router congestion when multiple pupils watch educational videos.

### 3.5 Paperless Assessment & Auto-Grading Suite
* **Authoring:** Interface to build timed quizzes with Multiple Choice, True/False, and Identification items.
* **Synchronized Delivery:** Broadcasts quiz start events over WebSockets and tracks student completion states in real time.
* **Instant Auto-Grading:** Grades objective submissions the millisecond they are received and populates the live teacher gradebook matrix.
* **Late / Reconnect Processing:** Verifies student finish timestamps for tests completed during temporary Wi-Fi disconnects.

### 3.6 Homework Review & Camera Photo Grading
* Receives compressed homework photos uploaded by pupils.
* Provides a full-screen image viewer with zoom/pan for handwritten worksheets, drawings, and calculations.
* Input fields for score entry and teacher text feedback, automatically synchronized to the student's app.

### 3.7 DepEd Class Record & USB Flash Drive Exporter
* Consolidates all student quiz scores, homework grades, and attendance into a unified gradebook.
* One-click export to formatted `.xlsx` and `.csv` files matching the official Department of Education (DepEd) Class Record layout.
* Native system tray action to write the exported files directly to a plugged-in USB flash drive.

### 3.8 Central SLM Queue Manager
* Manages concurrent Socratic AI requests from budget student phones (< 6GB RAM).
* Configures `llama-server` with 2 to 4 parallel slots.
* Excess requests enter a FIFO Queue that sends real-time queue updates (`"Pangalawa ka sa pila - est. 4s"`) over WebSockets to ensure host laptop stability.

---

## 4. Suggested Directory Structure

```
server/
├── src-tauri/
│   ├── Cargo.toml                    # Rust host dependencies (tauri, tokio, axum, sqlx)
│   ├── tauri.conf.json               # Window settings & permissions
│   ├── build.rs
│   └── src/
│       ├── main.rs                   # Tauri application entrypoint & window
│       ├── tray.rs                   # System tray menu & quick toggles
│       └── usb_detector.rs           # Plugged-in USB flash drive detection
├── backend/
│   ├── src/
│   │   ├── main.rs                   # Server daemon entrypoint
│   │   ├── config.rs                 # IP binding, port settings, paths
│   │   ├── db/
│   │   │   ├── mod.rs                # SQLite connection pool
│   │   │   └── migrations/           # Schema creation & updates
│   │   ├── routes/
│   │   │   ├── auth.rs               # Self-registration & class codes
│   │   │   ├── sync.rs               # Delta-sync endpoints (pull/push)
│   │   │   ├── materials.rs          # File upload & byte-range streaming
│   │   │   ├── quizzes.rs            # Assessment endpoints & auto-grading
│   │   │   └── export.rs             # DepEd Excel/CSV generation
│   │   ├── websocket/
│   │   │   ├── mod.rs                # WebSocket connection manager
│   │   │   └── events.rs             # Realtime event dispatcher
│   │   ├── discovery/
│   │   │   ├── mdns.rs               # mDNS responder service
│   │   │   └── udp.rs                # UDP subnet broadcast beacon
│   │   └── ai/
│   │       ├── llama_runner.rs       # llama-server subprocess manager
│   │       └── queue.rs              # FIFO inference queue
│   └── static/
│       └── portal/                   # Captive download web page (HTML, CSS, guide)
├── frontend/
│   ├── src/                          # Teacher Hub desktop GUI (React + TS + Tailwind)
│   │   ├── pages/                    # Dashboard, Classes, QuizBuilder, Gradebook
│   │   └── components/               # Status tiles, student roster, export dialog
│   └── package.json
└── README.md
```

---

## 5. Build & Execution Guidelines

* **Prerequisites:** Rust toolchain (`cargo`), Node.js 20+, and C++ compiler (for building `llama-server`).
* **Development Mode:** `npm run dev` (Runs backend engine, llama-server, and Tauri desktop window).
* **Production Build:** `npm run build` (Produces standalone desktop installer bundle for Windows `.exe` or Linux `.deb`).
