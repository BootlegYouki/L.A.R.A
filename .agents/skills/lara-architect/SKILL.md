---
name: lara-architect
description: Comprehensive architectural rules, networking protocols, SQLite delta-sync schemas, pluggable SLM guardrails, contracts, developer tooling, and Material 3 accessibility guidelines for developing the L.A.R.A offline LAN classroom ecosystem (mobile, desktop, and server). Use whenever implementing, modifying, or testing components across L.A.R.A applications.
---

# L.A.R.A System Architecture & Implementation Guidelines

Use this skill whenever designing, writing, modifying, auditing, or testing code in the **L.A.R.A** project (`mobile/`, `desktop/`, and `server/`).

---

## 1. Zero-Internet LAN Networking Invariants

All applications operate strictly within an isolated local area network (the classroom router; a laptop hotspot only as a fallback). Never introduce external cloud dependencies:
* **Forbidden Cloud Calls:** No Firebase (Auth, Firestore, Messaging), no Google Play APIs, no external CDNs (`cdnjs`, `unpkg`, `cdn.jsdelivr`), no Google Fonts web links (`fonts.googleapis.com`), and no remote analytics or telemetry.
* **All Assets Bundled Locally:** All fonts, icons (Phosphor), installers, media, and GGUF model files must be bundled locally or served from the Local Hub.

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
  * **Manual Fallback:** Always provide an easy-to-use input box to type the host IP manually if router client isolation blocks broadcast.
* **Firewall & AP Isolation Countermeasures:**
  * **Windows Defender Firewall:** Server installer must automatically register inbound TCP rules (`8080`, `8081`) and UDP (`8888`) via `netsh advfirewall`.
  * **Router AP Isolation:** If router blocks peer-to-peer traffic, use manual IP entry or switch teacher laptop to Mobile Hotspot mode.
  * **Android MulticastLock:** Mobile client must explicitly acquire `WifiManager.MulticastLock` to prevent the OS from dropping UDP discovery beacons.


---

## 2. API Contracts & Serialization Rules (`contracts/`)

The `contracts/` directory is the single source of truth for all network communication between Local Hub and client applications:
* **Canonical REST Specification:** `contracts/openapi.yaml` (OpenAPI 3.1).
* **WebSocket Event Schemas:** `contracts/events/*.json` plus `contracts/events/README.md` (handshake, direction table). Events: `EVENT_HELLO`, `EVENT_JOIN_REQUEST`, `EVENT_JOIN_APPROVAL`, `EVENT_ANNOUNCEMENT_PUSH`, `EVENT_QUIZ_START`, `EVENT_QUIZ_CLOSED`, `EVENT_QUIZ_SUBMIT`, `EVENT_GRADE_CONFIRMED`, `EVENT_PRESENCE`, `EVENT_AI_CHAT_REQUEST`, `EVENT_QUEUE_STATUS`, `EVENT_AI_TOKEN_STREAM`, `EVENT_ERROR`.
* **Auth:** bearer token from `POST /api/auth/login` on every route except `/download`, register and login; errors use `{error:{code,message}}`.
* **Serialization Case Rule:** All network JSON keys transmitted over HTTP and WebSockets must strictly use **`snake_case`**.
  * Kotlin uses `@SerialName("student_id")`.
  * Rust uses `#[serde(rename_all = "snake_case")]`.
  * TypeScript uses `snake_case` interfaces.
* **Strict Answer Key Redaction:** When student clients request active quizzes (`GET /api/quizzes/active` or `EVENT_QUIZ_START`), the server **must strictly omit `correct_answer`**. Student models and local databases must never store unsubmitted answer keys.
* **Contract-First Rule:** Never create or alter endpoints in Kotlin, TypeScript, or Rust without first defining or updating the schema in `contracts/`, the mock hub and the tests (its own `contract-change` PR, merged first).

---

## 3. Database Architecture & Delta-Sync (`rules/database-and-sync.md`)

