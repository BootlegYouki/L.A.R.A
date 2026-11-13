# Quiz start and close (step 4.1, #11)

## Purpose
A teacher starts a quiz for the whole class at one Hub instant (PRD FR-4.2) and closes it (FR-4.4).
- **Closing auto-submits** every unfinished attempt.
- **Closing releases held scores** (FR-4.6).
- **A learner who finished on time** while offline, or whose app was still answering at the close, keeps their own work (FR-4.5). It replaces the close's auto-submit once.

The contract routes and events are `POST /api/quizzes/{id}/start`, `POST /api/quizzes/{id}/close`, `EVENT_QUIZ_START`, `EVENT_QUIZ_CLOSED` and `EVENT_GRADE_CONFIRMED`. The rules are in `rules/quiz-and-anti-cheat.md` sections 2 to 4, and the codes and close grace in contract #293 and #294.

## Key files
| File | Purpose |
|---|---|
| `backend/src/routes/quiz_control.rs` | The `/start` and `/close` handlers: checks, one transaction, then the events |
| `backend/src/websocket/quiz_hub.rs` | `announce_start`, `announce_close`: the fan-out after commit; a failed recipient lookup is logged, never fails the request |
| `backend/src/db/quizzes.rs` | `active_in_class`, `start`, `close` (auto-submit and release), `ReleasedScore`; `store_submission` writes `auto_submitted` |
| `backend/src/services/quiz_submit.rs` | The replace-once rule inside the one submit path (`CLOSE_GRACE_MS`) |
| `backend/src/db/migrations/004_quiz_close_columns.sql` | `quizzes.closed_at` and `quiz_attempts.auto_submitted` (contract #294) |
| `backend/tests/quiz_control.rs` | Start, close, release, retries, who may do what, the replace-once rule and the 40-learner start |

## Data flow
1. **Start** (one `begin_immediate` transaction):
   1. The quiz: 404 "Quiz not found".
   2. A teacher of the class (owner or co-teacher), and the class is not archived: 403.
   3. Not a `DRAFT`: 409 `QUIZ_NOT_DRAFT` "This quiz has already been started".
   4. Another `ACTIVE` quiz in the class: 409 `QUIZ_ALREADY_ACTIVE` `Close "<title>" before starting another quiz`. This is checked before the `uq_one_active_quiz_per_classroom` index could answer 500.
   5. `status 'ACTIVE'`, `started_at` = the Hub's now, and a class revision, so the pull sends the quiz `ACTIVE`.
   6. After commit, `EVENT_QUIZ_START` `{quiz_id, title, start_epoch_ms, duration_seconds}` goes to every `ACTIVE` learner of the class. The reply is `{started_at, duration_seconds}`, where `duration_seconds = time_limit_minutes * 60`.
2. **Close** (one transaction):
   1. The quiz: 404. A teacher of the class: 403. An archived class may still close (see Gotchas).
   2. A `DRAFT` gets 409 `QUIZ_NOT_STARTED` "Start the quiz before closing it". An already `CLOSED` quiz gets 200 `{"success": true}` with no write and no event, so a retry is safe.
   3. The quiz becomes `CLOSED` with `closed_at` = now. When scores were held, `scores_released_at` = now too (TECH_SPEC Q16). A class revision is written.
   4. Each `IN_PROGRESS` attempt is graded from its stored `answers_json` and stored through `store_submission`, with `submitted_at` = now and `auto_submitted = 1`.
   5. When scores were held, each attempt submitted before the close gets a new learner revision, so a learner who pulled `score: null` gets the score.
   6. After commit:
      * `EVENT_GRADE_CONFIRMED` goes to each released learner;
      * then `EVENT_QUIZ_CLOSED` `{quiz_id, timestamp}` goes to the class's learners (the mock's order);
      * the reply is `{"success": true}`.
3. **The replace-once rule** (in `submit`, so REST, push and WebSocket all apply it):
   * A stored submission comes back as it is, except an auto-submitted one.
   * The learner's own work replaces that once, if it passes the time check from the Hub's start and `finish <= closed_at + 60 000` (`CLOSE_GRACE_MS`). Otherwise the answer is 422 `TIME_LIMIT_EXCEEDED`.
   * The replacement is stored with `auto_submitted = 0`, so the next copy gets the stored receipt.
   * The same close limit holds for a learner with no attempt row: they never reached `/begin`, so the close auto-submitted nothing. Without it, their work could arrive days after the close and still count, while a learner who began would be refused. This is a call beyond the mock (review of #11).
   * `EVENT_GRADE_CONFIRMED` goes out because the quiz is `CLOSED`.

## Who may do what
| Action | Who | Archived class |
|---|---|---|
| Start | The class's teachers (owner or co-teacher) | 403 "This class is archived" |
| Close | The class's teachers | Allowed |
| Replace the close's auto-submit | The learner, through `/submit`, the push or `EVENT_QUIZ_SUBMIT` | 403 (as for any new work) |

## Gotchas
- **The close almost always stores 0.** The Hub never has a learner's answers before they submit, so `answers_json` is `[]` for an unfinished attempt. A learner's app sends its saved answers when it gets `EVENT_QUIZ_CLOSED`. Its `submitted_at` is a little after `closed_at`, which is why the 60 s grace exists (the Lead's answer on #11).
- **Auto-submitted learners get no `EVENT_GRADE_CONFIRMED`** at the close, as in the mock. A 0 would flash on the screen just before the app's own answers replace it.
- **The release writes revisions but leaves `updated_at` alone.** The pull's attempt item (`GradeReceipt`) has no `updated_at`, and the mock doesn't touch the row.
- **An archived class may still close an `ACTIVE` quiz.** Closing ends work. Refusing it would leave the quiz `ACTIVE` and the learners' AI tutor locked (steps 5.1 and 5.2).
- **Closing an already-closed quiz:** the Hub answers `{"success": true}` as the contract's `Ok` response says. The mock answers `{"ok": true}` there, against the contract; the mock is the Lead's to fix.
- **A learner who connects after the start** misses `EVENT_QUIZ_START`. The mobile app then asks `GET /api/quizzes/active` (mobile TECH_SPEC).
- **Speed:** 40 connected learners get `EVENT_QUIZ_START` within about 1 ms of each other in a debug build (`forty_learners_get_the_start_within_300_ms`; the target is 300 ms). The broker never waits on a socket.
- **Teachers get no presence event for auto-submitted learners** (mock parity). A live roster may keep showing them `ANSWERING_QUIZ` until it reloads `/results`.
- **`closed_at`'s comment in the schema** says queued work "must finish before this". The 60 s grace (#294) came later; the comment is the Lead's to update.
- **`/results`** shows an auto-submitted attempt as `SUBMITTED` with its score. The contract's `ResultRow` has no field that marks it as auto-submitted.

## Manual check (WAN unplugged)
With the test accounts (`LARA_TEST_SEED=1`), teacher `T-0001` and learners `123456789012` (Juan) and `123456789013` (Ana) in one class:
1. Create a 15-minute quiz and start it. The reply is `{started_at, duration_seconds: 900}`, and a connected learner app gets `EVENT_QUIZ_START`.
2. Start a second quiz in the same class: 409 `QUIZ_ALREADY_ACTIVE`, naming the first.
3. Juan begins and submits. Ana only begins.
4. Close the quiz: `{"success": true}`. Both apps get `EVENT_QUIZ_CLOSED`, and `/results` shows Ana `SUBMITTED` with 0.
5. Ana's app pushes the answers she finished before the close: `SYNCED`, and `/results` shows her real score. Pushing again changes nothing.
6. Close again: `{"success": true}`, and nothing changes.
