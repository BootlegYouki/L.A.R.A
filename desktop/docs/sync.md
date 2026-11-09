# Delta sync and roster error follow-ups (#48)

Desktop mirrors the selected Hub into its existing account-scoped SQLite database and uploads stable-ID queued comments, private comments and submitted quiz attempts. Sync starts on connect, validated WebSocket ACK/announcement hints and every 60 seconds. No new dependency, schema, endpoint, Server or Mobile code is added. Stream/Classwork authoring and media/quiz UI remain their ordered issues; this task proves the mirror through native SQLite, not a newly built feed.

## Files and flow

- `services/sync.ts`: one coalescing authenticated pull/push loop, paging while `has_more`, expected integer cursor, 10-second metadata HTTP deadline, cancellation, retry backoff and Retry-After. Dispose on offline/account/Hub change; late results never publish another account. Hub identity is an opaque contract string, not an invented UUID requirement. Server time is never a cursor.
- `services/api.ts` and native HTTP capability: add only private/loopback fixed `/api/sync/pull` and `/api/sync/push` routes. Existing redirect rejection and credential boundaries remain.
- `services/realtime.ts`: validated ACK and `EVENT_ANNOUNCEMENT_PUSH` trigger sync. Hints do not write unvalidated event data into the mirror. Existing learner/teacher join behavior remains.
- `App.tsx`: sync lifecycle, restore the committed native account snapshot, account/Hub generation checks and a nonblocking resource-based warning. Cached screens remain available when sync fails. A previous Hub's error is cleared on Hub change.
- `src-tauri/src/sync.rs`: validate every nested contract family through a fixed field/table whitelist, normalize only contracted fields, and apply a whole page plus Hub/epoch/cursor in one SQLx transaction. Native commands independently require the in-memory selected Hub/account and persisted active account under the existing Accounts mutex. A changed expected state rejects stale pages; no client-created cursor rewind.
- `account.rs`/`lib.rs`: reuse the same migrated native database and account serialization; expose only `read_sync_state`, `apply_sync_pull`, `read_sync_queue`, `apply_sync_receipts`.

Every caller passes `{hub,userId}`. `read_sync_state` returns `{cursor,hub_id?,sync_epoch?}`. `apply_sync_pull` additionally receives `expected` and the unknown wire `payload`. `read_sync_queue` returns `{quiz_attempts,comments,private_comments}`; `apply_sync_receipts` receives the exact `sent` snapshot and response `payload`. Native, not an unsafe TypeScript cast or separate pooled JS statements, owns nested validation and transactions.

## Data safety

Parent-first upserts and child-first tombstones are parameterized. Reset retains `QUEUED_FOR_SYNC` AND `REJECTED` rows and their foreign-key parents, local media/chat, and in-progress attempts with their question bodies. Retaining a parent does not grant access to a revoked learner/co-teacher: canonical enrollment/teacher membership governs visible classes. User-only name revisions, enrollment statuses and roster tombstones reconcile the existing account caches. Other users' LRNs, PIN data, answer keys and server paths are never persisted.

Local media paths survive metadata refresh. A received submission without a local file uses the existing NOT NULL column's empty value to mean no local photo; no server path is copied. Held grade scores stay null. An unmatched GradeReceipt has no contracted quiz start time, so its whitelisted fields are retained in account-local `desktop:grade_receipts`, not turned into a fictitious attempt. It reconciles when an actual local attempt exists, preserving the real start/answers. Future gradebook reads must include this namespace. It is cleared on reset and pruned for known deleted/revoked scopes; it is not an upload-outcome ledger.

Outbox reads return at most 100 records per family. Photos use their separate future multipart upload path; in-progress quizzes are excluded. Only the caller's unchanged queued row matching the exact sent payload can be acknowledged. Partial/missing receipts leave rows queued; rejected rows retain the reason and are not automatically retried. A malformed receipt batch rolls back. Sync processes one batch per cycle; remaining work is picked up by later triggers/the 60-second tick. Stable IDs make retries safe after a lost response.

