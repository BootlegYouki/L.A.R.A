# Delta sync - MOBILE 2.5 (#52)

## Base and implementation

Branch `codex/52-mobile-2.5-delta-sync` originally started at Mobile 2.4 `6b174a6` and now includes `d140e77`, the independent REST approval fixes, including Mobile 2.3 `adade1a` and the Lead's roster `enrollment_id` contract update. The integration adds no shared API or schema changes.

- `SyncTransport`: fixed authenticated pull/push routes, session/address validation, no redirects, 5-second timeouts and an 8 MiB pull-response bound. Initial requests omit absent optional Hub identity fields.
- `SyncRepository`: serializes foreground/background sync, follows `has_more`, pushes bounded batches of the current user’s submitted quiz attempts and comments, then pulls authoritative changes. An unfinished quiz or another author’s work is never uploaded.
- `SyncStore`: applies a fully decoded page, public users, upserts, tombstones and `hub_id`/`sync_epoch`/integer `cursor` in one Room transaction. Fetch/decode failure changes no database state; apply failure rolls the page back. Stale concurrent responses cannot advance the cursor.
- `DeltaSyncWorker`: success when no account/endpoint exists; retries transport errors, 429 and server failures; reports authorization/permanent payload failures without clearing saved work.
- `SyncScheduler`: unique 15-minute periodic work, unique immediate chains, battery-not-low and exponential retry. Connectivity callbacks enqueue a separate replaceable reconnect job so an older offline retry chain cannot delay it. Session startup and offline comment writes enqueue ordinary immediate work. `NetworkType.NOT_REQUIRED` permits classroom Wi-Fi without internet validation; the transport must still reach and authenticate with the selected Hub.
- `SyncSessionViewModel`: one foreground socket shared with learner and teacher controls. HELLO/reconnect and announcement events trigger a direct pull and queued worker retry. Lifecycle disposal/background stops socket subscriptions. No UI reads remote responses directly.

## Enrollment visibility and 2.4 integration

A learner can have an advanced cursor before joining a class. Approval makes older classroom records visible without rewriting their revisions. If a delta contains a newly ACTIVE own enrollment or missing parent records, sync rescans from cursor zero without wiping existing data. Each rescan page still commits atomically. The rescan is performed once per pull sequence to avoid paging loops.

The teacher panel retains 2.4's independent authenticated REST bootstrap for classes and rosters, including loading/errors, actor checks and pending join hints before the first class arrives. Roster enrollment IDs come from the required server `enrollment_id`; no local identity is generated and sync is not a prerequisite. Both REST and sync populate the same Room mirror. The panel uses 2.5's shared socket rather than opening another connection.

The learner My classes / Join / Open screen from final 2.3 is mounted. `RoomEnrollmentRepository` now invokes `sync.pull` at its refresh boundary, and both learner and teacher controls share `SyncSessionViewModel` through `EnrollmentSessionSource`. This avoids duplicate sockets and supplies canonical data after joining/approval. The 2.4 loading, contrast and large-text fixes are retained.

