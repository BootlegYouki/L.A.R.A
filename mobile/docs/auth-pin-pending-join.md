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

## Current validation and staging integration

The original implementation baseline was `dcb65f1`. Before publication MOBILE 3.5
PR #338 was integrated at `92639e3`. The inherited homework filename test and
three lint errors were then resolved by merged PR #342; that staging baseline
`6f977e8ece6980374ae389db3f8cec9608b393eb` is now integrated without conflicts.
Those shared staging fixes are not duplicated in this PR's diff. Class-comment
code and the original MOBILE 3.5 checkout remain untouched.

On 2026-11-20 (Asia/Manila), the full command passed:

```text
./gradlew --max-workers=2 --init-script /private/tmp/lara-326-unit.init.gradle test lint assembleDebug
BUILD SUCCESSFUL in 2m 8s
90 actionable tasks: 43 executed, 47 up-to-date
```

- Debug and release: **214 tests each, zero failures/errors, seven opt-in skips**.
- Lint: **zero errors, 80 warnings**; no new suppression or baseline added here.
- Shared contract/mock Hub suite: **72 passed**.
- Offline invariants and `git diff --check`: **passed**.
- Earlier isolated app/instrumentation APK build and OPPO CPH2179 / Android 10:
  **three focused phone tests passed**, covering keyboard presentation, masking,
  focus, deletion/replacement, submission in both forms, offline pending metadata
  and denied pending access. The regular installed app/data were preserved and
  the isolated test packages were removed. This was before the final staging
  integration; it is not a claim of a new phone run of the integrated APK.

Tests cover six ASCII digits in both auth modes, retry gating, disconnected auth,
required receipt fields, failed follow-up refresh, legacy saved notices,
account/Hub scoping and file-backed Room reopen without fabricated memberships.
Submission reads the current PIN so immediate Enter after deletion cannot use
stale Compose eligibility. The phone harness refocuses after invalid Enter
correctly dismisses the keyboard.

The temporary Gradle init script sets test `java.io.tmpdir` to
`/private/tmp/lara-326-unit`, following the existing macOS workaround in
`evidence/266-group-8/unit.init.gradle`. This avoids `/var` versus `/private/var`
path-comparison failures; it does not suppress or skip tests. PyYAML for the
shared suite is installed in a temporary virtual environment.

[Current check summary](evidence/326/checks.txt),
[current test/lint/build output](evidence/326/ready-test-lint-build.txt),
[current lint diagnostics](evidence/326/ready-lint.txt),
[current shared tests](evidence/326/ready-shared-tests.txt),
[focused phone run](evidence/326/device-focused.txt),
[PIN screenshot](evidence/326/six-digit-pin-keyboard.png),
[offline pending screenshot](evidence/326/pending-class-offline.png).

## Remaining verification limits

Real Rust Hub integration (#324 remains open), normal-entry app restart with Hub
down, physical WAN-unplugged router, 200% font, TalkBack and memory profiling are
not verified. Room file reopen and explicit offline UI states are the verified
persistence/UI evidence. An exploratory broader phone test passed its auth
assertions, then failed at the unchanged teacher-approval callback assertion
([log](evidence/326/device-broad.txt)); it was not repaired under this issue.

The older `integrated-*`, `test-lint.txt`, `lint.txt` and `shared-tests.txt` logs
remain as historical evidence. Their test/lint failures are superseded by the
passing `ready-*` logs after integrating #342. Current validation no longer
requires draft status; the remaining verification limits remain explicit for
review and do not claim production integration or approval to merge.

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
- `mobile/docs/evidence/326/ready-lint.txt`
- `mobile/docs/evidence/326/ready-shared-tests.txt`
- `mobile/docs/evidence/326/ready-test-lint-build.txt`
- `mobile/docs/evidence/326/shared-tests.txt`
- `mobile/docs/evidence/326/six-digit-pin-keyboard.png`
- `mobile/docs/evidence/326/test-lint.txt`
