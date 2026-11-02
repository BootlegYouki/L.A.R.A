# MOBILE 2.7 validation — rebased for PR #209

Base: staging `30d19a4`, including merged shared fixes #210 and #211.

- Targeted `TeacherClassMockTest`: passed (`removal-regression.txt`).
- Full mock-enabled Debug and Release suites: 141 tests each, zero failures/skips (`full-tests.txt`). All three mock flags were set: `LARA_SYNC_MOCK=1`, `LARA_APPROVAL_MOCK=1`, `LARA_SETTINGS_MOCK=1`.
- Shared suite: 66 tests passed (`shared-checks.txt`).
- Offline/localization invariants passed (`invariants.txt`).
- Build/lint output is included in `full-tests.txt`; existing warnings are disclosed in the PR.

The previous removal failure is resolved and its regression remains enabled. The obsolete `tests-known-failure.txt` was removed. Own non-active enrollments without an accessible parent classroom are saved as notices until an authoritative classroom row is available; existing REST approval/reopen coverage passes.

## Phone evidence and limits

`phone-checks.md` and screenshots record the earlier physical Xiaomi normal-entry tests in separate package `org.lara.app.sprint27classes`. The installed production app/data were untouched, and temporary text-scale/USB settings were restored. A final phone pass on the rebased build is pending the Lead's review. Real Rust Hub, WAN-disconnected classroom-router testing and budget-device certification remain integration coverage. The memory sample is one foreground observation, not hardware certification.

## Supported Drive UI adaptation

Rounded class/People cards, initials avatars, a light-green code panel, grouped settings, rounded fields and create review use existing design tokens and bundled Phosphor icons. No remote artwork or unsupported class operations were added. Historical `ui-*.txt` outputs document initial UI work; the rebased outputs above supersede the earlier test status.