## Submission receipt timestamp

Migration 3 adds the nullable `assignment_submissions.received_at` column to the account databases and the Tauri SQL cache. Existing files, queued submissions and rejection state are preserved. Desktop stores the Hub's receipt timestamp when present in a sync pull. Older Hub responses can omit it, and queued uploads keep a null receipt timestamp until the Hub acknowledges them.

## Lead #246 follow-ups

Roster mutation failures now have their own named Accept/Decline/Remove error, separate from roster-read errors. A read retry cannot erase the failed-action message or pretend to repeat that action. Failed Accept/Decline regression tests leave Pending/count unchanged, recover controls and assert one POST. Production reachable-Hub 500 tests verified the named errors and unchanged Pending state for both actions. These use only English resources and the existing warning style.

## Verification (2026-11-07)

```text
npm test: 91 tests passed / 20 files
npm run lint: exit 0, no warnings/errors
npm run build: TypeScript and Vite passed, 115 modules
npm run tauri -- build --no-bundle --config G:/Codex/LARA/work/tauri-local-tools.json:
  optimized native release finished in 41.94s
cargo test --manifest-path desktop/src-tauri/Cargo.toml:
  24 passed, 0 failed, 3 existing opt-in OS/network tests ignored
python scripts/verify_invariants.py: passed
python -m unittest discover tests: 67 passed in 12.795s
```

Frontend regressions were observed failing before their implementation: missing sync service; absent announcement trigger; unnamed failed actions; absent account-safe commit callback; overly strict opaque Hub ID. The final WebSocket ACK regression also pins opaque Hub IDs and invalid identity rejection. Native tests cover every pull family, transaction/cursor rollback, reset retention, revoked membership, stale selected context/state, malformed nested rows, foreign secrets, held grades, unmatched grades, active question retention, unchanged-row receipts and atomic receipt batches. The shared suite initially hit sandbox socket restrictions; the full approved unsandboxed rerun passed all 67 tests. Node experimental SQLite, React test-renderer deprecation and Cargo hardlink-cache fallback are disclosed environment notices.

Independent read-only TypeScript/native review found and resolved the stale Hub-error state and reset's active-question loss; the final native re-review found no further actionable high-risk issue. Tests use real temporary migrated SQLite instead of a fake SQL transaction layer, to exercise actual constraints and rollback.

## Native production evidence

Physical host: Windows 11 Pro / B450M Steel Legend. The optimized executable used embedded `tauri.localhost` assets, native HTTP/SQLite/Windows credentials and WebSocket, with an isolated WebView profile on G: and process-only loopback inspection. No debugging flag/capability ships. Helper processes initially hit sandbox socket isolation; the final successful runs used approved unsandboxed synthetic helpers, without changing firewall/system settings. No original database or queued work was erased.

**Mock, 8380/8381:** a synthetic teacher/class and approved seed joining learner were created through existing APIs. A new class avoids the default seed video's non-UUID ID (`m200...`), which correctly fails the strict contract UUID guard; the shared mock was not edited. Production pull committed initial announcements. An actual `EVENT_ANNOUNCEMENT_PUSH` committed a new announcement in **68ms** (one local run, not a load benchmark; final rebuilt binary repeated ACK/live commit in 120ms). Parameterized fixtures in that synthetic account's DB supplied two queued comments: one received SYNCED; the comments-disabled one remained REJECTED with `COMMENTS_DISABLED`. Submitted quiz attempts and private comments also received actual SYNCED receipts through the production app; grade 1 and the original begin timestamp were retained, with no answer-key columns. Other people's LRNs were absent. A separate mock helper returned reachable-Hub 500 for Accept/Decline to verify the lead's failure cases; no repository Server/mock source was changed.

