# What A Good Tech Spec Looks Like

For the developers writing a team Tech Spec, for the Lead reviewing it, and for the AI agents that will read it as context every time they write code. Fill in [`TECH_SPEC_TEMPLATE.md`](./TECH_SPEC_TEMPLATE.md) (it is copied over `<team>/docs/TECH_SPEC.md`), then check it against this page before asking for review.

## Why this matters more with AI

Your team will use AI agents to write most of the code. An agent starts every session knowing nothing about your decisions. It reads `AGENTS.md`, the code and your Tech Spec, and builds what they imply. So the spec is not paperwork: **it is the instruction manual for every agent session**. A vague spec makes an agent guess, and different sessions guess differently, so the code drifts.

A good spec for humans and a good spec for agents are the same thing, taken a little further:

| Humans can cope with... | Agents need... |
|---|---|
| "Use a clean architecture" | The exact folders, and what may import what |
| "Handle errors properly" | Which error, which screen state, which retry rule |
| "We talked about this in standup" | The decision written down, with the reason |
| "You know what I mean" | A name for every module, used the same way everywhere |
| "Looks fine to me" | A command that passes or fails |

## The ten checks (the Lead reviews against these)

1. **It cites, it does not copy.** Every behavior links to `docs/PRD.md` (FR-x.x) or `contracts/`. No pasted route lists or column lists (they go stale). Nothing in the spec contradicts a contract. If you think a contract is wrong, write it under Open Questions.
2. **Every decision has a reason.** Library, pattern, storage choice: one line each in the Decisions table with the alternative you rejected. Agents then stop re-opening them, and the Lead can challenge the reasoning.
3. **Concrete file map.** The folder tree you will create, with one line per folder saying what lives there and what must not. An agent places new code by this map.
4. **One name per thing.** Pick the module and type names here (for example `SyncRepository`, `QuizSessionManager`) and use them identically in issues, PRs and code. Never two names for one idea.
5. **Contract usage is a complete table.** Each route and event this team touches, the module that handles it, and what happens on each documented error code, on timeout and when the Hub is unreachable.
6. **Offline and sync are explicit.** What is stored locally, the `sync_status` lifecycle, the cursor and transaction rule from `rules/database-and-sync.md`, and what each screen or service does with no Hub. "Handled gracefully" is not an answer.
7. **Parallel PRs share no files.** Any developer can take any issue, so the file map says which issue owns each file. Anything several PRs need (a shared interface, a DB entity) is assigned to one issue, and the others depend on it or use a stub.
8. **Every sprint item is testable.** Each deliverable has a command or a manual check that proves it, against the mock hub first. If you cannot say how you would prove it, it is not specified yet.
9. **Risks have an owner and a date.** Spikes (weak-phone memory, Tauri sidecar, Rust SSE) are scheduled, not "to be investigated".
10. **Non-goals are written.** What this team is deliberately not building, so an agent does not helpfully add it.

## Writing it so an AI can use it

* **Keep it short and stable.** Aim for 4 to 8 pages. Agents lose accuracy on long, repetitive documents. Link to a deeper file in `<team>/docs/` rather than growing this one.
* **Use the template headings unchanged.** Agents and scripts look up sections by name.
* **Prefer tables, lists and code blocks to prose.** Paths, names and commands go in backticks so they can be copied exactly.
* **Say "must" and "must not".** Not "should probably" or "ideally".
* **Give an example for any non-obvious pattern.** A 10-line sample (a repository method, an Axum handler, a Compose screen state) beats a paragraph, and agents copy the pattern.
* **Put the exact commands in.** Build, test, run against the mock hub. An agent runs them to prove its work.
* **Name the traps.** The gotchas you already know (Transsion battery killers, `MulticastLock`, SQLx migrations without PRAGMAs) belong in the Pitfalls section, because an agent will not know them otherwise.
* **Date it and keep it current.** When a decision changes, change the spec in the same PR as the code. A stale spec is worse than none, because agents trust it.
* **Do not put secrets or real pupil data in it.** Use the seed accounts from `AGENTS.md`.

## Reviewing a spec with an AI

Before you ask the Lead for review, run your own agent over the draft with this prompt. Fix what it finds.

```text
Read AGENTS.md, <team>/AGENTS.md, contracts/ and docs/templates/TECH_SPEC_GUIDE.md.
Then review <team>/docs/TECH_SPEC.md against the ten checks.
List: (1) anything that contradicts a contract or rule, (2) any decision without a
reason, (3) any file that two parallel PRs would edit, (4) any sprint item with
no way to prove it, (5) anything an agent would have to guess. Do not rewrite it.
```

## What the Lead checks at approval

* Ten checks above all pass, and the invariants checklist at the bottom of the template is ticked honestly.
* The first two sprints are specified in detail. Later sprints can be rougher, but must say which decisions are still open.
* Approval means "build on this". It is recorded by changing the Status line to `Approved` in the same PR.
