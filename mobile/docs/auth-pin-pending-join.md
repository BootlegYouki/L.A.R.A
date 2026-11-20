# Six-digit authentication PIN and pending-class metadata (#326)

Reference: #321. Baseline: `staging` / `origin/staging` at
`dcb65f158e5dcc042d8ccaeec00788a2f83188ec`, verified against GitHub before editing.
This work is isolated from MOBILE 3.5 in branch `mobile/326-pin-pending-join`.

## Verified dependencies

- [PR #327](https://github.com/BootlegYouki/L.A.R.A/pull/327), merged
  2026-11-18, commit `47f6cdff0d53ee54f52e1f548c2e4690526aba15`:
  six-digit PIN contract, join metadata, Mock Hub and tests.
- [PR #331](https://github.com/BootlegYouki/L.A.R.A/pull/331), merged
  2026-11-18, commit `76420590de7d0f4f16b4a1d6c89780b0df315e44`:
  class comments restored. Both commits are ancestors of the verified baseline.
- PR #332 (`c9e2a019be18c52bd45553b91b9f6ffafb5efd2d`) carries mobile
  class-discussion work to staging. This issue leaves class-comment code alone.
- `contracts/openapi.yaml` requires six ASCII digits for login/register.
  `POST /api/classrooms/join` requires `status`, `classroom_id`, `section` and
  `teacher_name`. The last two are non-null strings; neither has a minimum length.
  The reply has **no class name or teacher ID**.
- `scripts/mock_hub.py` uses seed PIN `123456`, rejects four-digit registration,
  and returns both display fields. The shared join test asserts the exact receipt
  keys and values.

## Behavior and data flow

`AuthPin` supplies the six-digit length and ASCII-only validation to AuthScreen,
AuthViewModel and AuthApi. Sign-in and learner registration use six masked boxes
backed by one numeric-password input. ID/name focus order, deletion, keyboard
submission, transient credentials, clearing on submission/mode change, errors and
Retry-After gating remain. Boxes share the available width with token spacing
inside one full-width, 56dp-high input, avoiding overflow on narrow phones. Their
individual visual widths can be smaller than the design-system input-box example;
they are decorations of one editable target, not six independent controls.

There is no automatic conversion of old four-digit PINs. Per the merged contract,
the Hub admin must reset those accounts with `/api/admin/users/{id}/reset-pin`.
Mobile does not add an admin setup or reset workflow.

EnrollmentApi decodes required `section` and `teacher_name` without optional
network defaults. EnrollmentViewModel saves them before attempting the subsequent
classroom-list refresh. EnrollmentNotice carries them into the existing
account/Hub-scoped Room `sync_state` JSON notice; no new table, migration, fake
user or fake enrollment is needed. Updates containing only a status or title retain
previous metadata from the same Hub. Old stored notices decode with nullable
metadata defaults. Missing required network fields fail the request; empty strings
are allowed and the existing card omits blank display lines.

Pending cards show saved section and teacher name alongside the class title from
`GET /api/classrooms` or the existing cached class. If no title has arrived, the
existing request-title fallback remains. A failed refresh cannot erase the join
metadata. With the Hub unreachable or after reopening Room, saved notices remain
readable and class access still requires canonical ACTIVE membership. Offline join
requests are not fabricated or queued; the existing Hub-unreachable state remains.

## Validation

Tests cover
both auth modes, ASCII-only six-digit validation, retry gating, disconnected auth,
required receipt fields, failed follow-up refresh, legacy saved notices,
account/Hub scoping and file-backed Room reopen without invented memberships.
Existing authentication fixtures use the new seed PIN; hardware keyboard tests
also check masking and deleting/replacing the sixth digit. Submission revalidates
the current PIN so an immediate Enter after deletion cannot use a stale Compose
callback eligibility value.

Initial results against `dcb65f1` on 2026-11-20 (Asia/Manila):

- Shared contract/mock suite: **72 passed**. PyYAML was installed in a temporary
  virtual environment; the system Python initially lacked it.
- Offline invariants: **passed**. `git diff --check`: **passed**.
- `./gradlew --init-script /private/tmp/lara-326-unit.init.gradle test lint`:
  debug and release each ran **210 tests, zero failures/errors, six opt-in skips**.
  The init script sets test `java.io.tmpdir` to `/private/tmp/lara-326-unit`, following
  the existing macOS workaround in `evidence/266-group-8/unit.init.gradle`.
  Without it, unrelated path comparisons see `/var` versus `/private/var`.
- The full required command **fails lint**: three errors and 78 warnings.
  `VideoPlayerScreen.kt:116` reports API 29 AutoCloseable use with minSdk 26;
  `VideoPlayerScreen.kt:113` and `MaterialReader.kt:331` report
  `ProduceStateDoesNotAssignValue`. Both files are byte-identical to the staging
  baseline. No suppression, baseline or unrelated fix was added in this issue.
- Isolated app and instrumentation APKs: **build passed**. OPPO CPH2179 / Android
  10: **three focused tests passed** (masking, deletion/replacement, keyboard focus
  and submission in both forms, keyboard presentation, offline pending metadata
  and denied class access). The regular installed app/data were not replaced.
- An exploratory broader device test passed its authentication assertions, then
  failed at the unchanged teacher-approval callback assertion. It was not fixed
  under this issue. Its log also records the earlier harness refocus problem;
  the final focused run uses the corrected refocus step and passes.
- Real-Hub integration remains **not verified**; server follow-up #324 is still
  open. Normal-entry app process restart with Hub down, physical WAN-unplugged
  router, 200% font and TalkBack checks are not verified; Room reopen and rendered
  offline states are the verified persistence/UI evidence.

[Shared output](evidence/326/shared-tests.txt),
[test/lint output](evidence/326/test-lint.txt),
[lint diagnostics](evidence/326/lint.txt),
[final focused phone run](evidence/326/device-focused.txt),
[broader phone run and limitations](evidence/326/device-broad.txt),
[device build](evidence/326/device-build.txt),
[check summary](evidence/326/checks.txt),
[PIN screenshot](evidence/326/six-digit-pin-keyboard.png),
[pending offline card screenshot](evidence/326/pending-class-offline.png).

## Integration for publication

Before publication, merged MOBILE 3.5 PR #338 was verified merged on GitHub and
`origin/staging` was fetched at `92639e36faf5fbce0cc295952a6d26835982a0db`.
That baseline was merged into this separate issue branch without conflicts.
Only this issue's mobile changes remain in the PR diff. The original MOBILE 3.5
checkout and the local `staging`/`main` branches were not modified.

Checks repeated on the integrated branch:

- Shared contract/mock suite: **72 passed**; offline invariants and diff check pass.
- Debug and release each run **215 tests: one failure, zero errors, seven opt-in
  skips**. All 21 focused AuthViewModel, EnrollmentViewModel, EnrollmentApi and
  enrollment notice persistence tests pass per variant.
- The failure is the multipart filename assertion in
  `HomeworkTransportTest.streamsMultipartAndAcceptsTheHubsCanonicalSubmissionId`.
  The test expects `filename="homework.jpg"`. Both the test and HomeworkApi are
  byte-identical to current staging; neither is edited by this issue.
- Lint reports the same **three errors**, now **80 warnings**, in unchanged
  MaterialReader and VideoPlayerScreen files. No lint suppression is added here.
- Integrated `assembleDebug`: **BUILD SUCCESSFUL**.
- These failed checks and missing integration/screen evidence remain blockers to
  merge readiness. Publication is a **draft PR**, with no merge requested.

The earlier passing phone checks cover the issue's production components before
staging integration, not a new phone run of the integrated APK.

[Integrated APK build](evidence/326/integrated-build.txt),
[Integrated test/lint log](evidence/326/integrated-test-lint.txt),
[integrated lint diagnostics](evidence/326/integrated-lint.txt),
[integrated shared suite](evidence/326/integrated-shared-tests.txt).

## Exact files changed

- `mobile/app/src/androidTest/java/org/lara/app/auth/MockAuthDeviceTest.kt`
- `mobile/app/src/androidTest/java/org/lara/app/reading/AssignmentUiDeviceTest.kt`
- `mobile/app/src/androidTest/java/org/lara/app/reading/ClassFormsDeviceTest.kt`
- `mobile/app/src/androidTest/java/org/lara/app/reading/ClassMenuDeviceTest.kt`
- `mobile/app/src/androidTest/java/org/lara/app/reading/ClassTabsDeviceTest.kt`
- `mobile/app/src/androidTest/java/org/lara/app/reading/Group8UiDeviceTest.kt`
- `mobile/app/src/androidTest/java/org/lara/app/reading/HomeNavigationDeviceTest.kt`
- `mobile/app/src/main/java/org/lara/app/data/remote/AuthApi.kt`
- `mobile/app/src/main/java/org/lara/app/data/remote/EnrollmentApi.kt`
- `mobile/app/src/main/java/org/lara/app/data/repository/RoomEnrollmentNoticeRepository.kt`
- `mobile/app/src/main/java/org/lara/app/domain/enrollment/EnrollmentModels.kt`
- `mobile/app/src/main/java/org/lara/app/domain/model/AuthPin.kt`
- `mobile/app/src/main/java/org/lara/app/ui/screens/auth/AuthScreen.kt`
- `mobile/app/src/main/java/org/lara/app/ui/screens/auth/AuthViewModel.kt`
- `mobile/app/src/main/java/org/lara/app/ui/screens/enrollment/EnrollmentScreen.kt`
- `mobile/app/src/main/java/org/lara/app/ui/screens/enrollment/EnrollmentViewModel.kt`
- `mobile/app/src/main/res/values/strings.xml`
- `mobile/app/src/sharedTest/java/org/lara/app/approval/RestApprovalFlowTests.kt`
- `mobile/app/src/sharedTest/java/org/lara/app/settings/SettingsMockBehaviorTests.kt`
- `mobile/app/src/sharedTest/java/org/lara/app/sync/MockSyncBehaviorTests.kt`
- `mobile/app/src/test/java/org/lara/app/data/AuthIntegrationTest.kt`
- `mobile/app/src/test/java/org/lara/app/data/EnrollmentNoticePersistenceTest.kt`
- `mobile/app/src/test/java/org/lara/app/data/HomeworkMockTest.kt`
- `mobile/app/src/test/java/org/lara/app/data/TeacherClassMockTest.kt`
- `mobile/app/src/test/java/org/lara/app/data/remote/EnrollmentApiTest.kt`
- `mobile/app/src/test/java/org/lara/app/media/VideoMockIntegrationTest.kt`
- `mobile/app/src/test/java/org/lara/app/ui/screens/auth/AuthViewModelTest.kt`
- `mobile/app/src/test/java/org/lara/app/ui/screens/enrollment/EnrollmentViewModelTest.kt`
- `mobile/docs/auth-pin-pending-join.md`
- `mobile/docs/authentication.md`
- `mobile/docs/enrollment.md`
- `mobile/docs/evidence/326/checks.txt`
- `mobile/docs/evidence/326/device-broad.txt`
- `mobile/docs/evidence/326/device-build.txt`
- `mobile/docs/evidence/326/device-focused.txt`
- `mobile/docs/evidence/326/integrated-build.txt`
- `mobile/docs/evidence/326/integrated-lint.txt`
- `mobile/docs/evidence/326/integrated-shared-tests.txt`
- `mobile/docs/evidence/326/integrated-test-lint.txt`
- `mobile/docs/evidence/326/lint.txt`
- `mobile/docs/evidence/326/pending-class-offline.png`
- `mobile/docs/evidence/326/shared-tests.txt`
- `mobile/docs/evidence/326/six-digit-pin-keyboard.png`
- `mobile/docs/evidence/326/test-lint.txt`
