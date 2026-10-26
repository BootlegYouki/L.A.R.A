# L.A.R.A: Agent Development Rules & Architectural Guidelines

Mandatory architectural invariants, technical constraints, coding standards and pedagogical guardrails for the **L.A.R.A** (Localized Augmented Resource & Assessment) offline classroom platform. All AI agents and human contributors must follow them. Nested `AGENTS.md` files in `server/`, `desktop/` and `mobile/` add team-specific detail; read the one for the folder you are working in.

---

## 0. What L.A.R.A Is (60-second context)

An **offline Google Classroom + paperless quizzes + Socratic AI tutor** for Philippine public schools (Grades 1 to 12, elementary to senior high). It runs entirely on the classroom's own Wi-Fi, with no internet at all. First deployment: the capstone defense plus one pilot class of about 40 learners.

**Three programs, one network:**
* **Hub (`server/`):** runs on a dedicated, always-on school PC wired to the router. Holds the master SQLite database, files and videos, the quiz broker and the AI queue. One Hub serves all teachers and is the single place that syncs. Its window is an admin console (accounts, port health, USB export and backup).
* **Mobile app (`mobile/`):** Android for learners (budget 3 to 4 GB phones) and teachers. A teacher can do on the phone everything they can do on desktop.
* **Desktop app (`desktop/`):** Tauri client for student laptops, lab PCs and teachers. Same features as mobile, laid out for a large screen.

**How a class runs:** the admin sets a PIN the first time the Hub starts and creates teacher accounts, a teacher creates a class and gets a 6-character code, learners self-register (LRN + 4-digit PIN), enter the code, and the teacher approves them. Everything syncs into each device's local SQLite so learners can study at home offline. Teachers post announcements, upload handouts and videos, group classwork under topics, assign homework (learners photograph their notebook, and can ask the teacher in a private comment), and run synchronized timed quizzes that auto-grade on the Hub and export to a DepEd class record on USB. The AI tutor never gives the final answer: it asks guiding questions grounded in the teacher's lesson text, in English or Filipino, and is locked while a quiz is active.

**Biggest known risk:** AI quality on weak hardware. No model is chosen yet; see section 5.

---

## 1. How To Work In This Repo

### 1.1 Source of truth (when two documents disagree, higher wins)
1. `contracts/` (OpenAPI, WebSocket event schemas, SQL schemas, `naming_rules.md`): the only shared surface between teams.
2. This file and `rules/*.md`.
3. `design-system/design-system.md` and `design-system/` (UI authority). For the remade desktop screens, `design-system/prototype.html` is the reference and wins over the text.
4. `docs/PRD.md` (product behavior and functional requirements).
5. Team `README.md`, `docs/TECH_SPEC.md` and GitHub issues.

If you find a conflict, do not pick silently: fix the lower document, or if the higher one looks wrong, raise it to the Lead Developer. Never redefine product behavior in a Tech Spec or issue.

### 1.2 Commands
| Task | Command |
|---|---|
| Run the Hub simulator (clients build against it) | `python3 scripts/mock_hub.py` |
| All contract, schema and mock hub tests | `python3 -m unittest discover tests` |
| Offline and localization guardrail | `python3 scripts/verify_invariants.py` |
| Mobile | `./gradlew test lint` (in `mobile/`) |
| Desktop | `npm run build` (`tsc && vite build`) (in `desktop/`) |
| Server | `cargo check && cargo test` (in `server/backend/`) |

Seed accounts for the mock hub (PIN `1234`): `T-0001` (teacher, class code `K7M4QX`), `123456789012` (enrolled learner), `123456789013` (join with the code), `ADMIN-0001` (the Hub admin, not a teacher).

### 1.3 Ownership
* One issue = one team = one PR. Edit only your team's folder.
* The three teams are the only parallel streams. Inside a team, both developers work the same issue together, in step order, one open PR at a time (`rules/team-workflow-and-prs.md` section 1.1).
* **Lead-owned (never edit in a feature PR):** `contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/`, this file. A change there is its own `contract-change` PR, merged before teams branch from it.
* Contract first: never add or change an endpoint, event or column in code before it exists in `contracts/`, the mock hub serves it, and the tests pass.

