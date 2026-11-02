# MOBILE 2.7 checkpoint — 2026-11-02

This is not a completion claim or a ready PR.

- APK and lint: BUILD SUCCESSFUL; zero lint errors, 27 existing warnings (build-lint.txt).
- Final full mock-enabled Debug suite: 141 tests, 140 passed, one failure, zero skips (tests-known-failure.txt). The archive visibility/read-only regression passes.
- Shared source: 66 tests passed (shared-checks.txt).
- Offline/localization guardrails passed (invariants.txt).

The failing TeacherClassMockTest reproduces a shared mock sync visibility defect: create a class, enroll/approve a synthetic learner, pull, remove the learner, pull again. The Hub's REST class list no longer includes the class, but the cached enrollment remains ACTIVE. scripts/mock_hub.py sync_pull filters ledger entries by currently visible class IDs, so the learner cannot receive their REMOVED enrollment after losing visibility. That Lead-owned file was not edited. The regression remains enabled.

Physical-phone checks now passed in `org.lara.app.sprint27classes`: create/review, Android PATCH rename/archive, code clipboard round trip, add/remove co-teacher, co-teacher restrictions, learner removal cancel/confirm, English/Filipino, 200% review/offline layout and archived-class cold launch with Hub routes removed. See `phone-checks.md` and the screenshots. Text scale and USB routes were restored. Real Rust Hub, WAN-disconnected classroom router and budget hardware remain integration coverage. No real accounts/data or the normal installed phone app were changed.

## Supported Drive UI adaptation

The class-management concepts were adapted into rounded class/People cards, initials avatars, a light-green code panel, grouped settings, rounded form fields and a create-class review step. Only supported fields and actions were used; repository design tokens and bundled Phosphor icons remain unchanged. No mockup image or remote artwork is rendered by the app.

`ui-build-lint.txt` records the initial adapted APK/lint build. `ui-targeted-tests.txt` records the four initial passing permission/API/transport regressions. `build-lint.txt` and `phone-checks.md` record the final mobile fixes, including white dialogs, cached teacher names and readable archived classes. The existing full-suite removal-sync failure above remains unresolved; targeted tests and phone results do not replace it. The shared fix is deferred at the user's request and its regression remains enabled.
