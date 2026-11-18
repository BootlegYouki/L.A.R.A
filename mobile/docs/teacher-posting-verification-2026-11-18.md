# Teacher Stream Posting physical verification — 18 November 2026

Testing only. Branch `mobile/60-announcement-staging`, HEAD/staging `f82768f06e4848c8f374c7589aa054fbdc3b5e58`, with the existing staged integration of PR #323. No production code, existing changes, skills, index, commits or remotes changed during this verification. No real Rust Hub was launched.

Device: OPPO CPH2179 (replacement for requested Infinix), ARM64. ADB detected it with `ADB_LIBUSB=1`. Installed `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` using `adb install -r`; no existing L.A.R.A package was detected before installation. Mock Hub reached via `adb reverse` on 8080 and 8081 and manual `127.0.0.1:8080`. This verifies USB transport, not LAN discovery or WAN-disconnected Wi-Fi.

The documented `python3 scripts/mock_hub.py` server initially ran unchanged. Its six-digit seed PINs cannot be entered in the app's four-digit login. With explicit user approval, a disposable imported MockHubServer was restarted and the teacher PIN hash changed only in memory AFTER `start()` resets seed state. No shared file was edited. Learner protocol client retained the normal seed credentials. Mock Hub was stopped for the offline test and remains stopped.

Evidence paths below are relative to `docs/evidence/60/physical-verification-2026-11-18/` in mobile.

| Criterion | Result | Evidence / limits |
|---|---|---|
| Connected physical phone and ARM64 install | PASS | `adb-devices.log`; actual device is OPPO, not Infinix |
| Teacher opens class Stream | PASS | `phone-stream-before.png` |
| 56dp FAB opens full-screen dialog | PASS | `phone-composer.png`; `PrimaryTouchTarget=56.dp` and FAB uses it |
| Title/content edit; Post disabled when empty | PASS | Observed empty disabled Post, filled enabled Post; `phone-composer.png` |
| Comments toggle changes visually | PASS | Initially checked; cleared in `phone-composer.png` |
| Comments preference honored by Hub | FAIL | `AnnouncementApi` sends `allow_comments`; Mock Hub handler ignores it and current OpenAPI has no such property. Current PRD FR-2.1 says announcements have no comments |
| Valid announcement posts | PASS | `mock-hub-fixture.log` POST /api/announcements 200; `phone-stream-posted.png` |
| Success toast | PASS (ADB rendering evidence) | `adb-ui.log` shows app ColorToastLayout added after dialog dismissal; source emits announcement_posted. Screenshot missed toast text; exact displayed text was not visually captured |
| Feed refresh | PASS | `phone-stream-posted.png` contains phone-created post at top |
| Second client gets Mock Hub push | PASS (protocol client) | `second-client.log`: separately authenticated learner received EVENT_HELLO_ACK and matching EVENT_ANNOUNCEMENT_PUSH ID/title |
| Second application renders received post | NOT TESTED | Second client was Python WebSocket client, not another app/device |
| Offline Post disabled and Hub explanation visible | PASS | Valid filled draft in `phone-offline-draft.png`; disabled Post tap had no effect |
| No offline announcement queue | PASS | `offline-queue-check.log`: draft absent from announcements; all four sync_status tables have zero QUEUED_FOR_SYNC rows before/after disabled tap and Cancel |
| Cached Stream accessible with Hub stopped | PASS | `phone-offline-stream.png`, including the newly posted announcement, no blocking error |
| English strings in resources | PASS (source audit) | All composer UI strings use English resources. Seeded lesson/post content is Filipino data, not UI strings |
| Filipino UI translations | NOT TESTED / not applicable | Current root guide and rules/ui-and-accessibility.md require English only, superseding issue #60's historical bilingual requirement |
| Tokens and Phosphor icon | PASS (source audit) | Composer uses LaraColors/Shapes/Spacing, themed typography, bundled Phosphor pencil drawable |
| Minimum 52dp / primary 56dp | PASS (source audit) | 52dp title/checkbox/back targets; 56dp FAB and Post; content minLines=4. Exact hit rectangles and font scaling not instrumented |
| Design-system toast appearance | FAIL (source audit) | Native short Android Toast lacks prescribed success icon/title/description/close and four-second timing; requires Lead clarification or approved deviation |
| Gradle test/lint | FAIL | See below |
| Offline invariant guardrail | PASS | `invariants.log` |

## Gradle results and smallest proposed fixes

Requested `./gradlew test lint --no-daemon --max-workers=2`: failed debug tests, 205 tests, 19 failed, 6 skipped (`gradle-test-lint.log`). Most extra failures were stale constructor/method bytecode after the earlier integration.

Clean verification `./gradlew clean assembleDebug test lint --no-daemon --max-workers=2`: APK build succeeded; debug tests: 205 tests, 5 failed, 6 skipped (`gradle-clean-build-test-lint.log`). Both existing announcement tests pass (`test-summary.log`). Release tests and lint were not reached by this combined run after debug failure.

Separate `./gradlew lint --no-daemon --max-workers=2`: failed lintDebug, 3 errors, 79 warnings (`gradle-lint.log`, `lint-summary.log`). No fixes applied.

Remaining failures:

- Four path equality assertions in StreamReadingTest (2) and HomeworkRepositoryTest (2) compare `/var/...` to canonical `/private/var/...` on macOS. Smallest proposed fix: canonicalize expected and actual files in those assertions.
- SchemaParityTest hardcodes 16 contract tables, but the loaded contract contains 15. Smallest proposed fix: first reconcile the stale expected count with current contract and maintain full entity/table parity comparison. Do not add a table merely to satisfy the test.
- VideoPlayerScreen.kt:116 uses MediaMetadataRetriever.use requiring API 29 with minSdk 26. Smallest proposed fix: explicit try/finally with release() on supported minimum API.
- MaterialReader.kt:331 and VideoPlayerScreen.kt:113 report ProduceStateDoesNotAssignValue despite visible `value =` assignments in complex try/conditional expressions. Likely detector limitation; propose simplifying producer assignments for lint recognition, or narrowly suppressing with justification after review.
- Comments requirement conflicts with the current contract/PRD. Smallest contract-aligned proposal is removing the unsupported control/payload after Lead decision; adding moderation would require a separate authorized contract change first.
- Standard mock PIN length prevents unmodified app/mock sign-in. Shared fixture correction belongs to Lead ownership; this test used only the approved in-memory workaround.

## Review readiness

Not ready for approval/merge: comments contract mismatch, unresolved test/lint gates and toast design deviation. Online posting, refresh, protocol push reception and offline behavior are demonstrated on the physical OPPO with the documented fixture exception. No claim is made for Infinix hardware, second-app rendering, LAN discovery, WAN-unplugged operation, physical hit-area measurement, 200% fonts or real-Hub behavior.

New verification artifacts are unstaged. Existing staged integration and UI/UX Pro Max files were preserved. No commit, push, PR or merge was performed.
