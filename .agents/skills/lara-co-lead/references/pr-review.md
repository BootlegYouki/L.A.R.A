# PR Review Protocol

Loaded by the `lara-co-lead` skill for every PR in the Review Queue. The co-lead audits, writes the test steps and drafts the comments; the Lead tests, decides and posts. Green CI never replaces a human test of anything user-visible.

---

## 1. The Five Non-Negotiable Audit Checks (The "Deadly Sins")

Whenever reviewing any pull request, branch, or code snippet, evaluate these five fatal flaws first. **If any are present, the verdict MUST be `REQUEST CHANGES`:**

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

### Additional Blockers (also `REQUEST CHANGES`)

6. **Secrets or Keys Reaching a Client:** `pin_hash`, another person's LRN, `correct_answer` or synonyms, or server `file_path` in a response, a client table, a DTO or a log line. Student serializers must be separate types that cannot hold `correct_answer`.
7. **Contract Drift:** an endpoint, event or column added or changed in code but not first in `contracts/`, or a `contracts/` change without the matching `scripts/mock_hub.py` and test updates. Check that `python3 -m unittest discover tests` output is attached.
8. **Sync Correctness:** a timestamp used as the sync cursor; a pull response applied without storing `next_cursor` in the same transaction; no handling of `reset: true`; a revision row not written in the same transaction as the change; `SYNCED` set without a server receipt.
9. **Scope Violation:** a PR that touches more than one team's folder, edits Lead-owned paths (`contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/`, `AGENTS.md`) inside a feature PR, or does not match the linked issue.
10. **Destructive Data Handling:** deleting users, classrooms or graded rows instead of deactivating or archiving.

### Also Check (usually `NEEDS DISCUSSION` or a nit)
* Design tokens only (no invented hex, gradients, non-Phosphor icons, non-Nunito fonts); purple used only for the AI tutor.
* Offline state, empty state and loading skeleton exist for every new screen; English and Filipino strings present.
* Server authorisation by role and class ownership, not only in the UI.
* Evidence is real: build or test output, a log or screenshot against the mock hub, and a plain statement of anything not verified.
* Docs updated in the team folder (and `TECH_SPEC.md` if the design changed).

---

## 2. Pull Request & Code Audit Workflow

When the Lead Developer shares a PR, commit hash, diff, or branch to review:

### Step 1: Scan the Diff & Scope
* Read every modified file using `git diff` or inspecting files directly.
* Ensure changes match the assigned GitHub Issue and do not touch unrelated modules.

### Step 2: Code Quality & Gotchas Audit
* **Memory & Lifecycles:** Unclosed SQLite cursors/statements, dangling WebSocket listeners, Coroutine/Task leaks, or retained Activity contexts.
* **Network Fault Tolerance:** Does the code handle unexpected Wi-Fi disconnects gracefully without crashing or wiping local state?
* **Transaction Safety:** Are delta-sync operations wrapped inside atomic SQLite transactions?
* **Type Safety & Clean Code:** Look for `any` types in TypeScript, unhandled `null` or force unwrap (`!!`) in Kotlin, and unwrap (`.unwrap()`) in Rust production paths.

### Step 3: Produce the Structured Review Report
Always structure review findings using this exact format:

```markdown
### PR Audit Report: [PR Title / Branch Name]

**Verdict:** [TEST FIRST / CHANGES NEEDED / READY / NEEDS DISCUSSION]
**Risk Level:** [LOW / MEDIUM / HIGH / CRITICAL]

#### Executive Summary
[2-3 sentences explaining what this code actually changes and whether it works safely offline.]

#### Critical Blockers (Must Fix Before Merge)
- **[File & Line Number]:** [Exact technical flaw and why it breaks L.A.R.A invariants.]
  * *Suggested Fix:* [Concrete code snippet or exact architectural instruction.]

#### Code Quality & Improvements (Optional / Nitpicks)
- **[File & Line Number]:** [Non-blocking suggestion for cleaner code or performance.]

#### Verified and Not Verified
- Verified: [commands run with their result, CI checks seen, diff lines read.]
- Not verified: [anything I could not run or see, for example a real phone, the Wi-Fi, load.]

#### Test Before You Approve (for the Lead)
[Numbered steps from section 4 of this file, or "No app test needed: docs/CI only. I verified it by: ...".]

#### Ready-to-Paste GitHub Review Comment
> [A concise, professional, constructive comment formatted for GitHub PR review that the Lead Developer can paste directly.]
```

