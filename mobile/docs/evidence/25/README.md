# MOBILE 3.1 implementation and verification

Branch: `codex/25-mobile-3.1-stream-reader`, from staging `7444312` (including merged #231). Earlier Sprint 0–2 mobile feature files and dependencies were inspected; see `../../stream-reader.md` for the audit and data flow.

The new Stream/Classwork layout follows the project’s Google Classroom reference while preserving L.A.R.A.’s flat green theme, Nunito, bundled icons and phone touch sizes.

## Automated verification

- Full mock-enabled `test lint assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL in 4m32s, 145 tests per Debug/Release, no failures/errors/skips.
- A final interrupted-transfer case was then added. All six file/comment regressions passed in both variants. The combined unique coverage is 146 cases per variant; this is not represented as one full 146-case run.
- Lint: zero errors, 24 existing warnings. Shared contract/mock checks: 66 passed. Offline invariants passed.
- `full-checks.txt`, `file-regressions.txt`, `test-counts.txt`, `shared-checks.txt`, `invariants.txt` contain output.
- On this Windows PC, JVM tests under the longer isolated phone application ID hit three file-backed SQLite-open errors. The normal-package full run passes with every case enabled. Phone APKs use a separate package; no tests were disabled to obtain the pass.

## Phone progress (not final acceptance)

Xiaomi 2311DRK48G, isolated `org.lara.app.sprint31reading`, original `org.lara.app` untouched. The first two phone fixture tests passed (13.731s): native PDF page rendering and image sampling, plus offline component flows for queued comments, PDF page navigation/button zoom, image zoom, DOCX chunks, empty and loading states. Initial screenshots were reviewed; the page controls were improved for 200% text afterward.

Final pinch gesture, first-name privacy, extra large-text/empty cases, final-build screenshots and full app live mock/offline/reconnect checks are prepared but pending the phone being unlocked. The phone locked itself; an unlock request is pending. The temporary USB stay-awake setting and USB forwarding routes were restored while awaiting unlock. Initial screenshots are not published as final-build evidence.

## Limits to report to the Lead

1. Announcement records have no author ID. The UI uses a neutral class icon and class-announcement source label rather than inventing an author. Actual posting-teacher avatars need a Lead-owned contract change.
2. The unmodified mock returns a non-renderable PDF placeholder for every material download. The app falls back to saved chunks. Native valid PDF/image behavior is tested with local synthetic fixture files, not claimed as a successful binary render from the unchanged mock.
3. Handout binary files are saved when opened; metadata/chunks sync alone does not mean every binary file was downloaded. Cached files/text remain available offline. Real Rust Hub, physical WAN-disconnected router and budget-phone certification are not claimed here.
