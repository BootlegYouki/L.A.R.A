---
name: lara-architect
description: Comprehensive architectural rules, networking protocols, SQLite delta-sync schemas, pluggable SLM guardrails, contracts, developer tooling, and Material 3 accessibility guidelines for developing the L.A.R.A offline LAN classroom ecosystem (mobile, desktop, and server). Use whenever implementing, modifying, or testing components across L.A.R.A applications.
---

# L.A.R.A System Architecture & Implementation Guidelines

Use this skill whenever designing, writing, modifying, auditing, or testing code in the **L.A.R.A** project (`mobile/`, `desktop/`, and `server/`).

---

## 1. Zero-Internet LAN Networking Invariants

All applications operate strictly within an isolated local area network (router or teacher laptop hotspot). Never introduce external cloud dependencies:
* **Forbidden Cloud Calls:** No Firebase (Auth, Firestore, Messaging), no Google Play APIs, no external CDNs (`cdnjs`, `unpkg`, `cdn.jsdelivr`), no Google Fonts web links (`fonts.googleapis.com`), and no remote analytics or telemetry.
* **All Assets Bundled Locally:** All fonts, icons (Material Symbols), installers, media, and GGUF model files must be bundled locally or served from the Local Hub.

### Ports & Protocol Standards
* **HTTP REST & File Server (Port 8080):**
  * Serves Captive Download Web Portal at `http://<hub-ip>:8080/download`.
  * Serves educational videos via HTTP Byte-Range requests (`Range: bytes=X-`, response `206 Partial Content`).
  * Enforces per-client streaming rate limit (maximum 2.0 MB/s) to protect router throughput.
  * Upload limit: Teacher videos capped at 250MB (recommended 720p H.264).
* **Realtime Event Broker (Port 8081):**
  * WebSocket channel for live quiz timers, active quiz lock signals, enrollment approvals, stream announcements, and Hub-assisted AI token streaming.
* **Network Discovery:**
  * **mDNS / Zeroconf:** Register service as `_lara._tcp.local` on port 8080.
  * **UDP Subnet Beacon:** Broadcast lightweight JSON heartbeat every 3 seconds to `255.255.255.255:8888`:
    `{"app": "lara", "version": "1.0.0", "name": "Grade 4 - Science", "ip": "192.168.1.50", "http_port": 8080, "ws_port": 8081}`
  * **Manual Fallback:** Always provide an elementary-friendly input box to type the host IP manually if router client isolation blocks broadcast.
* **Firewall & AP Isolation Countermeasures:**
  * **Windows Defender Firewall:** Server installer must automatically register inbound TCP rules (`8080`, `8081`) and UDP (`8888`) via `netsh advfirewall`.
  * **Router AP Isolation:** If router blocks peer-to-peer traffic, use manual IP entry or switch teacher laptop to Mobile Hotspot mode.
  * **Android MulticastLock:** Mobile client must explicitly acquire `WifiManager.MulticastLock` to prevent the OS from dropping UDP discovery beacons.


---

## 2. API Contracts & Serialization Rules (`contracts/`)

The `contracts/` directory is the single source of truth for all network communication between Local Hub and client applications:
* **Canonical REST Specification:** `contracts/openapi.yaml` (OpenAPI 3.1).
* **WebSocket Event Schemas:** `contracts/events/` (`quiz_start.json`, `quiz_submit.json`, `join_request.json`, `join_approval.json`, `ai_stream.json`, `queue_status.json`).
* **Serialization Case Rule:** All network JSON keys transmitted over HTTP and WebSockets must strictly use **`snake_case`**.
  * Kotlin uses `@SerialName("student_id")`.
  * Rust uses `#[serde(rename_all = "snake_case")]`.
  * TypeScript uses `snake_case` interfaces.
* **Strict Answer Key Redaction:** When student clients request active quizzes (`GET /api/quizzes/active` or `EVENT_QUIZ_START`), the server **must strictly omit `correct_answer`**. Student models and local databases must never store unsubmitted answer keys.
* **Contract-First Rule:** Never create or alter endpoints in Kotlin, TypeScript, or Rust without first defining or updating the schema in `contracts/`.

---

## 3. Database Architecture & Delta-Sync (`rules/database-and-sync.md`)

