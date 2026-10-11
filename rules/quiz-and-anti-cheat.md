# Paperless Quiz & Anti-Cheat Invariants

This rule document governs assessment engine authoring, synchronized delivery, cheating prevention, and auto-grading.

All AI agents and contributors must follow these rules.

---

## 1. Absolute AI Tutor Lockout During Quizzes

To guarantee assessment integrity, the Socratic AI tutor is strictly forbidden from running while a student has an active quiz session:

1. **Client UI Removal:** When the student opens the quiz screen, the floating "Ask L.A.R.A AI" button and chat drawer must be **completely unmounted from the view hierarchy**, not just hidden with CSS.
2. **Server-Side Rejection (`HTTP 403`):** A learner has an *active attempt* from the moment `POST /api/quizzes/{id}/begin` creates a `quiz_attempts` row with `status = 'IN_PROGRESS'` until it is `SUBMITTED`. While one exists, the Hub rejects any AI request: `HTTP 403` with error code `QUIZ_IN_PROGRESS` on REST, or `EVENT_ERROR` with code `QUIZ_IN_PROGRESS` on the WebSocket (`EVENT_AI_CHAT_REQUEST`).
3. **Visible state:** the AI tab may remain visible only as a disabled placeholder that explains why; the chat composables are never composed during the quiz.

---

## 2. Timing & Auto-Submission Rules

1. **Global Time Limit:** Countdowns run on overall quiz duration (e.g., 20 minutes for 15 questions), not per-item timers.
2. **Synchronized Start:** `POST /api/quizzes/{id}/start` sets `started_at` from the Hub clock and the Hub emits `EVENT_QUIZ_START` (`start_epoch_ms`, `duration_seconds`). Clients compute their offset from `server_time` in `EVENT_HELLO_ACK` and count down on a monotonic clock. Only one quiz per classroom can be `ACTIVE`.
   * The teacher can close the quiz (`POST /api/quizzes/{id}/close`): the Hub emits `EVENT_QUIZ_CLOSED`, auto-submits and grades every `IN_PROGRESS` attempt, and clients lock input and submit.
3. **Visual Countdown Pill:**
   * Green: Remaining time > 5 minutes.
   * Yellow: Remaining time <= 5 minutes.
   * Red (Pulsing): Remaining time <= 2 minutes.
4. **Hard Timeout Auto-Submit:** When the countdown reaches `00:00`, the client UI must instantly lock all input fields and submit current answers without waiting for a user click.

---

## 3. Disconnection & Offline Fault Tolerance

Classroom Wi-Fi routers may drop connection during a test. The system must handle this without losing student progress:

1. **Local Timer Continuation:** If Wi-Fi cuts out, the timer must continue ticking down locally using device hardware clocks (`SystemClock.elapsedRealtime()`).
2. **Offline Completion:** The student must be allowed to answer questions and finish the test even if the network icon indicates offline.
3. **Local Queueing:** Upon completion, the quiz attempt is saved in local SQLite with status `'QUEUED_FOR_SYNC'`.
4. **Auto-Flush Upon Reconnect:** As soon as the client detects the Hub Wi-Fi (even after class), the finished test auto-submits.
5. **Server Validation:** The server compares `submitted_at - started_at` against the allowed duration (with a 60-second grace window for network transmission latency) to ensure the student did not exceed the time limit while offline.

---

## 4. Objective Auto-Grading Rules

1. **Multiple Choice & True/False:** Exact string or option index match against `quiz_questions.correct_answer`.
2. **Identification / Short Answer:** Case-insensitive string comparison with leading/trailing whitespace trimmed. Optional support for teacher-defined synonym arrays.
3. **Execution Speed:** Auto-grading must execute in under 50 milliseconds per submission on the Hub.
4. **Receipts and score release:** The Hub records the score in `quiz_attempts` and, unless the teacher chose to hold scores (`release_scores_immediately = false`), emits `EVENT_GRADE_CONFIRMED` and returns the receipt. Held scores are released by the teacher and reach the learner on the next sync.
5. **Shuffle:** when `shuffle_questions` is set, each learner gets a different question order; the Hub still grades by `question_id`, never by position.
