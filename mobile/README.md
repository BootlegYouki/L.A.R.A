# L.A.R.A Mobile Client Specification

## 1. Overview
The **L.A.R.A Mobile Client** is a dual-role native Android application designed for both **Pupils (Grades 1 to 6)** and **Teachers**. It provides a full, offline-first classroom experience operating across a local area network (LAN) with zero internet dependency. 

For pupils, it features offline materials, video streaming, paperless assessments, camera homework capture, and an adaptive Socratic AI tutor. For teachers, it allows managing classes, one-click enrollee approvals on the go, starting paperless quizzes, and live score monitoring from a smartphone or tablet while moving around the classroom.

---

## 2. Technical Stack & Hardware Profile

* **Language & Runtime:** Kotlin 2.x, Java 17, Android SDK 26 to 35 (Android 8.0 to Android 15).
* **UI Framework:** Jetpack Compose with Google Material Design 3 (`androidx.compose.material3`).
* **Architecture Pattern:** Clean Architecture + MVI / MVVM with Kotlin Coroutines and StateFlow.
* **Local Persistence:** Android Room Database (SQLite) for offline-first caching of all classroom data.
* **Networking:** 
  * HTTP & WebSocket: OkHttp 4.x / Ktor Client.
  * Network Discovery: Android Network Service Discovery (NSD / mDNS) + DatagramSocket for UDP broadcast listening.
* **Hardware Camera:** CameraX (`androidx.camera`) for homework sheet photo capture and compression.
* **Media Playback:** Jetpack Media3 (ExoPlayer) with HTTP byte-range streaming and offline caching.
* **Local SLM Engine:** `llama.cpp` JNI C++ bindings compiled for ARM64-v8a.
* **Target Hardware Profile:** Entry-level Philippine Android smartphones (Transsion: Infinix, TECNO, itel; realme; Xiaomi) with 3GB to 4GB physical RAM. Strict app heap memory ceiling: < 250MB during Hub-assisted mode.

---

## 3. Core Functional Capabilities

### 3.1 Network Discovery & Connection
* Automatic detection of the classroom Local Hub via mDNS (`_lara._tcp.local`) and UDP broadcast packets (`port 8888`).
* Manual IP entry fallback screen displaying standard classroom network inputs (`e.g., 192.168.1.50:8080`).
* Automatic reconnection listener that resumes WebSocket and sync state upon detecting the classroom Wi-Fi.

### 3.2 Offline Identity & Class Enrollment
* Local profile setup: Full Name, Learner Reference Number (LRN), and 4-digit PIN.
* Enrollment via 6-character Class Code with visual status indicators (`PENDING_APPROVAL`, `ACTIVE`, `REJECTED`).
* Real-time WebSocket listener that immediately unlocks classroom modules when the teacher approves the request.

### 3.3 Offline Stream & Handouts
* Cached feed of teacher announcements with teacher-controlled commenting.
* Offline document reader supporting pre-extracted lesson text chunks, handouts, and worksheets.
* Educational video player supporting HTTP byte-range scrub streaming over Wi-Fi and permanent offline download.

### 3.4 Homework Camera Capture
* Integrated CameraX capture flow with document framing guides and automatic image compression (JPEG, target size < 800KB).
* Offline upload queue: Photos taken at home are stored locally and marked `QUEUED_FOR_SUBMISSION`. They automatically upload to the Hub when reconnecting to school Wi-Fi.

### 3.5 Paperless Quiz Engine
* Timed assessment screen with non-intrusive countdown timer (color shifts: Green -> Yellow at 5 mins -> Red at 2 mins).
* Supported items: Multiple Choice (with optional images), True or False, and Identification.
* Hard anti-cheat constraint: The Socratic AI tutor is completely removed and locked out during active quiz sessions.
* Auto-submission upon timer expiration (`00:00`) or manual completion.
* Disconnect resilience: Tests can be completed during unannounced Wi-Fi drops and auto-flush to the Hub upon reconnect.

### 3.6 Bilingual Socratic AI Tutor (L.A.R.A AI)
* Contextual trigger: "Magtanong kay L.A.R.A AI" / "Ask L.A.R.A AI" floating button inside the Material Viewer.
* Context binding: Injects the pre-extracted lesson text chunks into the Socratic prompt.
* Strict pedagogical behavior: Socratic guidance only; refuses to output direct solutions or homework answers.
* Adaptive Dual Execution:
  * If Physical RAM < 6GB: Streams hints from the Local Hub over WebSockets with real-time queue position display.
  * If Physical RAM >= 6GB and candidate model bundle exists: Executes active GGUF model (MiniCPM5-2B, Qwen2.5, Llama 3.2) 100% locally via `llama.cpp` JNI without network usage.


---


### 3.7 Teacher Mobile Capabilities (On-the-Go Classroom Control)
When logged in with a Teacher account, the mobile app dynamically switches to the Teacher UI:
* **Mobile Join Approvals:** Instant push notifications on the teacher's phone to Accept or Decline students entering Class Codes.
* **Stream Broadcasting:** Post announcements and notices directly from the phone.
* **Quiz Remote Controller:** Start and stop timed paperless quizzes with one tap while walking around the classroom.
* **Live Assessment Monitor:** Real-time dashboard showing which pupils are currently answering, who has submitted, and auto-graded score distributions.
* **Mobile Homework Review:** Review and score pupil camera photos directly from the phone.

---

## 4. Suggested Directory Structure

```
mobile/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/                          # Native C++/JNI bindings
│       │   │   ├── CMakeLists.txt
│       │   │   └── llama-jni.cpp             # llama.cpp Android wrapper
│       │   ├── java/org/lara/student/
│       │   │   ├── LaraApp.kt                # Application subclass & DI graph
│       │   │   ├── data/
│       │   │   │   ├── local/                # Room DB, DAOs, Entities
│       │   │   │   ├── remote/               # OkHttp, WebSockets, mDNS, UDP
│       │   │   │   └── sync/                 # Delta-sync worker & queue
│       │   │   ├── domain/                   # Business models & repositories
│       │   │   ├── ai/                       # Hardware checker, prompt builder, JNI bridge
│       │   │   └── ui/
│       │   │       ├── MainActivity.kt
│       │   │       ├── theme/                # Material 3 color schemes, typography, shapes
│       │   │       ├── components/           # Large tap target buttons, cards, timers
│       │   │       ├── screens/
│       │   │       │   ├── discovery/        # Server scanning & manual IP screen
│       │   │       │   ├── auth/             # LRN registration & class code join
│       │   │       │   ├── stream/           # Announcements feed
│       │   │       │   ├── classwork/        # Modules, video player, handouts
│       │   │       │   ├── homework/         # CameraX photo capture & submission
│       │   │       │   ├── quiz/             # Timed paperless quiz runner
│       │   │       │   └── tutor/            # Socratic chat bottom sheet
│       │   │       └── navigation/           # Compose Navigation graphs
│       │   └── res/
│       │       ├── values/                   # strings.xml (English)
│       │       └── values-tl/                # strings.xml (Filipino localization)
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 5. Build & Execution Guidelines

* **Prerequisites:** Android Studio Ladybug / Meerkat or CLI Android SDK with NDK 26+ installed.
* **Debug Build:** `./gradlew assembleDebug`
* **Release APK:** `./gradlew assembleRelease` (Generates `LARA-Student.apk` to be hosted on the Hub captive portal).
