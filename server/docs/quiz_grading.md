# Quiz grading (step 4.2, #12)

## Purpose
A learner's finished quiz is graded on the Hub the moment it arrives, and stored once (PRD FR-4.6). It can arrive three ways:
- live through REST;
- queued from a device that finished offline (FR-4.5);
- over the WebSocket.

The learner gets a receipt and `EVENT_GRADE_CONFIRMED` unless the teacher holds scores (FR-4.7). The teacher sees one results row per learner.

The contract routes and events are `POST /api/quizzes/{id}/submit`, `GET /api/quizzes/{id}/results`, `quiz_attempts` in `POST /api/sync/push`, `EVENT_QUIZ_SUBMIT` and `EVENT_GRADE_CONFIRMED`. Starting and closing a quiz (`/start`, `/close`) are step 4.1.

## Key files
| File | Purpose |
|---|---|
| `backend/src/services/grading_engine.rs` | `GradingQuestion`, `grade`: pure scoring by question id, with unit tests |
| `backend/src/services/quiz_submit.rs` | **The one submit path:** `submit(app, user, quiz_id, Submission) -> GradeReceipt`; checks, timing, grading, storage, events |
| `backend/src/routes/quiz_submit.rs` | REST submit and the teacher's results |
| `backend/src/routes/sync.rs` | `quiz_attempt`: one queued attempt to one receipt, through `submit` |
| `backend/src/websocket/mod.rs`, `events.rs` | `EVENT_QUIZ_SUBMIT` through `submit`; `EVENT_GRADE_CONFIRMED` |
| `backend/src/db/quizzes.rs` | `quiz_state`, `attempt_for`, `grading_key`, `store_submission`, `results` |
| `backend/tests/quiz_submit.rs` | Flows for all three paths, timing, held scores, results, and the 40-learner load test |

## Data flow
1. **One path.** REST, push and WebSocket all call `services::quiz_submit::submit`. Inside one `begin_immediate` transaction it goes:
   1. The quiz: 404, and a `DRAFT` is also 404.
   2. Membership: an `ACTIVE` learner of the class, else 403.
   3. A stored submission comes back as it is, never regraded. The one exception is the close's auto-submit (`auto_submitted = 1`), which the learner's own work replaces once (step 4.1, [`quiz_control.md`](./quiz_control.md)):
      * the work must be on time, timed from the Hub's start;
      * it must have finished no later than 60 s after `closed_at` (`CLOSE_GRACE_MS`), else 422 `TIME_LIMIT_EXCEEDED`;
      * the replacement is stored with `auto_submitted = 0`, so a second copy gets the stored receipt.
   4. The archived class: 403.
   5. The time limit: 422 `TIME_LIMIT_EXCEEDED`.
   6. Grade, then store the attempt as `SUBMITTED` with `score`, `total_points` and `answers_json`, plus a learner-scoped revision.
2. **Timing trusts the Hub's clock.**
   - The start is the attempt's `started_at`, written by `/begin`.
   - Only a learner with no attempt row has the device's `started_at` used, and then no earlier than the quiz's own start and no later than now.
   - The finish is the device's `submitted_at`, which can be an offline finish, but never later than the Hub's now.
   - The check is finish − start ≤ limit × 60 000 + 60 000 ms (rules section 3, rule 5).
   - **A device clock behind the Hub** gives a finish before the Hub's start. Then the elapsed time is the device's own duration (its `started_at` to its `submitted_at`, so a constant offset cancels). For a WebSocket submit, which is live and carries no start, it is the time since the Hub's start. Without this, the elapsed time would be 0, and a late finish would pass (review of #12).
   - **Clients must send Hub-synchronized times** (mobile and desktop TECH_SPECs: the offset from `EVENT_HELLO_ACK`). A device clock *ahead* of the Hub, on an offline finish pushed later, can still be refused wrongly. Trusting the device's duration there would let any device shorten its time.
3. **After commit:**
   - `EVENT_GRADE_CONFIRMED` `{quiz_id, attempt_id, score, total_points, timestamp}` goes to the learner when scores are released, or the quiz is `CLOSED` (a replacement after the close; mock parity).
   - `EVENT_PRESENCE` `SUBMITTED` goes to the class teachers.
   - A repeat sends neither.
