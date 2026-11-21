# Announcement, 21 Nov 2026: two contracts merged, and what changed in how we work

For the server, desktop and mobile developers and their AI agents. Read this before you start any work after the 23 Nov defense. Nothing here asks for code before the defense.

## 1. What was merged into `staging` today

### Hub activity history (PR #370, issues #366 #367 #368)
- New contract: `contracts/activity_logs.md`, `GET /api/admin/activity-logs` in `contracts/openapi.yaml`, and two Hub-only tables `hub_activity_events` and `hub_activity_state` in `contracts/schema/server_master.sql`.
- Admin-only and Hub-PC loopback-only (tunnel or forwarded requests are denied). Seven days of operational events, 20,000 rows maximum, fixed event codes, no free text, no names, identities, IPs or paths. Read it in full before you implement: the numeric limits, the pagination, the `ACTIVITY_LOG_RESET` 409 and the event catalog are in `contracts/activity_logs.md`.
- Nothing is implemented yet. **Server:** [SERVER] issue #368 builds the recorder, migration and the three-page console (Overview, Accounts, Activity logs). Do not start it before the defense. Mobile and desktop have no work here.
- Note for #368: the event catalog still lists `DOCUMENT_PROCESSED` ("extraction completed"). If document parsing moves to the devices (see section 3) that event is dropped; raise it in the contract PR then.

### Learners manage their own Stream posts (PR #388)
- Active learners may post announcements to their class Stream, edit or delete their own, and add, rename, replace or remove their own attachments. Class teachers may delete any announcement and moderate its comments. Learner attachments are never used as lesson text for the tutor.
- Contract: new nullable `announcements.author_id` (authenticated author UUID; NULL for legacy teacher posts) in both `server_master.sql` and `client_offline.sql`; learner rules on `POST /api/materials` and the announcement routes; `EVENT_ANNOUNCEMENT_PUSH` now means an announcement or its class comments changed. PRD FR-2.1/FR-2.4 and the design-system notes were updated in the same PR.
- Implementation is post-defense: **Server** (migration for `author_id`, ownership checks, tombstones on delete, learner upload rules), **Mobile** (Room migration and UI), **Desktop** (UI).

## 2. Expected red CI on `staging`: read this
Both contracts changed the SQL schemas before the code did (contract first). So these tests fail on `staging` until the implementing PRs land:
- **Server Local Hub CI:** `db::tests::migrations_reproduce_the_server_master_contract` (needs a new migration for `announcements.author_id` and the two activity tables).
- **Android Mobile CI:** `SchemaParityTest` (the Room schema must match `client_offline.sql`, including `announcements.author_id`).
Do not "fix" these by weakening or skipping the parity tests, and do not edit an applied migration. Add a new migration (server) or a Room migration with a version bump (mobile) in the issue that owns the change. Applied migrations and their checksums never change.

## 3. Other decisions of 21 Nov that your agents must know
- **Contracts are team-owned (PR #374, #389):** any member may change `contracts/`, `docs/PRD.md`, `design-system/`, `scripts/` and `tests/` inside their own `contract-change` PR, never inside a feature PR, only for what that change needs. Additive change: each consuming team says OK in the PR; breaking change: all three teams. Still lead-owned: `rules/`, `.github/`, `contracts/ai/`, AGENTS.md/CLAUDE.md. A contract change may not alter an AGENTS.md section 1.4 decision or section 3 invariant (that is a Design Proposal).
- **Accounts (PR #386):** the Hub admin creates student and teacher accounts; a teacher creates student accounts only; nobody self-registers. Desktop registration was removed (#385). Server #381 and mobile #382 follow after the defense, behind a contract-change PR (removal of `POST /api/auth/register`, the teacher-created-student route, bulk creation, the open questions in AGENTS.md 1.4).
- **Mock Hub:** removal is postponed until after the defense (PR #344 was closed; its branch `contract/remove-mock-hub` is kept). Until then keep testing against the real Hub (`server/backend`, `LARA_TEST_SEED=1`) as in the server docs; do not extend `scripts/mock_hub.py` further than a contract change needs.
- **Tutor direction (decided by the Lead on 21 Nov, now in AGENTS.md sections 1.4 and 5):** the model file is stored on the Hub and each phone or desktop downloads it over the classroom network (HTTP `:8080`, Range) and runs it fully offline on the device. The Hub does no inference: no `llama-server`, no AI queue, no queue status, no tutor token stream. The route, error code, minimum device RAM and the offline quiz lockout are to be defined in a later contract-change PR. Where documents are parsed into chunks is not decided in the docs yet (staging currently chunks PDF/DOCX uploads on the Hub); do not move it. The earlier "no AI on the Hub" draft PR #359 was closed as superseded. Start no AI work before the defense.
- **6-digit PIN** is in force on the Hub, mobile and desktop.

## 4. If you are an AI agent
Read AGENTS.md section 1.3, `contracts/activity_logs.md`, the merged contract PRs #370 and #388 and the issues they name, then your own issue. When a document disagrees with this note, the contract and AGENTS.md win; raise the conflict in the issue instead of guessing.
