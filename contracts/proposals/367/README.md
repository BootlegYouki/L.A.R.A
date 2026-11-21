# Proposed private Hub activity history (#367)

**Draft for Lead review. Not an active contract or an implemented feature.** Implements the specification step of proposal #366 and supplies concrete definitions for the blocked server issue #368. The running Hub is unchanged.

The proposed route/schema/DDL live beside this document, separate from canonical contracts. Proposal #366 has no Lead decision yet, #364 is open, and #344 still changes the shared testing architecture. This separation avoids advertising a route which neither the real Hub nor the current simulator serves. Before feature work, the Lead must approve the decisions below, promote these definitions into the canonical contract/docs in this same contract PR, supply the then-required shared acceptance coverage, and merge it. A merge of these proposal files alone does not authorize server implementation.

## Confirmed user behavior

An admin-only Tauri console with Overview, Accounts and Activity logs; searchable/filterable accounts; readable operational events with expandable safe details; newest first, automatic refresh, search/date/severity filters, display pause and Load older; 100 rows initially; seven-day history surviving restarts. No names, account identifiers, LRNs, PINs/hashes, tokens, query credentials, document/comment content or server paths in logs. Fixed L.A.R.A tokens, Nunito, Phosphor, English resource strings and loading/empty/unavailable states apply.

The logs describe backend operations, including those initiated by mobile/desktop clients and headless runs. They are **not** a complete personal-data access audit. No external logging, log export or clear-history command is added. No AI/port/device statistics, backup, export or tunnel feature is added. #66 owns full health metrics; #329 is a separate host-architecture proposal.

## Proposed decisions requiring Lead acceptance

| Decision | Concrete proposal | Reason / practical limit |
|---|---|---|
| Storage | Two Hub-only tables from `activity.sql` in the master database, added by a new server migration after contract approval | Same local durability; never change an applied migration or a client schema |
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

These numerical safety limits are proposals, not silently accepted product requirements. The Lead can change them before promotion, with the acceptance tests/spec updated together. No success/error entry contains a subject name, account/resource ID, filename, raw exception message, IP address or request URL.

## Safe entry and event catalog

`schemas.json` allows only sequence/time, catalog code, severity/result, service and numeric/enum details. No arbitrary `message`, `summary`, `context` or free-form JSON field exists. The UI maps catalog values to English dictionary text; expandable details map reason codes to dictionary wording and show approved counts/durations. Do not serialize Rust errors, tracing fields, headers or request bodies into the entry.

| Event codes | When recorded | Default severity / result |
|---|---|---|
| HUB_STARTED | Listeners are bound and the Hub is serving | INFO / SUCCEEDED |
| HUB_STOPPING | A clean shutdown begins, before bounded drain | INFO / SUCCEEDED (stopping began; does not claim shutdown completed) |
| UNCLEAN_RESTART | A previous recorder lifecycle marker was dirty | WARNING / DEGRADED |
| SERVICE_UNAVAILABLE, CONNECTION_PROBLEM | A known service stops responding or a connection subsystem fails; no per-request traffic logging | ERROR / FAILED, or WARNING / DEGRADED if other services continue |
| SERVICE_RECOVERED | Previously impaired service becomes usable | INFO / SUCCEEDED |
| DOCUMENT_UPLOADED, DOCUMENT_PROCESSED | Upload committed / extraction completed with text; item_count may contain chunk count | INFO / SUCCEEDED |
| DOCUMENT_UPLOAD_FAILED, DOCUMENT_PROCESSING_FAILED | Actual upload failure / no extracted lesson text (including scans and extraction timeout); uploading can still succeed | ERROR / FAILED for upload; WARNING / DEGRADED for extraction |
| QUIZ_STARTED, QUIZ_CLOSED | Relevant quiz operation committed | INFO / SUCCEEDED |
| QUIZ_START_FAILED, QUIZ_CLOSE_FAILED | Authorized operation failed validation or processing | WARNING / FAILED; ERROR / FAILED for service failures |
| ACCOUNT_CREATED, PIN_RESET | Account operation committed | INFO / SUCCEEDED |
| ACCOUNT_CREATE_FAILED, PIN_RESET_FAILED | Authorized operation failed; no input values retained | WARNING / FAILED; ERROR / FAILED for service failures |
| LOG_GAP | Recorder recovers enough to report dropped/suppressed entries | WARNING / DEGRADED; reason QUEUE_FULL, WRITE_FAILED or REPEATED_EVENT |

Only authenticated authorized business handlers emit account/quiz outcome entries. Do not log every unauthenticated probe or rejected credential. Emit committed success events after the transaction commits; never report a rolled-back operation as successful. Best-effort enqueue may lose an otherwise committed success, and gaps must be visible. The event tables have no user/graded-row foreign keys, actor fields or client sync revisions.

SQL `reason`, `item_count` and `duration_ms` columns map to non-null optional members of `details`. Absent values are omitted, not null. Reason details are allowlisted in SQL and JSON Schema. `recording_state` is runtime health, not a promise that historical capture was complete. A successful read can return stored rows while the recorder is DEGRADED or UNAVAILABLE; an unreadable history database answers safe 503 ACTIVITY_LOG_UNAVAILABLE. Metadata counter values saturate at the maximum JSON-safe integer rather than overflowing.

## Read contract and stable pagination

