# Hub operational activity history (#367)

Contract definitions are promoted in PR #370. This branch is not deployed: the Rust recorder/API and GUI belong to #368 after the canonical contract merges. Consumer review and the explicit post-defense hold still apply.

## Confirmed user behavior

An admin-only Tauri console with Overview, Accounts and Activity logs; searchable/filterable accounts; readable operational events with expandable safe details; newest first, automatic refresh, search/date/severity filters, display pause and Load older; 100 rows initially; seven-day history surviving restarts. No names, account identifiers, LRNs, PINs/hashes, tokens, query credentials, document/comment content or server paths in logs. Fixed L.A.R.A tokens, Nunito, Phosphor, English resource strings and loading/empty/unavailable states apply.

The logs describe backend operations, including those initiated by mobile/desktop clients and headless runs. They are **not** a complete personal-data access audit. No external logging, log export or clear-history command is added. No AI/port/device statistics, backup, export or tunnel feature is added. #66 owns full health metrics; #329 is a separate host-architecture proposal.

## Proposed decisions requiring consuming-team acceptance

| Decision | Concrete proposal | Reason / practical limit |
|---|---|---|
| Storage | Two Hub-only tables in `schema/server_master.sql` in the master database, added by a new server migration after contract approval | Same local durability; never change an applied migration or a client schema |
| Access | ADMIN bearer plus Hub-PC loopback, rejecting forwarded/tunnel requests using the existing trusted locality check | Operational history stays on the school PC even if a token is presented elsewhere |
| Retrieval | Read-only GET `/api/admin/activity-logs`; no push event; poll every 5 seconds only while the activity page is visible and unpaused | Keeps first version bounded and avoids new WebSocket semantics |
| Retention | Show the last seven days; delete older rows at startup and at least every 60 seconds, using Hub time | Read filters hide expired rows immediately; a wall-clock correction may change the visible time window |
| Hard bound | At most 20,000 retained events, each compact JSON representation at most 1,024 UTF-8 bytes | Evict oldest by seq in the writer's append transaction; heavy use can retain less than seven days. This caps logical history, not the physical size of the shared SQLite file |
| Producer queue | Capacity 256 entries; nonblocking try-send; drop newest when full | Logging cannot stall a business request. No runtime caller waits for disk |
| Writer | One background writer, at most 100 events per short transaction, flush by 500 ms; existing WAL/foreign-key/busy-timeout connection invariants apply | No file/network/queue wait under a business transaction. Failed batches are dropped with a gap count; retry with bounded backoff 1, 2, 4, then 5 seconds |
| Repeated failures | At most one WARNING/ERROR for the same `(event_code, service, reason)` per 30 seconds; count suppressed entries as gaps | Limits error storms without recording actors or identifiers. INFO outcomes and LOG_GAP are not suppressed |
| Loss reporting | Persist best-effort `dropped_total` on recovery and emit a LOG_GAP with a bounded item_count (split at 1,000,000 if needed); use DEGRADED/UNAVAILABLE read metadata while recording is impaired | Not a lossless audit. A crash can lose queued events and unpersisted gap counts |
| Restart | Mark `clean_shutdown=0` before serving; if already dirty emit UNCLEAN_RESTART. Flush HUB_STOPPING and set clean only after successful drain within 2 seconds | Shutdown is best-effort; power loss cannot create a guaranteed shutdown entry. Startup marker failure leaves the recorder degraded and other features usable |
| Restore | Regenerate `history_id` whenever the database is initialized or restored; stale read cursors get 409 ACTIVITY_LOG_RESET | Sequence values can repeat after a restore; clients must reset history pages, not merge different generations |

These numerical safety limits are proposals, not silently accepted product requirements. Consuming-team reviewers can request changes before promotion, with the acceptance tests/spec updated together. No entry contains a subject name, account/resource ID, filename, raw exception message, IP address or raw request URL. HTTP_REQUEST uses only approved endpoint templates.

## Safe entry and event catalog

The Activity schemas in `openapi.yaml` allows only sequence/time, catalog code, severity/result, service and numeric/enum details. HTTP_REQUEST additionally permits an allowlisted http_method, endpoint template and numeric status_code in details. No arbitrary `message`, `summary`, `context` or free-form JSON field exists. The UI maps catalog values to English dictionary text; expandable details map reason codes to dictionary wording and show approved counts/durations. Do not serialize Rust errors, tracing fields, headers or request bodies into the entry.

