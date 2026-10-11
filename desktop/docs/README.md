# Desktop Documentation

Maintained by the **Desktop Team**. This folder is where the Lead Developer and future teammates get context without reading commits. A PR that adds or changes a feature must update the matching file here.

## Required documents
| File | Purpose | Written in |
| :--- | :--- | :--- |
| [`TECH_SPEC.md`](./TECH_SPEC.md) | Architecture, file map, build order, risks, test plan. Approved by the Lead before Sprint 1 PRs merge. | Sprint 0 |
| `components.md` | Component structure, design-system token usage, i18n dictionaries | Sprint 1 |
| `database.md` | `plugin-sql` setup, migrations from `client_offline.sql`, `sync_state` | Sprint 1 and 2 |
| `sync_engine.md` | Pull and push loops, cursor handling, `reset`, offline queue | Sprint 2 |
| `realtime.md` | WebSocket client, handshake, reconnect, event handling | Sprint 2 |
| `video_player.md` | Range streaming with `?token=`, disk download, local playback | Sprint 3 |
| `quiz_runner.md` | Countdown with server offset, auto-submit, offline finish | Sprint 4 |
| `sidecar_llama.md` | Sidecar permissions, RAM check, Hub fallback, prompt building | Sprint 5 |
| `ux_audit.md` | Section 8 checklist results and fixes | Sprint 6 |

## What every document must include
1. **Purpose:** what problem this screen or module solves.
2. **Key files and entry points:** where a reviewer should start.
3. **Data flow and local state:** what is stored in SQLite or Zustand and how it syncs.
4. **Gotchas and edge cases:** Tauri capability limits, webview quirks, offline failure modes.

## Related
[`../AGENTS.md`](../AGENTS.md) (agent and developer guide), [`../README.md`](../README.md), [`../../contracts/`](../../contracts/), [`../../design-system/design-system.md`](../../design-system/design-system.md).
