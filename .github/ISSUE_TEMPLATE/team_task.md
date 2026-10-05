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
- **Team:** SERVER | DESKTOP | MOBILE | QA  |  **Slot:** Dev A | Dev B  |  **Sprint:** n
- **Depends on:** issue numbers or step codes (or "none")
- One issue = one PR. Do not edit another team's folder; shared paths (`contracts/`, `design-system/`, `rules/`) are Lead-owned.

### Contract & Mock Hub
- **Contract:** routes, events and tables from `contracts/` this issue implements or uses.
- **Mock hub:** which `scripts/mock_hub.py` flow to build against (clients) or to match (server).
- Needs a new route, event or column? Open a separate `contract-change` request first.

### Sub-Tasks & Implementation Checklist
- [ ] ...

### Target Files
- `...`

### Acceptance Criteria
- [ ] ...
- [ ] Works with the Hub unreachable (offline state defined)
- [ ] English and Filipino strings, design tokens only, touch targets at least 52dp (56dp primary)

### Docs & Design
- Update the team's `docs/` folder (purpose, key files, data flow, gotchas).
- **Design system:** relevant sections of `design-system/design-system.md` (or N/A).
- PR evidence: build or test output plus a log or screenshot against the mock hub, WAN unplugged.
