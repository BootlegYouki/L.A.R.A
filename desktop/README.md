# L.A.R.A Desktop Client Specification

## 1. Overview
The **L.A.R.A Desktop Client** is a lightweight, cross-platform desktop application built for student laptops, school computer laboratories (DepEd Computerization Program packages), and teacher desktop workstations. It provides an offline-first learning interface identical in capability to the mobile client, enhanced by desktop computing power for on-device Socratic AI execution.

---

## 2. Technical Stack & Target Platforms

* **Application Framework:** Tauri 2.x (Rust core + Web frontend).
* **Frontend Framework:** React 19, TypeScript 5.x, Vite 6.x.
* **Styling & Design System:** Tailwind CSS 4.x configured with Google Material Design 3 design tokens (surfaces, elevations, typography, dynamic primary tones).
* **Local Persistence:** Local SQLite database accessed via the official `@tauri-apps/plugin-sql` plugin.
* **Networking:**
  * HTTP REST: Tauri HTTP plugin / browser Fetch API.
  * WebSockets: Native WebSocket API connecting to Local Hub port 8081.
  * Network Discovery: Rust background thread for mDNS browsing and UDP broadcast listener on port 8888.
* **Media Engine:** HTML5 Video Player with hardware accelerated decoding and HTTP byte-range scrub streaming.
* **Local SLM Engine:** Embedded `llama.cpp` CLI binary / dynamic library executed via Tauri sidecar command for pluggable on-device GGUF inference (MiniCPM5-2B, Qwen2.5, Llama 3.2) on laptops with >= 4GB RAM.

* **Target Platforms:** Windows 10/11 (64-bit), Ubuntu/Debian Linux (DepEd lab PCs), and macOS. Binary size target: < 20MB installer.

---

## 3. Core Functional Capabilities

### 3.1 Network Discovery & Dual-Mode Connectivity
* Automatic discovery of the classroom Local Hub via mDNS and UDP subnet listener.
* Direct manual IP connection box on the setup screen.
* Seamless offline state: when disconnected from school Wi-Fi, students can review all previously downloaded modules, video lessons, and interact with the local AI tutor.

### 3.2 Student & Teacher Roles
* **Student Mode:**
  * Self-registration using Name, LRN, and PIN.
  * Enrollment into subjects via 6-character Class Code with pending approval status.
  * Announcements feed with comment moderation.
  * Module and handout reader with pre-extracted lesson text chunks.
  * Offline educational video streaming and local disk caching.
  * Homework submission via file upload (scanned images, documents).
  * Timed paperless quizzes with instant auto-grading and anti-cheat AI lockout.
* **Teacher Desktop Mode (Alternative to Hub GUI):**
  * When logged in with Teacher credentials, provides class creation, module uploads, paperless quiz authoring, homework grading, and live student roster monitoring.

### 3.3 Socratic AI Tutor on Laptops (L.A.R.A AI)
* **100% On-Device Mode (Laptops & Lab PCs):**
  * Utilizes laptop x86_64/ARM CPU (with AVX2 support) or integrated GPU to run `minicpm-2b-q4.gguf` locally via Tauri sidecar.
  * Operates at high speeds (10 to 25 tokens/s) with zero network traffic, enabling students to study at home without internet or classroom Wi-Fi.
* **Hub-Assisted Mode:**
  * Available as a toggle or fallback if running on low-spec netbooks (< 4GB RAM) or when the model file has not been downloaded to local disk.

---

## 4. Suggested Directory Structure

```
desktop/
├── src-tauri/
│   ├── Cargo.toml                    # Rust dependencies (tauri, tokio, mdns, sql)
│   ├── tauri.conf.json               # Window settings, permissions, sidecars
│   ├── build.rs
│   └── src/
│       ├── main.rs                   # Tauri application entrypoint
│       ├── discovery.rs              # Rust mDNS & UDP broadcast scanner
│       ├── sidecar_llama.rs          # llama.cpp process manager & IPC bridge
│       └── db.rs                     # Local SQLite migration scripts
├── src/
│   ├── main.tsx                      # React root
│   ├── App.tsx                       # Router & layout container
│   ├── index.css                     # Tailwind CSS & Material 3 variables
│   ├── components/                   # Reusable Material 3 cards, inputs, timers
│   ├── pages/
│   │   ├── DiscoveryPage.tsx         # Hub connection & manual IP input
│   │   ├── AuthPage.tsx              # LRN registration & class code join
│   │   ├── StreamPage.tsx            # Announcements feed
│   │   ├── ClassworkPage.tsx         # Handouts, video player, homework upload
│   │   ├── QuizPage.tsx              # Timed paperless quiz runner
│   │   └── TutorDrawer.tsx           # Contextual Socratic chat panel
│   ├── services/
│   │   ├── api.ts                    # REST endpoints (files, submissions)
│   │   ├── websocket.ts              # Realtime event bus
│   │   ├── sync.ts                   # Delta-sync engine
│   │   └── ai.ts                     # Routing between sidecar llama and Hub WS
│   ├── store/                        # Zustand or React Context for app state
│   └── types/                        # TypeScript interfaces for models & schemas
├── package.json
├── tsconfig.json
├── vite.config.ts
└── README.md
```

---

## 5. Build & Execution Guidelines

* **Prerequisites:** Node.js 20+, Rust toolchain (`cargo`), and system build essentials (`build-essential` on Linux, MSVC on Windows).
* **Development Mode:** `npm run tauri dev`
* **Production Build:** `npm run tauri build` (Produces `.msi`/`.exe` for Windows and `.deb`/`.AppImage` for Linux).
