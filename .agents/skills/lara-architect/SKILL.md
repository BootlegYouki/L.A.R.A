---
name: lara-architect
description: How to work a L.A.R.A issue so every agent session builds the same thing - the reading order, where each kind of truth lives (contracts, rules, design system, PRD, Tech Spec), the invariants that get a PR rejected, and the checks to run before opening it. Use at the start of any implementation, change or test in mobile/, desktop/ or server/.
---

# Working a L.A.R.A issue

L.A.R.A is an offline, LAN-only Google Classroom with self-grading timed quizzes and a Socratic AI tutor, for Philippine public schools. Most code is written by AI agents, so consistency comes from every session following the same process and reading the same sources. This skill is that process. It holds no copies of the contract or the rules: open the files it names.

## 1. Before you write code

Three teams (`server/`, `desktop/`, `mobile/`) work in parallel. Inside a team there is one issue in progress, taken in step order, with one open PR.

1. **Read the issue.** Its `PRD`, `Contract` and `Design system` lines name exactly what to open. The issue is the scope: its sub-tasks, its Target Files, its acceptance criteria.
2. **Open what it names,** in this order. Higher wins when two disagree.

   | Order | Source | Answers |
   |---|---|---|
   | 1 | `contracts/` (`openapi.yaml`, `events/`, `schema/`, `ai/socratic_system_prompt.txt`, `naming_rules.md`) | Every route, event, column, error code and the tutor's prompt |
   | 2 | `AGENTS.md` and the `rules/` file for the area | Invariants and decisions already made |
   | 3 | `design-system/design-system.md` (5.x components, 9.x screen layouts) | Every visual and wording choice |
   | 4 | `docs/PRD.md` (the FR the issue cites) | What the feature does for the teacher or learner |
   | 5 | `<team>/AGENTS.md`, `<team>/docs/TECH_SPEC.md` | File map, names, code patterns, known traps |

3. **Load the team skill:** `lara-hub-rust` (server), `lara-desktop-web` (desktop), `lara-android-offline` (mobile), and `lara-ai-runtime` for any AI issue.
4. **Search for existing code** that already does part of the job, and reuse its names.
5. **Stop and report** when a requirement is missing or two sources disagree. Write the open question in the issue or PR. Guessing is how sessions drift apart.

Done when you can name the routes or events you will touch, the files you will create, and the check that proves each acceptance criterion.

## 2. While you build

* **Clients build against the mock hub** (`python3 scripts/mock_hub.py`; seed accounts in `AGENTS.md` section 1.2). **The server matches it:** `scripts/mock_hub.py` and `tests/test_mock_hub.py` are the acceptance reference.
* **Contract first.** A route, event, column or prompt wording that is not in `contracts/` does not exist. Ask the Lead for a `contract-change` PR; never add it in a feature PR.
* **Stay in your team folder.** `contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/` and `AGENTS.md` are Lead-owned. Reports are the exception: new files in `docs/benchmarks/`, `docs/audits/`, `docs/evaluations/`.
* **Use the words the product uses:** "learner" (not pupil or student in prose), "Hub" or "classroom network" (never "internet"), and the classroom words in design system section 7.2.

## 3. What gets a PR rejected

Full list: `rules/team-workflow-and-prs.md` section 5. The ones agents trip on most:

| Invariant | The rule | Where |
|---|---|---|
| Zero internet | Everything is bundled or served by the Hub: fonts, icons, model, installers | `rules/networking-and-lan.md` |
| Offline first | Every screen reads local SQLite and has offline, empty and loading states; offline work is `QUEUED_FOR_SYNC` | `rules/database-and-sync.md` section 6 |
| Sync | The cursor is the integer `seq`; apply a pull and store `next_cursor` in one transaction; `SYNCED` only from a receipt | `rules/database-and-sync.md` section 3 |
| Secrets | A client never receives `pin_hash`, another person's LRN, `correct_answer` or a server file path | `rules/database-and-sync.md` section 2 |
| Quiz lockout | With an `IN_PROGRESS` attempt the chat UI is not composed and the Hub answers `QUIZ_IN_PROGRESS` | `rules/quiz-and-anti-cheat.md` |
| Socratic tutor | One prompt file, loaded unchanged; grounded in `material_chunks`; one clue then one question | `rules/socratic-ai-guardrails.md` |
| Budget phones | Heap under 250 MB; a model loads on a phone only at 6 GB RAM or more | `mobile/AGENTS.md` |
| Accessibility | Targets 52dp (56dp primary and quiz options); English and Filipino for every string; tokens only | `rules/ui-and-accessibility.md` |
| Data safety | Deactivate or archive; users, classrooms and graded rows are never deleted | `rules/database-and-sync.md` section 4 |

## 4. Before you open the PR

1. Run your team's verify command and the repo checks, and paste the output:

   | Team | Command |
   |---|---|
   | Server | `cargo check --all-targets && cargo test` in `server/backend/` |
   | Desktop | `npm run build` in `desktop/` |
   | Mobile | `./gradlew test lint` in `mobile/` |
   | All | `python3 scripts/verify_invariants.py` and `python3 -m unittest discover tests` from the root |

2. Walk every acceptance criterion in the issue and show the evidence for each: test output, or a log or screenshot against the mock hub with the Hub stopped for the offline case.
3. Update `<team>/docs/` (purpose, key files, data flow, gotchas), and `TECH_SPEC.md` if a decision or name changed.
4. Fill in `.github/PULL_REQUEST_TEMPLATE.md` with `Closes #n`, and say plainly what you could not verify (a real phone, real Wi-Fi, load).

Done when every acceptance criterion has evidence next to it or is listed as not verified with the reason.
