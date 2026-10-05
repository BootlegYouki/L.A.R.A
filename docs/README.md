# L.A.R.A Documentation Map

Start here to find the right document. When two documents disagree, the one higher in this list wins (the same order as `AGENTS.md` section 1.1).

## Source of truth, in order
| # | Document | Owns |
| :-- | :--- | :--- |
| 1 | [`../contracts/`](../contracts/) | REST (`openapi.yaml`), WebSocket events (`events/`), SQL schemas (`schema/`), naming rules |
| 2 | [`../AGENTS.md`](../AGENTS.md) and [`../rules/`](../rules/) | Invariants, decisions already made, working rules |
| 3 | [`design-system.md`](../design-system/design-system.md) and [`../design-system/`](../design-system/) | Every visual rule, token, component and the Google Classroom layout reference |
| 4 | [`PRD.md`](./PRD.md) | Product behavior and functional requirements |
| 5 | Team `README.md`, `docs/TECH_SPEC.md` and GitHub issues | How each team builds its part |

## By role
| You are | Read, in this order |
| :--- | :--- |
| **Server developer** | `AGENTS.md` -> `server/AGENTS.md` -> `server/README.md` -> `contracts/` -> `rules/database-and-sync.md`, `rules/quiz-and-anti-cheat.md`, `rules/networking-and-lan.md` |
| **Desktop developer** | `AGENTS.md` -> `desktop/AGENTS.md` -> `desktop/README.md` -> `contracts/` -> `design-system/design-system.md` -> `rules/database-and-sync.md` |
| **Mobile developer** | `AGENTS.md` -> `mobile/AGENTS.md` -> `mobile/README.md` -> `contracts/` -> `design-system/design-system.md` -> `rules/database-and-sync.md` |
| **Working on the AI tutor** | `rules/socratic-ai-guardrails.md` (including the evaluation requirement) -> your team's AI issues |
| **Reviewing a PR (Lead)** | `.agents/skills/lead-companion/SKILL.md` -> `rules/team-workflow-and-prs.md` |
| **New to the project** | Root `README.md` -> `PRD.md` sections 1 to 4 -> this map |

## All documents
| Path | What it is |
| :--- | :--- |
| [`PRD.md`](./PRD.md) | Product requirements, personas, modules, evaluation metrics |
| [`design-system.md`](../design-system/design-system.md) | Tokens, components, accessibility, Google Classroom reference |
| [`design-system/showcase.html`](../design-system/showcase.html) | Interactive preview of the design system (fully offline) |
| [`architecture/developer-ecosystem-and-workflow.md`](./architecture/developer-ecosystem-and-workflow.md) | How the mock hub, contracts and CI let three teams work in parallel |
| [`templates/TECH_SPEC_TEMPLATE.md`](./templates/TECH_SPEC_TEMPLATE.md) | Template each team fills in Sprint 0 |
| [`templates/TECH_SPEC_GUIDE.md`](./templates/TECH_SPEC_GUIDE.md) | What a good spec looks like, for developers, the Lead and AI agents |
| [`plans/`](./plans/) | Historical implementation plans (superseded, kept for context) |
| `benchmarks/` | Created in Sprint 5 and 6: AI model evaluation, router stress test, phone profiling |
| `evaluations/`, `audits/` | Created in Sprint 6: SUS and ISO 25010 results, WAN-unplugged audit |

## Rules for keeping docs healthy
* The schema and API live only in `contracts/`. Link to them, never copy column lists or routes.
* Product behavior lives only in `PRD.md`. Tech Specs and issues cite it, they do not redefine it.
* A change that makes any document wrong must fix that document in the same PR.
