# L.A.R.A Local Hub Server (`server/`)

> **Subsystem Scope:** The school's Local Hub: the LAN server for every teacher and learner, with an admin console window.  
> **Deployment:** one Hub per school on a **dedicated, always-on PC wired to the router**, serving every teacher and learner. The same app also runs on a team laptop for demos.  
> **Repository Role:** Single source of truth for the offline classroom: serves the captive APK portal, orchestrates live quiz WebSockets, manages the SQLite delta-sync ledger, and runs the multi-slot SLM inference queue.

---

## Start Here (New Developer Checklist)

| Step | What to do |
| :--- | :--- |
| 1 | Read the root [`AGENTS.md`](../AGENTS.md), then [`server/AGENTS.md`](./AGENTS.md) (this team's agent and developer guide). |
| 2 | Write [`docs/TECH_SPEC.md`](./docs/TECH_SPEC.md) from the template ([#36](https://github.com/BootlegYouki/L.A.R.A/issues/36)). The Lead approves it before Sprint 1 work merges. |
| 3 | Run the Hub simulator from the repo root: `python3 scripts/mock_hub.py`. Seed accounts (PIN `1234`): `T-0001` teacher (class code `K7M4QX`), `123456789012` learner, `123456789013` learner (join with the code), `ADMIN-0001`. |
| 4 | Take the next issue whose dependencies are merged: [all server issues](https://github.com/BootlegYouki/L.A.R.A/issues?q=is%3Aissue%20is%3Aopen%20label%3A%22scope%3Aserver%22). One issue = one PR. Or use the [sprint board](https://github.com/users/BootlegYouki/projects/2) (filter Team = Server). The sprint-by-sprint roadmap is in section 5. |
| 5 | Scaffold first: [#39](https://github.com/BootlegYouki/L.A.R.A/issues/39) creates the Axum, WebSocket and migration skeleton so developers can add routes in parallel PRs without touching each other's files. Then match the behavior of `scripts/mock_hub.py` and `tests/test_mock_hub.py`, which are the acceptance reference. |
| 6 | Before every PR: run the commands in [`server/AGENTS.md`](./AGENTS.md), `python3 scripts/verify_invariants.py` and `python3 -m unittest discover tests`; fill the PR template; update `server/docs/`. |

**Where things live:** API and events in [`contracts/`](../contracts/) (never edit in a feature PR), schema in [`contracts/schema/`](../contracts/schema/), UI rules in [`design-system/design-system.md`](../design-system/design-system.md), product behavior in [`docs/PRD.md`](../docs/PRD.md), all rules in [`rules/`](../rules/).

**Status:** No backend code yet. The contracts, SQL schema and a full behavioral reference (the mock hub) are ready.

---

## 0. Developer Pre-Flight & Hard Invariants

**Every contributor and AI agent working in `server/` MUST adhere to these rules:**

1. **Strict Zero-Internet Policy:** The Hub operates completely offline over the classroom router (the Hub PC's own hotspot only as a fallback). Never add remote cloud connections, telemetry, or external API dependencies.
2. **Ports & Protocol Standard:**
   * **Port 8080 (HTTP):** Captive download portal (`/download`), REST endpoints, and HTTP 206 video streaming.
   * **Port 8081 (WebSocket):** Realtime event broker (`tokio-tungstenite`) for quiz timers, enrollments, and AI streaming.
   * **Port 8888 (UDP):** Subnet broadcast beacon (`255.255.255.255:8888`) every 3 seconds.
   * **mDNS (`_lara._tcp.local`):** Registered on port 8080.
3. **Bandwidth Throttling (Router Protection):**
   * Enforce a per-client token-bucket rate limit (maximum **2.0 MB/s per client**, not per connection) on HTTP 206 video endpoints to prevent 40 connected desks from freezing cheap classroom routers.
   * Cap teacher video uploads at **250MB** (recommended 720p H.264).
4. **Windows Defender Firewall Countermeasure:**
   * Inbound ports (8080, 8081, 8888) are blocked by default on Windows "Public" networks.
   * The NSIS installer (`.exe`) must automatically register silent firewall rules:
     ```cmd
     netsh advfirewall firewall add rule name="LARA Local Hub" dir=in action=allow protocol=TCP localport=8080,8081
     netsh advfirewall firewall add rule name="LARA Discovery Beacon" dir=in action=allow protocol=UDP localport=8888
     ```
   * The Hub dashboard must include an in-app LAN Port Health Check indicator.
5. **Database Stack Invariant:** Use embedded **SQLite via SQLx (Rust)** with monotonic `sync_revisions` tracking. **Prisma is strictly forbidden.**
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
* **Gradebook Exporter:** Direct generation of gradebook spreadsheets (`.xlsx`, learners by work, points only) using `rust_xlsxwriter` with automatic detection of mounted USB flash drives across Windows and Linux.
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
* Unique 6-character Class Code generator (e.g. `K7M4QX`, no 0/O/1/I).
* Real-time join approval push notifications over WebSockets.
* Two-way delta-sync engine:
  * `POST /api/sync/pull`: Cursor-based deltas (`cursor` -> `next_cursor`, with `hub_id`, `sync_epoch` and `reset`).
  * `POST /api/sync/push`: Accepts queued quiz attempts and comments. Homework photos arrive through `POST /api/assignments/{id}/submit` (multipart, client-generated `submission_id`).

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

> **Source of truth:** [`contracts/schema/server_master.sql`](../contracts/schema/server_master.sql). Rules and protocol: [`rules/database-and-sync.md`](../rules/database-and-sync.md). Do not copy column lists into this README.

* **Migrations:** one SQL migration per logical group under `backend/src/db/migrations/`, reproducing `server_master.sql` exactly. PRAGMAs are connection options, not migration statements.
* **Never log or return:** `users.pin_hash`, `quiz_questions.correct_answer`, `synonyms_json`, server `file_path` values, raw session tokens.
* **Sync ledger:** every write a client must see also inserts a `sync_revisions` row in the same transaction. The cursor is `seq`, never a timestamp.
* **Identity:** create `hub_meta.hub_id` on first run; bump `hub_meta.sync_epoch` on restore from backup or when pruning old tombstones.
* **Deletion:** never delete users, classrooms or graded rows. Deactivate or archive.
* **Student-facing serializers** are separate types that cannot contain `correct_answer`. A test must scan every student route's JSON for it.

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
│   │   ├── services/                 # Auto-grader, gradebook exporter, text chunker
│   │   ├── ai/                       # llama-server process manager & FIFO queue
│   │   └── db/                       # SQLx migrations & SQLite schema
│   └── bin/                          # llama-server pre-compiled binary
└── docs/                             # Mandatory subsystem architectural documentation
```

---

## 5. Server Team Issues and Build Order

The live list is on GitHub, so it is not copied here: [open `server` issues](https://github.com/BootlegYouki/L.A.R.A/issues?q=is%3Aissue%20is%3Aopen%20label%3A%22scope%3Aserver%22%20sort%3Acreated-asc) and the [sprint board](https://github.com/users/BootlegYouki/projects/2).

* Issues are titled `[SERVER sprint.step]`. The team works them **one at a time, in step order**, both developers together, with one open PR (`rules/team-workflow-and-prs.md` section 1.1).
* Sprint 0 is `docs/TECH_SPEC.md`; its section 10 records the build order and what each step creates for later steps.
* Each issue names its PRD requirement, its contract routes and events, its Target Files and the checks that prove it.
