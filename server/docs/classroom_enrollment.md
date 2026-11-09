# Class codes and enrollment (step 2.1, #6)

Teachers create classes and approve each learner before that learner becomes an active member. This implements PRD FR-1.2 and FR-1.3 using the existing classroom routes, schemas and join events. Enrollment transitions follow contract-change PR #216, merged into `staging` on 2026-11-03.

## Key files

| File | Purpose |
|---|---|
| `backend/src/routes/classrooms.rs` | Create, list, join, roster, approve and reject handlers; role guards; notifications after commit |
| `backend/src/db/classrooms.rs` | Code generation, SQL queries, enrollment transitions and scoped revisions |
| `backend/src/models/classroom.rs` | `Classroom` and `RosterEntry` response types, with no PIN, answer-key or server-path fields |
| `backend/src/services/class_access.rs` | Owner/co-teacher authorization and archived-class write guard; pulls enrollment access checks forward from #121 as approved in TECH_SPEC section 14, Q5 |
| `backend/tests/classrooms.rs` | Real HTTP/WebSocket flow, transitions, concurrency, privacy, rollback and restart recovery |

## Data flow

- Creation uses the calling teacher as owner, regardless of body fields. Six random characters come from the contract's 32-character alphabet. The database's unique code constraint decides collisions: an insertion that conflicts on `class_code` writes nothing, then creation generates another code. Codes are stored and returned without a hyphen; clients display `XXX-XXX`. Join also accepts lowercase and a display hyphen, matching the mock.
- Listing returns owned/co-taught classes for teachers and only `PENDING`/`ACTIVE` classes for learners. Learner results include their own `enrollment_status`. Co-teacher IDs are sorted. The Hub admin has no classes.
- Join inserts `PENDING`, or changes an existing `REJECTED`/`REMOVED` enrollment back to `PENDING`, preserving its ID and original `joined_at`. Only a changed enrollment sends `EVENT_JOIN_REQUEST`, with learner name and LRN, to that class's teachers. A `PENDING`/`ACTIVE` retry returns its status without changing data or sending an event.
- Roster is available only to the owner/co-teachers and includes learner name, LRN, status, `joined_at` and the actual `enrollment_id`. Learners and unrelated teachers cannot read it.
- Approve changes `PENDING` to `ACTIVE`; reject changes `PENDING` to `REJECTED`. A retry already at the requested target answers `200 {"success": true}` without changing timestamps, writing a revision or sending an event. All other states or a missing enrollment answer `404 NOT_FOUND`.
- An archived class rejects join, approve and reject with `403 FORBIDDEN`, including retries. Roster and listing remain readable. Unknown codes answer `404 CLASS_CODE_INVALID`.
- Every write uses `begin_immediate`. Class changes record `Scope::class`; enrollment changes record `Scope::student`, using the enrollment row's own ID, in the same transaction. Only after commit does the route notify connected devices. A revision failure rolls the data change back and emits no event.

## Offline behavior and scope

Nothing in this feature calls an external service or adds a dependency. A disconnected teacher can find the pending request in the roster after reconnecting. A disconnected learner can retrieve their approval with classroom listing. Both survive Hub restarts; existing sessions remain valid. A failed notification never reverses a committed enrollment.

This issue adds no screen or UI strings; clients translate contract status/error codes using their own English dictionaries. Class-code grouping belongs to those clients.

