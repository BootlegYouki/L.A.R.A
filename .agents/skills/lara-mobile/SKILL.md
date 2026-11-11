
---
name: lara-mobile
description: Work on L.A.R.A. Mobile issues using Kotlin, Jetpack Compose, Room, Media3, offline-first architecture, Hub testing, and the approved design system. Check Lead announcements and use lightweight subagents when appropriate.
---

# L.A.R.A. Mobile team workflow

## Check announcements first

Before starting work, check the latest [Announcements](https://github.com/BootlegYouki/L.A.R.A/discussions/categories/announcements), including relevant updated threads, Lead comments, issues, PR reviews, and staging status.

Check again before PR submission or when resuming after a significant gap. Do not repeatedly reread unchanged announcements.

Report important changes before coding. If access fails, disclose the limitation.

## Follow project architecture

Load [lara-architect](../lara-architect/SKILL.md) and follow the applicable [root guide](../../../AGENTS.md), [Mobile guide](../../../mobile/AGENTS.md), and [Mobile Tech Spec](../../../mobile/docs/TECH_SPEC.md).

Read only relevant sections of the PRD, contracts, design references, and repository rules. Use targeted searches rather than repeatedly loading entire documents.

The assigned issue determines scope. Inspect existing code before editing. Never invent endpoints, database fields, or features.

## Use tokens efficiently

Prefer small diffs, targeted searches, concise progress reports, and existing components.

Avoid:
- Repeatedly reading unchanged files or documentation.
- Repeating completed planning or screenshot comparisons.
- Running full test suites after every small visual change.
- Unnecessary abstractions or unrelated refactoring.
- Reinvestigating known baseline failures without new evidence.
- Rebuilding working test infrastructure unnecessarily.

Reuse verified environments, previous test results, and documented limitations when applicable. Still rerun required checks before PR submission.

Delegate bounded, low-risk tasks to Luna when available and useful:
- Screenshot and layout comparisons.
- Simple Compose spacing and visual adjustments.
- Phosphor icon and string audits.
- Documentation summaries.
- Small isolated UI changes.

Keep architecture, navigation state, Room, Hub networking, authentication, synchronization, Media3 lifecycle, security, Git operations, and final verification with the main agent.

Verify Luna availability first. Do not delegate when overhead exceeds the expected benefit.

Use Ponytail when installed, subject to repository rules.

## Follow Mobile design and behavior

Use L.A.R.A. design tokens, Nunito, Phosphor icons, and string resources. No arbitrary colors, gradients, or Google branding.

For redesigns, read `design-system/mobile/google-classroom-reference/README.md` and inspect only the screenshots relevant to the assigned group.

Match Google Classroom's layout and flow without introducing unsupported features.

Keep touch targets at least 52dp (56dp primary). Preserve accessibility, offline, loading, empty, and error states.

Preserve existing ViewModels, repositories, Room data, Hub calls, and synchronization unless the issue explicitly requires changes.

## Protect Git and team ownership

Check Git status, current branch, and remote state before editing.

Start new issues from the approved staging revision. Continue PR review fixes on the existing PR branch.

Preserve uncommitted work. Never reset, force-push, merge unrelated changes, or edit another team's files without approval.

Follow the assigned issue's implementation order and PR structure.

Do not commit, push, open PRs, or merge without explicit permission.

## Test efficiently and collect evidence

Choose testing depth based on the change and applicable Lead requirements.

For UI-only redesigns:
- Prefer mock Hub, Room fixtures, and cached data during implementation.
- Use targeted Compose tests for navigation, accessibility, and UI behavior.
- Avoid unnecessary Rust Hub rebuilding or infrastructure troubleshooting.
- Reuse an existing working real-Hub setup when required.
- If required real-Hub testing cannot be completed, disclose it as pending and obtain approval before treating it as deferred.

For physical-device tests, verify the connected phone using `adb devices -l` and protect existing app data.

Run targeted tests during development. Complete applicable required checks before PR submission.

From `mobile/`:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

From the repository root:

```powershell
python scripts/verify_invariants.py
```

Record actual pass/fail results, known baseline failures, environment limitations, and tested revision.

For UI redesigns:
- Capture genuine BEFORE screenshots once before changes.
- Capture final AFTER screenshots when layouts stabilize.
- Include the corresponding reference screenshots.
- Document design deviations.
- Avoid repeated captures unless evidence is incorrect or a relevant UI change requires them.

Never claim an unexecuted or failing check passed.

## Handle unrelated failures

When a check fails, determine whether it is caused by the current change.

If a failure is already documented on untouched staging, reference its existing issue or evidence. Do not repeatedly reproduce or fix it in an unrelated UI PR without authorization.

Do not change Server, Desktop, shared contracts, or database schemas merely to make unrelated checks pass.

Report genuine regressions separately from known baseline problems.

## Finish and prepare PRs

Compare the diff against the correct base. Check authorized paths and run `git diff --check`.

Update `mobile/docs/` when required and follow Lead evidence instructions.

For multi-part issues, use `Part of #<issue-number>` until the final approved PR.

Avoid duplicate PR-body documents unless explicitly required.

Report concisely:
- Issue and branch.
- Announcements or requirement changes.
- Files and features changed.
- Tests and actual results.
- Screenshot evidence.
- Blockers and known limitations.
- Next action.

This skill supplements the architect guide, contracts, design system, and Tech Spec; it does not replace them.
