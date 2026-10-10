# Project Skills

Skills the AI agents on this project can load. Only skills that this project needs are kept here. Claude Code does not load this folder by itself: link the ones you want into `~/.claude/skills/` (for example `ln -s "$PWD/.agents/skills/tauri-v2" ~/.claude/skills/tauri-v2`).

| Skill | For | What it gives the agent |
|---|---|---|
| [`lara-architect`](./lara-architect/SKILL.md) | All developers | L.A.R.A invariants, protocols, schemas, guardrails |
| [`android-jetpack-compose`](./android-jetpack-compose/SKILL.md) | Mobile | Compose state and UI patterns |
| [`tauri-v2`](./tauri-v2/SKILL.md) | Desktop, Server window | Tauri 2 config, Rust commands, IPC, permissions |
| [`rust-skills`](./rust-skills/SKILL.md) | Server (also Tauri Rust code) | 265 rules for idiomatic, safe, fast Rust: ownership, errors, async, serde, testing |

Where a skill and a project rule disagree, `AGENTS.md` and `contracts/` win (see `AGENTS.md` section 1.1).

## Third-party skills

| Skill | Source | License | Version |
|---|---|---|---|
| `rust-skills` | https://github.com/leonardomso/rust-skills | MIT (`rust-skills/LICENSE`) | commit `fd2a861`; `checks/` scripts not copied |
| `android-jetpack-compose`, `tauri-v2` | community skills | see each folder | as committed |

## Rules for adding a skill

A skill is instructions an agent follows, so treat it like code that runs on every developer's machine.

1. **Need:** it covers something the three teams actually use (Kotlin and Compose, Tauri 2 and React, Rust and SQLx). No general-purpose or meta skills.
2. **Read all of it** before adding. Reject or edit anything that conflicts with the invariants: CDNs, Google Fonts, Firebase or Play Services, Material Symbols (we use Phosphor), dynamic color or gradients, a download or sync step that needs the internet.
3. **Check the license** and keep the `LICENSE` file with it. No scripts that fetch or run remote code.
4. **Record the source** in the table above (URL, license, version).
5. Add it in its own PR.

## Removed, and why

* `material-3`: its examples load Google Fonts and Material Symbols from a CDN and push dynamic color, which breaks the zero-internet, Phosphor and tokens-only rules. The design system in `design-system/` replaces it.
* `find-skills`, `skill-creator`: general tools for managing skills, not for building L.A.R.A. They can stay in your personal `~/.claude/skills/`.
* Considered and not added: a Tailwind 4 docs skill (needs internet and has a restrictive license); Next.js-oriented React skills (the desktop app is Vite, not Next.js).
