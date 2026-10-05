---
name: Feature Request
about: Propose a new capability. The Lead triages it into a single-team sprint issue.
title: '[FEAT] '
labels: 'type:feature'
assignees: ''
---

## 1. Who needs it and why
Which user (pupil Grade 1 to 6, teacher, admin) and what classroom problem does it solve? Link the PRD requirement (`FR-x.x`) if one exists.

## 2. Affected application(s)
- [ ] `mobile/` (Android)
- [ ] `desktop/` (Tauri)
- [ ] `server/` (Local Hub)
- [ ] `contracts/` (new route, event or column: becomes a separate `contract-change` PR first)

## 3. Offline LAN considerations
- What happens with the Hub unreachable (home study mode)? Does anything need `QUEUED_FOR_SYNC`?
- Any memory or CPU impact on 3 to 4 GB budget phones, or on the Hub with 40 devices?
- Does it touch the AI tutor or quizzes? (Quiz lockout must still hold.)
- Does it handle children's personal data (LRN, names, photos)?

## 4. Proposed solution and workflow
Step by step: how does the teacher or pupil use it?

## 5. Acceptance criteria
- [ ] Works 100% offline on the classroom network with zero internet
- [ ] Uses design-system tokens, touch targets at least 52dp (56dp primary)
- [ ] English and Filipino text