### Technology Invariants (No Prisma)
* **Android Client (`mobile/`):** Must use **Android Room (SQLite)**.
* **Server Backend (`server/`):** Must use **SQLx (Rust)** or **Drizzle ORM + `better-sqlite3` (Node)**. Single-binary embedded engine.
* **Desktop Client (`desktop/`):** Must use **`@tauri-apps/plugin-sql`**.
* **Forbidden ORMs:** Never introduce Prisma (causes 50MB binary bloat and packaging failures).

### Schema Parity & Client Slices
* Core entity tables share identical column definitions across server and clients (`users`, `classrooms`, `enrollments`, `announcements`, `materials`, `assignments`, `assignment_submissions`, `quizzes`, `quiz_questions`, `quiz_attempts`).
* **Client-Only Helper Columns:**
  * `sync_status`: `'SYNCED'` vs `'QUEUED_FOR_SYNC'` (for offline homework photos and quiz attempts completed at home).
  * `local_file_path`: Absolute on-disk path of cached offline PDF or video files.
* **Master Sync Ledger (`sync_revisions`):**
  * The Hub maintains monotonic change records: `(id, entity_table, entity_id, updated_at)`.
  * **Pull Phase (`POST /api/sync/pull`):** Client sends `{ student_id, last_synced_at }`; Hub returns deltas; client writes inside a single atomic SQLite transaction.
  * **Push Phase (`POST /api/sync/push`):** Client uploads queued offline submissions; Hub acknowledges; client marks local rows `'SYNCED'`.
* **Offline-First by Default (Home Study Mode):**
  * Zero blocking network error screens when launched offline or away from school.
  * Students can always browse enrolled classes, read announcements, study lesson text chunks, and watch downloaded videos completely offline.
  * Homework photos taken at home queue locally as `'QUEUED_FOR_SYNC'`.
  * Capable devices (RAM ≥ 6GB on mobile, or laptop with ≥ 4GB RAM) with local GGUF models can use Socratic AI 100% offline at home; otherwise, AI queries indicate they unlock upon reconnecting to the classroom Hub.


---

## 4. Pluggable Socratic AI & Experimental SLM Guardrails (`rules/socratic-ai-guardrails.md`)

The AI tutor (**L.A.R.A AI**) is a pedagogical guide for Filipino elementary students (Grades 1 to 6), not an answer engine. 

### Pluggable GGUF Runtime
* The inference architecture is **model-agnostic and pluggable via GGUF and `llama.cpp`** (Android JNI `arm64-v8a`, Desktop Tauri sidecar, and Hub `llama-server`).
* **Candidate SLM Evaluation Matrix:**
  * **Primary Baseline Candidate:** **MiniCPM5-2B (Int4 / Q4_K_M GGUF, ~1.55GB)**
  * **Experimental Candidates:** Qwen2.5-1.5B/3B, Llama-3.2-1B/3B, SmolLM2-1.7B, Gemma-2-2B.
* **Zero Code Changes for Model Swapping:** The system loads models dynamically (`MODEL_PATH=models/*.gguf`). Swapping models requires pointing to a different GGUF file without altering JNI bindings or WebSocket streaming logic.
* **Context Window Standard:** Capped at **2,048 tokens** across all candidate models to keep RAM and latency predictable.

### Hardware-Adaptive Dual Routing
* **Android Phones with < 6GB physical RAM:** Must strictly route inference to the Local Hub over WebSockets (`:8081`). Client JVM heap must stay **< 250MB** to prevent Android Low Memory Killer (OOM) crashes on 3GB/4GB budget devices (Infinix, TECNO, itel, realme).
* **Phones with ≥ 6GB RAM & Laptops:** Can execute supported candidate GGUF models 100% locally via `llama.cpp` (JNI on Android, sidecar binary on Desktop).
* **Hub Multi-Slot Queue:** Hub manages concurrent low-RAM requests using a FIFO queue with 2 to 4 parallel `llama-server` slots, pushing real-time queue position updates (`"Pangalawa ka sa pila - est. 4s"`) over WebSockets.

### Pedagogical System Prompt Directives
1. **Never provide direct answers:** If asked "What is the answer to #3?" or "Ano ang sagot?", politely decline:
   *"Hindi ko maibibigay ang mismong sagot, pero tutulungan kitang tuklasin ito! Balikan natin ang binasa mo. Ano ang unang hakbang?"*
