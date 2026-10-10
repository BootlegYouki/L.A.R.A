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
7. **Developers inside a team.** There are no fixed developer roles or slots: any developer on the team can take any issue whose dependencies are merged. The unit of work is the PR. Two open PRs must not edit the same file; the issue's Target Files list is the ownership boundary. If two issues need the same file, the later one depends on the earlier one.
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
5. **Lead Review:** Wait for the Lead Developer's review against the checklist in section 5 before merging.

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

---

## 5. What The Lead Checks In Every PR

Every PR is checked against these. If one of the first five is present the PR is sent back; the next five are also blockers. Green CI is necessary but is not the review: the Lead also runs the change.

1. **Cloud Leakage (Zero Internet Invariant):**
   * Check for: Firebase, Google Play Services, external CDNs, Google Fonts URLs, remote analytics, third-party tracking, or external API endpoints.
   * Rule: All networking must operate exclusively over local LAN (ports 8080, 8081, 8888, or mDNS).
2. **RAM Bloat & Hardware Crashes (Budget Phone Invariant):**
   * Check for: Heavy heap allocations, uncompressed bitmaps in memory, loading large files into RAM, or initiating on-device SLM inference without checking `RAM >= 6GB`.
   * Rule: Target devices are 3GB/4GB RAM phones (Infinix, TECNO, realme). Mobile heap must remain < 250MB during Hub-assisted mode.
3. **Pedagogical AI Leaks (Socratic Invariant):**
   * Check for: Prompts or endpoints that provide direct answers, complete formulas, or write out homework solutions for students.
   * Rule: The AI tutor must guide step-by-step using teacher-provided lesson chunks without revealing answers.
4. **Assessment Integrity Bypass (Quiz Lockout Invariant):**
   * Check for: Any code path allowing the AI tutor to execute, receive WebSocket tokens, or remain visible in the UI during an active quiz session.
   * Rule: While the pupil has an `IN_PROGRESS` attempt the chat UI must never be composed (a disabled explanation placeholder is fine) and the backend must reject AI requests (`HTTP 403` / `EVENT_ERROR` `QUIZ_IN_PROGRESS`).
5. **Accessibility & Touch Degradation (Elementary Invariant):**
   * Check for: Clickable elements with touch targets < 52dp (56dp for primary actions and quiz options), hardcoded English strings in UI files without Filipino resource keys, or tiny, unreadable fonts.

---

### Additional blockers

6. **Secrets or Keys Reaching a Client:** `pin_hash`, another person's LRN, `correct_answer` or synonyms, or server `file_path` in a response, a client table, a DTO or a log line. Student serializers must be separate types that cannot hold `correct_answer`.
7. **Contract Drift:** an endpoint, event or column added or changed in code but not first in `contracts/`, or a `contracts/` change without the matching `scripts/mock_hub.py` and test updates. Check that `python3 -m unittest discover tests` output is attached.
8. **Sync Correctness:** a timestamp used as the sync cursor; a pull response applied without storing `next_cursor` in the same transaction; no handling of `reset: true`; a revision row not written in the same transaction as the change; `SYNCED` set without a server receipt.
9. **Scope Violation:** a PR that touches more than one team's folder, edits Lead-owned paths (`contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/`, `AGENTS.md`) inside a feature PR, or does not match the linked issue.
10. **Destructive Data Handling:** deleting users, classrooms or graded rows instead of deactivating or archiving.

### Also checked (usually a comment, not a blocker)
* Design tokens only (no invented hex, gradients, non-Phosphor icons, non-Nunito fonts); purple used only for the AI tutor.
* Offline state, empty state and loading skeleton exist for every new screen; English and Filipino strings present.
* Server authorisation by role and class ownership, not only in the UI.
* Evidence is real: build or test output, a log or screenshot against the mock hub, and a plain statement of anything not verified.
* Docs updated in the team folder (and `TECH_SPEC.md` if the design changed).