Following the [Lead's #52 carry-over instruction](https://github.com/BootlegYouki/L.A.R.A/issues/52#issuecomment-6555538621), learner access now reads only canonical `enrollments` supplied by sync. `RoomLearnerClassAccess` and the optional REST access override are removed. Room's on-open callback deletes only the legacy `rest_class_access:<user>` keys when upgrading, preserving enrollments, cursors, notices and all queued work without a schema change.

REST class responses now supply request notices only. Their fetch/save runs under the sync mutex so a slow response cannot overwrite a newer sync notice. A canonical own membership updates its request notice in the page/cursor transaction; an old PENDING receipt cannot keep an approved learner locked out after background sync. Neither REST notices nor socket hints create an enrollment or grant ACTIVE access. Shared tests cover canonical-only access, pending-notice reconciliation, concurrent refresh and persistent-database upgrade cleanup.

## Reset, deletion and queued work

A reset deletes mirrored rows in dependency order while retaining every `QUEUED_FOR_SYNC` or `REJECTED` row and all required announcement/assignment/quiz/classroom ancestors. Local AI history and its class parent are retained. The signed-in profile is preserved. Retained upload rows are bound to the previous Hub ID before reset; work belonging to a different Hub is retained but not uploaded to the new Hub.

A tombstone cannot cascade through queued or rejected work. Blocked deletions are stored and retried on later pulls, so a deletion is not forgotten after its cursor advances. Once a queued child is acknowledged, its deferred parent deletion can finish; a rejected child remains protected. Unknown/unsafe tombstone tables fail the page instead of executing arbitrary SQL.

Downloaded material paths are preserved during ordinary upserts. A pulled submission without a cached local file has an empty local path, meaning no downloaded attachment. Official grade/feedback fields can update without overwriting queued photos, answers or their local paths.

## Receipts and existing key/value storage

Under contract #196, upload outcomes live on the four uploadable tables: `sync_status` is `SYNCED`, `QUEUED_FOR_SYNC` or `REJECTED`, and nullable `sync_reject_reason` carries the Hub's refusal reason. Rejected rows remain observable through Room/domain flows and are never retried automatically. Both successful and rejected receipts apply only to the sent row version, so a newer edit remains queued. An ordinary pull cannot overwrite local queued/rejected content or reason; grade fields remain authoritative from the Hub.

The existing `sync_state` key/value table also holds:

- Legacy `upload_receipt:<table>:<id>` keys are converted/removed by `RejectedUploadMigration`; there is no new writer or outcome reader for them.
- `queued_hub:<table>:<id>`: protects work retained across a Hub reset.
- `deferred_tombstone:<table>:<id>`: deletion blocked by queued children.
- `grade_receipt:<attempt_id>`: durable incoming grade receipt, including when the attempt is not cached yet.

These are metadata keys, not new schema fields or cursor substitutes. GradeReceipt omits quiz/student/start identity; sync updates known attempt scores and retains unknown receipts without inventing attempt identities. The later quiz/grades integration must resolve or display those receipts.

Room schema version 2 adds only the four nullable reason columns. The 1-to-2 migration preserves all rows, IDs, files, indices and cursors. Matching v1 comment/private-comment/quiz rejection receipts move into the columns; stale receipts for edited rows cannot reject the edit. V1 never produced multipart homework receipts, so its new photo reason column starts null. See `rejected-uploads.md` for upgrade tests and handoff details.

## Contract limitation to resolve with the Lead

The mock `POST /api/sync/push` is currently learner-only (`role="STUDENT"`), although the OpenAPI route does not state that role restriction. Teacher-authored queued comments therefore receive 403. The client keeps them queued and does not invent a different upload route. Shared mock/contract changes are Lead-owned; teacher comment upload cannot be claimed verified until that behavior is aligned. Learner queued-comment upload is covered against the actual mock.

Homework multipart upload remains with its later feature; 2.5 preserves that queue and its parents but does not upload image files.

## Verification

The shared `SyncBehaviorTests` run under Robolectric and have an AndroidJUnit4 wrapper in `SyncDeviceTest`. Coverage includes transaction rollback, interrupted responses, paging, membership rescans, reset rollback, every queue type, cross-Hub retention, deferred tombstones, rejected receipts, edited work, owner filtering, grade preservation and unknown grade receipts. Scheduler tests check that LAN work does not require INTERNET/VALIDATED capabilities.

The opt-in `MockSyncIntegrationTest` uses the actual local mock on REST 18080/WebSocket 18081. Set `LARA_SYNC_MOCK=1` and run it against a fresh isolated instance. It verifies canonical enrollment IDs, pending profile enrichment, approval, the learner’s full class snapshot, queued comment receipts and a live announcement event reaching Room in under one second. It changes only synthetic mock accounts and in-memory test databases.

Original command outputs and counts are in `evidence/52/`; the subsequent phone audit is in `evidence/phone-audit-2026-10-31/`. The latest 2.4 integration review is recorded separately under `evidence/52/latest-24-review/`. Earlier phone results do not certify the updated branch or a physical WAN-disconnected classroom network.

## Review after final Mobile 2.4

The branch now references 2.4 `dda7585`, including 2.3 `adade1a`, and wires the learner refresh/session boundaries to sync. Review details and reproducible evidence are in `evidence/52/latest-24-review/`.

The review reproduced identifier loss when a valid public-user update omits its optional LRN/ID, and reconfirmed the mock's teacher sync-push 403 blocker. These were unresolved at review time. The identifier bug is fixed in the follow-up below; the teacher-upload blocker remains, so #52 is not declared complete. The 96 ms announcement result is for an already-connected session, and actual reconnect latency and the final navigation's end-to-end phone flow still require verification.


## Follow-up fixes after the 2.4 review

Name-only PublicUser updates preserve an already saved identifier for the signed-in user and previously authorized teacher-roster profiles. A supplied replacement remains authoritative. Learner devices still remove foreign learners' identifiers, including a previously cached value. Shared Room regression tests cover all three cases.

SyncScheduler exposes conflated network-change hints. Auth reachability checks immediately on a hint as well as on the existing periodic fallback; the foreground shared socket restarts on the hint even when reachability remains true. Reconnect work has its own unique replaceable WorkManager chain, so it does not append behind an outbox retry's backoff. A real WorkManager test holds the normal job in retry and verifies the reconnect job starts independently.

The live mock test is shared between Robolectric (`LARA_SYNC_MOCK=1`) and Android (`MockSyncDeviceTest`, instrumentation argument `mockHub=127.0.0.1:18080`). USB device tests require reverse forwarding for 18080/18081. It tests a missed announcement with an injected unavailable/available signal, the actual Hub probe, authenticated pull and Room persistence; it does not simulate a physical router failure. Hub-only recovery without an Android network-change callback still relies on periodic probing/socket backoff, so no universal sub-second recovery guarantee is made.

The teacher-upload 403 remains an external blocker, not fixed by these mobile changes. Physical Wi-Fi/WAN-disconnected and long-running vendor/battery tests remain separate acceptance limits. Updated results are recorded under `evidence/52/fixes/`.


Final follow-up validation: 108 tests passed per JVM variant with both live mock tests enabled, plus lint and both APK builds. On the connected phone, 23 tests passed (20 shared Room sync behaviors, one actual-mock flow and two production approval UI regressions). Through normal app entry points, the learner join request survived an offline cold launch; the teacher received a newly registered synthetic learner's join event and opened the real saved-roster sheet automatically; the teacher's Accept unlocked the pending seed learner, who could open the class shell after another offline cold launch. The teacher's two pending rows also survived an offline cold launch. The real class shell is verified here, not the later Stream feature's content UI.

Latest independent-REST integration validation (`d140e77` plus the Lead's canonical-access cleanup): 117 tests passed in each JVM variant, lint and APK builds passed, and all 28 final phone tests passed. The device suite includes persistent legacy-key cleanup/queue preservation and the updated teacher REST-to-canonical learner sync approval flow. Normal sign-in, canonical class opening and offline cold-launch class opening also passed in the isolated test app. See `evidence/52/latest-rest-24/` for current logs, screenshots and verification limits. The shared mock's teacher-upload 403 remains unresolved.
