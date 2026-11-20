# Teacher submission review

Teachers open an assignment from Classwork to see active learners and whether their work is turned in, not turned in, or graded. Open a turned-in image, PDF, or DOCX to inspect it, enter integer points, type feedback, and save. Previous and Next follow the learner name order. A confirmation protects edits when leaving a learner.

The review screen is `ui/screens/teacher/review/SubmissionReview.kt`; `GradingApi` performs the Hub requests and `GradeValidation.kt` checks score bounds. The list comes from the account's Room roster and assignment submission rows, updated through the existing sync pull. The Hub's submission list is checked on entry and reconnect. Grading uses `PATCH /api/submissions/{id}` with `score` and `teacher_feedback`; files stream from `GET /api/submissions/{id}/file` into a temporary file. Images are decoded with sampling; PDFs use the handout reader’s bounded page renderer and DOCX files use its paragraph and image reader. The submission contract has no MIME type, so the document type is detected from its bytes. A successful grade updates Room and requests a normal sync pull. The learner receives the grade via the normal delta sync.

Grading and file retrieval require the Hub. The saved list remains available offline, and grade controls are disabled. HTTP 403 hides cached review data and the denial is remembered until a later successful authorization check. No offline grading queue is created. Image downloads are capped at 20 MiB and document downloads at 250 MiB. Temporary files are removed when review closes or the download fails. To bound memory in the existing DOCX reader, archives with more than 1,024 entries, more than 16 MiB expanded, any entry larger than 8 MiB, or more than four embedded images show an unavailable message; the reader shows paragraphs and inline images, not full Word formatting or tables. Sampled images are bounded for memory, so extreme zoom does not preserve full resolution.

## How to test

1. Start the mock Hub and sign in as a teacher. Open a class, then Classwork and an assignment with a learner submission.
2. Confirm the learner names and status text. Open a turned-in photo and pinch and drag to inspect it. Open PDF and Word submissions; use Previous page and Next page for a multi-page PDF.
3. Enter points within the assignment maximum and feedback, then tap Save grade. Use Next and Previous to inspect work. Edit a field and try to move away to see the discard confirmation.
4. Sign in on a learner account and sync once. Check that the score and feedback appear on the assignment.
5. Stop the Hub and reopen the assignment. Confirm the saved list remains visible, and grading is disabled with an explanation.
6. With a teacher who does not teach the class, confirm the Hub returns 403 and review data is hidden. Reconnect with authorized access to clear a remembered denial.

Real-Hub, physical-device memory and accessibility verification remain separate checks. Do not record learner identifiers, tokens, photos, or private network addresses in evidence.

## Automated checks and evidence

`GradeValidationTest` covers zero, the maximum, invalid text, negative, and over-maximum scores. With an isolated mock Hub listening on the test ports, `LARA_GRADING_MOCK=1 ./gradlew :app:testDebugUnitTest --tests '*GradingMockTest' --tests '*GradeValidationTest' --offline` exercises a file upload and retrieval, a grade and feedback, one learner sync pull, and a 403 for an unrelated teacher. The mock test uses synthetic work; it does not demonstrate the screen, offline interaction, photo decoding, or the real Hub. Those require separate hands-on checks. Run `./gradlew test lint --offline` for the full mobile checks.

The mock integration test has passed locally for the grade receipt, file bytes, learner-side pull and unauthorized-teacher response. UI appearance, TalkBack, unsaved navigation, photo gestures, offline interaction and real-Hub behavior have not yet been demonstrated on a device. A connected phone already had an installation, so it was not overwritten during this check.

## Clean staging verification

On the clean branch based on staging `15e9c9a`, the focused score validation and opt-in mock-Hub grading tests passed (three tests, no skips), as did `assembleDebug` and standalone `lint`, with one Gradle worker. The mock test checks the grade receipt, file retrieval, one learner pull, and an unrelated teacher's 403; it does not exercise the UI. The full `test lint` command stopped at debug unit tests: 217 tests, four failures and eight skips. The same four failures reproduced on unchanged staging `15e9c9a` (214 tests, four failures, seven skips): two `HomeworkRepositoryTest` and two `StreamReadingTest` assertions compare macOS `/var` paths to their `/private/var` canonical forms. An earlier parallel compilation attempt exhausted compiler memory; the single-worker retry reached the tests. Device, real-Hub, offline UI, gestures, TalkBack, large-font and WAN-disconnected checks remain unverified. This historical check predates the document viewer; see the current test results in the PR draft.

