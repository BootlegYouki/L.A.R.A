# Stream and handout reader — MOBILE 3.1 (#25)

## Staging prerequisite audit

This branch starts at `origin/staging` commit `7444312`, including merged #231. All original MOBILE Sprint 0–2 issues are closed and their feature PRs are present: #152 (spec), #160 (scaffold/theme), #164 (discovery), #182 (Room), #186 (navigation), #188 (authentication), #190 (join), #191 (approval), #195 (sync), #198 (rejection migration), #201 (Settings), and #209 (class management). The corresponding implementation files were inspected before changes. No earlier feature branch was used as a substitute for staging.

Existing `StreamDao`, `ClassworkDao`, Room repositories, `DeltaSyncWorker`, account-scoped databases, learner enrollment navigation and teacher role shell are reused. UI is English only under #223/#224; old bilingual issue wording is superseded.

## Design-system review

The first reading UI used plain text handout buttons and a single text block for empty states. The refinement follows design-system sections 5.5, 5.10–5.12: class banners gain a bundled subject icon and navy-tinted elevation; handouts use white bordered cards with blue type tiles and a saved-file check; topic headings have a green rule and item count; saved/queued/rejected states use an icon and a tonal pill. Stream posts retain the specified subtle fill and borderless shape,. Empty states separate their invitation heading from secondary explanatory text. Existing palettes, resource strings, large-text wrapping and 52/56dp action targets are retained; no gradients, dependencies or new product actions.

## Scope and data flow

`ui/screens/stream/ClassroomLearningHost.kt` supplies Stream and Classwork content to both existing role graphs. Learners enter an approved class through the existing class list. Teachers select from their saved classes. Classroom-style banners, post cards and topic-grouped handouts use existing L.A.R.A. tokens, Nunito and bundled icons. Other feature destinations remain owned by their later issues.

Announcements, topics, materials and extracted text come from Room flows. Announcement cards show text and attachments inline. The class comments action opens the announcement discussion, which reads from Room and saves new comments to the durable outbox. Private comments live on the assignment screen only (`HomeworkScreen.kt`).

`MaterialFiles` downloads only the fixed authenticated material route on the selected Hub, never the supplied arbitrary download URL. Redirects are refused. Transfers stream into a temporary account-private file, enforce the 250 MB contract cap, check completion/session continuity, and set `local_file_path` only after a successful transfer against the same material version. Sync preserves the cached path/MIME when material metadata is renamed or moved: the contract does not replace file bytes in place. After a pull commits, account-scoped cache pruning removes files no longer referenced by Room (including material tombstones), never before a transaction succeeds. Active downloads are not pruned. Missing downloads show saved extracted text and a nonblocking explanation. Opening a handout downloads its bytes; metadata sync alone does not imply its binary file has been downloaded.

`MaterialReader.kt` uses native Android PdfRenderer for one PDF page at a time, pinch/button zoom and Previous/Next navigation. Images are sampled; each raster is bounded to two million pixels and 2048 pixels on either edge. Page descriptors/renderers close immediately after rendering and bitmaps are released when replaced/disposed. DOCX/other documents read Hub-extracted chunks; this client does not parse DOCX or build AI prompts. Files without chunks explain that the tutor cannot help with that file.

## Contract/fixture limits to report

- Announcement schema has no author ID. The screen uses a class-announcement source label and neutral class icon; it never misattributes a co-teacher's post to the class owner. An actual posting-teacher avatar needs a Lead-owned contract change.
- The unmodified shared mock's material download route returns `%PDF-1.4 mock lesson handout` for every file, including uploaded images. This is not a valid PDF/image. The client must fail kindly and show cached chunks. Valid native PDF/image rendering is verified separately with synthetic fixture files; these must not be described as successful binary rendering against the unchanged shared mock.
- Real Hub Wi-Fi discovery, sign-in, class creation, empty reading screens and offline/reconnect checks passed on 6 November. Real content integration remains blocked by missing announcement routes (404) and material download/chunk stubs (501) on staging. Physical WAN-unplugged and budget-device certification remain unverified.

## Verification

Evidence and final acceptance status will be recorded under `mobile/docs/evidence/25/`. A passing initial build is not a completion claim; full regression and final screen/device verification are required.

Final verification: complete mock-enabled suites passed **146 tests per Debug/Release**, zero failures/errors/skips; lint zero errors; 66 shared checks and invariants passed. Final phone scenarios passed on Xiaomi 2311DRK48G, including real pinch, 200% text and first-name privacy. The full app also passed offline reconnect delivery exactly once, offline cold launch and both roles' reading entry points. A real download completion bug caused by endpoint object identity was fixed and regression-tested with newly constructed endpoints. See [evidence/25](evidence/25/README.md) for screenshots, exact commands, battery-constraint handling and remaining shared limits.

## Reproduce isolated phone scenarios

Create a temporary Gradle init script containing:

```groovy
allprojects { p ->
    p.afterEvaluate {
        def android = p.extensions.findByName('android')
        if (android != null && p.name == 'app') {
            android.defaultConfig.applicationId = 'org.lara.app.sprint31reading'
        }
    }
}
```

Build `assembleDebug assembleDebugAndroidTest` with `-I <init-script>`, install both APKs, then run:

```text
adb shell am instrument -w -r -e class org.lara.app.reading.HandoutDeviceTest,org.lara.app.reading.ReadingScreenDeviceTest org.lara.app.sprint31reading.test/androidx.test.runner.AndroidJUnitRunner
```

These synthetic fixture scenarios must use the isolated package: they write a test token/profile and leave fixture caches there. They do not require the mock Hub. Run JVM tests under the normal app ID to avoid this Windows machine's long test database paths. For the full app flow, start the unmodified mock, forward its REST/WS ports with ADB, connect through manual IP, and use the documented seed accounts. Remove forwarding to test Hub-unreachable behavior; restore it for reconnect. A low battery can legitimately hold background queued uploads until the worker's battery constraint is satisfied.
## Checking Stream comments and submissions

- Start the mock Hub from the repository root with `python3 scripts/mock_hub.py`, then connect the phones to the classroom network and enter the Hub's address in the app. The teacher signs in as `T-0001` and opens class `K7M-4QX`. Use PIN `1234` for the mock accounts.
- On a learner phone, sign in as `123456789013` and request the class. Confirm the waiting message, close and reopen the app, and check that the request is still pending. Submit a wrong code and check that the error is shown. In the teacher app, approve the real request, then reopen the class on the learner phone.
- As teacher and as approved learner, start an announcement. Check that Post is disabled until both text fields contain text. Pick two local files, remove one, then disconnect from the Hub. Close and reopen the composer and check that the remaining file and text are still present. Reconnect, tap Post, and open the new announcement to verify the file name and download. A file queued after the announcement is saved can retry from the details screen if the Hub drops.
- As a learner, edit your post, add another attachment, rename it, and delete the original attachment. Check that these controls are absent on a classmate's post. As a teacher, delete the learner's announcement and check that it disappears with its attachments after refreshing.
- In the Stream, open an announcement and add a multi-line comment. Check the space around the avatar, text, and composer. Open the keyboard and check that the Send button remains visible.
- On the learner phone, attach a file to an assignment and check the attachment icon and label under Your work. Turn it in. On the teacher phone, open the submission to see the same attachment style.
- As a teacher, open Classwork and select an assignment with a submission. Check its count under **To review**. Grade it, go back, and confirm that it appears under **Reviewed**. A score of zero also counts as reviewed.
- Disconnect from the Hub and reopen grading. Check the offline notice and verify that cached submissions remain in their respective tabs.
