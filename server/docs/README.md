# Server Documentation

Maintained by the **Server Team**. This folder is where the Lead Developer and future teammates get context without reading commits. A PR that adds or changes a feature must update the matching file here.

## Required documents
| File | Purpose | Written in |
| :--- | :--- | :--- |
| [`TECH_SPEC.md`](./TECH_SPEC.md) | Architecture, file map, build order, risks, test plan. Approved by the Lead before Sprint 1 PRs merge. | Sprint 0 |
| [`scaffold.md`](./scaffold.md) | Process model, `serve`, migration runner, error envelope, route stubs, WebSocket handshake, Hub window | Sprint 1 (#39) |
| [`portal.md`](./portal.md) | Captive `GET /download` portal: English 3-step sideload guide, bundled assets, installer serving, path-traversal guard | Sprint 1 (#3) |
| `discovery_mdns_udp.md` | mDNS and UDP beacon behavior, LAN IP detection, AP-isolation notes | Sprint 1 |
| [`database_sync.md`](./database_sync.md) | Migrations, `sync_revisions` writes, `hub_meta` (hub id, epoch), reset rules, backup and restore | Sprint 1 and 2 |
| [`auth_sessions.md`](./auth_sessions.md) | PIN hashing, session tokens, role guards, the login limit, first-run admin setup; ownership guards from 2.1 | Sprint 2 (#42) |
| [`admin_console.md`](./admin_console.md) | Hub setup/login, account creation and PIN reset, offline states, acceptance evidence and manual test steps | Sprint 2 (#43) |
| [`websocket_broker.md`](./websocket_broker.md) | Registry, handshake, presence, targeted delivery and reconnect recovery | Sprint 2 (#44) |
| [`classroom_enrollment.md`](./classroom_enrollment.md) | Class codes, roster privacy, teacher approval, retries and rejoin rules; acceptance evidence and manual test steps | Sprint 2 (#6) |
| [`class_management.md`](./class_management.md) | Rename, archive, remove a learner, co-teachers; who can do what, retries, what sync delivers and manual test steps | Sprint 2 (#121) |
| [`materials_and_submissions.md`](./materials_and_submissions.md) | Lesson file and homework uploads: storage layout, accepted formats, 250 MB cap, access rules, resubmission, downloads with Range and `?token=` | Sprint 3 (#53) |
| `streaming_rate_limit.md` | HTTP Range, token bucket, upload caps | Sprint 3 |
| `quiz_engine.md` | Start and close flow, attempt lifecycle, time validation, grading, score release | Sprint 4 |
| `slm_inference_queue.md` | `llama-server` flags, slots, FIFO queue, guardrails, measured tokens per second | Sprint 5 |
| [`hub_hardware.md`](./hub_hardware.md) | Installer (`.exe`/`.deb`), firewall rules and the LAN port check; pilot machine CPU and RAM, measured capacity | Sprint 1 (#20) and 5 |

## What every document must include
1. **Purpose:** what problem this module solves.
2. **Key files and entry points:** where a reviewer should start.
3. **Data flow and state:** tables touched, events emitted, transactions.
4. **Gotchas and edge cases:** hardware quirks, failure modes, security decisions.

## Related
[`../AGENTS.md`](../AGENTS.md) (agent and developer guide), [`../README.md`](../README.md), [`../../contracts/`](../../contracts/), [`../../rules/`](../../rules/).
