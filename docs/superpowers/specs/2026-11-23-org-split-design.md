# Org split: four repos, no gatekeeping

## Why
Teams wait on each other's PRs and on the Lead. The Lead does not want to micro-manage capable developers. Goal: each team ships on its own, and its agent still knows what the other teams are doing, and files an issue in their repo instead of waiting.

## Goals
- Four repos in the GitHub org `L-A-R-A-National-High-School` (empty today, owner is admin): `lara-server`, `lara-desktop`, `lara-mobile`, `lara-context`.
- Every agent can read the other repos and file an issue in them.
- Every app repo's `AGENTS.md` (with `CLAUDE.md` as a symlink to it) always sends the agent to `lara-context`.

## Non-goals / anti-goals
- No CI, no required reviews, no branch protection. Developers check their own work.
- No design system. Each team owns its UI.
- No history migration. No rules or docs ported into `lara-context` yet; the project may change drastically.
- An agent never edits another team's repo, only files an issue there.

## Approaches considered
- A: transfer the old repo into the org as `lara-context`, `filter-repo` the app folders out (keeps issue/PR numbers and history).
- **B (chosen): fresh repos, no history.** The Lead's reason: the developers file new issues from now on, and the shared repo starts blank. Old repo `BootlegYouki/L.A.R.A` stays as a read-only reference.
- C: filter-repo everything, archive the old repo.

## Design
**Repos (fresh `git init`, current code snapshot, single initial commit):**
- `lara-server`: `server/` becomes the root. Also owns `contracts/`, `scripts/mock_hub.py` and the contract tests (my call: the server is the API provider, so a client agent that needs a route files an issue here and the server team edits the contract in its own PR).
- `lara-desktop`: `desktop/` becomes the root.
- `lara-mobile`: `mobile/` becomes the root.
- `lara-context`: nearly blank. A README saying what it is, plus one empty folder per team (`server/`, `desktop/`, `mobile/`) and `shared/` for anything that spans teams. Each folder has a one-line README. Teams put their own docs in their folder (any team may write in `shared/`), and reference them from issues by link, so an issue stays short and the doc stays findable. The folders are the only structure; teams add what they need inside them.

**Per app repo:**
- Slim `AGENTS.md`: what L.A.R.A is in a few lines, the four-repo map, "read `lara-context` first", "scan siblings before building", "file issues, never edit other repos". The existing 216-line root file and the nested one are not copied. `CLAUDE.md` is a symlink to it.
- Skill `lara-context` (in `.agents/skills/`, same format as today): how to find the shared repo and siblings (look for a sibling clone `../lara-*`, else `gh repo clone` into a scratch folder), what to read first, how to file a cross-repo issue with `gh issue create -R L-A-R-A-National-High-School/<repo>`, and an issue template (what I need, why, which route/screen, what I already checked).
- A SessionStart hook in each app repo's `.claude/settings.json` runs one small script: it finds or clones `lara-context` and the sibling repos, fast-forwards them (`git pull --ff-only`), and prints a few lines: what changed recently in `lara-context`, and the open issues in this repo that another team filed. The `lara-context` skill tells the agent to read `lara-context` whenever the task touches another team or a doc may exist. If the script fails (no network, no clone), it prints one line and the session carries on; a hook must never block work. My call: the hook keeps things fresh, the skill carries the instructions.
- Existing team skills move with their repo (`lara-hub-rust`, `rust-skills`, `lara-ai-runtime` to server; `lara-desktop`, `lara-desktop-web`, `tauri-v2` to desktop; `lara-mobile`, `lara-android-offline`, `android-jetpack-compose` to mobile). `lara-architect` is dropped, since it assumes the old monorepo.
- Design system (revised 2026-11-23): not dropped. `design-system/` is copied unmodified into `lara-context/shared/design-system/` with a README saying it is a draft the teams are revising and not authoritative. The Lead will update it later; nobody edits it as part of the split. The design-token and prototype tests are not carried over (they would fail without CI to run them anyway).
- Dropped: `.github/workflows`, PR template, CODEOWNERS, `verify_invariants.py`, the design-token and prototype tests. Issue templates are kept as one simple template per repo.

**Old repo and loose ends (each confirmed with the Lead before I do it):**
- `BootlegYouki/L.A.R.A` is archived, not deleted.
- The 3 open issues (#430, #424, #422) are re-filed in the right new repo.
- The `lara-watch.service` GitHub watcher is repointed to the new repos.

## Left to the builder
Exact wording of the slim `AGENTS.md`, the issue template fields, repo descriptions, default branch name (`main`).

## Risks
- Dropping the old invariants (offline-only, children's data, quiz lockout) from the agents' instructions means nothing reminds them. Mitigation if you want it: one short "never break" paragraph in each `AGENTS.md`. I left it out because you want the shared context blank.
- Nothing stops a bad push to `main` without CI or protection. That is the intended trade.
