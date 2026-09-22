# L.A.R.A Local Hub Server (`server/`)

> **Subsystem Scope:** Teacher Host System & Local Area Network (LAN) Server.  
> **Repository Role:** Single source of truth for the offline classroom: serves the captive APK portal, orchestrates live quiz WebSockets, manages the SQLite delta-sync ledger, and runs the multi-slot SLM inference queue.

---

## 0. Developer Pre-Flight & Hard Invariants

**Every contributor and AI agent working in `server/` MUST adhere to these rules:**

1. **Strict Zero-Internet Policy:** The Hub operates completely offline over a router or laptop hotspot. Never add remote cloud connections, telemetry, or external API dependencies.
2. **Ports & Protocol Standard:**
   * **Port 8080 (HTTP):** Captive download portal (`/download`), REST endpoints, and HTTP 206 video streaming.
   * **Port 8081 (WebSocket):** Realtime event broker (`tokio-tungstenite`) for quiz timers, enrollments, and AI streaming.
   * **Port 8888 (UDP):** Subnet broadcast beacon (`255.255.255.255:8888`) every 3 seconds.
   * **mDNS (`_lara._tcp.local`):** Registered on port 8080.
3. **Bandwidth Throttling (Router Protection):**
   * Enforce a per-client token-bucket rate limit (maximum **2.0 MB/s per stream**) on HTTP 206 video endpoints to prevent 40 connected desks from freezing cheap classroom routers.
   * Cap teacher video uploads at **250MB** (recommended 720p H.264).
4. **Windows Defender Firewall Countermeasure:**
   * Inbound ports (8080, 8081, 8888) are blocked by default on Windows "Public" networks.
   * The NSIS installer (`.exe`) must automatically register silent firewall rules:
     ```cmd
     netsh advfirewall firewall add rule name="LARA Local Hub" dir=in action=allow protocol=TCP localport=8080,8081
     netsh advfirewall firewall add rule name="LARA Discovery Beacon" dir=in action=allow protocol=UDP localport=8888
     ```
   * The Hub dashboard must include an in-app LAN Port Health Check indicator.
5. **Database Stack Invariant:** Use embedded **SQLite via SQLx (Rust)** or **Drizzle + `better-sqlite3` (Node)** with monotonic `sync_revisions` tracking. **Prisma is strictly forbidden.**
6. **Strict Anti-Cheat Redaction:** When serving active quizzes to student clients (`GET /api/quizzes/active`), the server **must strictly omit `correct_answer`**.
7. **Canonical Network Contracts (`contracts/`):**
   * Check [`../contracts/openapi.yaml`](../contracts/openapi.yaml) and [`../contracts/events/`](../contracts/events/) before creating or altering any route or payload.
   * All JSON keys over HTTP and WebSockets are strictly **`snake_case`**.
8. **Pre-Push Linter:** Run the invariant scanner before opening any PR:
   ```bash
   python3 ../scripts/verify_invariants.py
   ```
9. **Mandatory Documentation:** Every major feature PR must include updated architectural notes in [`server/docs/`](./docs/).

---

## 1. Technical Stack & Architecture

* **Host GUI:** Tauri 2.x (Desktop window displaying current host LAN IP, active port indicators, server toggle, and USB export).
* **Backend Runtime:** Rust (Tokio async runtime + Axum HTTP engine).
* **Database Engine:** Central SQLite embedded database managed via SQLx with ACID compliance and automatic schema migrations.
* **Realtime Event Broker:** WebSockets via `tokio-tungstenite` on port 8081.
* **Network Discovery:**
  * `mdns-sd` registering `_lara._tcp.local` on port 8080.
  * UDP socket broadcasting JSON heartbeat packets to `255.255.255.255:8888` every 3 seconds.
* **Pluggable SLM Engine:** Child `llama-server` process hosting candidate GGUF models (MiniCPM5-2B, Qwen2.5, Llama 3.2, SmolLM2) with continuous batching (2 to 4 parallel slots) and a WebSocket FIFO queue.
* **DepEd Report Exporter:** Direct generation of official DepEd Class Record spreadsheets (`.xlsx`) using `rust_xlsxwriter` with automatic detection of mounted USB flash drives across Windows and Linux.
* **Disaster Recovery:** One-click encrypted snapshot backup (`.lara-backup`) to plugged-in USB flash drives.

---

## 2. Core Functional Modules

