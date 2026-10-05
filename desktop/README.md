# L.A.R.A Desktop Client (`desktop/`)

> **Subsystem Scope:** Cross-platform desktop application for **Student Laptops**, **School Computer Labs (DepEd PC Packages)**, and **Teacher Workstations**.  
> **Repository Role:** Dual-role client operating 100% offline within the classroom local area network (LAN).

---

## Start Here (New Developer Checklist)

| Step | What to do |
| :--- | :--- |
| 1 | Read the root [`AGENTS.md`](../AGENTS.md), then [`desktop/AGENTS.md`](./AGENTS.md) (this team's agent and developer guide). |
| 2 | Write [`docs/TECH_SPEC.md`](./docs/TECH_SPEC.md) from the template ([#37](https://github.com/BootlegYouki/L.A.R.A/issues/37)). The Lead approves it before Sprint 1 work merges. |
| 3 | Run the Hub simulator from the repo root: `python3 scripts/mock_hub.py`. Seed accounts (PIN `1234`): `T-0001` teacher (class code `K7M4QX`), `123456789012` pupil, `123456789013` pupil (join with the code), `ADMIN-0001`. |
| 4 | Take the next issue in **your slot**: [all desktop issues](https://github.com/BootlegYouki/L.A.R.A/issues?q=is%3Aissue%20is%3Aopen%20label%3A%22scope%3Adesktop%22) · [Dev A](https://github.com/BootlegYouki/L.A.R.A/issues?q=is%3Aissue%20is%3Aopen%20label%3A%22scope%3Adesktop%22%20label%3A%22slot%3Adev-a%22) · [Dev B](https://github.com/BootlegYouki/L.A.R.A/issues?q=is%3Aissue%20is%3Aopen%20label%3A%22scope%3Adesktop%22%20label%3A%22slot%3Adev-b%22). One issue = one PR. Or use the [sprint board](https://github.com/users/BootlegYouki/projects/2) (filter Team = Desktop). The sprint-by-sprint roadmap is in section 5. |
| 5 | Scaffold first: [#29](https://github.com/BootlegYouki/L.A.R.A/issues/29) (Tauri + React + SQLite + tokens). Copy `design-system/assets/` to `desktop/src/assets/design-system/` and import the font and icon CSS from `index.css`. |
| 6 | Before every PR: run the commands in [`desktop/AGENTS.md`](./AGENTS.md), `python3 scripts/verify_invariants.py` and `python3 -m unittest discover tests`; fill the PR template; update `desktop/docs/`. |

**Where things live:** API and events in [`contracts/`](../contracts/) (never edit in a feature PR), schema in [`contracts/schema/`](../contracts/schema/), UI rules in [`docs/design-system.md`](../docs/design-system.md), product behavior in [`docs/PRD.md`](../docs/PRD.md), all rules in [`rules/`](../rules/).

**Status:** No application code yet. Contracts, tokens, bundled fonts/icons and the mock hub are ready.

---

## 0. Developer Pre-Flight & Hard Invariants

**Every contributor and AI agent working in `desktop/` MUST adhere to these rules:**

1. **Strict Zero-Internet Policy:** Never import external CDNs, Google Fonts web links, or remote telemetry. All assets (Nunito, Phosphor Icons, fonts, dependencies) must be bundled locally into the binary.
2. **Why Tauri (Not Electron):** Tauri produces an ultra-lightweight ~15MB installer consuming ~40MB RAM (compared to Electron's ~150MB installer and 500MB RAM bloat), ensuring smooth performance on older school computer lab PCs (Intel Celeron / Core i3 with 4GB RAM).
3. **Database Stack Invariant:** Use native local SQLite via the official **`@tauri-apps/plugin-sql`** plugin. **Prisma is strictly forbidden** to prevent 50MB query engine binary bloat and packaging crashes.
4. **Canonical Network Contracts (`contracts/`):**
   * Consult [`../contracts/openapi.yaml`](../contracts/openapi.yaml) and [`../contracts/events/`](../contracts/events/) before writing any API call or interface.
   * All network JSON fields are strictly **`snake_case`**.
   * **Anti-Cheat Redaction:** The student view must never receive or parse `correct_answer` during active quizzes.
5. **UI & Design Authority:**
   * [`docs/design-system.md`](../docs/design-system.md) and `design-system/` are canonical. Layouts are yours to design if you use only the documented tokens and components and follow Google Classroom as the structural reference.
   * Configure Tailwind from `design-system/desktop/tailwind.theme.ts`; Nunito and Phosphor come from `design-system/assets/`. No invented colors, no gradients.
   * Touch and click targets must be minimum 52dp (40px compact only on teacher tables), contrast ratio ≥ 4.5:1.
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
* **Styling & Tokens:** Tailwind CSS 4.x configured with the L.A.R.A design tokens (`design-system/desktop/tailwind.theme.ts`): solid colors, Nunito scale, navy-tinted two-layer shadows.
* **State Management:** Zustand with offline persistence (`localStorage` / Tauri store).
* **Local Persistence:** Local SQLite database via `@tauri-apps/plugin-sql`.
* **Networking:**
  * HTTP REST: Tauri HTTP plugin / standard browser `fetch`.
  * WebSockets: Native browser `WebSocket` connecting to Local Hub port 8081.
  * Discovery: Rust background thread for mDNS browsing (`_lara._tcp.local`) and UDP subnet broadcast listening on port 8888.
* **Media Player:** HTML5 `<video>` element styled with design-system controls, supporting HTTP 206 Byte-Range streaming and offline local disk caching.
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
  * Class Code enrollment dialog (`K7M-4QX`) with real-time pending approval status.
  * Announcement stream with teacher comment moderation.
  * Lesson handouts with zoom controls.
  * Full-screen paperless quiz engine with synchronized timer and instant auto-grading.
  * Socratic AI drawer with split-screen view (lesson on left, tutor on right).
* **Teacher Mode (the full authoring surface; the Hub window is only an admin console):**
  * **Classroom, announcements, materials and assignments:** create a class and share its code, post to the stream (comments on or off), upload lessons and videos, create assignments, review and grade homework photos.
  * **Teacher Quiz Builder Wizard:** Multi-step wizard to create tests, manage question banks, and randomize question order.
  * **Live Quiz Submission Matrix:** Real-time telemetry grid showing which pupils are currently answering and their auto-graded scores.
  * **Class Roster Table:** List of enrolled pupils with one-click Accept/Decline actions.

### 2.3 On-Device Socratic AI Sidecar
* On student laptops and lab PCs with ≥ 4GB RAM, the desktop client executes candidate GGUF models locally via a spawned `llama.cpp` sidecar process.
* Operates at high speeds (10 to 25 tokens/s) with zero network traffic.
* If laptop RAM is < 4GB or model file is missing, automatically falls back to streaming tokens from the Local Hub over WebSockets.

---

## 3. Client Offline Database Schema (`@tauri-apps/plugin-sql`)

> **Source of truth:** [`contracts/schema/client_offline.sql`](../contracts/schema/client_offline.sql) (14 tables). Rules and protocol: [`rules/database-and-sync.md`](../rules/database-and-sync.md). Do not copy column lists into this README.

* Write migrations that reproduce `client_offline.sql` and enable `foreign_keys` when the connection opens.
* **Never store** a PIN hash, another person's LRN, `correct_answer`, or server file paths. The signed-in user's own LRN is allowed.
* **Client-only:** `sync_status` (`SYNCED` | `QUEUED_FOR_SYNC`), `materials.local_file_path`, and the `sync_state` key/value table (`hub_id`, `sync_epoch`, `cursor`, `current_user_id`).
* Apply a pull response and its `next_cursor` inside one `BEGIN`/`COMMIT`; on `reset: true`, wipe mirrored tables but keep `QUEUED_FOR_SYNC` rows.
* Use explicit TypeScript interfaces generated from or matching `contracts/openapi.yaml`; no `any`.

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

All desktop issues follow `[DESKTOP Sprint.Step]`. Each issue names the developer slot (Dev A or Dev B), its dependencies and the contract it implements. This list is generated from the GitHub milestones; the milestone is the live source.

* **Sprint 0 (Contract Freeze & Technical Spec):**
  * `[DESKTOP 0.1]`: Write desktop/docs/TECH_SPEC.md and get Lead approval ([#37](https://github.com/BootlegYouki/L.A.R.A/issues/37))
* **Sprint 1 (Scaffolding & LAN Discovery):**
  * `[DESKTOP 1.1]`: Setup Tauri 2.x + React 19 shell with local SQLite storage & design token foundations ([#29](https://github.com/BootlegYouki/L.A.R.A/issues/29))
  * `[DESKTOP 1.2]`: Implement mDNS/UDP discovery scanner in Rust/Tauri ([#5](https://github.com/BootlegYouki/L.A.R.A/issues/5))
  * `[DESKTOP 1.3]`: Build Hub connection screen: discovered hubs, manual IP entry and status banner ([#40](https://github.com/BootlegYouki/L.A.R.A/issues/40))
* **Sprint 2 (Roles, Classrooms & Delta-Sync):**
  * `[DESKTOP 2.1]`: Build login, role routing and classroom card grid ([#45](https://github.com/BootlegYouki/L.A.R.A/issues/45))
  * `[DESKTOP 2.2]`: Build Class Code join modal with live approval status ([#46](https://github.com/BootlegYouki/L.A.R.A/issues/46))
  * `[DESKTOP 2.3]`: Build teacher classroom creation and roster table with Accept / Decline ([#47](https://github.com/BootlegYouki/L.A.R.A/issues/47))
  * `[DESKTOP 2.4]`: Implement delta-sync engine with @tauri-apps/plugin-sql ([#48](https://github.com/BootlegYouki/L.A.R.A/issues/48))
* **Sprint 3 (Stream, Media & Homework):**
  * `[DESKTOP 3.1]`: Build HTML5 video lesson player with offline local disk caching ([#30](https://github.com/BootlegYouki/L.A.R.A/issues/30))
  * `[DESKTOP 3.2]`: Build Stream and Classwork pages with comments and PDF reader ([#55](https://github.com/BootlegYouki/L.A.R.A/issues/55))
  * `[DESKTOP 3.3]`: Build homework submission: file dropzone and queued upload ([#56](https://github.com/BootlegYouki/L.A.R.A/issues/56))
  * `[DESKTOP 3.4]`: Build teacher authoring: announcements, material upload and assignments ([#57](https://github.com/BootlegYouki/L.A.R.A/issues/57))
  * `[DESKTOP 3.5]`: Build full-screen homework review viewer with zoom, pan and grading ([#58](https://github.com/BootlegYouki/L.A.R.A/issues/58))
* **Sprint 4 (Paperless Quiz & Gradebook):**
  * `[DESKTOP 4.1]`: Build Teacher Quiz Builder wizard with question bank ([#31](https://github.com/BootlegYouki/L.A.R.A/issues/31))
  * `[DESKTOP 4.2]`: Build student timed quiz runner (full screen, countdown, auto-submit) ([#62](https://github.com/BootlegYouki/L.A.R.A/issues/62))
  * `[DESKTOP 4.3]`: Build teacher live quiz monitor and submission matrix ([#63](https://github.com/BootlegYouki/L.A.R.A/issues/63))
* **Sprint 5 (Socratic AI):**
  * `[DESKTOP 5.1]`: Embed llama.cpp sidecar for on-device candidate SLM execution on laptops ([#32](https://github.com/BootlegYouki/L.A.R.A/issues/32))
  * `[DESKTOP 5.2]`: Build Socratic chat drawer with lesson split view and bilingual toggle ([#67](https://github.com/BootlegYouki/L.A.R.A/issues/67))
  * `[DESKTOP 5.3]`: Implement desktop AI router: local sidecar vs Hub WebSocket fallback ([#68](https://github.com/BootlegYouki/L.A.R.A/issues/68))
* **Sprint 6 (Audit & Stress Test):**
  * `[DESKTOP 6.1]`: Conduct desktop UX audit: touch/click targets, contrast and loading skeletons ([#71](https://github.com/BootlegYouki/L.A.R.A/issues/71))
