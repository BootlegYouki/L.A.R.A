# L.A.R.A Mobile Client (`mobile/`)

> **Subsystem Scope:** Native Android application for **Pupils (Grades 1–6)** and **Teachers**.  
> **Repository Role:** Dual-role client operating 100% offline within the classroom local area network (LAN).

---

## 0. Developer Pre-Flight & Hard Invariants

**Every contributor and AI agent working in `mobile/` MUST adhere to these rules:**

1. **Strict Zero-Internet Policy:** Never import Firebase, Google Play APIs, external CDNs, Google Fonts web links, or remote telemetry. The application must operate with the phone's mobile data turned off and the router's WAN unplugged.
2. **Budget Hardware Reality (Heap < 250MB):** The primary target devices are Philippine budget smartphones (Infinix Smart 8, TECNO Spark, realme Note 50) with 3GB to 4GB physical RAM.
   * Total JVM heap memory must remain **strictly under 250MB** during Hub-assisted mode.
   * Devices with `< 6GB physical RAM` must **never** attempt to load GGUF weights into phone memory. They must stream tokens over WebSockets from the Local Hub.
3. **Database Stack Invariant:** Use **Android Room (SQLite)** with Kotlin Coroutines and StateFlow. **Prisma is strictly forbidden.**
4. **Canonical Network Contracts (`contracts/`):**
   * Check [`../contracts/openapi.yaml`](../contracts/openapi.yaml) and [`../contracts/events/`](../contracts/events/) before writing any API call or DTO.
   * All network JSON fields are strictly **`snake_case`**. Annotate Kotlin fields with `@SerialName("field_name")`.
   * **Anti-Cheat Redaction:** The student client must **never** contain fields or Room columns for `correct_answer` during active quizzes.
5. **UI & Design Authority:**
   * Visual wireframes and screen flows produced by the **Design Team** are the primary authority that must be implemented.
   * Implement screens using **Google Material Design 3 (`androidx.compose.material3`)**.
   * Touch targets must be **minimum 48dp (preferred 56dp)** for young elementary pupils.
   * Text contrast must meet **minimum 4.5:1**.
   * **Zero hardcoded strings:** All strings must be externalized in `mobile/app/src/main/res/values/strings.xml` and translated to Filipino in `values-tl/strings.xml`.
6. **Local Testing via Mock Hub:** Do not wait for the Server team. Start the standalone Local Hub simulator from the repo root:
   ```bash
   python3 ../scripts/mock_hub.py
   ```
7. **Pre-Push Linter:** Run the invariant checker before opening any PR:
   ```bash
   python3 ../scripts/verify_invariants.py
   ```
8. **Mandatory Documentation:** Every major feature PR must include updated architectural notes in [`mobile/docs/`](./docs/).
9. **Offline-First by Default (Home Study Mode):** The app must **never** show a blocking "No Connection" error screen on launch. When disconnected from the classroom server (e.g. at home), pupils must be able to view enrolled classes, read announcements, study lesson text chunks, and watch downloaded videos completely offline. Homework photos queue locally as `'QUEUED_FOR_SYNC'`. If the device has ≥6GB RAM and has downloaded a GGUF model, Socratic AI works 100% offline at home too; otherwise, AI features gracefully indicate they unlock upon reconnecting to the classroom Hub.


---

## 1. Technical Stack & Hardware Profile

* **Language & Runtime:** Kotlin 2.x, Java 17, Android SDK 26 to 35 (Android 8.0 Oreo to Android 15).
* **UI Framework:** Jetpack Compose with Google Material Design 3 (`androidx.compose.material3`).
* **Architecture Pattern:** Clean Architecture + MVI/MVVM with Kotlin Coroutines, StateFlow, and ViewModel.
* **Local Persistence:** Android Room Database (SQLite) with `@Transaction` atomic delta-sync execution.
* **Networking:** 
  * HTTP REST: OkHttp 4.x / Ktor Client.
  * WebSockets: OkHttp `WebSocketListener` connecting to Local Hub port 8081.
  * Discovery: Android Network Service Discovery (NSD) resolving `_lara._tcp.local` + UDP `DatagramSocket` listening on port 8888.
* **MulticastLock Requirement:** Must acquire `WifiManager.createMulticastLock("lara_discovery_lock")` to prevent Android OS power-saving from dropping UDP discovery packets on budget phones.
* **Hardware Camera:** CameraX (`androidx.camera`) with in-app rectangular framing overlay, downscaling to max 1080p, and compressing worksheets to JPEG < 800KB.
* **Media Playback:** Jetpack Media3 (ExoPlayer) supporting HTTP 206 Byte-Range streaming and offline local disk caching.
* **Pluggable Edge SLM:** `llama.cpp` JNI C++ bindings compiled for `arm64-v8a` executing candidate GGUF models (MiniCPM5-2B, Qwen2.5, Llama 3.2) only on phones with **≥ 6GB RAM**.

