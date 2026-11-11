# Group 1 review checkpoint

This records the pre-publication review. The user subsequently approved creating the Group 1 PR; publication and refreshed-base checks are recorded in the PR body. Group 2 remains out of scope.

1. **Issue:** [#266 — MOBILE 3.1.2](https://github.com/BootlegYouki/L.A.R.A/issues/266). Partial work uses `Part of #266`.
2. **Prerequisite:** [#258](https://github.com/BootlegYouki/L.A.R.A/pull/258) is merged into staging. Merge `f8dfbfac44374e8dcd971bf02137d89f23a582f0` contains approved video revision `8f63a70`, including fullscreen, offline saves and Remove from device.
3. **Branch/base:** `feat/266-mobile-classroom-layout-part-1`, based on fetched staging `f8dfbfac`. Original worktree was clean. Other worktrees and the previous feature branch were preserved.
4. **Lead announcements:** Discussions [#242](https://github.com/BootlegYouki/L.A.R.A/discussions/242) (real-Hub evidence), [#238](https://github.com/BootlegYouki/L.A.R.A/discussions/238) (screen-state evidence and click-through), issue #266 (group order, existing screens only), #258 approval (deferred class-tab large-text issue). See the implementation notes for older superseded guidance.
5. **Ponytail:** installed 5.3.3 skill inspected; full mode active. Architect and Android/offline skills read. No `lara-mobile` skill exists; none was created or changed.
6. **Screen inventory:** [Complete implemented-surface inventory](classroom-layout-group1.md#existing-screen-inventory-and-implementation-matrix), including local modes/dialogs, state owners, actions, state handling, tests and all eight groups. To-do, posting editors and dedicated Archive are not implemented destinations.
7. **Reference inventory:** Student 4, 5, 29 and Teacher 5, 7 opened. [Reference/evidence index](evidence/266-group-1/README.md). Teacher 7 actually depicts Stream; Group 3 retains its scope.
8. **Ownership:** no open PRs or assigned open issues for Nash172/Osbornejmb were returned; #266 had no assignees or comments. No visible local ownership conflict. Unpublished work on another computer cannot be established from GitHub.
9. **Shared components:** added `ClassHomeScaffold` and its small wrapping drawer-row component; reused `ClassroomCard`, `ClassroomTopBar`, banners, existing forms and token theme. No dependency added.
10. **Screens changed:** Learner Home/class list, Teacher Home/class list, shared class cards, Home drawer and plus sheet. Existing Settings and class-management destinations were rewired, not redesigned. Other screen groups are untouched.
11. **Flow:** Home menu opens Classes, cached classes and Settings; teachers also get Manage classes. Plus opens the role's permitted action in a bottom sheet. Create opens its existing form directly. Class entry/back, settings/back and form cancel were exercised.
12. **Preserved workflows:** existing session/role checks, Room flows, sync lifecycle, enrollment eligibility and pending notices, teacher approval entry, class management and archived records. No repository, transport, database or video behavior changed. Offline cached class entry was tested for both roles.
13. **Before screenshots:** [Learner Home](evidence/266-group-1/before/learner-home.png), [Teacher Home](evidence/266-group-1/before/teacher-home.png), [Learner Settings](evidence/266-group-1/before/learner-settings.png), [Teacher Settings](evidence/266-group-1/before/teacher-settings.png). Captured before production edits on the unchanged staging APK.
14. **After screenshots:** [Learner Home](evidence/266-group-1/after/final-learner-home.png), [Teacher Home](evidence/266-group-1/after/final-teacher-home.png), [Learner drawer](evidence/266-group-1/after/final-learner-drawer.png), [Teacher drawer](evidence/266-group-1/after/final-teacher-drawer.png), [Learner plus](evidence/266-group-1/after/final-learner-plus-sheet.png), [Teacher plus](evidence/266-group-1/after/final-teacher-plus-sheet.png). Final captures use current staging's Rust Hub. More configurations and unchanged destinations are indexed with these.
15. **Actual references:** `design-system/mobile/google-classroom-reference/student/{4- CLASSES_,5- CLASSES_,29- PLUS SIGN (JOIN_ CREATE)}.jpg`, `teacher/{5- CLASSES_,7- CLASSES_}.jpg`. See clickable originals in the evidence index; no reference asset modified.
16. **Accessibility:** menu 52dp and primary FAB 56dp bounds asserted on device for both roles. Wrapping/scrolling drawer and sheet, text+icon status, named icon actions, Nunito and token colors retained. Actual Android font scale 2.0 tested for live role flows; original 0.85 restored. Portrait and short landscape states, including Compose 200% rendering, tested. Accessibility-tree interaction passed; spoken TalkBack traversal remains unverified.
17. **Device:** Infinix X6731, Android 14, 1080×2400 portrait / 2400×1080 landscape. Confirmed package `org.lara.app`; none installed at discovery. Used `adb install -r`, no uninstall/data clear. Original font/rotation settings restored to 0.85/0/0.
18. **Automated results:** final `test lint assembleDebug assembleDebugAndroidTest` passed with `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:\Temp`. Debug and release each report 201 tests, zero failures/errors, six opt-in skips (195 passes each). Lint: zero errors, 42 warnings. Final test-only additions also rebuilt and linted. Repository tests: 69 passed with `PYTHONUTF8=1`. Device checks: normal role flows, state fixtures, actual OS 200% flows, real-Hub role flows, landscape role flows, landscape large-text fixtures, learner mock-Hub disconnection and teacher real-Hub disconnection passed. Logs linked below.
19. **Invariants:** UTF-8 `verify_invariants.py` and `git diff --check` pass. Our changes are under the approved Mobile targets/docs/tests. An unrelated untracked `.vscode/settings.json` appeared during the session and was left untouched. No contract, Server, Desktop, schema, sync, design-system or skill edit.
20. **Design deviations:** [Full log](classroom-layout-group1.md#design-deviations). White L.A.R.A cards; no Google imagery/accounts; role-specific plus action; unsupported destinations omitted; teacher approvals retained; phone-reference FAB placement instead of the design document's heading action; growing rows for accessibility. No claim of pixel-perfect matching.
21. **Changed files:** `ui/components/ClassHomeScaffold.kt` (new), `ui/components/ClassroomUi.kt`, `ui/screens/enrollment/EnrollmentScreen.kt`, `ui/screens/enrollment/LearnerEnrollmentNavigation.kt`, `ui/screens/teacher/TeacherClassroomNavigation.kt`, `ui/screens/teacher/TeacherApprovalPanel.kt`, `ui/screens/teacher/classes/ClassManagementHost.kt`, `res/values/strings.xml`, `androidTest/.../reading/HomeNavigationDeviceTest.kt` (new), these Mobile notes and evidence. Paths are relative to the Mobile source roots where abbreviated.
22. **Outstanding:** spoken TalkBack and WAN-unplugged/classroom Wi-Fi testing were not done. Existing unrelated camera/video/quiz workflows were not manually rerun. The deferred class-tab label issue remains for Group 3. No unsupported To-do/Archive destination was fabricated. Fixtures for empty/loading/error/pending prove rendering, not live server state transitions. Forty-two lint warnings remain.
23. **Suggested commit:** `feat(mobile): align home and navigation with Classroom layout`.
24. **Draft PR:** below; prepared as review text only. No commit, push, PR, assignment or external comment was created.
25. **Next step:** review the screenshots and deviations, then authorize PR preparation or changes. Do not proceed to Group 2 automatically.

## Reproducible evidence and environment notes

| Check | Evidence |
|---|---|
| Final build, debug/release unit suites and lint | [Full output](evidence/266-group-1/build-test-lint.log) |
| Final test build and lint | [Output](evidence/266-group-1/device-test-build-lint.log) |
| Actual staging Hub role navigation and hit targets | [Device log](evidence/266-group-1/realhub-navigation.log) |
| Teacher cached Home with real Hub unreachable | [Device log](evidence/266-group-1/realhub-teacher-offline.log) |
| Landscape role navigation | [Device log](evidence/266-group-1/landscape-navigation.log) |
| Landscape states including 200% text | [Device log](evidence/266-group-1/landscape-states.log) |
| Invariant guardrail | [Output](evidence/266-group-1/invariants.log) |
| All screenshots | [Comparison index](evidence/266-group-1/README.md) |

The real Hub was built from this branch's unchanged staging Server sources using the existing temporary Rust toolchain and Visual Studio environment. Initial Cargo build failed because the old extracted registry contained marker-only directories. An isolated copy of its cached archives/index under `mobile/build/realhub-cargo` rebuilt successfully; no existing toolchain or Server source was edited. Isolated database: `mobile/build/group1-realhub-data`. The synthetic class was created and its learner approved through real contract APIs, all HTTP 200. No tokens or credentials were recorded in evidence.

Windows default temp paths caused four pre-existing short-path/canonical-path assertions; using a long path then exposed a SQLite path error. `C:\Temp` made both complete unit suites pass without modifying tests or production data logic. Default Python encoding also failed printing/reading Unicode subprocess results; UTF-8 mode made all 69 repository tests pass. A mismatched baseline/new test-APK state-fixture attempt crashed; it produced no accepted state evidence. The matched final APK/tests pass. The initial landscape Settings assertion was corrected to scroll to content below the fold; no app change was needed for it.

USB forwarding carries both HTTP and WebSocket traffic to the PC. Removing both rules produced actual disconnection evidence; it does not prove mDNS/UDP or router WAN isolation. The phone's original settings were restored; test account caches were preserved, with test sessions signed out by the harness. No data was cleared.

The isolated real Hub and both USB forwards remain available for review. Its generated data and build outputs are under `mobile/build/`; no school database was used. Final visual review caught a state-fixture run that had returned to portrait despite the system rotation setting. Enforced landscape then exposed the sheet's partial expansion hiding Cancel. The sheet now skips that anchor; landscape state evidence was recaptured at Android 200% with explicit activity orientation and a width-greater-than-height assertion.

## Draft PR description

Title: `feat(mobile): align home and navigation with Classroom layout`

Part of #266

### Summary

Move the existing mobile Home actions into a shared navigation drawer and bottom-right plus sheet. Keep L.A.R.A's white cards, green subject tiles, Nunito, Phosphor icons and enrollment/approval states. Learners open the existing Join dialog; teachers open the existing Create form and reach class management from the drawer.

### Screens redesigned

Learner and Teacher Home/class cards, the Home navigation drawer and role-specific plus sheet. Existing forms, Settings and class content retain their behavior.

### Screenshot references

Student 4/5/29 and Teacher 5. Teacher 7 was inspected but depicts Stream, reserved for Group 3. [Reference paths and comparisons](evidence/266-group-1/README.md).

### Before and after screenshots

See the role-by-role three-column reference/before/after table in the evidence index, plus portrait, landscape, 200% text and offline captures. New drawer/sheet surfaces use their pre-existing Home as the before image because those overlays did not exist.

### Navigation/flow changes

Menu → Classes / cached classes / Settings, plus Manage classes for teachers. Plus → the existing role-permitted form. Existing class opening/back, form cancel, management and settings callbacks are reused.

### Design deviations

- L.A.R.A white cards and flat subject tiles replace Google banners/images, per design-system 5.12/9.3.
- Unimplemented To-do, dedicated Archive and Google-only destinations are omitted; archived records remain in existing teacher management.
- Learners only Join; teachers only Create. Pending approval stays unchanged.
- Teacher Home keeps its working approval controls.
- Phone-reference FAB/sheet placement differs from the heading/top-bar Join described in design-system 9.3/9.12; documented for Lead review.
- Drawer rows grow and drawer/sheet scroll to preserve 52dp/56dp targets and large text.

### Functionality preserved

Room-first classes, enrollment gating/notices, role checks, pending approval entry, class management, offline class entry and existing settings. No ViewModel/repository/schema/transport/video implementation changed and no new network call was introduced for visual fidelity.

### Accessibility verification

Menu/FAB bounds asserted at 52dp/56dp. Android accessibility labels used for navigation. Both roles exercised at actual 200% font scale; portrait/landscape and short-height large-text states captured. Spoken TalkBack traversal not verified.

### Device verification

Infinix X6731, Android 14. Installed with `adb install -r`; no data clear/uninstall. Synthetic mock-Hub and freshly built current-staging real-Hub runs, plus genuine disconnected cached class entry. Device font and rotation settings restored.

### Build/test/lint results

`JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:\Temp` with `gradlew.bat test lint assembleDebug assembleDebugAndroidTest`: passed. Debug and release each: 195 passed, six opt-in skips, no failures. Lint: no errors, 42 warnings. Final instrumentation tests built/linted and targeted physical-device checks passed. `PYTHONUTF8=1 python -m unittest discover tests`: 69 passed. See linked logs for exact output and environment retries.

### Invariants check

UTF-8 `python scripts/verify_invariants.py` and `git diff --check`: passed. Only Mobile UI, English strings, device tests and Mobile docs/evidence changed.

### Known limitations

No spoken TalkBack or WAN-unplugged classroom-router run; USB forwarding does not prove LAN discovery. Empty/loading/error/pending images are explicitly injected production-component rendering tests. Existing camera/video/quiz workflows were not manually retested. Class-tab 200% labels stay for Group 3. Groups 2–8 are not completed by this PR.
