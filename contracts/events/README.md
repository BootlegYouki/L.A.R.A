# WebSocket Event Contract (`ws://<hub-ip>:8081`)

Every frame is one JSON object with an `event` field and snake_case keys. One schema per event lives in this folder.

## Handshake
1. Client opens `ws://<hub-ip>:8081`.
2. Client sends `EVENT_HELLO` with the bearer token from `POST /api/auth/login` (and `last_event_id` after a reconnect).
3. Hub replies `{"event":"EVENT_HELLO_ACK","server_time":<epoch ms>}`. Invalid token: Hub closes with code `4401`.
4. Clients reconnect with exponential backoff (1s, 2s, 4s, max 10s) and must never show a blocking error; offline-first rules apply.

## Direction
| Event | Direction | Trigger |
|---|---|---|
| `EVENT_HELLO` / `EVENT_HELLO_ACK` | client to Hub / Hub to client | connect |
| `EVENT_JOIN_REQUEST` | Hub to teacher | pupil `POST /api/classrooms/join` |
| `EVENT_JOIN_APPROVAL` | Hub to pupil | teacher approve or reject |
| `EVENT_ANNOUNCEMENT_PUSH` | Hub to enrolled pupils | `POST /api/announcements` |
| `EVENT_QUIZ_START` | Hub broadcast | `POST /api/quizzes/{id}/start` |
| `EVENT_QUIZ_CLOSED` | Hub broadcast | `POST /api/quizzes/{id}/close` |
| `EVENT_QUIZ_SUBMIT` | pupil to Hub | alternative to REST submit; same grading path |
| `EVENT_GRADE_CONFIRMED` | Hub to pupil | grading finished |
| `EVENT_PRESENCE` | Hub to teacher | pupil connect, quiz progress |
| `EVENT_AI_CHAT_REQUEST` | pupil to Hub | tutor question |
| `EVENT_QUEUE_STATUS` | Hub to pupil | FIFO queue position change |
| `EVENT_AI_TOKEN_STREAM` | Hub to pupil | generated tokens |
| `EVENT_ERROR` | Hub to client | any rejected request |

## Rules
- `EVENT_QUIZ_START` carries `start_epoch_ms` (server clock). Clients compute the offset from `server_time` in `EVENT_HELLO_ACK` and then count down on a monotonic clock (`SystemClock.elapsedRealtime()`, `performance.now()`).
- REST remains the source of truth. Events are hints; a client that misses one recovers with `POST /api/sync/pull`.
- Changing any file here is a `contract-change` PR (see `rules/team-workflow-and-prs.md`).