### 1.4 Decisions already made (do not re-litigate)
* Server backend is **Rust** (Axum, Tokio, SQLx). No Node backend.
* The Hub runs on a **dedicated always-on machine** for the pilot (same app, any Windows or Linux PC).
* **Accounts:** admin creates teachers; learners self-register, join by class code, teacher approves.
* **The product is Google Classroom, offline, plus quizzes and the Socratic tutor.** When a classroom behavior is not written down, Google Classroom's behavior is the default, limited to what the contract supports. Out of scope: Meet, Drive, Calendar, guardian emails, rubrics and grade categories.
* **Teachers have the same features on desktop and mobile** (decided 2026-10-11, replacing "mobile is the on-the-go set"): classes, posts, materials, assignments, grading, gradebook, and building, starting and monitoring quizzes.
* **One Hub admin account** (`ADMIN-0001`, role `ADMIN`), created on the Hub PC at first run with a PIN (`POST /api/admin/setup`). It is not a teacher and is never synced to a client.
* **Sync cursor is a sequence number,** never a timestamp (`rules/database-and-sync.md`).
* **Remote/Suspension Mode is the one sanctioned exception to LAN-only** (decided 2026-10-26): a Hub may opt into a Cloudflare Tunnel so its own accounts can reach it from outside the school network while classes are suspended (e.g. bad weather). Same accounts, same bearer tokens, same contract, same sync cursor — nothing about in-school behavior changes, and the tunnel is the only sanctioned path off the LAN (section 3.5).
* **Quizzes may be taken remotely**, but the live-synced countdown (`EVENT_QUIZ_START`, tick-by-tick) is a classroom-LAN feature. Over the tunnel, each client runs its own local countdown from `started_at` and the quiz's duration, and submits under the same `submitted_at - started_at <= limit + 60s` rule as in-school (`rules/quiz-and-anti-cheat.md`).
* Canonical values: touch targets 52dp on phones (56dp primary actions and quiz options) and 40px buttons on desktop (as in Google Classroom), quiz lockout `HTTP 403` / `QUIZ_IN_PROGRESS`, Phosphor icons, Nunito font, queued status `QUEUED_FOR_SYNC`, Android package `org.lara.app`, class codes are 6 uppercase characters without 0/O/1/I (shown `XXX-XXX`).
* **AI grounding:** if the lesson text does not cover the question, the tutor says it cannot help with that and points the learner back to the lesson.
* Model choice is open until the AI evaluation (section 5.4) produces data.

### 1.5 If you think the plan is wrong or could be better
Say so, in an issue, with evidence. Every developer and agent may propose a better design for any part of the project, the invariants included. Open a *Design Proposal* issue (`type:proposal`), keep building by the current plan until the Lead answers, and never change the plan inside a feature PR. Process: `rules/team-workflow-and-prs.md` section 1.2.

---

## 2. Team Organization

1. **Mobile (`mobile/`):** Kotlin 2.x, Jetpack Compose, Room, CameraX, Media3, optional JNI `llama.cpp`. Hardware target: 3 to 4 GB RAM phones (Infinix, TECNO, itel, realme); **heap under 250 MB**.
2. **Desktop (`desktop/`):** Tauri 2.x, React 19, TypeScript, Tailwind, `@tauri-apps/plugin-sql`, bundled `llama.cpp` sidecar on laptops with at least 4 GB RAM.
3. **Server (`server/`):** Tauri window plus Rust backend: mDNS, UDP beacon, SQLite, REST `:8080`, WebSocket `:8081`, video streaming, `llama-server` queue, gradebook export, backup.
4. **Lead Developer:** reviews every PR against `rules/team-workflow-and-prs.md` section 5, gatekeeps `staging` and `main`, enforces the invariants. Does not write feature code.

Rule documents (read the ones for your task):
* [`rules/developer-tooling-and-testing.md`](./rules/developer-tooling-and-testing.md)
* [`rules/database-and-sync.md`](./rules/database-and-sync.md)
* [`rules/quiz-and-anti-cheat.md`](./rules/quiz-and-anti-cheat.md)
* [`rules/networking-and-lan.md`](./rules/networking-and-lan.md)
* [`rules/socratic-ai-guardrails.md`](./rules/socratic-ai-guardrails.md)
* [`rules/ui-and-accessibility.md`](./rules/ui-and-accessibility.md)
* [`rules/team-workflow-and-prs.md`](./rules/team-workflow-and-prs.md)

---

## 3. Non-Negotiable System Invariants

