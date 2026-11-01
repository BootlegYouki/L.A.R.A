# Rejected-upload migration verification

Base: contract/sync-reject-reason at 0971ce3 (PR #196), as requested by the Lead in discussion #194. This change is separate from the saved Settings 2.6 branch.

## Automated checks

- Final `gradlew.bat --max-workers=2 test lint assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- Debug and Release: 121 tests each, zero failures, zero skips; both actual-mock wrappers enabled.
- Lint: zero errors, 27 existing warnings.
- Root contract/mock tests: 66 tests, OK.
- Offline/localization guardrail: PASSED.

Full Gradle output is in checks.txt. Tests use synthetic accounts and local Hubs only.

The shared real-upgrade fixture builds a version-1 database from its unchanged exported schema and opens it with the production 1-to-2 migration. Fixed legacy hashes verify compatibility with previously stored rejection receipts. Matching comment/private-comment/quiz refusals migrate into row columns; a newer edited comment stays queued. Attachment paths, cursor 42, epoch 7, cache owner and old-Hub binding survive; reopening preserves the result. Current SchemaParityTest validates all 16 tables against the #196 contract.

Sync regressions verify rejected comments, private comments, homework metadata and quiz attempts survive a reset, incoming cache upserts and parent tombstones. Rejected private-comment/quiz receipts persist on their rows and never retry. An old rejection cannot reject a newer in-flight edit. Upload outcomes are no longer written to sync_state; only legacy migration reads them.

## Verification limits

This adopts the shared client's storage contract and existing receipt behavior. It adds no UI, server route or multipart uploader. The real Rust Hub, physical WAN-disconnected classroom network, vendor battery behavior and budget-device certification remain integration/field follow-ups. The previously reported teacher sync-push 403 in the shared mock is not changed by this migration.

## Physical phone checks

All 32 tests passed on the connected Xiaomi 2311DRK48G in the separate org.lara.app.migration196 test package: 27 shared sync behaviors, one real version-1-to-version-2 migration, one actual-mock sync flow, one teacher REST/canonical learner approval flow and two existing English/Filipino approval UI regressions (including 200% text). Device output is in device-tests.txt. The main app and its data were not changed or cleared.

USB reverse connected only the isolated test Hubs. These checks validate actual Android SQLite/Room migration behavior; they do not claim physical WAN/router, long-running battery or 3–4 GB target certification.
