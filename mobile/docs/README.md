# Mobile Documentation

Maintained by the **Mobile Team**. This folder is where the Lead Developer and future teammates get context without reading commits. A PR that adds or changes a feature must update the matching file here.

> **Reset (Sprint 0):** the Android project from `[MOBILE 1.1]` was removed because the mobile team changed. `TECH_SPEC.md` is the previous team's draft: the new team rewrites and gets it approved before any Sprint 1 PR. The old code is in git history (PR #23).

## Required documents
| File | Purpose | Written in |
| :--- | :--- | :--- |
| [`TECH_SPEC.md`](./TECH_SPEC.md) | Architecture, parallel-PR file map, risks, test plan. Approved by the Lead before Sprint 1 PRs merge. | Sprint 0 |
| `navigation.md` | Student and Teacher graphs, role routing, bottom navigation | Sprint 1 and 2 |
| `room_schema.md` | Entities versus `client_offline.sql`, DAOs, schema test | Sprint 1 |
| `discovery.md` | NSD, UDP, `MulticastLock`, manual IP, reconnect | Sprint 1 |
| `sync_worker.md` | WorkManager sync, cursor handling, `reset`, offline queue | Sprint 2 |
| `camerax.md` | Capture, orientation, JPEG under 800 KB, upload retry | Sprint 3 |
| `video_player.md` | Media3 Range streaming, Save for Home | Sprint 3 |
| `quiz_runner.md` | Countdown, auto-submit, offline finish, AI unmount | Sprint 4 |
| `ai_routing.md` | RAM router, JNI bridge, prompt builder, lockout | Sprint 5 |
| `budget_device_benchmarks.md` | Heap and battery results on Infinix and realme | Sprint 6 |

## What every document must include
1. **Purpose:** what problem this screen or module solves.
2. **Key files and entry points:** where a reviewer should start.
3. **Data flow and local state:** what lives in Room and how it syncs.
4. **Gotchas and edge cases:** Transsion and realme battery killers, camera orientation, low-memory behavior.

## Related
[`../AGENTS.md`](../AGENTS.md) (agent and developer guide), [`../README.md`](../README.md), [`../../contracts/`](../../contracts/), [`../../design-system/design-system.md`](../../design-system/design-system.md).