### 3.1 Zero Internet Dependency (LAN only)
* Everything must work 100% locally on an isolated router or hotspot with no uplink. The one exception is **Remote/Suspension Mode** (section 3.5): a Hub may opt into a Cloudflare Tunnel so its own accounts can reach it while classes are suspended. This does not make the app internet-dependent — every feature still works with no uplink at all when the Hub is reachable only on the LAN, and the tunnel carries nothing but the Hub's own REST/WebSocket traffic.
* **Never add:** Firebase, Google Play Services, external CDNs, Google Fonts links, remote analytics, remote API keys, or any third party in the data path other than the Cloudflare Tunnel itself (which only relays encrypted traffic to the Hub; it is transport, not a backend, and never sees plaintext content).
* All assets (fonts, icons, installers, model weights, media) are bundled locally or served by the Hub. Fonts and icons come from `design-system/assets/`.

### 3.2 Offline-first persistence
* Both clients keep an offline mirror of enrolled classes, announcements, handouts, videos and quiz history in local SQLite.
* A learner can launch the app at home with no Wi-Fi and read, watch and (when capable) use the local AI without errors or blocking loaders.
* Offline actions (finished quizzes, homework photos, comments) are saved with `sync_status = 'QUEUED_FOR_SYNC'` and flush automatically when the Hub is reachable. Never describe the connection as "internet"; say "Hub" or "classroom network".

### 3.3 Network protocol and ports
* **HTTP `:8080`:** captive portal `/download`, REST, file transfer, video streaming strictly via HTTP Range (`206 Partial Content`), per-client cap **2.0 MB/s**, video uploads capped at 250 MB.
* **WebSocket `:8081`:** live quiz timers, presence, enrollment approvals, announcement pushes, Hub-assisted AI tokens. Handshake in `contracts/events/README.md`.
* **Discovery:** mDNS `_lara._tcp.local` on 8080, UDP JSON beacon every 3 s to `255.255.255.255:8888`. A manual IP entry fallback must always exist on the connection screen. mDNS and the UDP beacon are LAN-only and never attempted off the school network; the same fallback field also accepts a saved Hub remote URL (the Cloudflare Tunnel hostname) when the device is off the LAN (section 3.5).

### 3.4 Security and privacy (children's data)
* Every route except `/download`, register and login needs a bearer token. Authorise by role and by class ownership on the server, never only in the UI. This holds identically over the Remote/Suspension Mode tunnel — the tunnel changes transport, never authorization.
* **Never reach a client:** PIN hashes, other people's LRN, answer keys (`correct_answer`, synonyms), server file paths. Student serializers are separate types that cannot contain `correct_answer`.
* Never log PINs, tokens, LRNs or homework file contents. Never delete users, classrooms or graded rows; deactivate or archive.
* Learners' names, LRNs and homework photos are personal data of minors under the Data Privacy Act. They may transit the internet only through the Hub's own Remote/Suspension Mode tunnel (section 3.5); never add a feature that exports or transmits them anywhere else (analytics, third-party storage, another server).

### 3.5 Remote/Suspension Mode (the one exception to LAN-only)
* **Purpose:** while a class is suspended and school is closed, learners and teachers may still use the app from home: stream, materials, assignments, grades, homework submission, private comments, the AI tutor, and quizzes (section 1.4). On school grounds the app always uses local LAN/mDNS discovery; the tunnel is a fallback a client only tries when LAN discovery fails and the user has saved that Hub's remote address.
* **Transport:** the Hub PC runs `cloudflared` and exposes `:8080`/`:8081` through a Cloudflare Tunnel at a stable subdomain (e.g. `hub-<school>.lara.app`), TLS-terminated by Cloudflare. No other third party sits in the data path; Cloudflare relays encrypted traffic only and never becomes a backend or a store of record.
* **Accounts and auth are unchanged:** identical bearer tokens, roles and class-ownership checks as on the LAN (section 3.4). There is no separate remote-only account tier.
* **Quizzes:** a learner may start or resume a timed quiz remotely. The classroom-LAN feature of a live, tick-by-tick synced countdown (`EVENT_QUIZ_START` pushed to every client) does not apply over the tunnel; instead each remote client runs its own local countdown from the quiz's duration, anchored to the `started_at` it fetches from the Hub. The Hub remains the sole source of truth for timing: it still validates `submitted_at - started_at <= limit + 60s` exactly as in-school (`rules/quiz-and-anti-cheat.md`). Lockout and auto-grading rules are unchanged.
* **Offline-first (section 3.2) still applies:** when the tunnel or the Hub is unreachable, clients queue writes as `QUEUED_FOR_SYNC` exactly as on a dropped LAN connection, and never describe the connection as "internet"; say "Hub" or "classroom network" even when reached remotely.
* **Setup is opt-in per Hub**, never automatic: an admin enables the tunnel from the Hub dashboard. A Hub that never enables it behaves exactly as before this section existed.

