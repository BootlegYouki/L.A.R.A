# Class enrollment preparation - MOBILE 2.3 (#50)

## Current scope

This branch starts from Mobile 2.2 commit `90a4c1f`, including Mobile 1.1, 1.2, 1.3 and 2.1. Mobile 2.2 implementation and its address-change review fix pass CI; PR #188 still awaits Lead approval/merge.

The independent preparation consists of:

- `domain/enrollment/ClassCode.kt`: six-character validation, uppercase normalization, removal of the display hyphen before submission, and XXX-XXX presentation. O, I, 0 and 1 are rejected.
- `ui/screens/enrollment/JoinClassDialog.kt`: bilingual Material 3 dialog with a grouped code field, invalid-code feedback, nonblocking offline warning, busy state, and 56dp primary action. Its owner supplies request/reachability state and the submit callback.
- `ClassCodeTest.kt`: request normalization, partial-code presentation and malformed-code coverage.

The dialog is not wired into AuthHost or the learner shell yet. No pretend enrollment, fabricated ID, WebSocket connection, sync cursor, or network write is created by this preparation.

## Dependency and next steps

The technical spec section 10 orders Mobile 2.5 before 2.3. POST /api/classrooms/join returns status and classroom_id; GET /api/classrooms and EVENT_JOIN_APPROVAL likewise do not provide the authoritative enrollment ID required by Room. The sync pull supplies that enrollment row, and its cursor/data transaction belongs to SyncRepository.

Recommended delivery sequence: merge 2.2, implement/test/merge 2.5, then finish 2.3 against the merged sync layer. Do not open a 2.3 feature PR claiming live enrollment is complete from these presentation components.

Remaining #50 work: bearer-authenticated join transport, pending/active class list read from Room, EVENT_HELLO and approval events with reconnect/backoff, sync refresh after event/reconnect, and mock-Hub/physical-device verification that teacher approval unlocks a class immediately and remains available offline.

## Preparation validation

`gradlew.bat test lint` passed on the preparation source (6m 2s in this fresh workspace). The three new class-code tests pass; full debug/release unit suites and lint pass. Output: `evidence/50/preparation-checks.txt`. No live joining, pending enrollment persistence, WebSocket approval, or physical-phone enrollment behavior is claimed by this check.
