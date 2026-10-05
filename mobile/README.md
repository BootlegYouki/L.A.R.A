# L.A.R.A Mobile Client (`mobile/`)

> **Subsystem Scope:** Native Android application for **Pupils (Grades 1–6)** and **Teachers**.  
> **Repository Role:** Dual-role client operating 100% offline within the classroom local area network (LAN).

---

## Start Here (New Developer Checklist)

| Step | What to do |
| :--- | :--- |
| 1 | Read the root [`AGENTS.md`](../AGENTS.md), then [`mobile/AGENTS.md`](./AGENTS.md) (this team's agent and developer guide). |
| 2 | Write [`docs/TECH_SPEC.md`](./docs/TECH_SPEC.md) from the template ([#38](https://github.com/BootlegYouki/L.A.R.A/issues/38)). The Lead approves it before Sprint 1 work merges. |
| 3 | Run the Hub simulator from the repo root: `python3 scripts/mock_hub.py`. Seed accounts (PIN `1234`): `T-0001` teacher (class code `K7M4QX`), `123456789012` pupil, `123456789013` pupil (join with the code), `ADMIN-0001`. |
| 4 | Open your sprint milestone and take the next issue in **your slot** (Dev A or Dev B). One issue = one PR. The roadmap is in section 5. |
| 5 | Scaffold first: [#23](https://github.com/BootlegYouki/L.A.R.A/issues/23). Copy `design-system/mobile/*.kt` to `app/src/main/java/org/lara/app/ui/theme/` and `design-system/mobile/res/font/*.ttf` to `app/src/main/res/font/`. Package is `org.lara.app`. |
| 6 | Before every PR: run the commands in [`mobile/AGENTS.md`](./AGENTS.md), `python3 scripts/verify_invariants.py` and `python3 -m unittest discover tests`; fill the PR template; update `mobile/docs/`. |

**Where things live:** API and events in [`contracts/`](../contracts/) (never edit in a feature PR), schema in [`contracts/schema/`](../contracts/schema/), UI rules in [`docs/design-system.md`](../docs/design-system.md), product behavior in [`docs/PRD.md`](../docs/PRD.md), all rules in [`rules/`](../rules/).

**Status:** No application code yet. Contracts, theme files, bundled Nunito and the mock hub are ready.

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
   * [`docs/design-system.md`](../docs/design-system.md) and `design-system/` are canonical. Layouts are yours to design if you use only the documented tokens and components and follow Google Classroom as the structural reference.
   * Implement screens with `androidx.compose.material3` components themed through `LaraTheme` (`design-system/mobile/`). No `Color(0xFF...)` in screens.
   * Touch targets must be **minimum 52dp (preferred 56dp)** for young elementary pupils.
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
* **UI Framework:** Jetpack Compose with `androidx.compose.material3` components themed by the L.A.R.A design tokens.
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
* **Student NavGraph:** Bottom Navigation (68 high) with four destinations: `Stream`, `Classwork`, `Quizzes`, `AI Tutor` (purple; shown as a disabled placeholder with an explanation during a quiz).
* **Teacher NavGraph (`TeacherNavGraph`):**
  * **Join Request Approvals:** Bottom sheet showing student Name, LRN, and one-click Accept/Decline buttons.
  * **Stream Broadcasting:** FAB allowing teachers to post announcements to the class directly from their smartphone.
  * **Quiz Remote Controller:** Remote "Start Quiz" trigger button to broadcast synchronized countdowns across the classroom.
  * **Live Submission Telemetry:** Card showing real-time count of pupils currently answering and auto-graded score distributions.

### 2.2 Paperless Quiz Engine
* Timed full-screen view with visual countdown timer pill (Green >5m, Yellow ≤5m, Red pulsing ≤2m).
* **Absolute AI Tutor Lockout:** The AI chat UI is never composed while a quiz attempt is `IN_PROGRESS` (the AI tab may show only a disabled explanation).
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

> **Source of truth:** [`contracts/schema/client_offline.sql`](../contracts/schema/client_offline.sql) (14 tables). Rules and protocol: [`rules/database-and-sync.md`](../rules/database-and-sync.md). Do not copy column lists into this README.

* One Room entity per table, package `org.lara.app.data.local.entities.*`, same column names and nullability. Add a schema test that compares Room's exported schema with the SQL file.
* **Never store** a PIN hash, another person's LRN, `correct_answer`, or server file paths. The signed-in user's own LRN is allowed.
* **Client-only:** `sync_status` (`SYNCED` | `QUEUED_FOR_SYNC`), `materials.local_file_path`, and the `sync_state` key/value table (`hub_id`, `sync_epoch`, `cursor`, `current_user_id`).
* Room cannot express CHECKs or partial indexes: enforce them in the repository layer.
* Apply a pull response and its `next_cursor` in **one** `@Transaction`; on `reset: true`, wipe mirrored tables but keep `QUEUED_FOR_SYNC` rows.
* Stay under the **250 MB heap** ceiling: page large lists, never load whole files or full-size bitmaps.

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
│   │   │   │       ├── components/      # 52dp Buttons, Cards, CountdownPill
│   │   │   │       └── screens/         # Discovery, Classwork, Quiz, Tutor, Camera
│   │   │   └── res/
│   │   │       ├── values/strings.xml   # Canonical English strings
│   │   │       └── values-tl/strings.xml# Filipino localization strings
└── docs/                               # Mandatory subsystem architectural documentation
```

---

## 5. Mobile Team Sprint Roadmap & Execution Order

All mobile issues follow `[MOBILE Sprint.Step]`. Each issue names the developer slot (Dev A or Dev B), its dependencies and the contract it implements. This list is generated from the GitHub milestones; the milestone is the live source.

* **Sprint 0 (Contract Freeze & Technical Spec):**
  * `[MOBILE 0.1]`: Write mobile/docs/TECH_SPEC.md and get Lead approval ([#38](https://github.com/BootlegYouki/L.A.R.A/issues/38))
* **Sprint 1 (Scaffolding & LAN Discovery):**
  * `[MOBILE 1.1]`: Scaffold Android project shell, custom design tokens foundation & navigation ([#23](https://github.com/BootlegYouki/L.A.R.A/issues/23))
  * `[MOBILE 1.2]`: Implement mDNS discovery & manual IP fallback screen in Jetpack Compose ([#4](https://github.com/BootlegYouki/L.A.R.A/issues/4))
  * `[MOBILE 1.3]`: Create Room database, DAOs and repository layer from client_offline.sql ([#41](https://github.com/BootlegYouki/L.A.R.A/issues/41))
* **Sprint 2 (Roles, Classrooms & Delta-Sync):**
  * `[MOBILE 2.1]`: Build role-based navigation shell (Student and Teacher graphs) ([#24](https://github.com/BootlegYouki/L.A.R.A/issues/24))
  * `[MOBILE 2.2]`: Build Student/Teacher login and registration screens ([#49](https://github.com/BootlegYouki/L.A.R.A/issues/49))
  * `[MOBILE 2.3]`: Build Class Code join dialog with live approval status ([#50](https://github.com/BootlegYouki/L.A.R.A/issues/50))
  * `[MOBILE 2.4]`: Build Teacher pending-approval bottom sheet ([#51](https://github.com/BootlegYouki/L.A.R.A/issues/51))
  * `[MOBILE 2.5]`: Implement DeltaSyncWorker (pull, push, tombstones) with WorkManager ([#52](https://github.com/BootlegYouki/L.A.R.A/issues/52))
* **Sprint 3 (Stream, Media & Homework):**
  * `[MOBILE 3.1]`: Build announcement stream cards and PDF reader ([#25](https://github.com/BootlegYouki/L.A.R.A/issues/25))
  * `[MOBILE 3.2]`: Implement CameraX homework photo capture with automatic JPEG compression (<800KB) ([#9](https://github.com/BootlegYouki/L.A.R.A/issues/9))
  * `[MOBILE 3.3]`: Build Media3 video player with Save for Home ([#59](https://github.com/BootlegYouki/L.A.R.A/issues/59))
  * `[MOBILE 3.4]`: Build teacher Post Announcement FAB and dialog ([#60](https://github.com/BootlegYouki/L.A.R.A/issues/60))
* **Sprint 4 (Paperless Quiz & Gradebook):**
  * `[MOBILE 4.1]`: Build paperless student quiz flow with countdown timer and AI unmount ([#26](https://github.com/BootlegYouki/L.A.R.A/issues/26))
  * `[MOBILE 4.2]`: Build Teacher Quiz remote controller and live submission monitor ([#64](https://github.com/BootlegYouki/L.A.R.A/issues/64))
  * `[MOBILE 4.3]`: Implement quiz offline queue and auto-flush ([#65](https://github.com/BootlegYouki/L.A.R.A/issues/65))
* **Sprint 5 (Socratic AI):**
  * `[MOBILE 5.1]`: Implement hardware RAM detection (<6GB vs >=6GB) and dual-mode inference router ([#15](https://github.com/BootlegYouki/L.A.R.A/issues/15))
  * `[MOBILE 5.2]`: Build Socratic AI chat bottom sheet with bilingual toggle ([#27](https://github.com/BootlegYouki/L.A.R.A/issues/27))
  * `[MOBILE 5.3]`: Implement SocraticPromptBuilder and client-side quiz lockout ([#69](https://github.com/BootlegYouki/L.A.R.A/issues/69))
  * `[MOBILE 5.4]`: Implement llama.cpp JNI bridge for arm64-v8a ([#70](https://github.com/BootlegYouki/L.A.R.A/issues/70))
* **Sprint 6 (Audit & Stress Test):**
  * `[MOBILE 6.1]`: Profile memory and battery consumption on 3GB/4GB Android devices (Transsion/realme) ([#18](https://github.com/BootlegYouki/L.A.R.A/issues/18))
  * `[MOBILE 6.2]`: Conduct elementary UX audit: touch targets, contrast and loading skeletons ([#28](https://github.com/BootlegYouki/L.A.R.A/issues/28))
