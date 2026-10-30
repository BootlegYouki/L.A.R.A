---
name: lara-desktop
description: Work on L.A.R.A desktop issues using the approved Tauri/React architecture, local Hub testing and native verification. Use for changes in desktop/; shared contracts and manuscript tasks follow their own issue scope.
---

# L.A.R.A Desktop team workflow

## Start from the assigned issue

Load [lara-architect](../lara-architect/SKILL.md) for the reading order. Follow the [root guide](../../../AGENTS.md), [desktop guide](../../../desktop/AGENTS.md) and approved [desktop Tech Spec](../../../desktop/docs/TECH_SPEC.md). They own the requirements, file names and build order; this skill does not redefine them. The [desktop stack guide](../lara-desktop-web/SKILL.md) supplies focused implementation notes when relevant.

Work from the latest staging branch, one issue at a time. Use the issue's cited contracts and design sections before editing. Update decisions in the spec with the feature rather than maintaining another copy here. The Lead requested this team-owned skill as its own small PR in [issue #5](https://github.com/BootlegYouki/L.A.R.A/issues/5#issuecomment-6442674867).

## Run and test

Use the commands in the team's package scripts and native prerequisites for the host platform. Desktop tests use Node's built-in SQLite support; use Node 22.13 or newer.

From the repository root, in a separate terminal:

```sh
python scripts/mock_hub.py
```

Use only the synthetic accounts documented in the [root guide](../../../AGENTS.md#12-commands). In desktop/:

```sh
npm ci
npm run lint
npm test
npm run build
cargo check --manifest-path src-tauri/Cargo.toml
npm run tauri dev
```

From the repository root before the PR:

```sh
python scripts/verify_invariants.py
python -m unittest discover tests
```

The shared suite creates its own mock instance; it does not prove the native frontend called the Hub. Exercise the feature in the real Tauri window against the mock, then stop the mock and check its offline state. Record the actual commands, results and limits in the feature documentation/PR. For real-Hub integration, use the [Hub hardware/run documentation](../../../server/docs/hub_hardware.md) and [network rules](../../../rules/networking-and-lan.md); do not claim a Hub launch if none was performed.

## Reuse the desktop foundation

- Trace UI state through the existing App, Zustand store and DB service before adding another abstraction. [Scaffold notes](../../../desktop/docs/scaffold.md) describe the implemented flow; [Tech Spec sections 4–5](../../../desktop/docs/TECH_SPEC.md#4-file-map) name the modules planned for later issues. Planned modules are not evidence that code exists.
- Keep language keys in the paired JSON dictionaries. Only nonsecret preferences belong in browser storage. Use the spec's credential design when the authentication issue begins.
- Keep native registration in lib.rs; main.rs remains the thin entry point. Add only the capability required by the current operation.
- Read the canonical [client schema](../../../contracts/schema/client_offline.sql) and [sync rules](../../../rules/database-and-sync.md) for persistence changes. The native transaction approach in the spec exists because separate pooled JS SQL calls cannot establish an atomic pull/cursor update.

## Traps observed in desktop work

- Bundled assets and passing builds do not establish visual fidelity. For UI changes, compare the native app with the [prototype](../../../design-system/prototype.html) at the same viewport and read the corresponding [design-system](../../../design-system/design-system.md) sections. Disclose deliberate accessibility differences. Check both dictionaries, keyboard focus and enlarged text; do not put development roadmap notes on a product screen.
- A reload is not a process restart. Close and reopen the entire app when claiming persisted language or cached-data behavior. Existing [verification notes](../../../desktop/docs/verification.md) distinguish these checks and their limits.
- Database initialization timeouts bound the UI wait but do not cancel native work already running. Preserve retry handling and saved data when changing startup logic.
- The mock broadcasts UDP; it does not prove real mDNS discovery. Validate the approved beacon fields, malformed packets, duplicate/stale entries and scanner shutdown. An advertisement alone must not label a Hub Connected. Read [networking](../../../rules/networking-and-lan.md) before implementing discovery or manual fallback; do not invent a health endpoint or disable access checks to probe one.
- The scaffold CSP permits local IPC, not arbitrary Hub traffic. Test native HTTP permissions, browser WebSocket and media separately when their issue adds them. Use the spec's transport decision rather than mixing request implementations.
- Test with temporary synthetic databases. Never erase a developer's or school's existing database to obtain an empty screenshot. Native runtimes keep some small state in OS app-data folders even when the source/build cache is elsewhere.
- When Windows test isolation restricts temporary folders, set TEMP/TMP for the task process to an allowed project-local directory. Do not change system-wide variables or weaken tests. Report environment warnings separately from failures.

Before submission, compare the diff with staging, confirm only the issue-owned paths changed, and follow the evidence requirements in the architect guide. Windows checks do not establish Linux/macOS behavior, release-installer behavior or physical WAN-unplugged testing.
