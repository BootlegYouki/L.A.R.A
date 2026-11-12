# Quiz authoring, the learner's view and begin (step 4.5, #61)

## Purpose
Teachers write quizzes with answer keys (PRD FR-4.1) and can change them only while they are drafts (FR-4.8). Learners only ever receive the quiz without its answers. A learner's `/begin` opens their `IN_PROGRESS` attempt, which the AI lockout keys on (FR-4.3, enforced in steps 5.1 and 5.2).

The contract routes are `POST /api/quizzes`, `PATCH` and `GET /api/quizzes/{id}`, `GET /api/quizzes/active` and `POST /api/quizzes/{id}/begin`. Starting and closing (`/start`, `/close`, step 4.1), submitting and grading (`/submit`, `/results`, step 4.2) are still 501 stubs.

## Key files
| File | Purpose |
|---|---|
| `backend/src/models/quiz.rs` | `TeacherQuiz` and `TeacherQuestion` (with `correct_answer`, `synonyms`); `StudentQuiz` and `StudentQuestion`, **separate types with no answer field**; `QuizInput`, `QuestionType` |
| `backend/src/routes/quizzes.rs` | `parse_quiz` (every 400 check), and the create, update, read, active and begin handlers |
| `backend/src/db/quizzes.rs` | `insert`, `replace`, `meta`, `teacher_view`, `student_view` (no answer columns selected), `shuffle_for`, `active_for`, `begin` |
| `backend/tests/quizzes.rs` | Authoring, editing, the learner view, the shuffle, `/active` and begin flows |
| `backend/tests/redaction.rs` | Scans the raw JSON of every learner route that carries a quiz, including the pull, for the answer key |

## Data flow
- **Create:** the quiz row (`status 'DRAFT'`) and one `quiz_questions` row per question (`options_json` and `synonyms_json` as JSON arrays) go in one transaction with a class-wide `quizzes` revision. The pull sends quiz metadata only, and never a `DRAFT` to learners (2.2).
- **Edit:** only a `DRAFT`. The row is updated, and the questions are deleted and inserted again. A question keeps its id only if it already belonged to this quiz.
- **Read:** a teacher of the class gets `TeacherQuiz`. Everyone else takes the learner path:
  - a `DRAFT` is 404;
  - then the caller must be a member;
  - then they get `StudentQuiz`, which `student_view` builds from queries that never select `correct_answer` or `synonyms_json`.
- **Active:** the most recently started `ACTIVE` quiz in the caller's classes, always as the learner view (teachers too, as in the mock).
- **Begin:**
  - Inside the write lock, it finds the learner's attempt or inserts an `IN_PROGRESS` one (`UNIQUE(quiz_id, student_id)`), with a learner-scoped `quiz_attempts` revision.
  - After commit, a new attempt sends `EVENT_PRESENCE` `ANSWERING_QUIZ` to the class teachers.
  - The pull sends attempts only once `SUBMITTED` (step 4.2).

## Who may do what
| Action | Who | Archived class |
|---|---|---|
| Create, edit | The class's teachers (owner or co-teacher) | 403 "This class is archived" |
| Read with answers | The class's teachers | Allowed |
| Read without answers, `/active` | `ACTIVE` learners of the class (never a `DRAFT`) | Allowed |
| Begin | `ACTIVE` learners, on an `ACTIVE` quiz | 403 "This class is archived" |

**Error codes:**
- 409 `QUIZ_NOT_DRAFT` "A quiz can be edited only before it starts".
- 409 `QUIZ_CLOSED` "This quiz is closed", for beginning a quiz that is not `ACTIVE`. Listed in the contract since #291, with an English message.

## Gotchas and calls beyond the mock
- **Redaction is enforced twice:** by type (no answer field on the learner structs) and by `tests/redaction.rs`. If a new learner route carries a quiz, add it to that test.
- **Answers must be gradeable.** For multiple choice and true/false, `correct_answer` must be one of the `options` (400). Step 4.2 grades by exact match, so any other answer could never be marked right.
- **Shuffle per learner** (FR-4.1; the mock does not shuffle).
  - With `shuffle_questions` on, each learner gets their own order: a Fisher–Yates shuffle seeded by the FNV-1a hash of `quiz_id:student_id`. It's the same order on every request.
  - The learner's questions are numbered 1..n (`order_index`) in that order. Clients sort by `order_index` (the mobile quiz cache), so keeping the teacher's numbers would undo the shuffle (review of #61).
  - Teachers see `order_index` order.
  - Grading uses question ids (rules section 4.5), so the order never changes a score.
- **Question ids and order:**
  - Another quiz's question id sent on edit gets a new id, and so does the second copy of one of this quiz's ids (a duplicated question). Either would otherwise hit the primary key and answer 500.
  - Repeated `order_index` values get 400 (the table has `UNIQUE(quiz_id, order_index)`).
- **Numbers:** `points` are 1 to 2^53−1, and so is their sum: step 4.2 stores it as `total_points`. `order_index` is 0 to 2^53−1, the desktop's integer range. `time_limit_minutes` is 1 to 150119987578, so 4.2's grace check (limit × 60 000 + 60 000 ms) can never overflow.
- **`topic_id`** must be a topic of the same class. Deleting a topic clears it on quizzes too (3.5).
- **`/begin` is idempotent.** A second begin returns the same attempt and sends no second presence event. A learner who has already submitted gets their attempt back; it never reopens. Two begins at once open one attempt.
- **`/active` with several active quizzes:** the newest by `started_at` wins.
- **`QuizMetadata` in the pull has no `topic_id` yet.** The pull's quiz item in the contract has none.

## Manual check (WAN unplugged)
Until step 4.1 adds `/start`, set a quiz `ACTIVE` in `lara.db` by hand (`UPDATE quizzes SET status='ACTIVE', started_at=<now ms> WHERE id=...`).
1. As teacher `T-0001`, create a quiz with a multiple-choice and an identification question. It comes back `DRAFT`, with the answers.
2. Edit its title: 200. Set it `ACTIVE` and edit again: 409 "A quiz can be edited only before it starts".
3. As learner `123456789012`, open `/api/quizzes/active` and `/api/quizzes/{id}`. There is no `correct_answer` or `synonyms` anywhere.
4. Begin it twice. You get the same `attempt_id` both times. A teacher connected over WebSocket sees `ANSWERING_QUIZ` once.
