# WebSocket broker (#44)

The broker delivers transient hints over port 8081. It stores no events or classroom data. REST and SQLite remain the source of truth. Per the Lead's decision in #213, there is no replay: after reconnect clients call `POST /api/sync/pull` and `GET /api/quizzes/active`. Presence, queue status and tutor tokens are not recovered.

Key files:

- `backend/src/websocket/broker.rs`: registry keyed by user id, with one 64-frame bounded queue per device. `send_to_user` reaches every registered device of that user. `send_to_classroom_students` queries active enrollments and active learner accounts. `send_to_teacher` queries the owner and co-teachers, deduplicated, with active teacher accounts only. The class helpers take `&SqlitePool` and are async because membership is resolved from committed data on each push.
- `backend/src/websocket/mod.rs`: authenticates `EVENT_HELLO` through the shared session lookup; queues `EVENT_HELLO_ACK` before registering. Disconnect, read failure, queue overflow, a write taking over ten seconds, or Hub shutdown ends the connection. Queue overflow signals the connection task so a stuck writer and reader are both dropped.
- `backend/src/websocket/presence.rs`: emits `EVENT_PRESENCE` to the teachers of each active enrollment. Connect emits `ONLINE`. Disconnect emits `OFFLINE` only when no device remains for the learner. Teachers and admins do not emit learner presence. No database rows are written.
- `backend/src/websocket/events.rs`: typed payloads for presence, enrollment and announcement hints, plus the existing handshake/error types. Tests read the shared JSON schemas locally and check required fields, types, constants and enums. The ACK is checked against the README handshake, which has no JSON schema.
- `backend/tests/websocket.rs`: real socket presence, abrupt disconnect, reconnect without replay, and targeted delivery using temporary Hub databases.

Routes must authorize the caller and commit their transaction before invoking a broker helper. A membership lookup failure is logged without turning a committed write into a failed response; the client recovers by pull. No registry lock is held during database or socket I/O. Each push uses `try_send`, so a full or closed device queue never waits on a phone. A disconnected learner gets no queued work; their persisted work remains in SQLite. Presence payloads contain internal student ids, not LRNs. `JoinRequest` includes the contract's LRN and must be sent only through teacher targeting. The broker is infrastructure; the classroom, announcement and quiz routes remain their own issues.

## Verification and how to test

From `server/backend/`:

```powershell
cargo check --all-targets
cargo test
cargo test --test websocket -- --nocapture
```

The last command starts a temporary Hub automatically and checks learner/teacher delivery. A successful run prints `test result: ok`. It requires no school accounts, installs nothing and leaves school data untouched. The queue isolation test runs with `cargo test full_and_closed_queues_do_not_delay_a_healthy_device`.

From the repository root, run `python3 -m unittest discover tests` and `python3 scripts/verify_invariants.py`. These check the mock Hub and shared guardrails; they do not prove that unfinished classroom and quiz routes work on the real Hub.

On this Windows machine the working interpreter is `C:/Users/QCU/AppData/Local/Programs/Python/Python313/python.exe`; `python3` resolves to an unavailable Windows app alias. Set `$env:PYTHONUTF8 = '1'` in PowerShell before running the Python checks so scanner subprocesses can print their Unicode status messages.

Verification recorded on 2026-11-03, based on staging merge `a1e32c1` (#213):

| Requirement | Evidence |
| --- | --- |
| Registry and targeted push | `targeted_push_uses_active_membership_and_all_class_teachers`: active enrollment only, owner/co-teacher delivery, owner deduplication, no teacher LRN event to learners. `registry_tracks_each_device_of_a_user`: separate device registration and idempotent cleanup. |
| Learner presence | `presence_tracks_multiple_devices_and_abrupt_disconnect`: real sockets, ONLINE, one device stays connected, last device drops, OFFLINE, reconnect and targeted approval. |
| Slow/closed client isolation | `full_and_closed_queues_do_not_delay_a_healthy_device`: fills one queue while draining a healthy device; dead/full connections are removed and the slow task is signaled. |
| Emitted schema conformance | `emitted_push_frames_match_the_contract_schemas`, `error_frames_match_error_json`, `hello_ack_has_the_handshake_keys`: all currently emitted payload variants and presence/enrollment enum values. |
| No replay after #213 | `reconnect_does_not_replay_missed_events`: a hint sent while disconnected is absent after HELLO_ACK. |
| Team check | `cargo check --all-targets`: exit 0, Finished dev profile. `cargo test`: exit 0, 120 passed across unit/integration suites, 0 failed. |
| Contract/mock Hub reference | Python discovery: `Ran 66 tests ... OK`, including join/approval WebSocket pushes, grading event and bad-token close. These run against the mock. Existing Rust handshake tests verify ACK, 4401 and shutdown against the real Hub; full REST-to-event parity awaits the route implementations. |
| Offline/localization guardrails | `verify_invariants.py`: exit 0, all offline invariants and localization rules PASSED. |
| Manual LAN smoke check (reported by Yaji) | Browser reached the real Hub at `10.24.228.235:8080`; unauthenticated `GET /api/classrooms` returned 401. WebSocket connected on port 8081 and an invalid HELLO token closed with 4401. Attach the browser screenshots to the PR and record whether the WAN cable was unplugged. This checks reachability and authentication only. |
| Physical classroom | Full classroom flows, 40-device latency and a physical Hub-unreachable client check remain unverified. The manual steps below remain required. |

For the physical classroom check, once the route issues are available:

1. Connect a teacher device and two learner devices to the Hub's classroom Wi-Fi. Unplug the router's WAN cable.
2. Sign in and open an enrolled learner on both a phone and laptop. The teacher should see the learner online. Close one learner device: they should stay online. Close the other: they should appear offline.
3. Have a pending learner request to join. Only that class's teacher sessions should receive the request. Approve it: only that learner's sessions should receive the approval.
4. Disconnect a learner, post an announcement, and reconnect them. Their app should recover it through sync pull; the Hub should not replay a WebSocket frame.

Physical Wi-Fi behavior, client UI behavior and 40-device latency require this separate integration check; local socket tests cannot verify those conditions.