**Verdict meanings.** `TEST FIRST`: no blockers found, but the Lead must run the test steps before approving. `CHANGES NEEDED`: at least one blocker; do not test yet, the comment is ready to post. `READY`: only after the Lead reports the test steps passed, or for a PR with nothing user-visible that I verified myself. `NEEDS DISCUSSION`: a product or contract question the Lead must answer first.

---

## 3. The Merge Brief (what the Lead reads)

Keep it to one screen: one sentence on what the PR does, the verdict, what I verified, what I could not, the test steps, and the one thing that worries me most. Lead with the verdict.

---

## 3. Teammate Guidance Workflow

When a team member is stuck, confused, or asking how to implement a complex feature:

1. **Provide Clear Architectural Blueprints:** Give them exact interfaces, data schemas, or function signatures rather than writing all their feature code for them.
2. **Highlight Gotchas Early:** Tell them what to avoid (e.g., *"Make sure you don't instantiate the database on the main thread,"* or *"Remember to use HTTP 206 for video chunks"*).
3. **Define Acceptance Criteria:** Give them the exact test steps they must pass before they open a PR.

---

## 4. Test Plans For The Lead, And What CI Proves

The Lead cannot read code but can see and operate the product. Give the plan for the kind of PR, with real values filled in. Seed accounts (PIN `1234`): teacher `T-0001`, class code `K7M-4QX`; enrolled pupil `123456789012`; pupil who joins with the code `123456789013`; admin `ADMIN-0001`.

| PR touches | Steps for the Lead |
|---|---|
| **Mobile** | 1. On the PC: `python3 scripts/mock_hub.py` and leave it running. 2. Phone on the same Wi-Fi, USB debugging on; in `mobile/` run `./gradlew installDebug`. 3. Open the app, connect (auto-discovery or type the PC's IP). 4. Walk the path the issue describes with the seed accounts. 5. Turn on airplane mode and reopen: the screen must still show cached content with no error dialog. 6. Switch the language to Filipino: no English left on the new screen. 7. Report pass or fail, screenshot on fail. |
| **Desktop** | 1. `python3 scripts/mock_hub.py`. 2. In `desktop/`: `npm install` then `npm run tauri dev`. 3. Connect to the mock hub, walk the issue's path with the seed accounts. 4. Stop the mock hub: the app must show an offline banner, never a blocking error. 5. Toggle English and Filipino. 6. Report pass or fail. |
| **Server (real Hub)** | 1. In `server/backend/`: `cargo run`. 2. From the PC, open `http://<its-ip>:8080/download`. 3. Point the mobile or desktop client at the real Hub instead of the mock and repeat the same path. 4. Any behavior that differs from the mock hub is a bug; report it with the step number. |
| **Quiz or AI** | Also: start a quiz, and while it runs confirm the AI button is gone from the screen; ask the tutor for "the answer to number 3" and confirm it declines and points back to the lesson. |
| **Docs, rules, CI, scripts** | No app test. I verify by running the repo commands, and for CI changes by a planted failure (a throwaway draft PR that must fail), then I tell the Lead exactly what I ran. |

### What a green CI does and does not prove
| Check | Green means | Does not mean |
|---|---|---|
| Contracts & Mock Hub Validation | Contracts are valid, the mock hub serves every contract route and event, and its rule tests pass (no answer key to pupils, quiz lockout, sync cursor) | The real Hub behaves the same |
| Offline Invariant & Localization Check | No Firebase, Play Services, analytics, CDN or Google Fonts in added lines (code, build files, CSS, XML, JSON), and Filipino strings match English; it fails if it cannot diff | Anything outside those file types, or a dependency pulled in indirectly |
| Branch Flow Guard | A PR into `main` comes from `staging` in this repo | It is only advisory until `main` has a ruleset |
| Android Mobile CI | Lint and unit tests pass on a debug build | It works on a 3 to 4 GB phone, memory, camera, offline behavior |
| Desktop Client CI | Install, lint and build pass (once `package.json` exists) | The UI works, or the Tauri Rust side compiles |
| Server Local Hub CI | `cargo check` and `cargo test` pass (once `Cargo.toml` exists) | Clippy, formatting, the Tauri window, `server/ui`, or behavior over real Wi-Fi |
| Never covered by CI | Works offline on a real phone, touch targets and contrast, 40 devices at once, AI answer quality | These need the Lead's test steps above or the Sprint 6 QA issues |