### 2.1 Teacher Control & Status Dashboard
* Displays current host LAN IP address (e.g., `192.168.1.50`) in large text for classroom projection or board writing.
* Live status tiles: Connected Student Count, Active Quizzes, CPU/RAM utilization, and Firewall Port Reachability.

### 2.2 Captive Download Web Portal (`http://<hub-ip>:8080/download`)
* Lightweight HTML web portal allowing students and teachers to bootstrap their devices over classroom Wi-Fi without Google Play or internet access.
* Serves:
  * `LARA-Student.apk` (Android client)
  * `LARA-Desktop-Setup.exe` and `.deb` (Desktop client)
  * Active candidate `.gguf` model weights for laptops and ≥6GB RAM phones.

### 2.3 Delta-Sync Protocol & Class Enrollment Gate
* Unique 6-character Class Code generator (e.g. `SCI4-AG`).
* Real-time join approval push notifications over WebSockets.
* Two-way delta-sync engine:
  * `POST /api/sync/pull`: Returns deltas modified after client's `last_synced_at`.
  * `POST /api/sync/push`: Accepts queued offline quiz attempts and compressed homework photos.

### 2.4 Timed Paperless Quiz Engine & Auto-Grading
* Synchronized `EVENT_QUIZ_START` broadcast over WebSockets.
* Instant auto-grading algorithm scoring Multiple Choice, True/False, and Identification questions in < 50ms.
* Hard Quiz Lockout: Rejects any AI inference calls originating from students with active quiz attempts (`HTTP 403 / QUIZ_IN_PROGRESS`).

### 2.5 Central SLM Multi-Slot Queue Manager
* Buffers concurrent hint requests from budget phones (< 6GB RAM).
* Spawns `llama-server` with `--cont-batching -np 4 -c 2048`.
* Emits real-time queue position updates (`"Pangalawa ka sa pila - est. 4s"`) over WebSockets.

---

## 3. Dedicated Server SQLite Schema (Master Local Hub)

Server developers must configure and execute their SQLx / SQLite migrations strictly adhering to the canonical SQL DDL at [`contracts/schema/server_master.sql`](../contracts/schema/server_master.sql).

### The 13 Authoritative Master Tables:
1. **`users`:** `id`, `lrn_or_id`, `full_name`, `role` (`TEACHER` | `STUDENT`), `pin_hash`, `created_at`, `updated_at`.
2. **`classrooms`:** `id`, `name`, `section`, `class_code`, `teacher_id`, `created_at`, `updated_at`.
3. **`enrollments`:** `id`, `classroom_id`, `student_id`, `status` (`PENDING` | `ACTIVE` | `REJECTED`), `joined_at`, `updated_at`.
4. **`announcements`:** `id`, `classroom_id`, `title`, `content`, `allow_comments`, `created_at`, `updated_at`.
5. **`announcement_comments`:** `id`, `announcement_id`, `author_id`, `content`, `created_at`, `updated_at`.
6. **`materials`:** `id`, `classroom_id`, `title`, `file_type`, `file_path`, `file_size_bytes`, `extracted_text`, `created_at`, `updated_at`.
7. **`assignments`:** `id`, `classroom_id`, `title`, `instructions`, `deped_category` (`WRITTEN_WORK` | `PERFORMANCE_TASK` | `QUARTERLY_ASSESSMENT`), `due_date`, `max_points`, `created_at`, `updated_at`.
8. **`assignment_submissions`:** `id`, `assignment_id`, `student_id`, `file_path`, `file_type`, `submitted_at`, `score`, `teacher_feedback`, `updated_at`.
9. **`quizzes`:** `id`, `classroom_id`, `title`, `instructions`, `deped_category`, `time_limit_minutes`, `status` (`DRAFT` | `ACTIVE` | `CLOSED`), `started_at` (authoritative epoch ms), `created_at`, `updated_at`.
10. **`quiz_questions`:** `id`, `quiz_id`, `order_index`, `question_text`, `question_type`, `options_json`, `points`, `image_path`, `correct_answer` (authoritative answer key for auto-grader), `created_at`, `updated_at`.
11. **`quiz_attempts`:** `id`, `quiz_id`, `student_id`, `started_at`, `submitted_at`, `score`, `total_points`, `answers_json`, `updated_at`.
12. **`ai_chat_messages`:** `id`, `classroom_id`, `student_id`, `material_id`, `role` (`USER` | `TUTOR`), `content`, `created_at`.
13. **`sync_revisions`:** `id`, `entity_table`, `entity_id`, `action` (`UPSERT` | `DELETE`), `updated_at` (monotonic changelog for delta-sync pull/push).

