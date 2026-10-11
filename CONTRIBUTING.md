# Contributing & Git Workflow Guide

Welcome to **L.A.R.A**. You are responsible for system stability, the architectural invariants and code quality. This guide is the workflow; the detailed rules are in [`rules/`](./rules/) and [`AGENTS.md`](./AGENTS.md).

---

## 0. Who Works On What

| Team | Folder | Developers | Lead's view |
| :--- | :--- | :--- | :--- |
| **Mobile** | `mobile/` | 2 developers | Kotlin, Compose, Room, CameraX, Media3, JNI |
| **Desktop** | `desktop/` | 2 developers | Tauri, React, TypeScript, sidecar AI |
| **Server** | `server/` | 2 developers | Rust Hub: REST, WebSocket, SQLite, video, AI queue |
| **Lead Developer** | all | 1 | Reviews every PR, owns `contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/` |

You only edit your own team's folder. Shared paths are Lead-owned (see `.github/CODEOWNERS`).

---

## 1. Your First Day

1. Read [`AGENTS.md`](./AGENTS.md) (what the system is, decisions already made, invariants) and your folder's `AGENTS.md`.
2. Read your team `README.md` (it has a **Start Here** checklist).
3. Run the Hub simulator and the checks:
   ```bash
   python3 scripts/mock_hub.py                 # leave running; clients build against it
   python3 -m unittest discover tests
   python3 scripts/verify_invariants.py
   ```
4. Write your team's `docs/TECH_SPEC.md` from [`docs/templates/TECH_SPEC_TEMPLATE.md`](./docs/templates/TECH_SPEC_TEMPLATE.md) and check it against [`what a good spec looks like`](./docs/templates/TECH_SPEC_GUIDE.md) (the Sprint 0 issue). The Lead approves it before Sprint 1 PRs merge.

---

## 2. Picking and Doing Work

