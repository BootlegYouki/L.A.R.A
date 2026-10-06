# Server Documentation

Maintained by the **Server Team**. This folder is where the Lead Developer and future teammates get context without reading commits. A PR that adds or changes a feature must update the matching file here.

## Required documents
| File | Purpose | Written in |
| :--- | :--- | :--- |
| [`TECH_SPEC.md`](./TECH_SPEC.md) | Architecture, Dev A / Dev B split, risks, test plan. Approved by the Lead before Sprint 1 PRs merge. | Sprint 0 |
| `discovery_mdns_udp.md` | mDNS and UDP beacon behavior, LAN IP detection, AP-isolation notes | Sprint 1 |
| `database_sync.md` | Migrations, `sync_revisions` writes, `hub_meta` (hub id, epoch), reset rules, backup and restore | Sprint 1 and 2 |
| `auth_sessions.md` | PIN hashing, session tokens, role and ownership guards, rate limits | Sprint 2 |
| `websocket_broker.md` | Registry, handshake, presence, replay, event emission points | Sprint 2 |
| `streaming_rate_limit.md` | HTTP Range, token bucket, upload caps | Sprint 3 |
| `quiz_engine.md` | Start and close flow, attempt lifecycle, time validation, grading, score release | Sprint 4 |
| `slm_inference_queue.md` | `llama-server` flags, slots, FIFO queue, guardrails, measured tokens per second | Sprint 5 |
| `hub_hardware.md` | Pilot machine CPU and RAM, measured capacity, firewall and installer notes | Sprint 1 and 5 |

## What every document must include
1. **Purpose:** what problem this module solves.
2. **Key files and entry points:** where a reviewer should start.
3. **Data flow and state:** tables touched, events emitted, transactions.
4. **Gotchas and edge cases:** hardware quirks, failure modes, security decisions.

## Related
[`../AGENTS.md`](../AGENTS.md) (agent and developer guide), [`../README.md`](../README.md), [`../../contracts/`](../../contracts/), [`../../rules/`](../../rules/).
