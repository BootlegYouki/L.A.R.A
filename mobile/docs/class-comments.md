# Class comments on announcements and handouts

Local implementation authorized by the user after verbal Lead approval on 18 November 2026. Shared contract/mock/test changes must be reviewed and landed as a separate contract change before the mobile feature PR is merged. Prepared for publication at the user’s request as a shared prerequisite and the existing mobile PR.

## Shared surface

Restores `announcement_comments`, `allow_comments`, announcement comment REST routes, `comments` in sync pull/push and COMMENTS_DISABLED receipts. Teachers and active learners may push public comments; private-comment and quiz pushes remain learner-only. UUID retries are idempotent and cannot move another author's comment or change a saved payload. Comment mutations send EVENT_ANNOUNCEMENT_PUSH to class teachers and active learners, triggering a normal delta pull. Existing private assignment threads are preserved.

Standalone materials receive an associated announcement atomically when uploaded. The material and its Stream post use that single thread. Existing standalone Hub materials must be backfilled before syncing; the Mock Hub seeds linked threads. Assignment attachments retain their private assignment discussion. Explicit attachment announcement IDs must belong to the same class.

## Mobile files and flow

- `ui/screens/stream/ClassDiscussion.kt`: full-screen post/handout detail, stable-key comment list, named author initials, timestamps, bottom input and Phosphor send action. English resources and existing Lara tokens; 52dp controls, 56dp send.
- `ClassroomLearningHost.kt`: Add class comment row on Stream cards; handouts in Classwork open the linked discussion; attachments still open the existing reader/video screens.
- Existing RoomStreamRepository, StreamDao, SyncRepository/SyncStore and account-specific databases provide local reads and QUEUED_FOR_SYNC writes. No new Room migration is needed: the existing v4 mobile schema already retained these columns and tables when the shared contract removed them.

A queued comment appears immediately. The composer attempts foreground synchronization after the durable save; WorkManager retries when the Hub is reachable; only a receipt marks it SYNCED. Rejected comments remain visible with an explanation. Cached threads remain readable offline, drafts survive configuration changes, blank comments cannot send, disabled/archived threads are read-only. A failed local save keeps the draft.

## Validation and limitations

Shared suite: 72 tests pass, including teacher handout comment sync and learner moderation rejection. Initial APK build and announcement tests pass. Final `assembleDebug --no-daemon --max-workers=2`: BUILD SUCCESSFUL. Full `test lint --continue --no-daemon --max-workers=2`: debug and release each ran 205 tests, 4 failures, 6 skipped. The four failures are existing StreamReadingTest/HomeworkRepositoryTest macOS `/var` versus `/private/var` path comparisons; the restored schema now passes parity. Lint reports the existing 3 errors in MaterialReader/VideoPlayerScreen, 78 warnings. No new discussion-file lint errors were reported. Logs are under `docs/evidence/class-comments/`.

This is a contract plus Mock Hub and mobile implementation. The Rust Hub has not been changed or launched. Real-Hub public-comment and handout-thread backfill support must be implemented by the server team before using this feature there. Existing six-digit Mock Hub / four-digit mobile sign-in mismatch remains; phone checks use the user's already-approved in-memory teacher PIN fixture and preserve the existing teacher UUID across Mock Hub restarts to avoid a saved-account identity conflict, never a source or phone-data change.

Google Classroom structure is used with L.A.R.A branding: discussion entry below a post, author initials/name/date, attached file above comments, composer at the bottom. Public comments contain no LRN. Unlike Google Classroom, no Google account avatar or cloud service is used.

## Physical OPPO check

Installed the ARM64 debug APK without clearing app data. Verified Stream comment entry rows, announcement thread, handout thread opened from Classwork, teacher comment saved locally with author/name/time, and successful receipt after refresh (queued badge disappears; Mock Hub sync push HTTP 200). Screenshots: `phone-stream.png`, `phone-announcement-thread.png`, `phone-handout-thread.png`, `phone-comment-queued.png`, `phone-comment-synced.png`. Initial WorkManager delivery was delayed on the OPPO, so a foreground sync attempt was added after durable save and rebuilt successfully. No real Rust Hub was used.

Final review build installed successfully on the OPPO. The helper text and duplicate top-bar heading were removed, and navigation-bar padding was confirmed on-device.

## Composer navigation inset correction

The full-screen discussion now consumes navigation-bar insets before keyboard insets, keeping the bottom comment field above Android's Back/Home/Recents area. The removed helper sentence remains absent. `assembleDebug --no-daemon --max-workers=2` passed for this adjustment. Before/after phone screenshots are recorded under `docs/evidence/class-comments/` when available.

Navigation inset fix installed successfully on the OPPO and confirmed in `phone-composer-insets-after.png`: the composer is above the three-button system navigation area. Keyboard-state verification was not completed.

The duplicate Class comments label was removed from the discussion top bar at the user's request. The top bar retains Back; the Class comments heading appears once above the thread.
