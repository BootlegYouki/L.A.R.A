# Teacher approvals - MOBILE 2.4 (#51)

## Branch ancestry and scope

`codex/51-mobile-2.4-teacher-approvals` includes the final Mobile 2.3 fixes at `adade1a`, Mobile 2.2 and earlier work. The merge preserves the learner My classes / Join / Open navigation and the teacher-only approval panel. Mobile 2.5 remains separate.

## Runtime wiring

`AuthHost` now supplies the teacher approval panel through the teacher-only navigation action slot. Learners do not compose that panel. `TeacherApprovalPanel` reads saved classrooms from Room and limits controls to unarchived classes owned or co-taught by the signed-in teacher.

Each class mounts `TeacherApprovalHost`, which connects the pending badge and bottom sheet to its `RosterViewModel`. It starts event collection on foreground entry and stops on background/disposal. One session-owned WebSocket is shared across all class controls; it stops when there are no foreground subscribers or Hub reachability is lost. Expired credentials return to sign-in without clearing saved or queued work.

The bilingual bottom sheet shows one saved pending learner at a time, name and LRN, Accept/Decline and visible Cancel. Primary actions are 56dp and secondary controls at least 52dp. Offline/loading/empty/error states are provided. Optimistic decisions remove a pending row immediately and roll back on a failed request; successful server decisions are not rolled back merely because a later refresh failed. Concurrent join events request another refresh instead of disappearing during an in-progress request.

`SavedRosterRefresher` fetches the teacher roster through the authenticated, no-redirect transport. In a Room transaction it updates status and teacher-visible identity fields for existing enrollment/user rows only. Existing enrollment IDs, joined/updated timestamps and sync cursors are preserved. It never inserts an enrollment ID or treats the roster response as a delta-sync pull. Unknown enrollments are reported as a partial refresh; known rows remain readable.

## Dependency still outstanding

The roster and join contracts omit enrollment IDs. A newly joined learner therefore cannot become a new saved roster entry through these responses. Mobile 2.5 must populate authoritative classroom/user/enrollment rows, including the first class list on a fresh installation. No new sync scheduler, schema, cursor handling or invented enrollment identities are included here.

This finishes 2.4 wiring against existing saved data. It does not complete the full #51 acceptance flow on a fresh installation. After 2.5, replace the saved-row refresh boundary with the sync-owned refresh and verify new pending rows appear immediately and remain after an offline relaunch. The learner navigation and durable status notices from 2.3 are now included. Both roles still need authoritative enrollment rows from 2.5.

## How to test

1. Run the mock Hub. Sign in as the seed teacher (`T-0001`, development PIN `1234`). Use saved classroom and pending-enrollment fixtures from a test database, or authoritative data once 2.5 is integrated. A fresh install currently shows the honest no-saved-classes state.
2. In the teacher shell, open the pending-count button for a saved class. Accept or Decline a learner. Confirm the pending count falls and the roster refresh reflects the Hub decision.
3. Trigger a join request for that class. A foreground matching event refreshes the saved roster and opens the sheet when a saved pending row exists. Events for other classes do not open it.
4. Stop the Hub. Saved requests stay readable, actions disable, and an offline message appears. Restore the Hub and confirm refresh/reconnect.
5. Force an approval failure and confirm the learner returns to the pending list. Background the app and confirm event collection stops.

Automated protocol verification is opt-in: start a fresh `scripts/mock_hub.py` server instance on REST 18080 / WebSocket 18081, set `LARA_APPROVAL_MOCK=1`, then run `testDebugUnitTest --tests org.lara.app.data.remote.MockTeacherApprovalFlowTest`. It creates a synthetic learner, verifies HELLO, join request, approval and rejection events, and verifies the learner classroom response. It does not modify an installed session or prove phone UI behavior.

## Evidence

Validation results are recorded in `evidence/51/`. The final 2.3 navigation is included. Full new-enrollment/offline-relaunch acceptance still needs the authoritative-row producer from 2.5; standalone UI fixture tests do not replace that integration check.

## Recheck after final 2.3 changes

The six overlapping files were app dependency setup, AuthHost, LaraNavigation, RoleNavGraphs and both string dictionaries. Resolution retains both role-specific navigation slots, both repository factories and both sets of localized strings.

Remaining acceptance gaps:
- New classrooms and pending enrollment identities need the 2.5 producer; a clean install consequently has no saved teacher classes.
- Repeat new-request, approval, learner unlock and cold offline access on the final merged stack. Earlier combined-build phone evidence does not prove this updated standalone branch.
- Physical classroom LAN without WAN remains unverified. English and 200% Filipino approval UI, offline controls and induced-request-failure rollback passed on the connected phone using synthetic saved-roster fixtures (see below).

No 2.4 PR is opened while 2.3 awaits Lead review. This recheck does not implement sync or alter shared contracts.

## Approval UI fixes

`ApprovalLoadingSkeleton` supplies both the saved-class panel and roster sheet with pulsing token-colored placeholders and a localized, polite accessibility loading description. The skeleton leaves the loading state only when the Room-backed flow supplies content, including an empty result.

Accept uses PrimaryDark with Surface text (6.58:1 contrast). Pending labels, Decline and Cancel use PrimaryDark; badge text on PrimaryLight measures 5.84:1. Existing 56dp primary / 52dp secondary minimum targets are preserved, with vertical decision actions for large text. The sheet opens fully expanded; its title, banner and roster scroll together while Cancel remains in a fixed footer. This prevents a long Filipino offline message at 200% text size from consuming the roster and dismiss controls. Its white surface and 50% navy scrim use existing tokens.

`TeacherApprovalDeviceTest` exercises the production sheet and view model using synthetic saved-roster fixtures, including localized loading, automatic event opening, failed-request rollback, offline disabled actions, successful rejection and approval. It uses ActivityScenario and Android accessibility actions without adding a dependency. The Filipino case supplies the activity and dialog resources with Filipino and 200% text size, restoring their original configuration afterward; it does not change the phone's global language or font size. Screenshots contain synthetic fixture values only. It does not exercise discovery, sync or the final learner unlock.

Phone UI regression result: **2 tests passed**, including automatic sheet opening, rollback, offline controls, Accept and Decline in both configurations. Screenshots and final build output are in `evidence/51/ui-fixes/`. This closes the UI/rollback verification gaps for the production components, while the real data producer and final newly synced learner-access flow remain with 2.5.
