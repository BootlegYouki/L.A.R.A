# Mobile 2.5 review against final Mobile 2.4

## Integration reference

Original 2.5 head: `37894dc`. Latest 2.4 incorporated: `dda7585` (includes final 2.3 `adade1a`). Only AuthHost conflicted. Resolution preserves both role routes and 2.5's shared sync session. RoomEnrollmentRepository now supplies the canonical pull callback requested by 2.3, and SyncSessionViewModel implements EnrollmentSessionSource so learners share the existing socket. No 2.4 or shared contract code was redefined.

## Confirmed findings

### P2: a public-user update can erase an authenticated identifier

`SyncStore.apply` reads the existing user but computes lrnOrId entirely from the incoming optional field. A contract-valid PublicUser with only id/full_name/role overwrites the saved current user's identifier with null. The same mapping can erase previously authorized roster identifiers when an incoming name-only user is present. Preserve the previously authorized identifier when the optional field is absent, while continuing to strip identifiers from foreign learners on a learner device.

Reproduction: `identifier-regression.kt.txt` was temporarily compiled as SyncReviewProbeTest and run directly against the production Room SyncStore. It failed with `expected:<synthetic> but was:<null>`. The reproduction is a review artifact, not a passing feature test; the bug was not fixed in this integration review. Its source was removed from the normal suite after the run, and its failure output is retained in review-probes.txt.

### P1 external blocker: teacher queued comments cannot be uploaded against the mock

An authenticated teacher POST to the actual isolated `/api/sync/push` returned HTTP 403 / FORBIDDEN, even with an empty comments array. `scripts/mock_hub.py` restricts the whole route to STUDENT; OpenAPI does not specify that restriction and the product supports teacher comments. The mobile client retains the queue, but 2.5 cannot claim all-role offline uploads work. Correction belongs to the Lead-owned mock/contract workflow. No alternate endpoint or shared-file patch was added.

## Acceptance gaps / risks still to verify

- The live test's 96 ms figure measures an announcement on an already-connected socket, not network/Hub reconnection. Actual reconnect-to-Room under one second remains unproven. Auth reachability polls every 10 seconds, and immediate WorkManager requests append behind an existing retry chain; those paths need a true offline-to-online timing test.
- Repeat the full new learner join, teacher approval, learner unlock and offline cold launch through the final merged navigation. Prior phone results used older stacks or UI fixtures and cannot establish this exact wiring.
- Physical classroom Wi-Fi without WAN, long-running vendor/battery behavior, and target-phone heap certification remain unverified by this review.

## Verification performed

- Full `test lint assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL (10m 14s).
- Baseline: 104 tests discovered per variant, 102 passed, two opt-in tests skipped, zero failures.
- Shared contract/mock tests: 66 passed. Offline/localization invariants passed.
- Explicit actual-mock sync integration test passed; canonical enrollment IDs, pending-profile enrichment, learner approval snapshot, queued learner-comment receipt and live announcement persistence were exercised. Announcement event to Room: 96 ms.
- Separate identifier regression probe failed as described above. Passing baseline tests do not negate that finding.
- Existing debug suite was subsequently rerun with both opt-in mock tests enabled; see existing-suite-live.txt for its result.

No 2.5 PR was opened. This report does not declare issue #52 complete.
