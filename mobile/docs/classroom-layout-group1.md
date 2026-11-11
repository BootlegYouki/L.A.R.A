# Classroom layout: Group 1 — Home and navigation

Part of #266. The user approved publishing the Group 1 PR after review. Group 2 remains outside this change.

## Prerequisites and ownership

- #258 merged into `staging` on 9 November 2026 at 18:26 Manila time. Base: `f8dfbfac44374e8dcd971bf02137d89f23a582f0`; its second parent is the approved video revision `8f63a70`. Fullscreen, offline playback and Remove from device are included.
- Branch: `feat/266-mobile-classroom-layout-part-1`, created from fetched `origin/staging`. The previous feature branch was clean; no stash, reset or history rewrite was used. Two existing detached worktrees were left alone.
- #266 had no assignees or comments. No open PRs, or open issues assigned to Nash172 or Osbornejmb, were returned at discovery. Local processes showed this Codex session; that does not prove another developer is not working elsewhere.
- Ponytail 5.3.3 is installed and its `SKILL.md` was read; full mode is active. `lara-architect`, `lara-android-offline` and `android-jetpack-compose` were read. `lara-mobile` is absent. Older bilingual guidance in supplemental skills is superseded by the English-only root rules.
- Lead announcements: Discussions #242 requires mock plus real-Hub evidence; #238 requires screen-state evidence and a click-through. #258's approval moves the existing 200% bottom-tab labels to #266; those class tabs belong to Group 3. The lead's newer root English-only decision supersedes older bilingual discussion comments.

## Existing screen inventory and implementation matrix

Only implemented surfaces are listed. A local selection, dialog or sheet is described as such; it is not a new Navigation Compose route. `S`/`T` below mean the repository's student/teacher reference directories. All screens also use design-system sections 1–5.