4. **Receipts and the pull:**
   - The learner's receipt (`GradeReceipt`) has `score: null` while scores are held (`release_scores_immediately = 0` and the quiz is not `CLOSED`).
   - The pull already applies the same rule (2.2).
   - Teachers always see the score.
5. **Queued push:** each `quiz_attempts` item gets its own receipt, first and in order: `SYNCED`, or `REJECTED` with `reason` set to the error code (`BAD_REQUEST`, `NOT_FOUND`, `FORBIDDEN`, `TIME_LIMIT_EXCEEDED`). Only a Hub failure fails the whole push.
6. **WebSocket:**
   - `EVENT_QUIZ_SUBMIT` `{quiz_id, submitted_at, answers}` is accepted only after `EVENT_HELLO`, and its `student_id` is ignored (TECH_SPEC Q2).
   - A refusal is `EVENT_ERROR` `BAD_REQUEST` with the code as the message, the mock's choice: `error.json` lists no quiz codes. A malformed frame is answered too, not ignored.
   - It carries no `started_at`, so a learner with no attempt row (no `/begin`) is refused with message `BAD_REQUEST` ("Missing: started_at" on REST).
   - A WebSocket submit on a held-scores quiz, and any repeat, gets no reply at all (mock parity). Clients confirm through the pull.

## Grading
- **Multiple choice, true/false:** `selected_option` must equal `correct_answer` exactly. There is no index matching: the contract's `selected_option` is text, and an index would be ambiguous when the options are numbers.
- **Identification:** trimmed and lower-cased (Unicode), compared with the answer and each synonym, all trimmed and lower-cased as well.
- **Points:** the total counts every question, and an unanswered question scores 0. Answers to unknown questions are ignored. When a question is answered twice, the last answer counts.
- **No percentage is stored or sent.** The contract has only `score` and `total_points`; clients divide.

## Who may do what
| Action | Who |
|---|---|
| Submit (REST, push, WebSocket) | `ACTIVE` learners of the class. A `DRAFT` is 404. An archived class is 403 for new work, but a stored submission's receipt still comes back |
| Results | The class's teachers. One row per `ACTIVE` learner (`NOT_STARTED`, `IN_PROGRESS` or `SUBMITTED`), by name, with real scores only once submitted |

## Gotchas
- **A `CLOSED` quiz still accepts an on-time submission** that was queued offline (FR-4.5, as in the mock). The timing check decides.
- **One attempt per learner per quiz:** two submits at once are serialized by the write lock and give the same attempt.
- **Closing (step 4.1) is in [`quiz_control.md`](./quiz_control.md).** It auto-submits through `db::quizzes::store_submission`, not `submit`: `submit` is learner-only, runs the time check and sends presence. It writes a new revision for each released held score, so a learner who pulled `score: null` gets the score. The Lead settled FR-4.4 against FR-4.5 (#11, contract #293 and #294): the close auto-submits, and on-time work replaces that once.
- **For step 5.1, the AI lockout:** a learner whose submit was refused (422) keeps an `IN_PROGRESS` attempt. A lockout that ends only at `SUBMITTED` keeps the tutor off until the teacher closes the quiz, or forever if they never do. Lock only while the quiz is `ACTIVE`, or within the time limit plus grace.
- **Speed:** 40 submits at once grade and store in about 0.2 s in a debug build (`forty_learners_are_graded_in_under_two_seconds`; the target is 2 s).

## Manual check (WAN unplugged)
Until 4.1 adds `/start`, set a quiz `ACTIVE` in `lara.db` (`UPDATE quizzes SET status='ACTIVE', started_at=<now ms> WHERE id=...`).
1. As learner `123456789012`, begin the quiz and submit one right and one wrong answer. The receipt shows `score: 1, total_points: 2`, and the learner's app gets `EVENT_GRADE_CONFIRMED`.
2. Submit again with all answers right. The same receipt comes back, still 1.
3. As the teacher, open `/api/quizzes/{id}/results`. Every learner is listed, and the submitted one shows 1 of 2.
4. With a held-scores quiz (`release_scores_immediately: false`), the learner's receipt shows `score: null` while the teacher's results show the score.