---

## 4. UI/UX Guidelines (Accessibility for Grades 1 to 12)

* **Design authority:** [`design-system/design-system.md`](./design-system/design-system.md) and `design-system/` are the reference. The prototype (`design-system/prototype.html`) is the reference for the remade desktop screens (Home, Stream and the quiz): it was built and approved under the Lead's supervision. Where the text of the design system and the prototype disagree on one of those screens, the prototype wins and the text is fixed. Tokens, invariants and accessibility rules are fixed. Build the remade screens to match the prototype; where real development shows something must change, change it, keep the spirit and report the deviation in the PR so the Lead can update the design system (see the note at the top of `design-system.md`). Screens the prototype does not cover yet (including the phone layouts) are built from the section 9 anatomies and the matching Classroom Screens entry in `showcase.html`, using only the documented tokens and components. Layout is not free design (`rules/ui-and-accessibility.md` section 1).
* **Components:** Android: `androidx.compose.material3` themed with `design-system/mobile/*`. Desktop: Tailwind with `design-system/desktop/tailwind.theme.ts`. Phosphor icons and Nunito, bundled.
* **Tokens only:** no invented hex values, no gradients, purple only for the AI tutor.
* **Target sizes:** phones minimum 52dp, 56dp for primary actions and quiz options. Desktop buttons and icon buttons 40px, tabs and drawer items 48px, as in Google Classroom. Never rely on color alone: pair status color with an icon and text.
* **Bilingual:** all user-facing text in English and Filipino, no hardcoded strings (`values/strings.xml` + `values-tl/strings.xml`; a JSON dictionary on desktop with runtime toggle).
* **Every screen** needs an offline state, an empty state and a loading skeleton.
* **Camera:** CameraX with a document framing guide, compress to JPEG under 800 KB.
* **Gradebook export:** `.xlsx` and `.csv` of learners by assignment and quiz with points, per class, direct to USB. Like Google Classroom, L.A.R.A records points only; each teacher applies their own grading system.

---

## 5. Socratic AI Tutor (L.A.R.A AI)

A guide for Grades 1 to 12, never an answer engine. It is Socratic by design, so it behaves the same at every grade level: no grade setting, no per-grade prompt. Inference is pluggable GGUF via `llama.cpp` (JNI on Android, sidecar on Desktop, `llama-server` on the Hub). Full rules: `rules/socratic-ai-guardrails.md`.

### 5.1 Routing
* **Phones under 6 GB RAM:** always use the Hub over WebSocket. Never load a model locally.
* **Phones with 6 GB or more, and laptops with 4 GB or more:** may run a downloaded GGUF fully offline.
* The Hub runs a FIFO queue over 2 to 4 `llama-server` slots and pushes queue status (`"Pangalawa ka sa pila - est. 4s"`).
* **The Hub ships with the AI installed; using it is optional.** Nothing else may depend on it. Downloading the model to run on your own device is opt-in, never automatic. If the Hub's model file is missing or fails to load, the Hub answers `AI_NOT_AVAILABLE` and shows the problem on its dashboard, and clients show "AI is not available right now" while everything else keeps working.

### 5.2 Behavior (every model, every path)
0. **One prompt for every path:** `contracts/ai/socratic_system_prompt.txt`. The Hub, mobile and desktop load that file byte for byte and never write their own; changing it is a `contract-change` PR followed by a re-run of the evaluation.
1. **Never give the final answer.** Decline warmly: *"Hindi ko maibibigay ang mismong sagot, pero tutulungan kitang tuklasin ito! Balikan natin ang binasa mo. Ano ang unang hakbang?"*
2. **Strict grounding** in the teacher's lesson chunks (`material_chunks`). If the lesson does not cover it, say so and point back to the lesson. Never extrapolate.
3. **One small clue, then one leading question.**
4. **Reply in the learner's chosen language** (English or natural Filipino/Taglish).
5. **Quiz lockout:** while a learner has an `IN_PROGRESS` quiz attempt the AI is unavailable. Clients never compose the chat UI; the Hub rejects requests with `HTTP 403` / `EVENT_ERROR` code `QUIZ_IN_PROGRESS`.