Rename, archive, remove and co-teacher management are in [`class_management.md`](./class_management.md) (#121). The tests in the table below seed archived, removed and co-teacher states directly, which still verifies #6 on its own. The shared WebSocket broker already supports bounded, targeted delivery and event-schema validation.

`POST /api/sync/pull` is implemented in #7; see [`delta_sync.md`](./delta_sync.md). It returns the same enrollment IDs as the roster and recovers missed approvals after reconnecting, including older class records for newly approved learners.

## Acceptance evidence

| Requirement | Automated evidence |
|---|---|
| Unique six-character codes; collision retry; caller owns class | `create_generates_valid_unique_codes_and_scoped_revisions`, `code_collision_can_retry_without_replacing_a_class_or_writing_a_revision` |
| Learner joins; teacher sees request; learner receives approval over a real socket | `join_approve_flow_pushes_websocket_events` |
| Teacher and learner classroom visibility; roster name/LRN/enrollment ID | `join_approve_flow_pushes_websocket_events`, `co_teachers_can_list_read_roster_and_decide_but_foreign_teachers_cannot` |
| #216 transitions, silent retries, rejected/removed rejoin | `decisions_retries_and_rejoins_preserve_the_enrollment_identity`, `concurrent_join_and_approval_retries_write_and_notify_once` |
| Archived write rejection; unknown code/class/enrollment errors | `archived_class_refuses_joins_and_decisions_without_changes_or_events`, `missing_codes_classes_and_enrollments_use_contract_errors` |
| No teacher-only LRN event to learners or unrelated teachers | `co_teachers_can_list_read_roster_and_decide_but_foreign_teachers_cannot`, response scans in `join_approve_flow_pushes_websocket_events` |
| Atomic revisions and durable offline recovery | `revision_failure_rolls_back_create_join_and_approval_without_events`, `disconnected_devices_recover_enrollment_after_hub_restart` |

From `server/backend/`, run `cargo check --all-targets` and `cargo test`. From the repository root, run `python3 -m unittest discover tests` and `python3 scripts/verify_invariants.py`. On this Windows PC, Python is `C:/Users/QCU/AppData/Local/Programs/Python/Python313/python.exe`; the launcher cannot locate it in the sandbox.

These checks use loopback networking. A physical classroom router with its WAN cable unplugged, real mobile/desktop clients and client-side offline SQLite remain integration-day checks, not claims made by these backend tests.

### Recorded verification (2026-11-03)

| Command | Result |
|---|---|
| `cargo check --all-targets` | PASS; final check finished in 11.33 s |
| `cargo test` | PASS; 130 tests in total, including all 10 enrollment tests; no failures |
| `python3 -m unittest discover tests` | PASS; 68 tests in 26.689 s against the mock |
| `python3 scripts/verify_invariants.py` | PASS; offline invariants and localization rules |
| `rustfmt --check` on the new/rewritten Rust files; `git diff --check` | PASS |

The final Rust enrollment test output includes:

```text
test join_approve_flow_pushes_websocket_events ... ok
test decisions_retries_and_rejoins_preserve_the_enrollment_identity ... ok
test concurrent_join_and_approval_retries_write_and_notify_once ... ok
test revision_failure_rolls_back_create_join_and_approval_without_events ... ok
test disconnected_devices_recover_enrollment_after_hub_restart ... ok
test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 3.47s
```

Full local command output is in `backend/target/issue-6-{check,tests,shared-tests,invariants}.log` (ignored build artifacts). Rust dependencies were cached and the commands ran with `CARGO_NET_OFFLINE=true`. An earlier shared-suite run timed out in the mock's unrelated submission sync-pull test during compilation; the final rerun above passed all 68 tests.

## How to test (no programming required)

Ask a developer to start this branch's Hub with one teacher account and two learner accounts, and point the test clients at that Hub. The Hub window manages accounts; use a teacher client to create classes.

1. Keep the classroom Wi-Fi connected and unplug the router's WAN cable. Sign in as the teacher and create **Science 4**, section **Aguinaldo**. Record its six-character class code.
2. On one learner device, enter that code. Expect **Pending approval**. The teacher should see the learner's name and LRN in the pending roster. The second learner must not see that person's LRN or pending request.
3. On the teacher device, choose **Accept**. The first learner should receive approval immediately and see the class as active. Refresh or repeat Accept if the client permits it: the roster must still contain one enrollment.
4. Use a fresh request to check **Decline**. The declined learner should lose the class from their list. Entering the code again should create a pending request for the same learner, and the teacher can accept it.
5. Sign in as a teacher who does not teach that class. That account must not see its roster or approve its learners. With a co-teacher fixture prepared by the developer, the co-teacher should be able to view and decide the request.
6. Disconnect the teacher device before another learner joins. Reconnect: the pending roster entry must still be there. Disconnect the learner before accepting; reconnect: the learner must see the active class. Restart the Hub and check the same roster again.
7. Stop the Hub. Requests should fail to connect, and the client should show its existing Hub-unreachable behavior. Restart the Hub: the class and enrollment must still be present. Checking the client's offline mirror is a separate client-team check.

For archived and removed states, use the steps in [`class_management.md`](./class_management.md). Record screenshots and any client limitations alongside the backend test output in the PR.