| Event codes | When recorded | Default severity / result |
|---|---|---|
| HUB_STARTED | Listeners are bound and the Hub is serving | INFO / SUCCEEDED |
| HUB_STOPPING | A clean shutdown begins, before bounded drain | INFO / SUCCEEDED (stopping began; does not claim shutdown completed) |
| UNCLEAN_RESTART | A previous recorder lifecycle marker was dirty | WARNING / DEGRADED |
| SERVICE_UNAVAILABLE, CONNECTION_PROBLEM | A known service stops responding or a connection subsystem fails; request outcomes are recorded separately as HTTP_REQUEST | ERROR / FAILED, or WARNING / DEGRADED if other services continue |
| SERVICE_RECOVERED | Previously impaired service becomes usable | INFO / SUCCEEDED |
| DOCUMENT_UPLOADED, DOCUMENT_PROCESSED | Upload committed / extraction completed with text; item_count may contain chunk count | INFO / SUCCEEDED |
| DOCUMENT_UPLOAD_FAILED, DOCUMENT_PROCESSING_FAILED | Actual upload failure / no extracted lesson text (including scans and extraction timeout); uploading can still succeed | ERROR / FAILED for upload; WARNING / DEGRADED for extraction |
| QUIZ_STARTED, QUIZ_CLOSED | Relevant quiz operation committed | INFO / SUCCEEDED |
| QUIZ_START_FAILED, QUIZ_CLOSE_FAILED | Authorized operation failed validation or processing | WARNING / FAILED; ERROR / FAILED for service failures |
| ACCOUNT_CREATED, PIN_RESET | Account operation committed | INFO / SUCCEEDED |
| ACCOUNT_CREATE_FAILED, PIN_RESET_FAILED | Authorized operation failed; no input values retained | WARNING / FAILED; ERROR / FAILED for service failures |
| LOG_GAP | Recorder recovers enough to report dropped/suppressed entries | WARNING / DEGRADED; reason QUEUE_FULL, WRITE_FAILED or REPEATED_EVENT |

Only authenticated authorized business handlers emit account/quiz outcome entries. HTTP_REQUEST records rejected authentication/authorization without recording credentials or actor information. Emit committed success events after the transaction commits; never report a rolled-back operation as successful. Best-effort enqueue may lose an otherwise committed success, and gaps must be visible. The event tables have no user/graded-row foreign keys, actor fields or client sync revisions.

SQL `reason`, `item_count` and `duration_ms` columns map to non-null optional members of `details`. Absent values are omitted, not null. Reason details are allowlisted in SQL and JSON Schema. `recording_state` is runtime health, not a promise that historical capture was complete. A successful read can return stored rows while the recorder is DEGRADED or UNAVAILABLE; an unreadable history database answers safe 503 ACTIVITY_LOG_UNAVAILABLE. Metadata counter values saturate at the maximum JSON-safe integer rather than overflowing.

## Read contract and stable pagination

Authentication/authorization/locality precede parameter validation. Unknown, duplicate, malformed or out-of-range query keys answer 400 BAD_REQUEST. Integer query values are base-10 whole numbers; no floating/exponential syntax. `before_seq` and `after_seq` are mutually exclusive. Any sequence parameter requires `history_id`; a mismatch is 409 ACTIVITY_LOG_RESET, never an empty successful page. `snapshot_seq` cannot exceed the highest sequence ever issued in the current history; such a request answers 400.

Each read is a short consistent SQLite read transaction. Let H be the highest ever issued seq (including pruned rows), captured at the first request or supplied as `snapshot_seq`. Return H as `high_water_seq`. All matching entries are <= H. Cursors use seq, not occurred_at; date filters apply to occurred_at. Results always serialize descending by seq. Filtering values must remain fixed while following a page chain; changing a filter begins a new initial load.

1. **Initial/older history:** without after_seq, select the largest matching seq values below before_seq, if supplied. Return up to limit entries descending. If more matching older rows exist, `next_before_seq` is the smallest returned seq; otherwise null. `next_after_seq` is null. Keep H and history_id fixed when loading older pages.
2. **Refresh/catch-up:** send after_seq equal to the previous completed H, omit snapshot_seq on the first request, then retain the newly returned H while draining. Select the **smallest** matching seq values in `(after_seq, H]` first, up to limit, then serialize that selected page descending. This prevents a burst larger than one page from skipping its oldest new events. If more matching rows remain in that interval, `next_after_seq` is the largest returned seq; otherwise null. `next_before_seq` is null. After all pages are drained, advance the refresh watermark to H even if no entries matched.
3. **Pause/resume:** pausing stops display polling only. On resume, drain catch-up before advancing the watermark. Merge by `(history_id, seq)`, preserve expanded rows and the older-history boundary, and trim UI rows outside the current seven-day window on successful refresh. On 409 clear stored rows/cursors and restart; on failed requests retain the current signed-in session's last successful rows with a stale indicator.
4. **Retention:** response `pruned_through_seq` is the highest evicted sequence in this history. It may advance due to age or the hard row cap. A matching history_id with an old cursor is not itself an error: return surviving rows and the pruning watermark. The UI shows that older history may have been removed. Gaps and pruning are independent of active filters. A cursor cannot promise a complete interval across pruning or crashes.

