# Desktop Technical Spec

> Owner: TBD  |  Reviewer: Lead Developer  |  Status: Draft (not yet started)  |  Last updated: TBD
> This is not a PRD. Product behavior lives in `docs/PRD.md` and `contracts/`. This document says how this team will build its part. Never redefine behavior here; link to the source instead.
> AI agents read this file as context in every session. Read [`TECH_SPEC_GUIDE.md`](../../docs/templates/TECH_SPEC_GUIDE.md) before filling it in. Keep the headings unchanged, use exact names and paths in backticks, and write "must" or "must not".

## 1. Scope and Non-Goals
**In scope:** which PRD modules (FR-x.x) and GitHub issues this team owns, per sprint.
**Non-goals:** what this team is deliberately not building (so nobody adds it).

## 2. Architecture
Layers, modules and how they depend on each other. One diagram. Name the entry points. List the rule for what may import what (for example "screens never touch the database directly").

## 3. Key Decisions
| Decision | Choice | Why | Rejected alternative |
|---|---|---|---|
One row per library, pattern or storage choice. Agents treat these as settled.

## 4. File Map
The folder tree this team will create, one line per folder: what lives there and what must not.
```text
<folder>/        what it holds
```

## 5. Names (one name per thing)
| Name | Kind (module, class, table, event) | Responsibility |
|---|---|---|
Use these exact names in issues, PRs and code.

## 6. Contract Usage
| Contract file and route or event | Our module | On error codes | On timeout or Hub unreachable |
|---|---|---|---|
List every `contracts/openapi.yaml` route and `contracts/events/*` event this team touches. Link, do not copy.

## 7. Data and Offline Behavior
Local storage layout, the `sync_status` lifecycle, cursor and transaction handling (`rules/database-and-sync.md`), and what each screen or service does when the Hub is unreachable.

## 8. Code Patterns
A short worked example (about 10 lines) for each non-obvious pattern: a repository method, a request handler, a screen state, an offline write. Agents copy these.

## 9. Pitfalls
Known traps for this stack and hardware, each with the fix. Example: "Transsion phones kill background work: use WorkManager."

## 10. Build Order
| Step | Issue | Builds (folders/files) | Needs first |
|---|---|---|---|
The team works one issue at a time, in step order, with one open PR (`rules/team-workflow-and-prs.md` section 1.1). List every issue of Sprints 1 and 2 in the order you will build them, what each one creates, and which earlier step it needs. Anything a later step reuses (a shared interface, a DB entity) is named here once, in the step that creates it.

## 11. Dependencies on Other Teams
What you need from the other two teams and when. Default is: use `scripts/mock_hub.py` until the real endpoint merges.

## 12. Risks and Unknowns
| Risk or spike | Owner | Decision date | Fallback |
|---|---|---|---|

## 13. Commands and Verification
Exact commands to build, test and run against the mock hub (agents run these to prove their work). Then: what is checked by unit tests, by integration against the mock hub, and what is proven on integration day against the real Hub.
For each Sprint 1 and Sprint 2 deliverable, the check that proves it.

## 14. Open Questions
Anything unclear or in conflict with a contract or the PRD. Write it here and raise it with the Lead; never guess.

## 15. Invariants Checklist
- [ ] No internet or CDN dependency (fonts and icons come from `design-system/assets/`)
- [ ] Offline state defined for every screen
- [ ] English and Filipino strings for all UI text
- [ ] Touch targets at least 52dp (56dp primary and quiz options)
- [ ] `correct_answer` never stored on a client
- [ ] AI unavailable during an active quiz
