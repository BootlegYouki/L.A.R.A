# Post an announcement on mobile

On a teacher's class Stream, the pencil button opens a full-screen form. Enter a title and announcement, and choose whether learners can comment. Posting requires a reachable Hub. The saved Stream remains available offline.

`PostAnnouncement.kt` owns the draft and submission state. `AnnouncementApi` sends the authenticated `POST /api/announcements` request. A successful response closes the form, shows a confirmation, and pulls the latest changes into the Room-backed Stream. WorkManager covers a failed refresh. Enrolled learner clients refresh through the existing `EVENT_ANNOUNCEMENT_PUSH` handler. A failed request keeps the draft in the form. If the connection drops after sending, check the Stream before retrying because the Hub might have accepted the post.

## How to test

1. Start `python3 scripts/mock_hub.py` on a local network with the router's WAN disconnected. Sign in on one client as the seeded teacher and on a second client as an enrolled learner.
2. Open the same class on both clients. On the teacher's Stream, tap the pencil, enter an announcement, deselect **Allow learner comments**, then tap **Post**.
3. Verify the confirmation and that the announcement appears on both Streams. Verify the learner cannot add a comment or open the posting form.
4. Open the teacher's form again with a new draft. Stop the Hub. Verify that Post becomes disabled with the Hub message and that the draft remains visible. Close the form and verify the cached Stream remains readable.
5. Repeat the online flow against the real Hub and record the online and offline screen states for the PR.

## Validation

- The Android emulator posted to the mock Hub. The teacher Stream refreshed, and a separate learner WebSocket client received the push and synced the matching announcement with comments disabled. [Composer](evidence/60/mock-composer.png) · [Teacher Stream](evidence/60/mock-posted.png)
- Stopping the mock Hub disabled Post with both draft fields filled. The teacher could close the form and read the cached Stream. [Offline form](evidence/60/mock-offline.png)
- The emulator posted to a disposable headless Rust Hub with test accounts and showed the announcement in the refreshed Stream. [Real Hub Stream](evidence/60/real-hub-posted.png)
- `gradlew.bat --offline --no-daemon --max-workers=2 test lint` passed. Debug and release each ran 205 unit tests. All 71 repository tests and the offline invariant check passed.

The emulator reached both Hubs over ADB loopback. A physical handset and router with the WAN unplugged were unavailable for this run.

## Design deviations

The phone composer uses the full-screen dialog and 56dp button specified in #60. It keeps the required title field and uses a checkbox row for learner comments because the choice takes effect on Post. The confirmation uses Android's native toast.