A loopback-only test adapter on 8480 deliberately cut a sync JSON response mid-body. Production Desktop kept the prior announcements and cursor byte-for-value, displayed its nonblocking warning with cached Home, and safely recovered when the cut was removed. Native transaction tests additionally forced failures during apply to prove rollback after writes, not only before a complete response.

**Real Hub, unchanged staging `49e20de`:** isolated `G:/Codex/LARA/work/real-hub-48-data`, `LARA_TEST_SEED=1`. Existing authenticated APIs created the classroom, join and approval. Announcement authoring returned **404**, so parameterized synthetic announcements/quiz plus matching ledger revisions were inserted only into that isolated test DB. This is fixture setup, not a claim of working real authoring or live announcement emission. Production Desktop pulled them, uploaded a comment that was confirmed on the Hub, retained a rejected comment/reason, applied a tombstone to unprotected rows and kept a rejected row's FK parent. The real quiz upload returned acknowledged=true but no item receipt; Desktop correctly kept its attempt QUEUED_FOR_SYNC.

Stopping the real Hub left cached Home accessible and queued/rejected work intact. SQLite integrity and foreign-key checks passed. Closing/reopening the entire executable produced a different browser-process endpoint and restored cached Home without a PIN. Restarting the same Hub data reconnected without a PIN and retained outstanding work. No renderer exception was captured in the main mock/real flows.

Screenshots `screenshots/sync-*.png` show mock/real online, interrupted pull, failed Accept/Decline, stopped Hub, fresh offline process and recovered Hub. A feed screen is not claimed. Token-free helper logs/proofs remain in the project-local work folder; PINs/tokens are not printed in evidence.

## Repeat

1. Run mock or real staging Hub with isolated synthetic data. Create a new class, join seed learner `123456789013` / seed PIN `1234`, approve through a teacher session, and sign into Desktop as that learner.
2. Add a contracted announcement/revision (mock API; real fixture if authoring remains unavailable). Confirm the announcement and integer cursor appear together in that account's native mirror. On mock, create another announcement while connected and confirm actual WS-triggered pull.
3. Queue synthetic stable-ID comments in that exact test account's DB. Trigger reconnect: successful receipt alone sets SYNCED; disabled comments become REJECTED with reason. Missing receipts remain queued. Never use a real school's DB for fixture writes.
4. Interrupt the pull response, then restore it: no partial records/cursor; cached Home stays open and safe retry recovers. Native tests cover failure inside the write transaction too.
5. Stop the Hub, fully close/reopen Desktop, check saved state without a PIN, then restart the same Hub. Switch accounts/Hubs to verify isolation.
6. For roster follow-ups, simulate reachable-Hub 500 on Accept/Decline; named failure stays visible, Pending/count remains, controls recover and no POST repeats automatically.

## Not verified / integration limits

- Real quiz-attempt push is not implemented: the current Hub ignores `quiz_attempts` and returns no receipts. Real private-comment upload was not exercised; native/mocked receipt tests cover it. Successful quiz-upload coverage is mock/native tests, not a real-Hub success claim.
- Real announcement authoring returned 404; real live announcement emission was not exercised. Fixture-backed pull/comments/tombstones were verified. The mock's default seed video ID is not a UUID; no client relaxation or shared-file workaround is included.
- Backend `MaterialChunk.heading` serializes None as null while OpenAPI permits an omitted string, not null. Strict boundary validation retains the old cursor on such a page. This source-level contract mismatch needs the owning team/lead; a real null-heading page was not exercised in this run.
- WAN-unplugged operation, a second physical PC/multiple-device load, low-spec timing, Linux/macOS runtime, a new installer and full screen-reader audit. The 68ms sample is local; no classroom latency claim.
- Unknown grades require the documented namespace reader in future gradebook work. Future authoring/upload screens own rejection explanations and photos; no speculative UI was added here.
- Hosted CI is separate from local results; the prior owner billing lock is not reported as a source-test failure or a CI pass.