Authentication/authorization/locality precede parameter validation. Unknown, duplicate, malformed or out-of-range query keys answer 400 BAD_REQUEST. Integer query values are base-10 whole numbers; no floating/exponential syntax. `before_seq` and `after_seq` are mutually exclusive. Any sequence parameter requires `history_id`; a mismatch is 409 ACTIVITY_LOG_RESET, never an empty successful page. `snapshot_seq` cannot exceed the highest sequence ever issued in the current history; such a request answers 400.

Each read is a short consistent SQLite read transaction. Let H be the highest ever issued seq (including pruned rows), captured at the first request or supplied as `snapshot_seq`. Return H as `high_water_seq`. All matching entries are <= H. Cursors use seq, not occurred_at; date filters apply to occurred_at. Results always serialize descending by seq. Filtering values must remain fixed while following a page chain; changing a filter begins a new initial load.

1. **Initial/older history:** without after_seq, select the largest matching seq values below before_seq, if supplied. Return up to limit entries descending. If more matching older rows exist, `next_before_seq` is the smallest returned seq; otherwise null. `next_after_seq` is null. Keep H and history_id fixed when loading older pages.
2. **Refresh/catch-up:** send after_seq equal to the previous completed H, omit snapshot_seq on the first request, then retain the newly returned H while draining. Select the **smallest** matching seq values in `(after_seq, H]` first, up to limit, then serialize that selected page descending. This prevents a burst larger than one page from skipping its oldest new events. If more matching rows remain in that interval, `next_after_seq` is the largest returned seq; otherwise null. `next_before_seq` is null. After all pages are drained, advance the refresh watermark to H even if no entries matched.
3. **Pause/resume:** pausing stops display polling only. On resume, drain catch-up before advancing the watermark. Merge by `(history_id, seq)`, preserve expanded rows and the older-history boundary, and trim UI rows outside the current seven-day window on successful refresh. On 409 clear stored rows/cursors and restart; on failed requests retain the current signed-in session's last successful rows with a stale indicator.
4. **Retention:** response `pruned_through_seq` is the highest evicted sequence in this history. It may advance due to age or the hard row cap. A matching history_id with an old cursor is not itself an error: return surviving rows and the pruning watermark. The UI shows that older history may have been removed. Gaps and pruning are independent of active filters. A cursor cannot promise a complete interval across pruning or crashes.

Example: last refresh H=100, new H=350, limit=100. Catch-up returns 200..101 (next_after_seq=200), then 300..201 (next_after_seq=300), then 350..301 (null), all against snapshot_seq=350. Only then advance the refresh watermark to 350. New arrivals 351+ belong to the next refresh. If the filter matches nothing, return empty entries and advance to H after that successful request.

Filters: from_time inclusive, to_time exclusive, and from_time < to_time. All filters combine with AND. Trim q, replace underscores with spaces and case-fold; match it literally as a substring over the concatenated catalog event/service/result/reason words, with their underscores replaced by spaces. No SQL wildcard interpretation and no search over private/raw payloads; bind all values. Empty q means no text filter. Date and sequence timestamps remain epoch milliseconds; the UI converts local date boundaries to epoch milliseconds and labels displayed local times.

Return Cache-Control: no-store. No browser persistent storage for the bearer, rows or filters containing user input. No activity table/state in delta-sync, client schemas, exports or learner/teacher responses. Existing backup/restore work must preserve safe history or explicitly reset it with a new history_id; never reuse pre-restore cursors.

## Promotion and implementation gates

1. Lead records acceptance/changes on #366 and approves this proposed API, catalog and safety bounds.
2. Update PRD section 1.1 with accepted behavior and Hub design guidance for three admin pages (no change to brand tokens). Promote the route/schemas to canonical OpenAPI, SQL tables to server_master.sql and private naming/sync exclusions to the contract docs. Keep server migration/runtime code out of this contract PR.
3. Coordinate shared checks with #344: do not add new simulator dependencies to the planned real-Hub workflow. The active root rules still require simulator parity; before promotion, the Lead must resolve this shared-rule transition in #344 or explicitly provide the required interim simulator coverage. Do not weaken coverage or mark an unimplemented route as working.
4. Run shared contract/schema tests and verify_invariants.py. Merge the approved contract PR, then branch the server work from staging containing it and #364, after the freeze and in Lead-approved queue order.
5. Implement #368 with temporary databases and a real Hub. Verify role/locality, capture/redaction, restart/expiry/cap/overflow, graceful recording failure, paging during bursts/pruning/restore, display pause, UI offline states and native screenshots. No runtime implementation is present in this draft.

## Acceptance and remaining limits

`test_contract.py` checks executable proposed SQL, allowlisted enum parity, strict payload bounds, private/unknown-field rejection, valid response examples and a paging fixture illustrating the >100-arrival contract. These validate a proposal, not a working backend. Run with `uv run --python 3.12 --with pyyaml --with jsonschema python -m unittest discover -s contracts/proposals/367 -p test_contract.py` from the repository root. Its development-only schema validator is supplied by uv, so no runtime or current CI dependency is added. The full existing shared test suite remains unchanged in behavior.

The Lead decision, canonical promotion, #344 coordination, contract merge, server implementation and hardware validation remain outstanding. No production database is migrated and the running Hub GUI is unchanged.

Pre-PR validation, 2026-11-21, isolated worktree based on staging `02e6bec5`:

```text
Proposal artifact tests: 10 passed in 0.192s
Existing shared suite: 73 tests in 9.741s; OK
verify_invariants.py: All L.A.R.A offline invariants PASSED
git diff --cached --check: passed
```

No Rust/UI build or hardware run was performed for this specification-only change. Those checks cannot establish runtime activity capture before #368 is implemented.