## Document review and mock-Hub dependency

`ReviewDocumentTest` checks content-based PDF and DOCX detection, Word paragraph extraction, and archive limits. The connected phone already contains the production app and its test package; neither was replaced. Actual PDF rendering, Word display, photo gestures, unsaved navigation and offline interaction still require an isolated device run.

Before the mock fix, a document upload could not complete through `HomeworkApi`: the mock creates every submission with `file_type = IMAGE`, including PDF and DOCX, while the contract and upload client distinguish `DOCUMENT`. The client rejects that mismatched response. The mock and its tests are now updated on this branch with the Lead authorization relayed by the user.

The document change was checked with `LARA_GRADING_MOCK=1 ./gradlew :app:testDebugUnitTest --tests '*ReviewDocumentTest' --tests '*GradingMockTest' --tests '*GradeValidationTest' :app:assembleDebug lint --offline --max-workers=1`: six tests passed without skips, and the debug build and lint passed. The grading test also confirms that another teacher receives 403 for file retrieval. A full `test lint --continue` run during this change ran 220 tests in each of debug and release: each had the same four previously reproduced path failures and eight skips; lint passed. The offline invariant guard passed. The final focused run includes the later archive-entry bound and file-authorization assertion.

## Upload diagnosis and reproduction

The learner picker now checks copied PDF bytes for the `%PDF-` signature and DOCX archives for the standard content-types and Word document entries before accepting the attachment; a failure deletes the copied file and shows the existing save error. These checks reject obvious mismatches without reading the whole file, but they are not complete PDF or Word validation; malformed documents that pass them can still fail safely in the viewer. Uploads retain the correct multipart MIME type and reject a Hub receipt whose `file_type` disagrees with the attachment.

To reproduce the unchanged mock's behavior without altering a running classroom Hub, start `python3 mobile/docs/evidence/127/isolated-mock.py` in a separate terminal, then run `python3 mobile/docs/evidence/128/reproduce-document-upload.py`. The script creates synthetic assignments on the isolated Hub at `127.0.0.1:38080`; its image and PDF fixtures are minimal byte sequences for classification, not valid render tests. It sends JPEG, PDF and DOCX with their respective multipart MIME types. All return HTTP 200 and their stored bytes are retrievable, The corrected mock returns and lists `IMAGE` for JPEG and `DOCUMENT` for PDF and DOCX, and returns the matching content type and bytes. Before this fix it returned `IMAGE` and `image/jpeg` for all three, and the client correctly rejected document upload receipts.

The corrected mock detects JPEG, PNG, PDF and DOCX from file bytes, updates `file_type` on replacement and returns the matching MIME on file retrieval. Unsupported content is rejected. The tests cover type detection, misleading filename and MIME, replacements, idempotent retries, sync and unauthorized retrieval without weakening mobile receipt validation.

In an explicitly authorized disposable mock copy with synthetic content classification changed, the three uploads had the expected kinds, the teacher retrieved identical bytes, and a grade and feedback for both documents appeared in one learner sync. This is API-level evidence from a modified test double, not a real-Hub or device viewer test. The same correction is now implemented in the shared mock on this branch.

Real-Hub isolated tests passed for PDF submission as `DOCUMENT` and teacher file retrieval, image submission and access control, and four format-classification tests including DOCX. They did not exercise a DOCX upload or Android rendering. The most recent focused mobile run passed 12 tests (document checks, multipart MIME and receipt validation, viewer format checks, scoring and mock grading); debug build and lint passed. The full mobile debug and release suites each ran 223 tests, with the four previously reproduced staging path failures and eight skips per variant; lint passed. No phone was connected for this run. Photo zoom, PDF page rendering, DOCX display, navigation, unsaved changes and offline interaction remain unverified on device.

With the shared mock correction on this branch, the full repository test suite passed (73 tests), as did the invariant guard. The focused Android run passed against the corrected mock with PDF and DOCX upload receipts, teacher list and file retrieval, grading, and learner receipt after one sync; debug build and lint passed. These synthetic files exercise the API and sync, not on-device PDF or Word rendering. Existing running mock processes retain their prior code and in-memory state until restarted.

The predefined feedback shortcuts were removed at the user’s request. This deviates from issue #128 and the design reference, which call for encouraging phrase buttons; the feedback field remains available.