### Technology Invariants (No Prisma)
* **Android Client (`mobile/`):** Must use **Android Room (SQLite)**.
* **Server Backend (`server/`):** Must use **SQLx (Rust)** with embedded SQLite. Single-binary engine; no Node backend.
* **Desktop Client (`desktop/`):** Must use **`@tauri-apps/plugin-sql`**.
* **Forbidden ORMs:** Never introduce Prisma (causes 50MB binary bloat and packaging failures).

### Schema, Parity & Sync (details: `rules/database-and-sync.md`)
* The schema lives only in `contracts/schema/server_master.sql` (16 tables) and `client_offline.sql` (14 tables). Never copy column lists into other docs.
* **Never on a client:** `pin_hash`, other people's LRN, `correct_answer` or synonyms, server file paths. Clients get other people's names via `PublicUser`.
* **Client-only:** `sync_status` (`SYNCED` / `QUEUED_FOR_SYNC`), `materials.local_file_path`, `sync_state` (`hub_id`, `sync_epoch`, `cursor`, `current_user_id`).
* **Sync cursor is `sync_revisions.seq`, an integer sequence, never a timestamp** (the offline Hub clock can be wrong). The Hub writes a revision row in the same transaction as each change, scoped by `classroom_id` and `student_id`.
  * **Pull (`POST /api/sync/pull`):** `{cursor, hub_id?, sync_epoch?}` -> changed records, tombstones, `users`, `next_cursor`, `has_more`, `reset`. Apply the response and store `next_cursor` in ONE local transaction. On `reset: true` wipe mirrored tables but keep `QUEUED_FOR_SYNC` rows.
  * **Push (`POST /api/sync/push`):** queued quiz attempts and comments; mark `SYNCED` only from a per-item receipt. Homework photos use the multipart submit route with a client-generated `submission_id`.
* **Integrity:** never delete users, classrooms or graded rows (deactivate/archive); one ACTIVE quiz per classroom; `quiz_attempts` exists from `begin` as `IN_PROGRESS`.
* PRAGMAs are connection settings, not migrations (SQLx runs migrations in a transaction).
* **Offline-First by Default (Home Study Mode):**
  * Zero blocking network error screens when launched offline or away from school.
  * Students can always browse enrolled classes, read announcements, study lesson text chunks, and watch downloaded videos completely offline.
  * Homework photos taken at home queue locally as `'QUEUED_FOR_SYNC'`.
  * Capable devices (RAM ≥ 6GB on mobile, or laptop with ≥ 4GB RAM) with local GGUF models can use Socratic AI 100% offline at home; otherwise, AI queries indicate they unlock upon reconnecting to the classroom Hub.


---

## 4. Pluggable Socratic AI & Experimental SLM Guardrails (`rules/socratic-ai-guardrails.md`)

The AI tutor (**L.A.R.A AI**) is a pedagogical guide for Filipino students (Grades 1 to 12), not an answer engine. 

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
2. **Strict Grounding:** Always ground hints exclusively in the teacher's lesson chunks (`material_chunks`). If the lesson does not cover the question, say so and point back to the lesson; never answer from general knowledge.
3. **Step-by-Step Questioning:** Offer one small hint followed by a guiding question.
4. **Bilingual:** Detect and reply in the learner's preferred language (English or natural conversational Filipino/Taglish).
5. **Quiz Lockout:** While the learner has an `IN_PROGRESS` quiz attempt, the chat UI is never composed (the AI tab may show a disabled explanation) and the Hub rejects AI requests with `HTTP 403` / `EVENT_ERROR` `QUIZ_IN_PROGRESS`.

### Model Choice Is Open
MiniCPM5-2B is only a baseline candidate. Choose the model from the evaluation in `rules/socratic-ai-guardrails.md` section 5 (scored test set run on the real Hub machine). Do not claim a model is good enough without those numbers.

---

