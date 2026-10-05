# Server Technical Spec

> Owner: TBD  |  Reviewer: Lead Developer  |  Status: Draft (not yet started)
> This is not a PRD. Product behavior lives in `docs/PRD.md` and `contracts/`. This document says how this team will build its part. Never redefine behavior here; link to the source instead.

## 1. Scope
Which PRD modules (FR-x.x) and which GitHub issues this team owns, per sprint.

## 2. Architecture
Layers, modules and package or folder layout. One diagram. Name the entry points.

## 3. Contract Usage
| Contract file | Used for | Our module that consumes or implements it |
|---|---|---|
List every `contracts/openapi.yaml` route and `contracts/events/*` event this team touches, and how errors and retries are handled.

## 4. Data and Offline Behavior
Local storage layout, the `sync_status` lifecycle, and what each screen or service does when the Hub is unreachable.

## 5. Dev A / Dev B Work Split
| Slot | Owns (folders/files) | Sprint 1 | Sprint 2 | ... |
|---|---|---|---|---|
Two developers must not edit the same file in parallel.

## 6. Dependencies on Other Teams
What you need from the other two teams and when. Default is: use `scripts/mock_hub.py` until the real endpoint merges.

## 7. Risks and Unknowns
Hardware limits, library choices, spikes needed. Each with an owner and a decision date.

## 8. Test and Verification Plan
Unit, integration against the mock hub, and what is proven on integration day against the real Hub.

## 9. Invariants Checklist
- [ ] No internet or CDN dependency (fonts and icons come from `design-system/assets/`)
- [ ] Offline state defined for every screen
- [ ] English and Filipino strings for all UI text
- [ ] Touch targets at least 52dp (56dp primary and quiz options)
- [ ] `correct_answer` never stored on a client
- [ ] AI unavailable during an active quiz
