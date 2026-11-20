# Teacher classwork authoring (MOBILE 3.5 / #127)

Teachers create and correct assignments, material posts, and topics in Classwork. Existing Stream posting remains available. Cached classwork reads from the account's Room mirror; authoring metadata requires the Hub. A topic deletion keeps its work under **No topic** for both roles. Assignment deletion archives the Hub record and retains turn-ins and grades.

## Key files and data flow

- `ui/screens/teacher/classwork/TeacherClasswork.kt`: Classroom-style Create menu, assignment/material/topic forms, item menus, confirmation dialogs, upload status.
- `ClassworkAuthoringViewModel.kt`: prevents repeated submits; remembers an already-created assignment and successfully enqueued attachments when a later attachment fails.
- `domain/ClassworkAuthoringRepository.kt`: UI boundary and draft/status models.
- `data/repository/ClassworkAuthoringRepository.kt`: contract mutations, attachment staging, durable work requests and retry observation.
- `data/remote/ClassworkAuthoringApi.kt`: authenticated fixed REST routes, bounded responses, redirect rejection, disk-streamed multipart body.
- `data/sync/MaterialUploadWorker.kt`: foreground WorkManager execution, progress, transient backoff, account/Hub binding.
- `LaraApp.kt`: account-scoped wiring and post-mutation refresh. Refresh failure schedules sync without repeating a successful write.

POST/PATCH/DELETE use the existing materials, assignments, and topics contracts. Files attach through material `assignment_id` or `announcement_id`, never both. Material creation lets the teacher select an existing assignment or announcement; assignment forms also add and remove attachments. The Hub creates linked threads for standalone materials. Metadata receipts trigger the existing atomic delta pull; no cursor/schema changes are introduced.

The system document picker grants temporary read access. Before enqueueing, the repository copies to app-private persistent storage with a 32 KiB buffer. This owns the bytes, so WorkManager never depends on a temporary URI grant or an external provider remaining available. Known file sizes over 250 MiB are rejected before reading; unknown sizes are counted and stopped at the same limit. Partial staging files are removed. Staging and upload use IO threads, never whole-file byte arrays. The worker rechecks the limit, streams from disk, and removes a successful upload's staged copy.

WorkManager persists filenames and metadata, not bearer tokens. Every execution reloads the current encrypted session and requires the original owner and Hub. LAN connections do not need internet validation. Foreground upload notifications support long transfers. Connection errors, 429, and 5xx retry with exponential backoff (six attempts total); terminal failures retain their private file and show Retry upload. Retrying replaces the failed status in the UI without globally pruning other workers. No client database columns were added: upload execution state lives in WorkManager, and mirrored classwork stays in Room.

## Gotchas and deliberate differences

- Upload retries restart the multipart request from byte zero. The contract has neither resumable material uploads nor an idempotency key; a response lost after Hub acceptance can leave a duplicate material. Do not claim exactly-once uploads.
- Private staging temporarily needs disk space equal to each selected file. Failed files remain available for retry. A provider read or disk-space failure retains the form and shows recovery text.
- If an assignment was created before an attachment failed, retry edits that saved assignment and skips already-enqueued attachments. Leaving that form does not undo an already-saved assignment.
- Material metadata supports title/topic only: no invented material description, draft scheduling, recipient selection, Drive, links, or Google-only controls.
- Forms reuse `ClassFormDialog`; Create stays at the top left per design-system 9.5. Date/time use native Android pickers. Item deletion and abandoning edits require confirmation.
- English and Filipino resources for this feature follow the user's explicit task instruction, an exception to the repository's English-only rule. This does not add a language selector. Existing unrelated screens still use their existing resources; default strings provide fallback.
- Staging currently has a separate four-digit Mobile sign-in vs six-digit contract/Mock Hub PIN mismatch. Automated transport fixtures authenticate with the contract PIN without changing the Mock Hub or production authentication. This bypass is test-only; it does not establish that normal Mobile login works against the unchanged seed.

## How to test (for teachers and reviewers)

Ask the test operator to start the Mock Hub (`python3 scripts/mock_hub.py`) and install the Mobile APK. Use a synthetic teacher and learner account in Science 4. The operator must resolve the existing PIN mismatch or set up a documented test session; do not use real learner data.

1. Open Science 4, then **Classwork → Create → Topic**. Enter “Unit 1” and save. It should appear as a heading.
2. Choose **Create → Assignment**. Enter a title, instructions, points, due date/time, late-work choice and Unit 1. Save. On the learner's phone, refresh once: the assignment should appear.
3. On the teacher phone, use the assignment's three-dot menu → **Edit**, change its title, and save. Refresh the learner once: the new title should appear.
4. Edit the assignment, choose **Add attachment**, and select a small PDF from Files. Save. Watch its progress in Classwork and open the attachment on the learner after syncing.
5. Choose **Create → Material** or **Video**. Give it a title, choose one matching file, and save. To attach to an existing announcement or assignment, select it under **Attach to** before saving. Multiple files are allowed on an assignment; individual material posts use one file.
6. Try a file larger than 250 MB. It must show the size explanation and must not start a network upload. The operator can supply a synthetic large test file; never use private documents.
7. During a large upload, put the app in the background. Ask the operator to stop and restart the test Hub or inject a temporary failure. Uploads should retry with backoff; a final failure should offer **Retry upload** after the connection is fixed. Keep vendor battery optimization on and record the phone model.
8. Delete Unit 1 using its menu and confirm. The assignment must remain under **No topic** on both phones after refreshing.
9. Delete the assignment and read the confirmation: turn-ins and grades stay on the Hub. After one learner refresh it should disappear from active classwork.
10. Ask the operator to stop the Hub. Reopen Classwork: saved items must remain visible, with a nonblocking Hub explanation. Save actions must be disabled. Restart the Hub and refresh.
11. Repeat a form at the largest text size and in landscape. All controls should remain reachable by scrolling. Check both English and Filipino if the operator changes the phone's system language, then restore its original setting.

## Automated checks and evidence

Use one Gradle worker on the 8 GB Intel Mac. The canonical temporary directory avoids the existing macOS `/var` versus `/private/var` test-path mismatch:

```sh
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/private/tmp ./gradlew test lint --continue --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1536m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/private/tmp' \
  -Pkotlin.compiler.execution.strategy=in-process
```

`ClassworkMockTest` is opt-in with `LARA_AUTHORING_MOCK=1` and an unchanged Mock Hub on 38080/38081. It verifies Room after exactly one learner pull for each create/edit/topic-delete/assignment-delete step. `MaterialStreamingTest` checks the 250 MiB boundary without allocating a 250 MiB array. `ClassworkAuthoringApiTest` checks multipart fields, streaming progress, redirects and Hub binding. The ViewModel test verifies partial attachment failure/retry without creating a second assignment or duplicating prior enqueues.

Physical evidence and actual outcomes belong in `evidence/127/README.md`; do not infer LAN/WAN, real-Hub, vendor-kill or Android Studio profiler results from transport/unit fixtures.
