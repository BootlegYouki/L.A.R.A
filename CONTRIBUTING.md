# Contributing & Git Workflow Guide

Welcome to **L.A.R.A**. You are responsible for system stability, the architectural invariants and code quality. This guide is the workflow; the detailed rules are in [`rules/`](./rules/) and [`AGENTS.md`](./AGENTS.md).

---

## 0. Who Works On What

| Team | Folder | Developers | Lead's view |
| :--- | :--- | :--- | :--- |
| **Mobile** | `mobile/` | Dev A, Dev B | Kotlin, Compose, Room, CameraX, Media3, JNI |
| **Desktop** | `desktop/` | Dev A, Dev B | Tauri, React, TypeScript, sidecar AI |
| **Server** | `server/` | Dev A, Dev B | Rust Hub: REST, WebSocket, SQLite, video, AI queue |
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
4. Write your team's `docs/TECH_SPEC.md` from [`docs/templates/TECH_SPEC_TEMPLATE.md`](./docs/templates/TECH_SPEC_TEMPLATE.md) (the Sprint 0 issue). The Lead approves it before Sprint 1 PRs merge.

---

## 2. Picking and Doing Work

* Open your sprint's [milestone](https://github.com/BootlegYouki/L.A.R.A/milestones). Issues are titled `[TEAM sprint.step]` and name a developer slot (**Dev A** or **Dev B**), their dependencies, the contract they implement and the mock-hub flow to use.
* Take an issue from **your** slot. **One issue = one PR.** Do not start an issue whose dependencies are unmerged unless the issue says to use the mock hub.
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

## 4. Commits and Pull Requests

1. **Commit atomically** with Conventional Commits and a team scope: `feat(mobile): ...`, `fix(server): ...`, `docs(desktop): ...`, `test(server): ...`. Keep messages plain; do not add co-author or tool attribution lines.
2. **Open the PR to `staging`** and fill every section of [`.github/PULL_REQUEST_TEMPLATE.md`](./.github/PULL_REQUEST_TEMPLATE.md). Link the issue with `Closes #n`.
3. **Attach evidence:** build or test output, plus a log or screenshot showing it works against the mock hub (or the real Hub) with the WAN unplugged. If you could not verify something, say so.
4. **Update your folder's docs** (`mobile/docs/`, `desktop/docs/`, `server/docs/`): purpose, key files, data flow, gotchas.
5. **CI must be green** (contracts and tools, guardrails, your team's job).
6. **Lead review** uses the `lead-companion` protocol. If approved the Lead **squash-merges**. If changes are requested, push fixes to the same branch.

### Definition of done
- [ ] Linked issue, one team, one PR
- [ ] Contract, mock hub and tests updated first if the API changed (separate PR)
- [ ] Build, tests and `verify_invariants.py` pass, output attached
- [ ] Works with the Hub unreachable (offline state defined)
- [ ] English and Filipino strings, design tokens only, touch targets at least 52dp
- [ ] No answer key, PIN data or other person's LRN reaches a client
- [ ] AI unavailable during a quiz
- [ ] Team docs updated

---

## 5. Fatal Rejection Rules

A PR containing any of these is rejected immediately:

1. **Cloud leakage:** Firebase, Google Play APIs, external CDNs, Google Fonts or unpkg links, remote analytics. Everything is 100% offline LAN.
2. **Hardware RAM crashes:** mobile heap over 250 MB, or loading an on-device model without checking physical RAM of at least 6 GB.
3. **Socratic AI leaks:** prompts or logic that give direct answers or homework solutions.
4. **Quiz lockout bypass:** any path that lets the AI tutor run while the pupil has an `IN_PROGRESS` quiz attempt.
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
