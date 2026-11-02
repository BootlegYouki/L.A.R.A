# MOBILE 2.7 checkpoint — 2026-11-02

This is not a completion claim or a ready PR.

- APK and lint: BUILD SUCCESSFUL; zero lint errors, 27 existing warnings (build-lint.txt).
- Full mock-enabled Debug suite: 140 tests, 139 passed, one failure, zero skips (tests-known-failure.txt).
- Shared source: 66 tests passed (shared-checks.txt).
- Offline/localization guardrails passed (invariants.txt).

The failing TeacherClassMockTest reproduces a shared mock sync visibility defect: create a class, enroll/approve a synthetic learner, pull, remove the learner, pull again. The Hub's REST class list no longer includes the class, but the cached enrollment remains ACTIVE. scripts/mock_hub.py sync_pull filters ledger entries by currently visible class IDs, so the learner cannot receive their REMOVED enrollment after losing visibility. That Lead-owned file was not edited. The regression remains enabled.

Device/emulator checks still needed: Android PATCH rename/archive, code clipboard, co-teacher navigation and large-text/visual checks. Real Rust Hub, WAN-disconnected classroom router and budget hardware remain integration coverage. No real accounts/data or the normal installed phone app were changed.
