---
name: Team Task (sprint work item)
about: A single-team work item for a sprint milestone. One issue = one team = one PR.
title: '[SERVER|DESKTOP|MOBILE|QA n.n] '
labels: 'type:feature'
assignees: ''
---

### Objective
What this delivers and why, in two or three sentences.

### Ownership
- **Team:** SERVER | DESKTOP | MOBILE | QA  |  **Sprint:** n
- **Depends on:** step codes such as `[SERVER 2.0]` (or "none")
- **PRD:** the requirement this delivers, for example `docs/PRD.md` FR-3.5 (behavior is defined there, not here)
- One issue = one PR. The team works its issues one at a time, in step order. Do not edit another team's folder; shared paths (`contracts/`, `design-system/`, `rules/`) are Lead-owned.

### Contract & Mock Hub
- **Contract:** routes, events and tables from `contracts/` this issue implements or uses.
- **Mock hub:** which `scripts/mock_hub.py` flow to build against (clients) or to match (server).
- Needs a new route, event or column? Open a separate `contract-change` request first.

### Sub-Tasks & Implementation Checklist
- [ ] ...

### Target Files
- `...`

### Acceptance Criteria
Each line is something a reviewer can see pass or fail.
- [ ] ...
- [ ] The team's verify command passes and its output is in the PR (`cargo check && cargo test`, `npm run build`, or `./gradlew test lint`)
- [ ] Works with the Hub unreachable (offline state defined)
- [ ] English and Filipino strings, design tokens only, touch targets at least 52dp (56dp primary)

### Docs & Design
- Update the team's `docs/` folder (purpose, key files, data flow, gotchas).
- **Design system:** the component sections (5.x) and, for a screen, the layout section (9.x) of `design-system/design-system.md` (or N/A).
- PR evidence: build or test output plus a log or screenshot against the mock hub, WAN unplugged.