## 5. Paperless Assessment (Quiz) Engine Rules (`rules/quiz-and-anti-cheat.md`)

Designed to replace paper test printing for Philippine public school teachers.

1. **Global Time Limit:** Countdowns run on overall quiz time (e.g., 20 mins for 15 items), not per-question timers.
2. **Visual Countdown Pill:** Prominent timer (Green > 5m -> Yellow <= 5m -> Red pulsing <= 2m).
3. **Auto-Submit on Timeout:** When `00:00` is reached, input fields lock immediately and current answers auto-submit.
4. **Network Disconnect Resilience:** If Wi-Fi cuts out during an active test, the timer continues locally on device hardware clocks (`SystemClock.elapsedRealtime()`). Upon completion, answers are stored as `'QUEUED_FOR_SYNC'` and auto-flush to the Hub the moment Wi-Fi reconnects.
5. **Auto-Grading:** Instant scoring on the Hub in < 50ms for Multiple Choice, True/False, and Identification.

---

## 6. UI/UX Design Authority & Accessibility (`rules/ui-and-accessibility.md`)

* **Design authority:** `design-system/design-system.md` and `design-system/` are canonical (tokens only, no gradients, Nunito, Phosphor, purple only for the AI tutor). Layouts are free if they use the documented components and follow Google Classroom as the structural reference.
* **Components:** `androidx.compose.material3` themed with `design-system/mobile/*` on Android; Tailwind with `design-system/desktop/tailwind.theme.ts` on Desktop.
* **Touch Targets (Grades 1–12):** Minimum **52dp**, **56dp** for primary actions and quiz options.
* **Contrast & Typography:** Minimum **4.5:1** text-to-background contrast across all surfaces. Minimum 14sp body text, 18sp headings.
* **Bilingual Localization:** Zero hardcoded strings. English strings in `values/strings.xml`, Filipino strings in `values-tl/strings.xml`.
* **CameraX Homework Capture:** Viewfinder must display a clear rectangular document framing guide with automatic downscaling and JPEG compression (<800KB).
* **Gradebook Export:** Local Hub desktop app provides one-click export of points per learner per assignment and quiz to `.xlsx` / `.csv`, directly to a plugged-in USB flash drive. No DepEd categories or weights.

---

## 7. Teacher Mobile Capabilities (Dual-Role Client)

The mobile Android application is a **dual-role client** supporting both Students and Teachers:
* **TeacherNavGraph:** When authenticated as a Teacher, the bottom navigation presents management controls.
* **Join Approvals:** Mobile bottom sheet to Accept or Decline student enrollment requests on the go.
* **Stream Broadcasting:** FAB allowing teachers to post announcements to the classroom feed directly from their smartphone.
* **Quiz Remote Controller:** Remote "Start Quiz" trigger button to broadcast synchronized countdowns while walking around the room.
* **Live Assessment Monitor:** Real-time submission counter card displaying how many learners have completed the test.

---

## 8. Developer Tooling & Quality Gates (`rules/developer-tooling-and-testing.md`)

* **Mock Hub:** `python3 scripts/mock_hub.py` simulates the whole contract: UDP beacon, REST with bearer auth, uploads, `206` streaming, and a WebSocket broker on `:8081`. Seed accounts (PIN `1234`): `T-0001` (class code `K7M4QX`), `123456789012`, `123456789013`, `ADMIN-0001`.
* **Tests:** `python3 -m unittest discover tests` (contract, schema, mock hub behavior and `test_contract_coverage.py`, which fails when contracts and the mock hub drift). All must pass.
* **Invariant scanner:** `python3 scripts/verify_invariants.py` before every PR (forbidden cloud dependencies, Filipino string parity).
* **Folder docs:** major PRs update `mobile/docs/`, `desktop/docs/` or `server/docs/`. Each team also has a nested `AGENTS.md` (read the one for your folder) and a `docs/TECH_SPEC.md`.