---

## 4. Directory Structure

```
server/
├── src-tauri/
│   ├── Cargo.toml                    # Tauri GUI dependencies
│   ├── tauri.conf.json               # Desktop window & permissions
│   └── src/
│       ├── main.rs                   # Tauri GUI entrypoint
│       └── usb_detector.rs           # USB flash drive detection
├── backend/
│   ├── Cargo.toml                    # Rust server dependencies (axum, tokio, sqlx)
│   ├── src/
│   │   ├── main.rs                   # Server daemon entrypoint
│   │   ├── discovery/                # mDNS responder & UDP beacon
│   │   ├── routes/                   # Classrooms, Sync, Quizzes, Video stream
│   │   ├── websocket/                # Realtime event broker & AI streaming
│   │   ├── services/                 # Auto-grader, DepEd exporter, text chunker
│   │   ├── ai/                       # llama-server process manager & FIFO queue
│   │   └── db/                       # SQLx migrations & SQLite schema
│   └── bin/                          # llama-server pre-compiled binary
└── docs/                             # Mandatory subsystem architectural documentation
```

---

## 5. Server Team Sprint Roadmap & Execution Order

All server issues on GitHub follow the `[SERVER Sprint.Step]` naming convention:

* **Sprint 1 (Scaffolding & LAN Discovery):**
  * `[SERVER 1.1]`: Implement mDNS responder and UDP subnet broadcast beacon ([#1](https://github.com/BootlegYouki/L.A.R.A/issues/1))
  * `[SERVER 1.2]`: Set up central SQLite database with schema migrations ([#2](https://github.com/BootlegYouki/L.A.R.A/issues/2))
  * `[SERVER 1.3]`: Build captive web portal (:8080/download) with 3-step Android sideloading guide ([#3](https://github.com/BootlegYouki/L.A.R.A/issues/3))
  * `[SERVER 1.4]`: Package standalone zero-dependency installer (.exe / .deb) for teacher laptops ([#20](https://github.com/BootlegYouki/L.A.R.A/issues/20))
* **Sprint 2 (Class Codes & Delta-Sync):**
  * `[SERVER 2.1]`: Implement Class Code generation and teacher manual approval gate ([#6](https://github.com/BootlegYouki/L.A.R.A/issues/6))
  * `[SERVER 2.2]`: Implement delta-sync protocol (pull/push) with SQLite transactions ([#7](https://github.com/BootlegYouki/L.A.R.A/issues/7))
* **Sprint 3 (Text Chunking & Video Streaming):**
  * `[SERVER 3.1]`: Automated document text extraction & chunking for lesson handouts ([#10](https://github.com/BootlegYouki/L.A.R.A/issues/10))
  * `[SERVER 3.2]`: Implement HTTP 206 Byte-Range video streaming with 2 MB/s client rate-limiting ([#8](https://github.com/BootlegYouki/L.A.R.A/issues/8))
* **Sprint 4 (Quiz Engine, Auto-Grader & DepEd Export):**
  * `[SERVER 4.1]`: Implement timed paperless quiz engine with synchronized countdown and auto-submission ([#11](https://github.com/BootlegYouki/L.A.R.A/issues/11))
  * `[SERVER 4.2]`: Instant auto-grading engine for Multiple Choice, True/False, and Identification questions ([#12](https://github.com/BootlegYouki/L.A.R.A/issues/12))
  * `[SERVER 4.3]`: One-click DepEd Class Record export (.xlsx/.csv) to plugged-in USB flash drives ([#13](https://github.com/BootlegYouki/L.A.R.A/issues/13))
  * `[SERVER 4.4]`: Implement one-click SQLite database backup & restore (.lara-backup) to USB flash drive ([#21](https://github.com/BootlegYouki/L.A.R.A/issues/21))
* **Sprint 5 (Central SLM Queue & Guardrails):**
  * `[SERVER 5.1]`: Configure embedded llama-server with 2-4 slots and FIFO request queue ([#14](https://github.com/BootlegYouki/L.A.R.A/issues/14))
  * `[SERVER 5.2]`: Implement strict Socratic prompt template and hard quiz lockout enforcement ([#16](https://github.com/BootlegYouki/L.A.R.A/issues/16))
* **Sprint 6 (Router Stress Test):**
  * `[SERVER 6.1]`: Simulate 40 concurrent connected devices on local Wi-Fi router (quizzes & video) ([#17](https://github.com/BootlegYouki/L.A.R.A/issues/17))
