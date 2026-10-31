# Teacher approvals - MOBILE 2.4 (#51)

## Branch ancestry

`codex/51-mobile-2.4-teacher-approvals` starts at Mobile 2.3 commit `3471861`, preserving Mobile 2.2 and all earlier mobile work. The incomplete 2.3 integration is inherited; this branch does not mark it complete.

## Components

- `ApprovalSheet`: bilingual pending-request sheet showing one learner at a time, learner name and LRN, Accept/Decline, visible Cancel, loading/empty/offline/error states. Primary actions are 56dp; secondary actions are at least 52dp.
- `PendingApprovalButton`: pending count and badge derived from saved Room roster rows with transient optimistic decisions.
- `RosterViewModel`: foreground event subscription, class-code filtering, automatic sheet opening after a matching join request and saved roster refresh, duplicate-action guard, optimistic removal and rollback on failed decisions. Offline actions are disabled. A successful server decision remains optimistic until Room confirms it; a refresh failure does not pretend the server decision failed.
- `ClassManagementRepository`: authenticated fixed roster/approve/reject routes using the existing session validation and no-redirect Hub transport. Teacher authorization is enforced by the Hub. Sensitive roster/event objects redact their default string representations.
- `RealtimeClient`: extends the 2.3 HELLO/reconnect transport with validated `EVENT_JOIN_REQUEST` decoding and delivery after acknowledgement.

## Remaining integration

This is component work, not a completed issue or phone-visible feature. The teacher shell must mount the sheet and count, supply the session-owned foreground event stream and reachability state, and connect the required saved-roster refresh callback. The existing Room roster repository remains the source of displayed identities and enrollment status.

The roster contract provides student IDs but no enrollment IDs. Authoritative enrollment insertion stays with Mobile 2.5 sync, as for 2.3. No fake enrollment IDs, new cache schema or sync scheduler are added. The roster transport is available for the integration owner; it must not be used to overwrite canonical enrollment rows with invented identities.

Before marking #51 complete, verify against the mock Hub that a new learner request opens the mounted sheet, Accept unlocks the learner, Decline updates status, failed actions restore the pending row, reconnect catches missed requests, and saved pending requests remain visible with the Hub stopped. Final phone screenshots and end-to-end evidence remain outstanding.


## Validation

Final mobile test, lint and debug APK assembly passed (1m 32s): 67 debug unit tests and the release suite. All 66 repository tests passed. Logs are in evidence/51/. No mounted-screen or physical-phone verification is claimed.
