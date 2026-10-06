# Team Workflow & Pull Request Governance

This rule document governs team organization, git branching, PR submission requirements, and code review criteria for L.A.R.A.

All AI agents and contributors must follow these rules.

---

## 1. The Three Decoupled Project Teams

The engineering group operates across three independent streams overseen by the Lead Developer:

1. **Mobile Project Team (`mobile/`):** Android Native (Kotlin 2.x, Jetpack Compose M3, Room DB, CameraX, JNI `llama.cpp`).
2. **Desktop Project Team (`desktop/`):** Tauri Desktop Client (Tauri 2.x, React 19, TypeScript, Tailwind M3, bundled `llama.cpp`).
3. **Server Project Team (`server/`):** Local Hub Host (mDNS, UDP `:8888`, SQLite, WebSockets `:8081`, video streaming, `llama-server` queue).
4. **Lead Developer:** Reviews and gatekeeps all pull requests before merging into `staging`.

---

## 1.1 Parallel Work Protocol (How Three Teams Avoid Blocking Each Other)

1. **One issue, one team, one PR.** Issue titles follow `[SERVER|DESKTOP|MOBILE|QA|LEAD sprint.step]`. Features that need all three teams are split into one issue per team that share the same `Contract:` line.
2. **Contract first.** The shared surface is `contracts/` only. A contract change goes in its own small PR (label `contract-change`), reviewed by the Lead, and merged before any team branches from it. Nobody edits `contracts/` inside a feature PR.
3. **Clients build against the mock hub.** `python3 scripts/mock_hub.py` implements every route and WebSocket event in `contracts/`. Mobile and Desktop never wait for the Rust server. A route is "real" once its server issue merges; until then the mock is the truth.
4. **Mock hub parity is enforced.** `tests/test_contract_coverage.py` fails CI when a contract route or event has no mock implementation.
5. **File ownership.** A team edits only its own folder. `contracts/`, `design-system/`, `rules/`, `scripts/`, `tests/` and `.github/` are Lead-owned (see `.github/CODEOWNERS`).
6. **Integration day.** The last working day of each sprint, clients switch from the mock hub to the real Hub on `staging`. Failures become bug issues labelled with the owning team. The sprint is done only when the Definition of Done runs end to end against the real Hub.
7. **Dev A / Dev B inside a team.** Issues name a slot. Two developers in one team must not edit the same file in parallel; the issue's Target Files list is the ownership boundary.
8. **Technical Spec before code.** Each team writes `<team>/docs/TECH_SPEC.md` from `docs/templates/TECH_SPEC_TEMPLATE.md` and the Lead approves it before Sprint 1 work starts. The spec cites `contracts/` and never redefines product behavior; `docs/PRD.md` is the single product source.

---

## 2. Protected Branches & GitFlow

* **`main` (Protected):** Defense-ready production branch. Merges happen only from `staging` via Pull Request upon sprint completion. Direct push and force push are disabled.
* **`staging` (Protected):** Shared integration branch. All developers branch off `staging` and open PRs targeting `staging`. Direct push is disabled.
* **Feature Branches (`feat/*`, `fix/*`, `docs/*`, `test/*`):** Created from `staging`. Must focus strictly on a single issue.

---

## 3. Pull Request Requirements

Before opening a PR targeting `staging`:
1. **Link Issue:** Must contain `Closes #X` in description.
2. **PR Template:** Complete all sections in `.github/PULL_REQUEST_TEMPLATE.md`. CI (contracts, guardrails and the team job) must be green.
3. **Subsystem Documentation Updated:** Must include documentation of changes within the assigned subsystem folder (`mobile/docs/`, `desktop/docs/`, or `server/docs/`).
4. **Attach Verification Evidence:** Attach a log snippet, terminal output, or screenshot proving your code works on local LAN with zero internet.
5. **Lead Review:** Wait for the Lead Developer's audit using the `lara-co-lead` PR review protocol before merging.

---

## 4. Subsystem Documentation Invariant (Mandatory Folder-Level Context)

To ensure the Lead Developer and teammates always have immediate architectural context without digging through commit histories, **all developers must maintain documentation inside their assigned folder**:

* **Mobile Developers (`mobile/`):** Maintain module documentation and architecture notes in `mobile/docs/` (e.g. `navigation.md`, `room_schema.md`, `camerax.md`, `budget_device_benchmarks.md`) or update `mobile/README.md`.
* **Desktop Developers (`desktop/`):** Maintain component and service notes in `desktop/docs/` (e.g. `components.md`, `database.md`, `video_player.md`, `sidecar_llama.md`) or update `desktop/README.md`.
* **Server Developers (`server/`):** Maintain endpoint, WebSocket, and background worker notes in `server/docs/` (e.g. `discovery_mdns_udp.md`, `database_sync.md`, `streaming_rate_limit.md`, `slm_inference_queue.md`) or update `server/README.md`.

### What Every Subsystem Document Must Include:
1. **Feature / Component Purpose:** What problem does this screen, route, or module solve?
2. **Key Files & Entry Points:** What files should the reviewer or future teammates look at first?
3. **Data Flow & Local State:** How is data persisted locally (Room / SQLite) and synced with the network?
4. **Gotchas & Edge Cases:** Hardware quirks (Transsion background battery killer, camera orientation, USB drive mounting) or failure modes.

**PR Requirement:** If a PR introduces a new feature or refactors an existing subsystem, the PR MUST include updated documentation in that folder. A PR without folder-level documentation will be requested for changes.

---

## 5. The Five Fatal Rejection Rules


The Lead Developer will immediately reject any PR that introduces:
1. **Cloud Leakage:** External CDNs, Firebase, Google Fonts links, remote analytics, or Google Play Billing.
2. **Hardware RAM Crashes:** Mobile heap allocations exceeding 250MB or loading on-device LLM models without verifying `RAM >= 6GB`.
3. **Socratic AI Leaks:** Prompts or logic that provide direct answers to students.
4. **Quiz Lockout Bypass:** Any pathway allowing the AI tutor to run during an active quiz session.
5. **Accessibility Regressions:** Touch targets smaller than 52dp or missing Filipino string resources.
