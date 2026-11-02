# MOBILE 2.7 checkpoint — 2026-11-02

This is not a completion claim or a ready PR.

- APK and lint: BUILD SUCCESSFUL; zero lint errors, 27 existing warnings (build-lint.txt).
- Full mock-enabled Debug suite: 140 tests, 139 passed, one failure, zero skips (tests-known-failure.txt).
- Shared source: 66 tests passed (shared-checks.txt).
- Offline/localization guardrails passed (invariants.txt).

The failing TeacherClassMockTest reproduces a shared mock sync visibility defect: create a class, enroll/approve a synthetic learner, pull, remove the learner, pull again. The Hub's REST class list no longer includes the class, but the cached enrollment remains ACTIVE. scripts/mock_hub.py sync_pull filters ledger entries by currently visible class IDs, so the learner cannot receive their REMOVED enrollment after losing visibility. That Lead-owned file was not edited. The regression remains enabled.

Device/emulator checks still needed: Android PATCH rename/archive, code clipboard, co-teacher navigation and large-text/visual checks. Real Rust Hub, WAN-disconnected classroom router and budget hardware remain integration coverage. No real accounts/data or the normal installed phone app were changed.

## Supported Drive UI adaptation

The class-management concepts were adapted into rounded class/People cards, initials avatars, a light-green code panel, grouped settings, rounded form fields and a create-class review step. Only supported fields and actions were used; repository design tokens and bundled Phosphor icons remain unchanged. No mockup image or remote artwork is rendered by the app.

`ui-build-lint.txt` records the adapted APK/lint build. `ui-targeted-tests.txt` records the four passing permission/API/transport regressions. The existing full-suite removal-sync failure above remains unresolved; targeted tests do not replace it. No phone was connected, so these are compilation/test results, not visual phone evidence.
