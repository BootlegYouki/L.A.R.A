---
name: Design Proposal
about: You think the plan is wrong, missing something, or could be better. Anyone, human or agent, may open one. Process in rules/team-workflow-and-prs.md section 1.2.
title: '[PROPOSAL] '
labels: 'type:proposal'
assignees: ''
---

Keep building by the current plan while this is open. Do not change the plan inside a feature PR.

## 1. The problem, with evidence
What did you hit or notice? A failing case, a measurement, a prototype or a concrete classroom scenario. "It feels wrong" is not evidence.

## 2. What the plan says today
Cite the file and section (PRD `FR-x.x`, a contract route, a rule, a design-system section, a sprint issue).

## 3. What you propose
One change. Say how a teacher or learner would see the difference.

## 4. What it touches
- [ ] `contracts/` (and the mock hub and tests)
- [ ] `AGENTS.md` or `rules/` (an invariant in `AGENTS.md` section 3? say which and why)
- [ ] `design-system/`
- [ ] `docs/PRD.md`
- [ ] Sprint issues or order (list the step names, for example [SERVER 4.2])

## 5. Cost, risk and the offline check
Effort, which teams, what could break. Does it still work with no internet, on a 3 to 4 GB phone, and with the quiz lockout and children's data rules intact?

## 6. If we do nothing
What is the cost of keeping the plan as it is?

---
For the Lead: accepted, accepted with changes, deferred (when) or declined, with the reason. Only an accepted proposal starts work, and its documents change first.
