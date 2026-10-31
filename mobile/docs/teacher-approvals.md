# Teacher approvals - MOBILE 2.4 (#51)

## Branch ancestry and scope

`codex/51-mobile-2.4-teacher-approvals` includes Mobile 2.3 commit `3471861`, Mobile 2.2 and earlier mobile work. The incomplete 2.3 integration remains incomplete. Mobile 2.5 is deliberately separate, as requested.

## Runtime wiring

`AuthHost` now supplies the teacher approval panel through the teacher-only navigation action slot. Learners do not compose that panel. `TeacherApprovalPanel` reads saved classrooms from Room and limits controls to unarchived classes owned or co-taught by the signed-in teacher.

Each class mounts `TeacherApprovalHost`, which connects the pending badge and bottom sheet to its `RosterViewModel`. It starts event collection on foreground entry and stops on background/disposal. One session-owned WebSocket is shared across all class controls; it stops when there are no foreground subscribers or Hub reachability is lost. Expired credentials return to sign-in without clearing saved or queued work.

The bilingual bottom sheet shows one saved pending learner at a time, name and LRN, Accept/Decline and visible Cancel. Primary actions are 56dp and secondary controls at least 52dp. Offline/loading/empty/error states are provided. Optimistic decisions remove a pending row immediately and roll back on a failed request; successful server decisions are not rolled back merely because a later refresh failed. Concurrent join events request another refresh instead of disappearing during an in-progress request.

`SavedRosterRefresher` fetches the teacher roster through the authenticated, no-redirect transport. In a Room transaction it updates status and teacher-visible identity fields for existing enrollment/user rows only. Existing enrollment IDs, joined/updated timestamps and sync cursors are preserved. It never inserts an enrollment ID or treats the roster response as a delta-sync pull. Unknown enrollments are reported as a partial refresh; known rows remain readable.

## Dependency still outstanding

The roster and join contracts omit enrollment IDs. A newly joined learner therefore cannot become a new saved roster entry through these responses. Mobile 2.5 must populate authoritative classroom/user/enrollment rows, including the first class list on a fresh installation. No new sync scheduler, schema, cursor handling or invented enrollment identities are included here.

This finishes 2.4 wiring against existing saved data. It does not complete the full #51 acceptance flow on a fresh installation. After 2.5, replace the saved-row refresh boundary with the sync-owned refresh and verify new pending rows appear immediately and remain after an offline relaunch. The actual learner app unlock still needs 2.3 integration as well.

## How to test

1. Run the mock Hub. Sign in as the seed teacher (`T-0001`, development PIN `1234`). Use saved classroom and pending-enrollment fixtures from a test database, or authoritative data once 2.5 is integrated. A fresh install currently shows the honest no-saved-classes state.
2. In the teacher shell, open the pending-count button for a saved class. Accept or Decline a learner. Confirm the pending count falls and the roster refresh reflects the Hub decision.
3. Trigger a join request for that class. A foreground matching event refreshes the saved roster and opens the sheet when a saved pending row exists. Events for other classes do not open it.
4. Stop the Hub. Saved requests stay readable, actions disable, and an offline message appears. Restore the Hub and confirm refresh/reconnect.
5. Force an approval failure and confirm the learner returns to the pending list. Background the app and confirm event collection stops.

Automated protocol verification is opt-in: start a fresh `scripts/mock_hub.py` server instance on REST 18080 / WebSocket 18081, set `LARA_APPROVAL_MOCK=1`, then run `testDebugUnitTest --tests org.lara.app.data.remote.MockTeacherApprovalFlowTest`. It creates a synthetic learner, verifies HELLO, join request, approval and rejection events, and verifies the learner classroom response. It does not modify an installed session or prove phone UI behavior.

## Evidence

Final validation results are recorded in `evidence/51/`. Physical-phone UI evidence remains outstanding because no Android device was connected during this wiring session. Full new-enrollment/offline-relaunch acceptance remains blocked on 2.5 and 2.3 integration; no completion claim or closing PR is made.