---

## 2. Core Functional Capabilities

### 2.1 Dual-Role Architecture (Student & Teacher)
The app switches navigation graphs depending on authenticated role:
* **Student NavGraph:** Bottom Navigation (`Stream`, `Classwork`, `Grades`).
* **Teacher NavGraph (`TeacherNavGraph`):**
  * **Join Request Approvals:** Bottom sheet showing student Name, LRN, and one-click Accept/Decline buttons.
  * **Stream Broadcasting:** FAB allowing teachers to post announcements to the class directly from their smartphone.
  * **Quiz Remote Controller:** Remote "Start Quiz" trigger button to broadcast synchronized countdowns across the classroom.
  * **Live Submission Telemetry:** Card showing real-time count of pupils currently answering and auto-graded score distributions.

### 2.2 Paperless Quiz Engine
* Timed full-screen view with visual countdown timer pill (Green >5m, Yellow ≤5m, Red pulsing ≤2m).
* **Absolute AI Tutor Lockout:** Floating "Ask L.A.R.A AI" button is completely unmounted from the UI during active test sessions.
* Auto-submit upon timer expiration (`00:00`).
* If Wi-Fi drops mid-quiz, timer continues locally on hardware clocks (`SystemClock.elapsedRealtime()`). Finished tests save as `'QUEUED_FOR_SYNC'` and auto-flush to the Hub upon reconnect.

### 2.3 Socratic AI Tutor (L.A.R.A AI)
* Draggable `ModalBottomSheet` inside the lesson module viewer.
* Bound strictly to teacher lesson text chunks. Never provides direct answers or homework solutions.
* Bilingual toggle: English and conversational Filipino/Taglish.
* **RAM Routing:**
  * Physical RAM < 6GB: Streams hints via WebSocket from Hub FIFO queue (`heap < 250MB`).
  * Physical RAM ≥ 6GB: Runs local GGUF model via `llama.cpp` JNI 100% offline.

---

## 3. Client Offline Database Schema (Android Room)

Mobile developers must model their Room Database (`@Database`) strictly on the canonical SQL DDL at [`contracts/schema/client_offline.sql`](../contracts/schema/client_offline.sql).

### The 12 Room Entities (`org.lara.app.data.local.entities.*`):
1. **`UserEntity` (`users`):** `id`, `lrn_or_id`, `full_name`, `role` (`TEACHER` | `STUDENT`), `pin_hash`, `created_at`, `updated_at`.
2. **`ClassroomEntity` (`classrooms`):** `id`, `name`, `section`, `class_code`, `teacher_id`, `created_at`, `updated_at`.
3. **`EnrollmentEntity` (`enrollments`):** `id`, `classroom_id`, `student_id`, `status` (`PENDING` | `ACTIVE` | `REJECTED`), `joined_at`, `updated_at`.
4. **`AnnouncementEntity` (`announcements`):** `id`, `classroom_id`, `title`, `content`, `allow_comments`, `created_at`, `updated_at`.
5. **`AnnouncementCommentEntity` (`announcement_comments`):** `id`, `announcement_id`, `author_id`, `content`, `created_at`, `updated_at`, `sync_status` (`SYNCED` | `QUEUED_FOR_SYNC`).
6. **`MaterialEntity` (`materials`):** `id`, `classroom_id`, `title`, `file_type`, `file_size_bytes`, `extracted_text`, `download_url`, `local_file_path` (cached disk path for home study), `created_at`, `updated_at`.
7. **`AssignmentEntity` (`assignments`):** `id`, `classroom_id`, `title`, `instructions`, `deped_category` (`WRITTEN_WORK` | `PERFORMANCE_TASK` | `QUARTERLY_ASSESSMENT`), `due_date`, `max_points`, `created_at`, `updated_at`.
8. **`AssignmentSubmissionEntity` (`assignment_submissions`):** `id`, `assignment_id`, `student_id`, `file_path`, `file_type`, `submitted_at`, `score`, `teacher_feedback`, `updated_at`, `sync_status` (`SYNCED` | `QUEUED_FOR_SYNC`).
9. **`QuizEntity` (`quizzes`):** `id`, `classroom_id`, `title`, `instructions`, `deped_category`, `time_limit_minutes`, `status` (`DRAFT` | `ACTIVE` | `CLOSED`), `started_at` (server synchronized epoch ms), `created_at`, `updated_at`.
10. **`QuizQuestionEntity` (`quiz_questions`):** `id`, `quiz_id`, `order_index`, `question_text`, `question_type`, `options_json`, `points`, `image_path`, `created_at`, `updated_at`. **Strictly omits `correct_answer`.**
11. **`QuizAttemptEntity` (`quiz_attempts`):** `id`, `quiz_id`, `student_id`, `started_at`, `submitted_at`, `score`, `total_points`, `answers_json`, `updated_at`, `sync_status` (`SYNCED` | `QUEUED_FOR_SYNC`).
12. **`AiChatMessageEntity` (`ai_chat_messages`):** `id`, `classroom_id`, `student_id`, `material_id`, `role` (`USER` | `TUTOR`), `content`, `created_at` (persists conversation during offline home study).

