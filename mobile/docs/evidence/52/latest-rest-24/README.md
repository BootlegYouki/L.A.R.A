# Mobile 2.5 after the independent 2.4 REST fixes

Base: Mobile 2.5 `21eb2d2`, merged with Mobile 2.4 `d140e77` (including staging `f17bcb5` and roster contract PR #192).

The teacher panel retains fresh REST class/roster bootstrap, canonical `enrollment_id`, early join hints and lifecycle handling, while consuming the single shared sync socket. Teacher REST snapshots use the same sync mutex so their requests cannot interleave with a pull.

The [Lead's carry-over instruction](https://github.com/BootlegYouki/L.A.R.A/issues/52#issuecomment-6555538621) is implemented: learner access reads synced enrollments only; the temporary REST access implementation is removed. Room clears all legacy `rest_class_access:<user>` keys on open/upgrade, with no destructive migration or schema change. Persistent-database tests verify canonical rows, cursor, profile and queued comments survive cleanup. REST ACTIVE notices and old metadata cannot grant access without a canonical enrollment.

REST fetch/request-notice saves run under the sync mutex. Pulling own canonical enrollment changes reconciles the request notice atomically with the cursor. Regression tests cover stale pending notices, slow REST racing a pull, reset closing old access and queued work surviving both operations. No enrollment IDs are invented. The live approval test retains independent teacher REST bootstrap but now verifies learner access using canonical synced enrollment IDs across database reopen.

## Automated checks

- `gradlew.bat --max-workers=2 test lint assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL; 117 tests per Debug/Release variant, zero failures, zero skips. Both live mock tests enabled using `LARA_SYNC_MOCK=1` and `LARA_APPROVAL_MOCK=1`.
- Lint: zero errors; 27 existing warnings.
- `python -m unittest discover tests`: 66 tests, OK.
- `python scripts/verify_invariants.py`: all offline invariants/localization checks passed.
- Actual local mock announcement event to Room: 49 ms (Debug).
- Injected network-regained hint through the actual Hub probe/pull to Room: 44 ms (Debug).

The reconnect measurement injects a network-change signal; it is not evidence for a physical router/WAN outage or all vendor battery restrictions. Full Gradle output is in `checks.txt`.

## Remaining external limitation

The shared mock's `POST /api/sync/push` still restricts callers to STUDENT even though the contract permits teacher-authored comments. Teacher queued uploads receive 403 and remain saved. This mobile integration does not modify Lead-owned mock or contracts; #52 is not declared fully complete while that mismatch remains. Homework multipart uploads remain with their later sprint.

Only the 2.5 branch is being prepared. No new PR is opened while Mobile 2.4 PR #191 awaits review/merge.

## Final phone build

The final isolated package org.lara.app.sprint25integrated and its test APK build successfully. ADB lost the phone before installation, so this final canonical-access/upgrade build is not yet device-verified. Earlier phone evidence remains historical and is not represented as validation of these latest changes. The original installed app and its data were not modified.
