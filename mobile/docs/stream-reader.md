# Stream and handout reader — MOBILE 3.1 (#25)

## Staging prerequisite audit

This branch starts at `origin/staging` commit `7444312`, including merged #231. All original MOBILE Sprint 0–2 issues are closed and their feature PRs are present: #152 (spec), #160 (scaffold/theme), #164 (discovery), #182 (Room), #186 (navigation), #188 (authentication), #190 (join), #191 (approval), #195 (sync), #198 (rejection migration), #201 (Settings), and #209 (class management). The corresponding implementation files were inspected before changes. No earlier feature branch was used as a substitute for staging.

Existing `StreamDao`, `ClassworkDao`, Room repositories, `DeltaSyncWorker`, account-scoped databases, learner enrollment navigation and teacher role shell are reused. UI is English only under #223/#224; old bilingual issue wording is superseded.

## Scope and data flow

`ui/screens/stream/ClassroomLearningHost.kt` supplies Stream and Classwork content to both existing role graphs. Learners enter an approved class through the existing class list. Teachers select from their saved classes. Classroom-style banners, post cards, flat comments and topic-grouped handouts use existing L.A.R.A. tokens, Nunito and bundled icons. Other feature destinations remain owned by their later issues.

Announcements, comments, public author names, topics, materials and extracted text come from Room flows. Learners' comment names are reduced to first names; the name projection contains no LRN. Comments are inserted as `QUEUED_FOR_SYNC` and use the existing worker/receipt path. The local allow-comments check and insertion share a Room transaction. Rejected comments stay visible; failed local saves retain the draft.

`MaterialFiles` downloads only the fixed authenticated material route on the selected Hub, never the supplied arbitrary download URL. Redirects are refused. Transfers stream into a temporary account-private file, enforce the 250 MB contract cap, check completion/session continuity, and set `local_file_path` only after a successful transfer against the same material version. Sync preserves the cached path/MIME when material metadata is renamed or moved: the contract does not replace file bytes in place. After a pull commits, account-scoped cache pruning removes files no longer referenced by Room (including material tombstones), never before a transaction succeeds. Active downloads are not pruned. Missing downloads show saved extracted text and a nonblocking explanation. Opening a handout downloads its bytes; metadata sync alone does not imply its binary file has been downloaded.

`MaterialReader.kt` uses native Android PdfRenderer for one PDF page at a time, pinch/button zoom and Previous/Next navigation. Images are sampled; each raster is bounded to two million pixels and 2048 pixels on either edge. Page descriptors/renderers close immediately after rendering and bitmaps are released when replaced/disposed. DOCX/other documents read Hub-extracted chunks; this client does not parse DOCX or build AI prompts. Files without chunks explain that the tutor cannot help with that file.

## Contract/fixture limits to report

- Announcement schema has no author ID. The screen uses a class-announcement source label and neutral class icon; it never misattributes a co-teacher's post to the class owner. An actual posting-teacher avatar needs a Lead-owned contract change.
- The unmodified shared mock's material download route returns `%PDF-1.4 mock lesson handout` for every file, including uploaded images. This is not a valid PDF/image. The client must fail kindly and show cached chunks. Valid native PDF/image rendering is verified separately with synthetic fixture files; these must not be described as successful binary rendering against the unchanged shared mock.
- Real Hub Wi-Fi discovery, sign-in, class creation, empty reading screens and offline/reconnect checks passed on 6 November. Real content integration remains blocked by missing announcement routes (404) and material download/chunk stubs (501) on staging. Physical WAN-unplugged and budget-device certification remain unverified.

## Verification

Evidence and final acceptance status will be recorded under `mobile/docs/evidence/25/`. A passing initial build is not a completion claim; full regression and final screen/device verification are required.

Final verification: complete mock-enabled suites passed **146 tests per Debug/Release**, zero failures/errors/skips; lint zero errors; 66 shared checks and invariants passed. Final phone scenarios passed on Xiaomi 2311DRK48G, including real pinch, 200% text and first-name privacy. The full app also passed offline comment/reconnect delivery exactly once, offline cold launch and both roles' reading entry points. A real download completion bug caused by endpoint object identity was fixed and regression-tested with newly constructed endpoints. See [evidence/25](evidence/25/README.md) for screenshots, exact commands, battery-constraint handling and remaining shared limits.

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
