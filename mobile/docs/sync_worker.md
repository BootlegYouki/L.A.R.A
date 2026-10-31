# Delta sync - MOBILE 2.5 (#52)

## Base and implementation

Branch `codex/52-mobile-2.5-delta-sync` starts exactly at Mobile 2.4 commit `6b174a6`. It includes the earlier mobile work, including incomplete 2.3. No shared contracts, server code or schema columns are changed.

- `SyncTransport`: fixed authenticated pull/push routes, session/address validation, no redirects, 5-second timeouts and an 8 MiB pull-response bound. Initial requests omit absent optional Hub identity fields.
- `SyncRepository`: serializes foreground/background sync, follows `has_more`, pushes bounded batches of the current user’s submitted quiz attempts and comments, then pulls authoritative changes. An unfinished quiz or another author’s work is never uploaded.
- `SyncStore`: applies a fully decoded page, public users, upserts, tombstones and `hub_id`/`sync_epoch`/integer `cursor` in one Room transaction. Fetch/decode failure changes no database state; apply failure rolls the page back. Stale concurrent responses cannot advance the cursor.
- `DeltaSyncWorker`: success when no account/endpoint exists; retries transport errors, 429 and server failures; reports authorization/permanent payload failures without clearing saved work.
- `SyncScheduler`: unique 15-minute periodic work, unique immediate chains, battery-not-low and exponential retry. Connectivity callbacks, session startup and offline comment writes enqueue work. `NetworkType.NOT_REQUIRED` permits classroom Wi-Fi without internet validation; the transport must still reach and authenticate with the selected Hub.
- `SyncSessionViewModel`: one foreground socket shared with teacher controls. HELLO/reconnect and announcement events trigger a direct pull and queued worker retry. Lifecycle disposal/background stops socket subscriptions. No UI reads remote responses directly.

## Enrollment visibility and 2.4 integration

A learner can have an advanced cursor before joining a class. Approval makes older classroom records visible without rewriting their revisions. If a delta contains a newly ACTIVE own enrollment or missing parent records, sync rescans from cursor zero without wiping existing data. Each rescan page still commits atomically. The rescan is performed once per pull sequence to avoid paging loops.

The teacher panel now refreshes through canonical sync before its authorized roster lookup. Enrollment IDs come from sync. The mock pull omits PENDING learner profiles; the roster supplies their actual student ID/name/LRN and populates the user profile for an already saved enrollment. No enrollment ID is generated locally.

This provides 2.3’s sync dependency, but does not mount its learner join screen or declare #50 complete. That remaining integration and phone verification stay with 2.3.

## Reset, deletion and queued work

A reset deletes mirrored rows in dependency order while retaining every `QUEUED_FOR_SYNC` row and all required announcement/assignment/quiz/classroom ancestors. Local AI history and its class parent are retained. The signed-in profile is preserved. Queued rows are bound to the previous Hub ID before reset; work belonging to a different Hub is retained but not uploaded to the new Hub.

A tombstone cannot cascade through queued work. Blocked deletions are stored and retried on later pulls, so a deletion is not forgotten after its cursor advances. Once the queued child is acknowledged, the deferred parent deletion can finish. Unknown/unsafe tombstone tables fail the page instead of executing arbitrary SQL.

Downloaded material paths are preserved during ordinary upserts. A pulled submission without a cached local file has an empty local path, meaning no downloaded attachment. Official grade/feedback fields can update without overwriting queued photos, answers or their local paths.

## Receipts and existing key/value storage

The schema permits only `SYNCED` and `QUEUED_FOR_SYNC`. A rejected row therefore remains queued and intact; durable receipt metadata carries its `REJECTED` status, reason code, sent-version fingerprint and Hub binding. Identical rejected versions are not uploaded repeatedly; an edited version can retry. A successful receipt acknowledges only the exact sent version, so an edit made during upload remains queued.

The existing `sync_state` key/value table also holds:

- `upload_receipt:<table>:<id>`: status/reason/fingerprint/Hub identity, read through `SyncRepository.receipt`.
- `queued_hub:<table>:<id>`: protects work retained across a Hub reset.
- `deferred_tombstone:<table>:<id>`: deletion blocked by queued children.
- `grade_receipt:<attempt_id>`: durable incoming grade receipt, including when the attempt is not cached yet.

These are metadata keys, not new schema fields or cursor substitutes. GradeReceipt omits quiz/student/start identity; sync updates known attempt scores and retains unknown receipts without inventing attempt identities. The later quiz/grades integration must resolve or display those receipts.

## Contract limitation to resolve with the Lead

The mock `POST /api/sync/push` is currently learner-only (`role="STUDENT"`), although the OpenAPI route does not state that role restriction. Teacher-authored queued comments therefore receive 403. The client keeps them queued and does not invent a different upload route. Shared mock/contract changes are Lead-owned; teacher comment upload cannot be claimed verified until that behavior is aligned. Learner queued-comment upload is covered against the actual mock.

Homework multipart upload remains with its later feature; 2.5 preserves that queue and its parents but does not upload image files.

## Verification

The shared `SyncBehaviorTests` run under Robolectric and have an AndroidJUnit4 wrapper in `SyncDeviceTest`. Coverage includes transaction rollback, interrupted responses, paging, membership rescans, reset rollback, every queue type, cross-Hub retention, deferred tombstones, rejected receipts, edited work, owner filtering, grade preservation and unknown grade receipts. Scheduler tests check that LAN work does not require INTERNET/VALIDATED capabilities.

The opt-in `MockSyncIntegrationTest` uses the actual local mock on REST 18080/WebSocket 18081. Set `LARA_SYNC_MOCK=1` and run it against a fresh isolated instance. It verifies canonical enrollment IDs, pending profile enrichment, approval, the learner’s full class snapshot, queued comment receipts and a live announcement event reaching Room in under one second. It changes only synthetic mock accounts and in-memory test databases.

Final command outputs and counts are in `evidence/52/`. No Android device was connected, so instrumentation execution, physical LAN-without-WAN scheduling, phone heap and cold/offline relaunch are not claimed. Test APK assembly only proves the device suite builds.