* **Board:** the [L.A.R.A Sprint Board](https://github.com/users/BootlegYouki/projects/2) shows every issue by Team, Sprint and Status. Move your card to *In Progress* when you start and *Done* when the PR merges.
* **Find your list:** use the team and sprint filter links in the [README](./README.md#start-in-10-minutes). Every issue is labelled by team (`scope:*`) and sprint (`sprint:N`). Sprint themes are in the [milestones](https://github.com/BootlegYouki/L.A.R.A/milestones). Issues are titled `[TEAM sprint.step]` and name their dependencies, the contract they implement and the mock-hub flow to use.
* **Your team works one issue at a time, in step order** (`2.1`, then `2.2`, ...). Both developers work that issue together and open one PR; start the next issue when it is merged. The three teams are the only parallel streams. **One issue = one PR.** An issue that depends on another team's step builds against the mock hub and does not wait.
* Clients build against `scripts/mock_hub.py`. The real Hub is used on **integration day** at the end of each sprint.
* **Need a new route, event or column?** Do not add it in code. Ask the Lead: it becomes a separate `contract-change` PR (contracts + mock hub + tests) merged first. Never edit `contracts/` inside a feature PR.
* Stuck or found a contradiction between documents? Write it in the issue instead of guessing. The documents are ranked in [`docs/README.md`](./docs/README.md).

---

## 3. Branching

```
feature/bug branches (feat/*, fix/*, docs/*, test/*)
          │
          ▼  (PR + Lead approval, CI green)
       staging  ◄─── integration testing on classroom Wi-Fi
          │
          ▼  (PR at sprint completion)
        main    ◄─── defense-ready
```

* **`main` (protected):** release quality. Updated only from `staging` at sprint completion. No direct or force pushes.
* **`staging` (protected):** shared integration branch. All work arrives by PR with Lead approval. No direct pushes.
* **Working branches** are created from the latest `staging`, one per issue:
  ```bash
  git checkout staging && git pull origin staging
  git checkout -b feat/server-mdns-beacon      # feat/*, fix/*, docs/*, test/*
  ```
  Branch names should include the team and topic (`feat/mobile-camera-capture`).

---

## 4. From Issue to Merged PR, Step by Step

Follow these steps in order for every issue. Replace the example (`#6`, `[SERVER 2.1]`, `feat/server-class-code-approval`) with yours.

### Step 1: Start your team's next issue
1. Open the [sprint board](https://github.com/users/BootlegYouki/projects/2) and take your team's next card in step order. Your team's previous PR must be merged first.
2. Assign both developers of the team to the issue.
3. Move the card to **In Progress**.
4. Read the whole issue: Objective, PRD, Contract, Sub-Tasks, Target Files, Acceptance Criteria. Open the PRD requirement and the contract routes it names. If anything contradicts another document, ask in the issue **before** coding.

### Step 2: Create your branch from the latest `staging`
```bash
git checkout staging
git pull origin staging
git checkout -b feat/server-class-code-approval
```
Naming: `<type>/<team>-<short-topic>` where type is `feat`, `fix`, `docs` or `test`. One branch per issue. Never commit on `staging` or `main` (they reject pushes).

### Step 3: Do the work in small commits
Commit when one logical thing works, not at the end of the day. Use Conventional Commits with the team as the scope:

```bash
git add server/backend/src/routes/classrooms.rs
git commit -m "feat(server): generate unique 6-character class codes"
git commit -m "feat(server): emit EVENT_JOIN_REQUEST when a learner joins"
git commit -m "test(server): cover approve and reject flows"
git commit -m "docs(server): document the enrollment gate"
```

| Good | Bad |
| :--- | :--- |
| `fix(mobile): keep quiz timer running when Wi-Fi drops` | `fixed stuff` |
| `feat(desktop): add roster table with Accept / Decline` | `WIP` / `update` / `final final` |
| `test(server): reject AI requests during an active quiz` | a commit that mixes three unrelated changes |

Rules: plain messages only (no co-author or tool attribution lines), no secrets, no generated or build files, never `git add .` blindly (check `git status` first).

### Step 4: Keep your branch current
If `staging` moved while you worked (check at least once a day and always before opening the PR):
```bash
git fetch origin
git rebase origin/staging          # or: git merge origin/staging if rebasing worries you
```
**If there are conflicts:** git stops and lists the files. Open each, keep the correct combination of both sides (remove the `<<<<<<<`, `=======`, `>>>>>>>` markers), then:
```bash
git add <fixed-file>
git rebase --continue              # (merge users: git commit)
```
Re-run your tests after resolving. If the conflict is in `contracts/`, `rules/` or another Lead-owned file, **stop and tell the Lead**: you should not be editing those files in a feature branch. If you pushed before rebasing, update the branch with `git push --force-with-lease` (only on your own branch, never on `staging` or `main`).

### Step 5: Check your work before you push
Run these from the repository root and your team folder; all must pass:

| Everyone | `python3 -m unittest discover tests` and `python3 scripts/verify_invariants.py` |
| :--- | :--- |
| **Server** | in `server/backend/`: `cargo check --all-targets` and `cargo test` |
| **Desktop** | in `desktop/`: `npm run lint` and `npm run build` |
| **Mobile** | in `mobile/`: `./gradlew test lint` |

Then run the real thing: start `python3 scripts/mock_hub.py`, exercise your feature against it, and test **with the Hub unreachable** (stop the mock hub, or use airplane mode). Go through the Acceptance Criteria in the issue one by one.

### Step 6: Push and open the pull request
```bash
git push -u origin feat/server-class-code-approval
```
GitHub prints a link to open the PR. Or use the CLI:
```bash
gh pr create --base staging --title "feat(server): class code generation and approval gate" --body-file pr.md
```
* **Base branch must be `staging`**, never `main`.
* **Title** is a Conventional Commit line (the Lead squash-merges, and this title becomes the commit message on `staging`).
* **Body:** fill **every** section of the template (see the example below). The line `Closes #6` links and auto-closes the issue when the PR merges.
* Not ready for review? Open it as a **Draft** (`gh pr create --draft` or "Create draft pull request") and click *Ready for review* when finished. Draft PRs are not reviewed.
* Leave the card in **In Progress** while it is in review. It moves to **Done** when the PR merges.

### Step 7: Attach evidence (this is what the Lead reads first)
A PR without evidence is sent back. Paste into the PR description or a comment:
* **Test output:** the last lines of `cargo test` / `npm run build` / `./gradlew test lint` and the unittest run, showing they pass.
* **Proof it works:** a log snippet or screenshot (or a short screen recording) of the feature running against the mock hub or the real Hub, **with the WAN unplugged**. Drag images straight into the PR text box.
* **Offline proof:** a screenshot or log of the feature with the Hub unreachable.
* **Not verified:** if you could not test something (for example no physical budget phone), say so plainly. That is acceptable; hiding it is not.

Remove tokens, PINs and real learner names or LRNs from every log and screenshot first.

### Step 8: Wait for CI, fix failures
After you push, GitHub runs the checks listed at the bottom of the PR. All must be green.

| Failing check | Usually means | Fix |
| :--- | :--- | :--- |
| **Contracts & Mock Hub Validation** | A contract, schema or mock hub test failed | Run `python3 -m unittest discover tests` locally and read the failing test name |
| **Invariant Guardrails** | A forbidden pattern (Firebase, Google Fonts, CDN) was added, or Android Filipino strings are missing | Run `python3 scripts/verify_invariants.py`; remove the link or add the `values-tl` string |
| **Server / Desktop / Mobile CI** | Build, lint or type error in your team's code | Run your team's commands from Step 5 and fix the first error |

Click **Details** on the red check to see the log. Push a fix to the same branch; CI re-runs automatically. Do not ask for review while CI is red.

### Step 9: Review loop
1. The Lead reviews with the checklist in [`rules/team-workflow-and-prs.md`](./rules/team-workflow-and-prs.md) section 5: the five fatal checks and the extra blockers, scope, offline behavior, security, evidence.
2. The verdict is **Approve**, **Request changes** (with blocking items) or **Needs discussion**.
3. For each comment: fix it, push to the same branch, and reply `Fixed in <commit>` (or explain why not). Do **not** open a new PR and do not resolve a thread yourself unless the Lead says so.
4. Push the fixes, then click **Re-request review**. Repeat until approved.

### Step 10: After the merge
The Lead **squash-merges** into `staging`. Then:
```bash
git checkout staging
git pull origin staging
git branch -d feat/server-class-code-approval    # delete your local branch
```
GitHub deletes the remote branch and closes the issue. Make sure the card is in **Done**. On the next issue, start again from Step 2 with the updated `staging`.

### Example: a filled-in PR description
```markdown
## Summary of Changes
Adds server-side class code generation and the teacher approval gate: unique 6-character codes
(no 0/O/1/I), learner join creating a PENDING enrollment, approve/reject endpoints, and
EVENT_JOIN_REQUEST / EVENT_JOIN_APPROVAL pushes.

Closes #6

## Subsystems Touched
- [ ] mobile/   - [ ] desktop/   - [x] server/   - [ ] contracts/   - [x] Documentation

## Verification & Testing
- cargo check --all-targets: OK; cargo test: 14 passed
- python3 -m unittest discover tests: 34 OK
- Ran against the same flow as tests/test_mock_hub.py::test_join_approve_flow_pushes_websocket_events
  (log below), WAN unplugged
- Hub unreachable case: N/A (server-side change)
- Not verified: Windows firewall behavior (tested on Linux only)

<paste log snippet / screenshot>

## Non-Regression Checklist
- [x] Zero external dependencies added
- [x] server/docs/ updated (enrollment_gate.md)
- [x] No answer key, PIN data or foreign LRN in any response
```

### Special cases
| Situation | What to do |
| :--- | :--- |
| **You need a new route, event or column** | Stop. Comment on the issue. The Lead opens a separate `contract-change` PR (contracts + mock hub + tests). Branch from `staging` after it merges. Never edit `contracts/` in your feature PR. |
| **Your issue depends on another team's unmerged work** | Build against `scripts/mock_hub.py` and say so in the PR. Do not copy their unmerged code into your branch. |
| **The PR is getting huge** | Split it: land the foundation (models, DTOs) first, then the screen or route. One reviewable idea per PR. |
| **You found a bug outside your folder** | Open a Bug Report issue for the owning team. Do not fix it in your PR. |
| **You are blocked or the requirement is unclear** | Write the question on the issue, tag the Lead, and work on another card meanwhile. |
| **You pushed something sensitive (token, PIN, real LRN, photo of a child)** | Tell the Lead **immediately**; do not just delete the commit. The secret must be treated as exposed. |
| **Docs-only change** | Same process: branch `docs/<team>-<topic>`, PR to `staging`, CI must pass. |

### Common mistakes (each one gets a PR sent back)
* Opening the PR against `main` instead of `staging`.
* Leaving the template sections empty or deleting them.
* No `Closes #n`, or one PR that closes several unrelated issues.
* Touching another team's folder or a Lead-owned path (`contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/`).
* No evidence, or evidence from the happy path only (nothing offline, nothing for the error case).
* Forgetting the Filipino strings, or hardcoding a hex color or English text in a screen.
* Committing build output, `node_modules`, `.env` files or large model files.
* Force-pushing over someone else's work or to `staging` / `main`.

### Definition of done
- [ ] Linked issue, one team, one PR, based on the latest `staging`
- [ ] Contract, mock hub and tests updated first if the API changed (separate PR)
- [ ] Build, tests and `verify_invariants.py` pass; output attached
- [ ] Works with the Hub unreachable (offline state defined)
- [ ] English and Filipino strings, design tokens only, touch targets at least 52dp
- [ ] No answer key, PIN data or other person's LRN reaches a client
- [ ] AI unavailable during a quiz
- [ ] Team docs updated
- [ ] CI green, evidence attached, card still In Progress until merged

---

## 5. Fatal Rejection Rules

A PR containing any of these is rejected immediately:

1. **Cloud leakage:** Firebase, Google Play APIs, external CDNs, Google Fonts or unpkg links, remote analytics. Everything is 100% offline LAN.
2. **Hardware RAM crashes:** mobile heap over 250 MB, or loading an on-device model without checking physical RAM of at least 6 GB.
3. **Socratic AI leaks:** prompts or logic that give direct answers or homework solutions.
4. **Quiz lockout bypass:** any path that lets the AI tutor run while the learner has an `IN_PROGRESS` quiz attempt.
5. **Accessibility regressions:** touch targets under 52dp (56dp for primary actions and quiz options) or English-only strings.
6. **Secrets reaching a client:** `pin_hash`, other people's LRN, `correct_answer`, or server file paths in a response, client table or log.
7. **Contract drift:** a route, event or column changed in code without `contracts/`, the mock hub and the tests.
8. **Scope violation:** a PR that edits more than one team's folder or a Lead-owned path inside a feature PR.

---

## 6. Useful Commands

```bash
# Start a feature branch
git checkout staging && git pull origin staging
git checkout -b feat/mobile-camera-capture

# Commit and push
git add <files>
git commit -m "feat(mobile): implement CameraX capture with JPEG compression"
git push -u origin feat/mobile-camera-capture
# then open a PR targeting staging

# Verify before the PR
python3 -m unittest discover tests && python3 scripts/verify_invariants.py
```