| Existing screen / destination | Role | Composable / state owner | Actions and existing states | Reference; design section; group | Existing coverage |
|---|---|---|---|---|---|
| Sign in / register (`AuthHost` local mode) | Shared | `AuthScreen`; `AuthViewModel` | Choose Hub, sign in, register, PIN keypad; restore blocks, busy, validation/rate-limit errors; new login needs Hub, cached session restores offline | L.A.R.A-only; 9.1; 8 | `AuthViewModelTest`, `AuthIntegrationTest`, `MockAuthDeviceTest` |
| Hub selection (`Discovery` or auth local mode) | Shared | `DiscoveryScreen`; `DiscoveryViewModel` | Scan, select, manual address, Check Hub, Back; scan blocks, no-Hub message, unavailable/error banners | L.A.R.A-only; 5.5–5.6; 8 | Discovery logic/ViewModel/feedback tests |
| Learner Home (no selected class) | Learner | `LearnerEnrollmentNavigation`, `EnrollmentScreen`; `EnrollmentViewModel` | Open eligible cached class, Join; skeletons, empty help, Hub-unreachable and refresh-error banners, pending/rejected/removed cards | S4/5/29; 9.2/9.3/9.12; **1** | Enrollment ViewModel/Room tests; `HomeNavigationDeviceTest` |
| Teacher Home (no selected class) | Teacher | `TeacherClassroomNavigation`, `TeacherApprovalPanel`; `TeacherApprovalSession`, per-class `RosterViewModel` | Open class, manage/create class, pending approvals; loading skeleton, no saved classes, offline/error banner | T5; 9.2/9.3/9.12; **1** | Teacher class visibility, roster/approval tests; Home device test |
| Join dialog (`showJoinDialog`) | Learner | `JoinClassDialog`; `EnrollmentViewModel` | Code, Cancel, Join; busy/invalid/offline states; receipt retains pending approval | S30; 9.10; 2 | `ClassCodeInputTest`, enrollment tests |
| Manage classes dialog / teacher `People` destination | Teacher | `ClassManagementPage`/`ClassManagementScreen`; `ClassManagementViewModel` | Refresh, choose class, copy code, People/settings, roster and co-teachers; skeleton, no classes/no learners, offline/errors, archived read-only records | T3/4/12/16/45; 9.6; 2 (create), 3 (People), 7 (settings/archive) | Class policy/API/repository/archive tests |
| Create/review/rename/add/remove/archive dialogs (`form`) | Teacher | Existing `ClassManagementScreen` form state; same ViewModel | Validate fields, review/edit, confirm/cancel; buttons gated by online/busy/ownership | T3/4/12/45; 5.8/9.6; 2 and 7 | Teacher class policy/API/mock tests |
| Stream (`Stream`) | Shared | `ClassroomLearningHost`/`ClassFeed`; `StreamViewModel`, Room flows | Open material/assignment, add permitted post comment; Room skeletons, no posts/classes, queued state, shell offline banner | S15/32, T11/41; 9.4; 3 | `StreamReadingTest`, `ReadingScreenDeviceTest` |
| Classwork (`Classwork`) | Shared | Same host/Room classwork flow | Open materials, video, assignments; no work, skeleton, shell offline banner | S16, T15; 9.5; 3 | Reading/handout/video tests |
| Pending approvals (`sheetOpen`) | Teacher | `TeacherApprovalHost`, `ApprovalSheet`; `RosterViewModel` | Pending count, Accept, Decline, Cancel; skeleton, empty, offline/forbidden/session error | L.A.R.A-only; 9.6; 8 | `TeacherApprovalDeviceTest`, `RestApprovalDeviceTest`, roster tests |
| Assignment / Your work (`openedAssignment`) | Learner workflow | `HomeworkScreen`; `HomeworkViewModel` | Take/choose/retake photo, Turn in, Back; skeleton, no photo, busy/error, queued/rejected/graded state | S23–28; 9.7; 5 | Homework repository/transport/photo/device tests |
| Camera (homework local mode) | Learner workflow | `CameraCaptureScreen`; local camera state | Permission, capture, cancel; not-ready/busy/error, entirely local | S23–25 camera action; 5.16; 5 | `HomeworkDeviceTest`, `DocumentScannerDeviceTest` |
| Scan review (homework local mode) | Learner workflow | `DocumentReviewScreen`; `HomeworkViewModel` | Corners, preview/enhance, use scan/full photo, cancel; raster skeleton, unavailable/error, entirely local | S23–25 equivalent; 5.16; 5 | `DocumentReviewDeviceTest`, scanner/corner tests |
| Handout (`opened` material) | Shared | `MaterialReader`; local rendering state, material repositories | Page/zoom, download/retry, Back; skeleton, unavailable/empty chunks, saved-file offline reading | L.A.R.A-only; 9.5; 8 | `HandoutDeviceTest`, `StreamReadingTest` |
| Video / fullscreen / removal confirmation | Shared | `VideoPlayerScreen`; `VideoViewModel`, `VideoPlaybackSession` | Play/pause/seek, fullscreen, Save for Home, cancel/resume, Remove from device; loading, unavailable/error, saved offline playback | L.A.R.A-only; 9.5; 8 | Video ViewModel/integration/mock/device tests |
| Settings (`AuthHost.settings`) | Shared | `SettingsHost`/`SettingsScreen`; `SettingsViewModel` | Hub info, change Hub, version, sign out/queued-work confirmation, Back; loading blocks, no Hub, offline/error | S14, T47–58 supported subset; 9.2; 7 (destination wired in 1) | Settings behavior/ViewModel/device tests |
| Quizzes / Tutor / Grades shell placeholders | Role-gated | `ShellPlaceholder`, `ManagementPlaceholder`; `ShellState` | Existing empty placeholders, shell skeleton/offline; Tutor guarded during active attempt. No quiz builder, tutor chat or gradebook screen exists | No redesign beyond existing functionality; class navigation in 3 | `ShellStateTest` |
| Unauthenticated Home/About scaffolding (`Home`, `About`) | Unauthenticated | `PlaceholderPage`; local navigation | Discovery/About/Back, static offline and empty explanation; no data-loading operation | L.A.R.A-only; 8 | No dedicated device test found |

