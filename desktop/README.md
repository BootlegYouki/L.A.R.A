# L.A.R.A Desktop Client (`desktop/`)

> **Subsystem Scope:** Cross-platform desktop application for **Student Laptops**, **School Computer Labs (DepEd PC Packages)**, and **Teacher Workstations**.  
> **Repository Role:** Dual-role client operating 100% offline within the classroom local area network (LAN).

---

## 0. Developer Pre-Flight & Hard Invariants

**Every contributor and AI agent working in `desktop/` MUST adhere to these rules:**

1. **Strict Zero-Internet Policy:** Never import external CDNs, Google Fonts web links, or remote telemetry. All assets (Material Symbols, fonts, dependencies) must be bundled locally into the binary.
2. **Why Tauri (Not Electron):** Tauri produces an ultra-lightweight ~15MB installer consuming ~40MB RAM (compared to Electron's ~150MB installer and 500MB RAM bloat), ensuring smooth performance on older school computer lab PCs (Intel Celeron / Core i3 with 4GB RAM).
3. **Database Stack Invariant:** Use native local SQLite via the official **`@tauri-apps/plugin-sql`** plugin. **Prisma is strictly forbidden** to prevent 50MB query engine binary bloat and packaging crashes.
4. **Canonical Network Contracts (`contracts/`):**
   * Consult [`../contracts/openapi.yaml`](../contracts/openapi.yaml) and [`../contracts/events/`](../contracts/events/) before writing any API call or interface.
   * All network JSON fields are strictly **`snake_case`**.
   * **Anti-Cheat Redaction:** The student view must never receive or parse `correct_answer` during active quizzes.
5. **UI & Design Authority:**
   * Visual mockups and flows from the **Design Team** are the primary authority.
   * Implement screens using **Material Design 3 design tokens configured in Tailwind CSS**.
   * Touch and click targets must be minimum 48dp, contrast ratio ≥ 4.5:1.
   * Externalize all strings to support instant runtime toggling between English and Filipino.
6. **Local Testing via Mock Hub:** Do not wait for the Server team. Start the standalone Local Hub simulator from the repo root:
   ```bash
   python3 ../scripts/mock_hub.py
   ```
7. **Pre-Push Linter:** Run the invariant scanner before opening any PR:
   ```bash
   python3 ../scripts/verify_invariants.py
   ```
8. **Mandatory Documentation:** Every major feature PR must include updated architectural notes in [`desktop/docs/`](./docs/).
9. **Offline-First by Default (Home Study Mode):** The app must **never** show a blocking "No Connection" error on launch. When disconnected from the school server, students must always be able to browse enrolled classes, read announcements, study lesson text chunks, and play downloaded videos completely offline. If the laptop has a downloaded GGUF model file, local Socratic AI via sidecar works 100% offline at home too; otherwise, AI queries indicate they unlock when connected to the classroom Hub.


---

## 1. Technical Stack & Architecture

* **Desktop Application Core:** Tauri 2.x (Rust core + Webview frontend).
* **Frontend Framework:** React 19, TypeScript 5.x, Vite 6.x.
* **Styling & Tokens:** Tailwind CSS 4.x configured with Google Material Design 3 surface roles, typography scales, and elevation tokens.
* **State Management:** Zustand with offline persistence (`localStorage` / Tauri store).
* **Local Persistence:** Local SQLite database via `@tauri-apps/plugin-sql`.
* **Networking:**
  * HTTP REST: Tauri HTTP plugin / standard browser `fetch`.
  * WebSockets: Native browser `WebSocket` connecting to Local Hub port 8081.
  * Discovery: Rust background thread for mDNS browsing (`_lara._tcp.local`) and UDP subnet broadcast listening on port 8888.
* **Media Player:** HTML5 `<video>` element styled with custom M3 controls, supporting HTTP 206 Byte-Range streaming and offline local disk caching.
* **Pluggable Desktop SLM:** Bundled `llama.cpp` CLI binary (`llama-cli`) executed via Tauri sidecar process for 100% offline on-device inference (MiniCPM5-2B, Qwen2.5, Llama 3.2) on laptops with **≥ 4GB RAM**.
* **Target Platforms:** Windows 10/11 (64-bit), Ubuntu/Debian Linux (DepEd lab PCs), and macOS.

---

## 2. Core Functional Modules

### 2.1 Network Discovery & Setup
* Background mDNS scanner detects classroom Hub automatically.
* Manual IP fallback input dialog allowing connection even if router AP isolation blocks broadcast.
* Seamless offline transition: When disconnected from classroom Wi-Fi, cached handouts and downloaded video lessons remain playable offline.

### 2.2 Dual-Role Capabilities
* **Student Mode:**
  * Class Code enrollment dialog (`SCI4-AG`) with real-time pending approval status.
  * Announcement stream with teacher comment moderation.
  * Lesson handouts with zoom controls.
  * Full-screen paperless quiz engine with synchronized timer and instant auto-grading.
  * Socratic AI drawer with split-screen view (lesson on left, tutor on right).
* **Teacher Desktop Mode (Alternative to Hub GUI):**
  * **Teacher Quiz Builder Wizard:** Multi-step wizard to create tests, manage question banks, and randomize question order.
  * **Live Quiz Submission Matrix:** Real-time telemetry grid showing which pupils are currently answering and their auto-graded scores.
  * **Class Roster Table:** List of enrolled pupils with one-click Accept/Decline actions.

### 2.3 On-Device Socratic AI Sidecar
* On student laptops and lab PCs with ≥ 4GB RAM, the desktop client executes candidate GGUF models locally via a spawned `llama.cpp` sidecar process.
* Operates at high speeds (10 to 25 tokens/s) with zero network traffic.
* If laptop RAM is < 4GB or model file is missing, automatically falls back to streaming tokens from the Local Hub over WebSockets.

---

## 3. Client Offline Database Schema (`@tauri-apps/plugin-sql`)

Desktop developers must initialize and query their local SQLite database strictly adhering to the canonical SQL DDL at [`contracts/schema/client_offline.sql`](../contracts/schema/client_offline.sql).

### The 12 Local Tables:
1. **`users`:** `id`, `lrn_or_id`, `full_name`, `role` (`TEACHER` | `STUDENT`), `pin_hash`, `created_at`, `updated_at`.
2. **`classrooms`:** `id`, `name`, `section`, `class_code`, `teacher_id`, `created_at`, `updated_at`.
3. **`enrollments`:** `id`, `classroom_id`, `student_id`, `status` (`PENDING` | `ACTIVE` | `REJECTED`), `joined_at`, `updated_at`.
4. **`announcements`:** `id`, `classroom_id`, `title`, `content`, `allow_comments`, `created_at`, `updated_at`.
5. **`announcement_comments`:** `id`, `announcement_id`, `author_id`, `content`, `created_at`, `updated_at`, `sync_status`.
6. **`materials`:** `id`, `classroom_id`, `title`, `file_type`, `file_size_bytes`, `extracted_text`, `download_url`, `local_file_path` (cached disk path for home study), `created_at`, `updated_at`.
7. **`assignments`:** `id`, `classroom_id`, `title`, `instructions`, `deped_category` (`WRITTEN_WORK` | `PERFORMANCE_TASK` | `QUARTERLY_ASSESSMENT`), `due_date`, `max_points`, `created_at`, `updated_at`.
8. **`assignment_submissions`:** `id`, `assignment_id`, `student_id`, `file_path`, `file_type`, `submitted_at`, `score`, `teacher_feedback`, `updated_at`, `sync_status`.
9. **`quizzes`:** `id`, `classroom_id`, `title`, `instructions`, `deped_category`, `time_limit_minutes`, `status` (`DRAFT` | `ACTIVE` | `CLOSED`), `started_at` (server synchronized epoch ms), `created_at`, `updated_at`.
10. **`quiz_questions`:** `id`, `quiz_id`, `order_index`, `question_text`, `question_type`, `options_json`, `points`, `image_path`, `created_at`, `updated_at`. **Strictly omits `correct_answer`.**
11. **`quiz_attempts`:** `id`, `quiz_id`, `student_id`, `started_at`, `submitted_at`, `score`, `total_points`, `answers_json`, `updated_at`, `sync_status`.
12. **`ai_chat_messages`:** `id`, `classroom_id`, `student_id`, `material_id`, `role` (`USER` | `TUTOR`), `content`, `created_at` (persists conversation during offline study).

---

## 4. Directory Structure

```
desktop/
├── src-tauri/
│   ├── Cargo.toml                    # Rust dependencies (tauri, tokio, mdns, sql)
│   ├── tauri.conf.json               # Window settings, permissions, sidecars
│   ├── build.rs
│   └── src/
│       ├── main.rs                   # Tauri application entrypoint
│       ├── discovery.rs              # Rust mDNS & UDP broadcast scanner
│       ├── sidecar_llama.rs          # llama.cpp sidecar process manager & IPC
│       └── db.rs                     # Local SQLite migration scripts
├── src/
│   ├── main.tsx                      # React root
│   ├── App.tsx                       # Router & layout shell
│   ├── index.css                     # Tailwind CSS & Material 3 variables
│   ├── components/                   # Reusable M3 cards, buttons, video player
│   ├── pages/                        # Stream, Classwork, Quiz, Teacher Dashboard
│   ├── services/                     # Tauri SQL DB, WebSocket, Mock Hub client
│   └── store/                        # Zustand global state stores
└── docs/                             # Mandatory subsystem architectural documentation
```

---

## 5. Desktop Team Sprint Roadmap & Execution Order

All desktop issues on GitHub follow the `[DESKTOP Sprint.Step]` naming convention:

* **Sprint 1 (Scaffolding & LAN Discovery):**
  * `[DESKTOP 1.1]`: Setup Tauri 2.x + React 19 + Tailwind M3 shell with local SQLite storage ([#29](https://github.com/BootlegYouki/L.A.R.A/issues/29))
  * `[DESKTOP 1.2]`: Implement mDNS/UDP discovery scanner in Rust/Tauri ([#5](https://github.com/BootlegYouki/L.A.R.A/issues/5))
* **Sprint 3 (Media Player & Offline Caching):**
  * `[DESKTOP 3.1]`: Build HTML5 video lesson player with offline local disk caching ([#30](https://github.com/BootlegYouki/L.A.R.A/issues/30))
* **Sprint 4 (Teacher Quiz Builder):**
  * `[DESKTOP 4.1]`: Build Teacher Quiz Builder wizard with question bank & live submission matrix ([#31](https://github.com/BootlegYouki/L.A.R.A/issues/31))
* **Sprint 5 (On-Device SLM Sidecar):**
  * `[DESKTOP 5.1]`: Embed llama.cpp sidecar for on-device candidate SLM execution on laptops ([#32](https://github.com/BootlegYouki/L.A.R.A/issues/32))