### 5.3 Model choice is open
MiniCPM5-2B is a baseline candidate only. Pick the model from the evaluation below, not from assumption. There may be two choices: a smarter model on the Hub and a smaller one for devices that can run it. Each must pass the same evaluation; if the small one does not, devices use the Hub.

### 5.4 Evaluation requirement
Before the chat UI is built, run the AI feasibility spike (`rules/socratic-ai-guardrails.md` section 5): a fixed scored test set in English, Filipino and Taglish, run on the actual Hub machine. Do not claim "good enough" without those numbers.

---

## 6. Subsystem Conventions

### 6.1 Mobile
Kotlin 2.x, Compose Material 3, Clean Architecture with MVI/MVVM, Coroutines and StateFlow, Room, Media3, JNI for `arm64-v8a`. Teacher and Student nav graphs. Verify: `./gradlew test lint`.

### 6.2 Desktop
Tauri 2.x, React 19, TypeScript 5 with `strict: true` and no `any`, Tailwind 4 with design-system tokens, `@tauri-apps/plugin-sql`. Verify: `npm run build`.

### 6.3 Server
Rust, Axum, Tokio, SQLx with automatic ACID migrations (PRAGMAs on the connection, not in migrations), 2 MB/s per-stream rate limit. Verify: `cargo check` and `cargo test`.

### 6.4 Folder documentation (mandatory)
Every PR that adds a feature updates its team folder: `mobile/docs/`, `desktop/docs/` or `server/docs/` (purpose, key files, data flow, gotchas), or the team README.

---

## 7. Working Rules For Agents

* **Read before you write, in this order:** the issue (its `PRD`, `Contract` and `Design system` lines name what to open), the PRD requirement it cites, the contract routes and events, the rule file, the nested `AGENTS.md`, then your team's `docs/TECH_SPEC.md` for file names and patterns. Search for existing code before creating new code.
* **The issue is the scope.** Build its sub-tasks and acceptance criteria, in its Target Files, and nothing else. If the issue disagrees with a contract, rule or the PRD, the higher document wins: stop and report it.
* **Do not invent behavior.** If a requirement is missing or contradicts another document, write the open question in the PR or issue instead of guessing.
* **Smallest change that satisfies the issue.** No drive-by refactors, no new dependencies without need, no edits outside your folder.
* **Evidence before assertions.** Run the commands in 1.2 and attach output. Never say "done" without a passing build or test, and say plainly what you could not verify.
* **Graceful failure.** Broken sockets, timeouts and Wi-Fi drops never crash the app or corrupt SQLite. Writes that must be atomic use transactions.
* **Security by default.** Authorise on the server, strip secrets from responses and logs.
* **Comments only when they explain why:** non-obvious domain logic, a hardware workaround or a pedagogical constraint. No comments that restate the code.

### Definition of done (every PR)
- [ ] Linked issue (`Closes #n`), one team, one PR
- [ ] Contract, mock hub and tests updated first if the API changed
- [ ] Build, tests and `verify_invariants.py` pass, output attached
- [ ] Works with the Hub unreachable (offline state defined)
- [ ] English and Filipino strings, tokens only, targets at least 52dp on phones and 40px buttons on desktop
- [ ] No answer key, PIN data or foreign LRN reaches a client
- [ ] AI unavailable during a quiz
- [ ] Team docs updated

---

## 8. Glossary

| Term | Meaning |
|---|---|
| **Hub** | The server app on the school PC: database, files, WebSocket, AI queue |
| **Class Code** | 6-character code a learner enters to ask to join a class (stored `K7M4QX`, shown `K7M-4QX`) |
| **LRN** | Learner Reference Number, the 12-digit DepEd learner ID. Personal data |
| **QUEUED_FOR_SYNC** | Work created offline, waiting for the Hub |
| **Cursor / `seq`** | Strictly increasing sync position; never a clock time |
| **Socratic** | Guide with questions and one clue at a time instead of giving the answer |
| **Grounding** | Tying every tutor reply to a specific lesson chunk |
| **Mock hub** | `scripts/mock_hub.py`, a Python stand-in for the Hub that implements the whole contract |
| **Remote/Suspension Mode** | The one sanctioned exception to LAN-only: a Hub reachable from outside the school network through its own Cloudflare Tunnel, for use while classes are suspended (section 3.5) |