Groups 4 (teacher posting), 6 (To-do), and unsupported parts of 7 have no implemented screen to redesign. Private comments, topic filters, learner People, and a dedicated Archived destination are absent. Group 1 does not create them. Existing teacher management still shows archived records. Do not infer implemented features from the Tech Spec's future file map.

Ordered groups: **1 Home/navigation → 2 Join/Create → 3 class tabs/banner → 4 existing teacher posting only → 5 assignment/turn-in → 6 existing To-do only → 7 supported menus/settings/archive → 8 L.A.R.A-only surfaces**. Each later group requires its own scope check and user review checkpoint.

## Implementation and data flow

`ClassHomeScaffold` owns only drawer and action-sheet visibility. It takes cached class labels and existing callbacks. Drawer rows grow vertically at large font sizes; the drawer and action sheet scroll. The sheet skips the partial expansion anchor so its Cancel action remains reachable on short screens. Home uses a 56dp plus FAB, 52dp minimum drawer rows/menu control and 56dp primary sheet action. Strings stay in `values/strings.xml`.

Both role homes reuse this scaffold. Learner drawer membership is filtered through `EnrollmentScreenState.openableClass`, so pending notices do not grant access. Home cards still show those notices. Teacher Home retains pending approvals and opens existing management from the drawer. The plus action opens the existing create form using `startCreating`; validation, review, cancel and requests are unchanged.

`ClassroomCard` now uses the approved white card, dark-green subject tile, name, section, icon-and-text status and Open footer. No class code is exposed. Home lists reserve scroll space below the last card for the FAB. Existing Join remains available for standalone `EnrollmentScreen` callers; production Home uses the FAB instead of a duplicate full-width button.

No ViewModel, repository, DAO, schema, sync, endpoint or transport implementation changed. Room and the existing session lifecycle remain the source of data. Saved memberships open offline; adding a class requires a reachable Hub. The script/image evidence is from synthetic accounts only.

## Screenshot references and evidence

References opened and visually inspected (not inferred from filenames):

| Role / reference | What it actually shows | Application |
|---|---|---|
| `student/4- CLASSES_.jpg` | Open side drawer, Classes, enrolled classes and settings | Drawer hierarchy, supported entries only |
| `student/5- CLASSES_.jpg` | Single-column class list and bottom-right plus | Home and action placement |
| `student/29- PLUS SIGN (JOIN_ CREATE).jpg` | Join/Create bottom action sheet | Role-appropriate sheet |
| `teacher/5- CLASSES_.jpg` | Teacher class list and plus | Teacher Home |
| `teacher/7- CLASSES_.jpg` | Class Stream with banner and bottom tabs | Context only; class tabs/banner deferred to Group 3 |

All reference paths above are relative to `design-system/mobile/google-classroom-reference/`. Original images are untouched. [Evidence directory](evidence/266-group-1/) contains real full-device PNG captures, not generated mockups. `before/learner-home.png` and `before/teacher-home.png` were captured from the unchanged staging APK before production edits; corresponding `after/*-home.png` show the redesign. Settings captures prove the existing destination remains reachable. A drawer/action sheet did not exist before; use the same role's baseline Home for the before comparison. Existing form captures show preserved behavior, not a Group 2 redesign.

`after/learner-{empty,loading,error,pending,offline,font200}.png` use explicitly supplied synthetic presentation states in the production components on the phone. These prove rendering, not live network failures. `actual-cached-offline*.png` are the real cached-session disconnection test. `font200-*` files use Android's actual 2.0 font scale, restored afterward; `learner-font200.png` additionally uses a Compose density override.

## Design deviations