Example: last refresh H=100, new H=350, limit=100. Catch-up returns 200..101 (next_after_seq=200), then 300..201 (next_after_seq=300), then 350..301 (null), all against snapshot_seq=350. Only then advance the refresh watermark to 350. New arrivals 351+ belong to the next refresh. If the filter matches nothing, return empty entries and advance to H after that successful request.

Filters: from_time inclusive, to_time exclusive, and from_time < to_time. All filters combine with AND. Trim q, replace underscores with spaces and case-fold; match it literally as a substring over the concatenated catalog event/service/result/reason words and approved HTTP method/endpoint/status details, with their underscores replaced by spaces. No SQL wildcard interpretation and no search over private/raw payloads; bind all values. Empty q means no text filter. Date and sequence timestamps remain epoch milliseconds; the UI converts local date boundaries to epoch milliseconds and labels displayed local times.

Return Cache-Control: no-store. No browser persistent storage for the bearer, rows or filters containing user input. No activity table/state in delta-sync, client schemas, exports or learner/teacher responses. Existing backup/restore work must preserve safe history or explicitly reset it with a new history_id; never reuse pre-restore cursors.

## Delivery and verification

The authoritative route and DTOs are in `openapi.yaml`; both private table definitions are in `schema/server_master.sql`. All identifiers/fields are additive and private to the Hub admin console. The Server team is the only consumer; request written OK from that team before the co-lead merges. A team member can author this contract without prior Tech Lead approval under governance commit `7bed6b3`. Any separate change to product decisions, PRD or shared design guidance retains its documented ownership/review process.

Current staging still requires simulator parity, so this contract PR supplies a bounded in-memory route reference and tests in the existing simulator. It creates no new simulator dependency for the Rust Hub or server tests. Coordinate the small reference route/test with #344 when that simulator is removed; do not weaken current coverage. The simulator does not prove durable recording or implement the production recorder.

No server migration/runtime/UI code is included in this contract change. Runtime parity/route-registration tests will need the #368 implementation after contract merge; do not advertise the new API as available in a deployed Hub. The original draft artifacts are retained under `proposals/367/` for provenance; this document and canonical OpenAPI/SQL take precedence.

## HTTP request activity (user-approved extension)

Record every HTTP request except `/api/admin/activity-logs` and GET/OPTIONS `/api/admin/users` when response headers become available, including public
portal/assets, successful writes/reads, rejected authentication, validation errors,
unknown routes, unsupported methods and CORS preflight. Requests to the activity
history endpoint are excluded for every method/status to prevent viewer feedback.
Reads also omit pre-existing HTTP_REQUEST rows for that endpoint, without deleting
stored history or changing sequence/high-water semantics. GET/OPTIONS `/api/admin/users`
is also excluded for every status, and its pre-existing request entries are hidden
from reads. OPTIONS is the browser preflight accompanying account polling. Account
polling is unchanged. POST `/api/admin/users`, PIN resets and
other methods/endpoints continue to be recorded.
This covers the REST/portal listener; WebSocket frames on port 8081 are not HTTP
endpoint requests and retain their existing operational events.

Use HTTP_REQUEST with service HTTP. Details contain http_method (the fixed enum,
OTHER for unfamiliar methods), endpoint (a registered template or UNMATCHED),
status_code (100–599) and duration_ms (time to response headers, bounded as before).
Never retain actual path parameters, raw paths/query strings, headers, bodies or
response contents. Only catalog endpoint templates may cross the logger boundary.
For HTTP_REQUEST, 400–499 means WARNING/FAILED, 500–599 ERROR/FAILED and other
statuses INFO/SUCCEEDED. Existing business outcome events are unchanged.

HTTP_REQUEST bypasses repeated-error suppression so separate requests remain
separate entries. Queue overflow/write failures still produce visible gaps under
the existing best-effort guarantees. Polling history does not create request events;
pausing only stops display polling. Existing retention/row limits still apply.
New migration preserves events, sequence high-water mark and history identity.