*All delta-sync batch operations in Room DAOs must be wrapped in `@Transaction`.*

---

## 4. Directory Structure

```
mobile/
├── app/
│   ├── build.gradle.kts
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml
│   │   │   ├── cpp/                     # llama.cpp JNI ARM64 C++ bindings
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   └── llama-jni.cpp
│   │   │   ├── java/org/lara/app/
│   │   │   │   ├── data/
│   │   │   │   │   ├── local/           # Room Database, DAOs, Entities
│   │   │   │   │   ├── remote/          # Ktor/OkHttp, WebSocket, Discovery, MulticastLock
│   │   │   │   │   └── sync/            # DeltaSyncWorker, HomeworkUploadWorker
│   │   │   │   ├── domain/              # Models, Repositories, UseCases
│   │   │   │   └── ui/
│   │   │   │       ├── navigation/      # StudentNavGraph, TeacherNavGraph
│   │   │   │       ├── theme/           # Material 3 Color, Type, Shape tokens
│   │   │   │       ├── components/      # 48dp Buttons, Cards, CountdownPill
│   │   │   │       └── screens/         # Discovery, Classwork, Quiz, Tutor, Camera
│   │   │   └── res/
│   │   │       ├── values/strings.xml   # Canonical English strings
│   │   │       └── values-tl/strings.xml# Filipino localization strings
└── docs/                               # Mandatory subsystem architectural documentation
```

---

## 5. Mobile Team Sprint Roadmap & Execution Order

All mobile issues on GitHub follow the `[MOBILE Sprint.Step]` naming convention:

* **Sprint 1 (Scaffolding & LAN Discovery):**
  * `[MOBILE 1.1]`: Scaffold Material 3 theme, design tokens & connection screens ([#23](https://github.com/BootlegYouki/L.A.R.A/issues/23))
  * `[MOBILE 1.2]`: Implement mDNS discovery & manual IP fallback screen in Jetpack Compose ([#4](https://github.com/BootlegYouki/L.A.R.A/issues/4))
* **Sprint 2 (Roles, Navigation & Sync):**
  * `[MOBILE 2.1]`: Build role-based classroom navigation shell, Class Code dialog & Teacher approval sheet ([#24](https://github.com/BootlegYouki/L.A.R.A/issues/24))
* **Sprint 3 (Media & Homework Camera):**
  * `[MOBILE 3.1]`: Build announcement cards, Media3 video player & CameraX document capture overlay ([#25](https://github.com/BootlegYouki/L.A.R.A/issues/25))
  * `[MOBILE 3.2]`: Implement CameraX homework photo capture with automatic JPEG compression (<800KB) ([#9](https://github.com/BootlegYouki/L.A.R.A/issues/9))
* **Sprint 4 (Paperless Quiz Engine):**
  * `[MOBILE 4.1]`: Build paperless quiz flow with animated countdown timer & Teacher Quiz remote controller ([#26](https://github.com/BootlegYouki/L.A.R.A/issues/26))
* **Sprint 5 (Socratic AI Tutor):**
  * `[MOBILE 5.1]`: Implement hardware RAM detection (<6GB vs >=6GB) and dual-mode inference router ([#15](https://github.com/BootlegYouki/L.A.R.A/issues/15))
  * `[MOBILE 5.2]`: Build Socratic AI chat bottom sheet & desktop drawer with bilingual language toggle ([#27](https://github.com/BootlegYouki/L.A.R.A/issues/27))
* **Sprint 6 (Audits & Benchmarks):**
  * `[MOBILE 6.1]`: Profile memory and battery consumption on 3GB/4GB Android devices (Transsion/realme) ([#18](https://github.com/BootlegYouki/L.A.R.A/issues/18))
  * `[MOBILE 6.2]`: Conduct elementary UX audit: >=48dp touch targets, contrast ratios, and loading skeletons ([#28](https://github.com/BootlegYouki/L.A.R.A/issues/28))
