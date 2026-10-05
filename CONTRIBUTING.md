# Contributing & Git Workflow Guide

Welcome to the **L.A.R.A** repository. As a contributor, you are responsible for maintaining system stability, architectural invariants, and code quality.

---

## 0. Team Streams (Who Works on What)

The engineering team is organized into three decoupled project streams:
* **Mobile Team (`mobile/`):** Focuses exclusively on Kotlin, Jetpack Compose M3, Room DB, CameraX, and mobile performance on 3GB/4GB phones.
* **Desktop Team (`desktop/`):** Focuses on Tauri 2.x, React 19, TypeScript, Tailwind CSS, and laptop `llama.cpp` execution.
* **Server Team (`server/`):** Focuses on the Local Hub backend (mDNS, UDP beacons, SQLite, WebSockets, video streaming, and `llama-server`).
* **Lead Developer:** Reviews and approves all PRs before they enter `staging`.

---

## 1. Branching Architecture

We follow an adapted GitFlow branching model tailored for multi-module capstone development:

```
feature/bug branches (feat/*, fix/*)
          │
          ▼  (PR + Lead Approval)
       staging  ◄─── (Integration testing on classroom Wi-Fi)
          │
          ▼  (PR + Milestone Release)
        main    ◄─── (Production / Defense-Ready)
```

### The Three Branch Tiers:
1. **`main` (Protected — Defense-Ready / Production):**
   * Represents release-quality, defense-ready software.
   * **Direct pushes and force pushes are strictly disabled.**
   * Merges into `main` occur only from `staging` via Pull Request upon completing a Milestone.
2. **`staging` (Protected — Active Integration):**
   * The shared integration branch where all tested feature branches merge.
   * End-to-end integration testing (Android phone connecting to the Tauri server over local Wi-Fi) happens here.
   * **Direct pushes are disabled.** All code must arrive via Pull Request with Lead Developer approval.
3. **Working Branches (`feat/*`, `fix/*`, `docs/*`, `test/*`):**
   * Created off of `staging`:
     ```bash
     git checkout staging
     git pull origin staging
     git checkout -b feat/server-mdns-beacon
     ```
   * Scope must be focused on a single issue. Never mix mobile, desktop, and server changes in one branch unless implementing a shared API schema contract.

---

## 2. Pull Request (PR) Rules & Lifecycle

Before opening a Pull Request targeting `staging`, ensure you satisfy all requirements:

### The PR Lifecycle:
1. **Branch Off `staging`:** Always base feature branches on latest `staging`.
2. **Commit Atomically:** Follow Conventional Commits format (`feat:`, `fix:`, `docs:`, `test:`).
3. **Fill the PR Template:** Complete all sections in `.github/PULL_REQUEST_TEMPLATE.md`.
4. **Link the Issue:** Explicitly link the tracked issue in the description (e.g., `Closes #4`).
5. **Attach Verification Evidence:** Attach a log snippet, terminal output, or screenshot proving your changes work on local LAN with zero internet connection.
6. **Lead Developer Code Review:** The Lead Developer will audit your PR using the `lead-companion` protocol.
   * If approved: The Lead Developer merges using **Squash and Merge** to maintain a clean git history.
   * If changes requested: Address feedback directly on your branch and push updates.

---

## 3. The Five Fatal PR Rejection Rules

Any Pull Request containing any of the following will be **immediately rejected**:

1. **Cloud Leakage:** Importing Firebase, Google Play APIs, external CDNs, Google Fonts, or remote analytics. Everything must be 100% offline LAN.
2. **Hardware RAM Crashes:** Unoptimized mobile heap allocations (> 250MB) or attempting on-device SLM inference without verifying `physical RAM >= 6GB`.
3. **Socratic AI Leaks:** Providing direct answers, solution formulas, or homework keys in prompts.
4. **Quiz Lockout Bypass:** Any pathway that allows the AI tutor to run during an active timed quiz.
5. **Accessibility Regressions:** Touch targets smaller than 52dp (preferred 56dp) or hardcoding English strings without Filipino localization keys.

---

## 4. Useful Git Commands for Team Members

```bash
# Start a new feature branch:
git checkout staging
git pull origin staging
git checkout -b feat/mobile-camera-capture

# Commit with Conventional Commits:
git add .
git commit -m "feat(mobile): implement CameraX capture with JPEG compression"

# Push to GitHub:
git push -u origin feat/mobile-camera-capture
# Then open a PR targeting 'staging' on GitHub!
```