| Reference / expected structure | Implemented L.A.R.A structure | Reason / preserved requirement / lead decision |
|---|---|---|
| S4 drawer includes To-do, calendar, notifications, offline files, archive and classroom folders | Classes, eligible cached classes, Settings; teacher also has Manage classes | No unsupported destinations or fake actions. To-do is future #129; archive remains in existing teacher management. No new behavior requested here. |
| S5/T5 illustrated class banners | White cards with dark-green subject tile, status and Open footer | Design-system 5.12 and 9.3 explicitly retain L.A.R.A card styling. No Google illustrations or gradients. |
| S29 exposes both Join and Create | Learner Join only; teacher Create only | Preserve existing role permissions and pending approval. A teacher join workflow is not implemented. |
| S5/T5 account avatar and overflow actions | Branded green bar, menu; settings in drawer | No learner photos or unsupported account menu; approved brand and working settings preserved. |
| Teacher Home reference shows compact cards | Existing pending-approval control remains under each managed card | Do not hide or relocate approval functionality into an unimplemented flow; broader relocation belongs to later groups. |
| Drawer rows and sheet have compact fixed geometry | Text-wrapping rows and scrolling drawer/sheet, large touch targets | 200% text and short-height accessibility take precedence. |
| Design-system 9.3 places Join/Create at Classes heading; 9.12 mentions top-bar Join | Bottom-right plus and sheet | User and #266 explicitly name actual phone references as action-placement authority; appearance stays token-based. Reported for Lead review. |
| T7 is listed under Group 1 but depicts a class Stream | Class navigation preserved; no class tabs/banner restyle | Same issue assigns those surfaces to Group 3. No silent scope expansion. |

No business behavior intentionally differs. Approval timing, account isolation, offline queueing and existing video implementation are preserved. Outstanding visual decisions are the documented destination gaps and reference/design-system placement differences; no fake features were added to resolve them.

## Validation

Physical device: Infinix X6731, Android 14, USB ADB. Package confirmed as `org.lara.app`; no installation of that package existed at discovery. Installed debug APKs with `adb install -r`, never uninstalled or cleared data. Mock Hub reached over `adb reverse` on 8080/8081, not a firewall change or LAN-discovery test.

- `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:\Temp` with `gradlew.bat test lint assembleDebug assembleDebugAndroidTest`: passed. This avoids Windows short-path/canonical-path assertions and SQLite long-path problems in the existing tests. Initial default-temp runs failed and were not counted as passes.
- `PYTHONUTF8=1 python -m unittest discover tests`: 69 passed. Default Windows encoding initially failed the invariant subprocess tests.
- `PYTHONIOENCODING=utf-8 python scripts/verify_invariants.py`: passed.
- `HomeNavigationDeviceTest`: opt-in actual mock-backed role flows and explicit presentation-state screenshots; genuine offline test removes both USB forwarding rules, then opens the cached class and verifies the offline Join explanation.
- Android 200% font scale: actual role flows captured with the device setting restored in `finally`.

Full command output is kept under `mobile/build/group1-*.log`; final evidence/result details and the PR draft are maintained in the review report alongside this document. Do not mark real-Hub, landscape, TalkBack, WAN-unplugged or unrelated video/camera workflows passed without their evidence.

Repeat device tests only on an opted-in synthetic installation: build/install both APKs, forward the mock Hub ports, then run `adb shell am instrument -w -e class org.lara.app.reading.HomeNavigationDeviceTest -e homeHub 127.0.0.1:8080 -e homeStates true org.lara.app.test/androidx.test.runner.AndroidJUnitRunner`. The separate offline test needs `-e homeOffline true` and forwarding removed after its ready message. Tests refuse to replace an unrelated signed-in user and refuse sign-out when queued work needs confirmation.

For short-height fixture checks, select `#homeStatesAndLargeText` with `-e homeStates true -e homeLandscape true`; every screenshot asserts landscape dimensions. The final run also set Android font scale to 2.0 and restored 0.85 afterward, covering modal-sheet text as well as the main Compose density. Teacher offline testing adds `-e homeRole teacher` to the separate offline method.