2. **Strict Grounding:** Always ground hints exclusively in the teacher's uploaded lesson module text chunks.
3. **Step-by-Step Questioning:** Offer one small hint followed by a guiding question.
4. **Bilingual:** Detect and reply in the pupil's preferred language (English or natural conversational Filipino/Taglish).
5. **Quiz Lockout:** While a quiz is active, the AI tutor floating button is completely unmounted from the UI, and the Hub server rejects any inference calls with `HTTP 403 / QUIZ_IN_PROGRESS`.

---

## 5. Paperless Assessment (Quiz) Engine Rules (`rules/quiz-and-anti-cheat.md`)

Designed to replace paper test printing for DepEd teachers.

1. **Global Time Limit:** Countdowns run on overall quiz time (e.g., 20 mins for 15 items), not per-question timers.
2. **Visual Countdown Pill:** Prominent timer (Green > 5m -> Yellow <= 5m -> Red pulsing <= 2m).
3. **Auto-Submit on Timeout:** When `00:00` is reached, input fields lock immediately and current answers auto-submit.
4. **Network Disconnect Resilience:** If Wi-Fi cuts out during an active test, the timer continues locally on device hardware clocks (`SystemClock.elapsedRealtime()`). Upon completion, answers are stored as `'QUEUED_FOR_SYNC'` and auto-flush to the Hub the moment Wi-Fi reconnects.
5. **Auto-Grading:** Instant scoring on the Hub in < 50ms for Multiple Choice, True/False, and Identification.

---

## 6. UI/UX Design Authority & Accessibility (`rules/ui-and-accessibility.md`)

* **Design Team Authority (Primary):** The wireframes, mockups, and prototypes produced by the project's **Design Team** are the primary authority that must be implemented.
* **Material Design 3 Best Practice:** Developers should implement the Design Team's layouts using Google **Material Design 3 (Material You)** primitives (`androidx.compose.material3` on Android, Tailwind M3 tokens on Desktop) to guarantee native accessibility, elevation, and tactile child-friendly feedback.
* **Touch Targets (Grades 1–6):** Minimum **48dp** (preferred **56dp**) on all clickable cards, buttons, and radio options.
* **Contrast & Typography:** Minimum **4.5:1** text-to-background contrast across all surfaces. Minimum 14sp body text, 18sp headings.
* **Bilingual Localization:** Zero hardcoded strings. English strings in `values/strings.xml`, Filipino strings in `values-tl/strings.xml`.
* **CameraX Homework Capture:** Viewfinder must display a clear rectangular document framing guide with automatic downscaling and JPEG compression (<800KB).
* **DepEd Export:** Local Hub desktop app provides one-click export of student grades to `.xlsx` / `.csv` formatted for official DepEd Class Records directly to plugged-in USB flash drives.

---

## 7. Teacher Mobile Capabilities (Dual-Role Client)

The mobile Android application is a **dual-role client** supporting both Students and Teachers:
* **TeacherNavGraph:** When authenticated as a Teacher, the bottom navigation presents management controls.
* **Join Approvals:** Mobile bottom sheet to Accept or Decline student enrollment requests on the go.
* **Stream Broadcasting:** FAB allowing teachers to post announcements to the classroom feed directly from their smartphone.
* **Quiz Remote Controller:** Remote "Start Quiz" trigger button to broadcast synchronized countdowns while walking around the room.
* **Live Assessment Monitor:** Real-time submission counter card displaying how many pupils have completed the test.

---

## 8. Developer Tooling & Quality Gates (`rules/developer-tooling-and-testing.md`)

* **Standalone Mock Hub:** Run `python3 scripts/mock_hub.py` to simulate UDP beacon (`:8888`), HTTP REST (`:8080`), byte-range video streaming, and teacher mobile triggers.
* **Pre-Push Invariant Scanner:** Run `python3 scripts/verify_invariants.py` before opening PRs to catch forbidden cloud imports or missing Filipino string keys.
* **Automated Test Suite:** Run `python3 -m unittest discover tests` (13/13 tests must pass).
* **Folder-Level Documentation Invariant:** Major PRs must include updated architectural notes in the assigned folder (`mobile/docs/`, `desktop/docs/`, or `server/docs/`) to preserve context for the Lead Developer.
* **GitFlow Standard:** All feature branches branch off `staging` and open PRs targeting `staging`. Merges to `main` occur only upon sprint milestone completion.

