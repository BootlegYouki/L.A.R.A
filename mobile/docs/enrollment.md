# Class enrollment - MOBILE 2.3 (#50)

## Implemented components

This branch builds on Mobile 2.2 (`90a4c1f`), including the earlier mobile milestones. It deliberately leaves Mobile 2.5 sync implementation to that issue.

- Grouped XXX-XXX class-code dialog, normalization, bilingual invalid-code and offline feedback, and 56dp primary action.
- Bearer-authenticated join and classroom-list requests, with session/user/address validation, bounded responses and redirects disabled.
- Foreground WebSocket lifecycle: HELLO first, approval filtering by signed-in learner, handshake timeout, 1/2/4/8/10-second reconnect backoff, and expired-session handling.
- Enrollment screen, lifecycle host and view model for pending/approved/rejected/removed notices. Existing canonical Room enrollment rows supply saved classes; only ACTIVE rows enable opening. Restrictive approval notices immediately disable stale access.
- Explicit required refresh callback for a future sync owner. Approval notices never create enrollment records or grant access by themselves.

OkHttp 4.12 supplies the Android WebSocket transport; MockWebServer is test-only. No sync scheduler, cursor or schema changes are included.

## Remaining integration

These components are not mounted in AuthHost or the learner shell yet. Full issue #50 is not complete and no end-to-end phone behavior is claimed.

The join response, classroom list and approval event omit the authoritative enrollment ID required by Room. Mobile 2.5 must supply authoritative enrollment rows through its sync transaction. No temporary enrollment IDs or substitute sync implementation are introduced here.

When that layer is available, connect the required refresh callback and session/reachability owners, mount the screen, and verify teacher approval unlocks the saved class and survives offline relaunch. Mock-Hub and final physical-device evidence remain required. Future sprint work stays with its own issue, as requested.

## Validation

`gradlew.bat test lint assembleDebug` passed (2m 10s). All 59 debug unit tests and the release suite pass, including existing authentication tests and new transport, WebSocket, Room-reader and view-model coverage. Lint and APK assembly pass. See `evidence/50/implementation-checks.txt`. Earlier presentation-only validation remains in `preparation-checks.txt`.
