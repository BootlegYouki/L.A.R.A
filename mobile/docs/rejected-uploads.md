# Rejected uploads: Room version 2 (contract #196)

This focused mobile follow-up is built on `contract/sync-reject-reason`, as requested in discussion #194. It is separate from Settings #106; Settings can include it after the required contract/mobile review order. No shared schema, route, rule or mock implementation is edited by this mobile change.

## Storage and data flow

The four uploadable entities now accept REJECTED and expose nullable syncRejectReason mapped to the contract's sync_reject_reason column. Domain mappings carry that reason to consumers. SyncRepository writes successful/rejected receipts directly to the matching row version in a transaction. REJECTED rows are excluded from queue queries, so they never retry automatically. No outcome is newly stored/read under upload_receipt keys.

Pull upserts retain local queued/rejected content and reasons. Reset retains both statuses, all required parents and local attachment paths. Tombstones defer parent deletion when either kind of work remains. queued_hub bindings still prevent retained work being sent to another Hub. Official scores/feedback can still update separately.

## Upgrade

LaraDatabase version 2 registers RejectedUploadMigration (1 -> 2). Four ALTER TABLE statements add nullable columns without rebuilding or deleting tables. Room executes the upgrade transactionally and validates the resulting schema; destructive fallback is not enabled.

The migration recognizes the v1 worker's comment, private-comment and quiz fingerprints. A matching rejection moves its status/reason into the row. A stale receipt cannot reject a newer edit, and malformed metadata is ignored without logging contents. Legacy upload_receipt keys are removed after conversion, including obsolete successful receipts. V1 did not upload multipart homework or produce its receipts, so that new column starts null. Other metadata namespaces are preserved.

## Verification

SchemaParityTest compares the exported version 2 Room schema to the actual contract. The shared RejectedUploadMigrationTests builds a real version 1 file from its preserved export, seeds work and fixed legacy hashes, opens it through the production migration, and reopens it again. It checks statuses/reasons, newer edits, the homework path, profile, cursor, epoch and Hub binding. Android instrumentation uses the same test fixture and migration.

Shared sync regressions cover every rejected row type surviving reset/upsert/tombstones, no automatic retry, private-comment/quiz refusal reasons and a rejection arriving after a newer edit. Existing paging, receipts, interrupted requests, privacy, queue and actual-mock tests remain required. Final validation passed: 121 tests per JVM variant with both actual-mock flows enabled, lint and both APK builds, 66 root contract/mock tests and the invariant guardrail. All 32 phone cases passed, including the real upgrade. Current logs and limits are in mobile/docs/evidence/196/.

Homework multipart upload and future feature screens' localized rejection explanations remain with their owning features. This migration supplies durable statuses/reasons to those consumers; it adds no UI or upload route.
