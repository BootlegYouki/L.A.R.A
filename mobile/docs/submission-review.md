# Teacher submission review

Teachers open an assignment from Classwork to see active learners and whether their work is turned in, not turned in, or graded. Open a turned-in image to inspect it, enter integer points, choose or type feedback, and save. Previous and Next follow the learner name order. A confirmation protects edits when leaving a learner.

The review screen is `ui/screens/teacher/review/SubmissionReview.kt`; `GradingApi` performs the Hub requests and `GradeValidation.kt` checks score bounds. The list comes from the account's Room roster and assignment submission rows, updated through the existing sync pull. The Hub's submission list is checked on entry and reconnect. Grading uses `PATCH /api/submissions/{id}` with `score` and `teacher_feedback`; photos stream from `GET /api/submissions/{id}/file` into a temporary file and are decoded with sampling. A successful grade updates Room and requests a normal sync pull. The learner receives the grade via the normal delta sync.

Grading and photo retrieval require the Hub. The saved list remains available offline, and grade controls are disabled. HTTP 403 hides cached review data and the denial is remembered until a later successful authorization check. No offline grading queue is created. Downloads are limited to 20 MiB, stored temporarily in app cache, and removed after decoding. This screen currently cannot preview document submissions; it shows a message instead. Sampled images are bounded for memory, so extreme zoom does not preserve full resolution.

## How to test

1. Start the mock Hub and sign in as a teacher. Open a class, then Classwork and an assignment with a learner submission.
2. Confirm the learner names and status text. Open a turned-in photo and pinch and drag to inspect it.
3. Enter points within the assignment maximum and feedback, then tap Save grade. Use Next and Previous to inspect work. Edit a field and try to move away to see the discard confirmation.
4. Sign in on a learner account and sync once. Check that the score and feedback appear on the assignment.
5. Stop the Hub and reopen the assignment. Confirm the saved list remains visible, and grading is disabled with an explanation.
6. With a teacher who does not teach the class, confirm the Hub returns 403 and review data is hidden. Reconnect with authorized access to clear a remembered denial.

Real-Hub, physical-device memory and accessibility verification remain separate checks. Do not record learner identifiers, tokens, photos, or private network addresses in evidence.

## Automated checks and evidence

`GradeValidationTest` covers zero, the maximum, invalid text, negative, and over-maximum scores. With an isolated mock Hub listening on the test ports, `LARA_GRADING_MOCK=1 ./gradlew :app:testDebugUnitTest --tests '*GradingMockTest' --tests '*GradeValidationTest' --offline` exercises a file upload and retrieval, a grade and feedback, one learner sync pull, and a 403 for an unrelated teacher. The mock test uses synthetic work; it does not demonstrate the screen, offline interaction, photo decoding, or the real Hub. Those require separate hands-on checks. Run `./gradlew test lint --offline` for the full mobile checks.

The mock integration test has passed locally for the grade receipt, file bytes, learner-side pull and unauthorized-teacher response. UI appearance, TalkBack, unsaved navigation, photo gestures, offline interaction and real-Hub behavior have not yet been demonstrated on a device. A connected phone already had an installation, so it was not overwritten during this check.

## Clean staging verification

On the clean branch based on staging `15e9c9a`, the focused score validation and opt-in mock-Hub grading tests passed (three tests, no skips), as did `assembleDebug` and standalone `lint`, with one Gradle worker. The mock test checks the grade receipt, file retrieval, one learner pull, and an unrelated teacher's 403; it does not exercise the UI. The full `test lint` command stopped at debug unit tests: 217 tests, four failures and eight skips. The same four failures reproduced on unchanged staging `15e9c9a` (214 tests, four failures, seven skips): two `HomeworkRepositoryTest` and two `StreamReadingTest` assertions compare macOS `/var` paths to their `/private/var` canonical forms. An earlier parallel compilation attempt exhausted compiler memory; the single-worker retry reached the tests. Device, real-Hub, offline UI, gestures, TalkBack, large-font and WAN-disconnected checks remain unverified. No claim is made that document submissions can be opened by this screen; FR-3.5 requires that capability.
