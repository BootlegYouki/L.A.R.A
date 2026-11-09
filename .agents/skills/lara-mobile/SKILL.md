
---
name: lara-mobile
description: Work on L.A.R.A. Mobile issues using Kotlin, Jetpack Compose, Room, Media3, offline-first architecture, Hub testing, and the approved design system. Check Lead announcements and use lightweight subagents when appropriate.
---

# L.A.R.A. Mobile team workflow

## Check announcements first

Before starting or resuming work, addressing PR reviews, or submitting a PR, check the latest [Announcements](https://github.com/BootlegYouki/L.A.R.A/discussions/categories/announcements).

Include updated older threads, Lead comments, relevant issues, PR reviews, and the current staging status. Report important changes before coding.

If announcements cannot be accessed, disclose that limitation. Never assume there are no updates.

## Follow the project architecture

Load [lara-architect](../lara-architect/SKILL.md) and follow the [root guide](../../../AGENTS.md), [Mobile guide](../../../mobile/AGENTS.md), [Mobile Tech Spec](../../../mobile/docs/TECH_SPEC.md), PRD, contracts, and repository rules.

The assigned issue determines scope. Read its dependencies, comments, contracts, and design references before implementation.

Inspect existing code first. Do not invent endpoints, database fields, features, or requirements.

## Use tokens efficiently

Prefer targeted searches, small diffs, and concise reports. Avoid repeatedly reading unchanged files or creating unnecessary abstractions.

Delegate bounded, low-risk tasks to an available Luna subagent when doing so is likely to reduce total effort:

- Screenshot and layout comparisons.
- Simple Compose spacing and UI checks.
- Phosphor icon and string audits.
- Documentation summaries.
- Small isolated visual changes.

Keep architecture, navigation state, Room, Hub networking, authentication, synchronization, Media3 lifecycle, security, Git operations, and final verification with the main agent.

Verify Luna availability before using it. Fall back to the main agent when unavailable. Never sacrifice correctness or testing to save tokens.

Use Ponytail when installed, but repository rules take priority.

## Follow Mobile design and behavior

Use L.A.R.A.'s design tokens, Nunito, Phosphor icons, and string resources. Never introduce arbitrary colors, gradients, or Google branding.

For redesign issues, read `design-system/mobile/google-classroom-reference/README.md` and inspect the actual referenced screenshots. Match Google Classroom's layout and flow, not its branding or unsupported features.

Keep touch targets at least 52dp (56dp primary). Preserve accessibility, offline, loading, empty, and error states.

Keep existing ViewModels, repositories, Room data, Hub calls, and sync behavior intact unless the issue explicitly requires changes.

## Protect Git and team ownership

Check `git status`, current branch, and latest remote state before editing.

Start new issues from the approved staging revision. Continue review fixes on their existing PR branch.

Preserve uncommitted work. Never reset, force-push, merge unrelated changes, or edit another team's files without approval.

Follow the issue's PR structure and screen-group order. Do not automatically commit, push, or merge without permission.

## Test and collect evidence

Use the issue's required mock Hub and, when applicable, the real Rust Hub. Distinguish mock, real-Hub, and deferred integration tests.

For physical-device testing, verify the connected phone with `adb devices -l`. Protect existing app data.

From `mobile/`, run the applicable Gradle checks:

`.\gradlew.bat assembleDebug`
`.\gradlew.bat test`
`.\gradlew.bat lint`

From the repository root:

`python scripts/verify_invariants.py`

Run relevant Compose/device tests. Record actual results, screenshots, tested commit, and limitations.

For UI redesigns, include reference, BEFORE, and AFTER screenshots, plus documented design deviations.

## Finish and prepare PRs

Compare the diff with the correct base. Verify authorized paths and run `git diff --check`.

Update `mobile/docs/` when required. Follow the Lead's PR evidence instructions.

For multi-part issues, use `Part of #<issue-number>` until the final approved PR.

Report concisely: issue/branch, announcements, files changed, tests, evidence, blockers, and next steps.

This skill supplements the architect guide, contracts, design system, and Tech Spec; it does not replace them.
