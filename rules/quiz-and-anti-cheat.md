# Paperless Quiz & Anti-Cheat Invariants

This rule document governs assessment engine authoring, synchronized delivery, cheating prevention, and auto-grading.

All AI agents and contributors must follow these rules.

---

## 1. AI Tutor Lockout During Quizzes (enforced by the client)

To protect assessment integrity, the on-device Socratic AI tutor must not run while a student has an active quiz session. The tutor lives only on the device and the Hub never sees its requests, so **the client enforces this alone** (decided 2026-11-20).

1. **Client UI Removal:** When the student opens the quiz screen, the floating "Ask L.A.R.A AI" button and chat drawer must be **completely unmounted from the view hierarchy**, not just hidden with CSS.
2. **Local attempt state:** A learner has an *active attempt* from the moment `POST /api/quizzes/{id}/begin` creates a `quiz_attempts` row with `status = 'IN_PROGRESS'` until it is `SUBMITTED`. The client already holds that row in its local database (it created it, or pulled it with `POST /api/sync/pull`). While one exists the client must refuse to load the model or generate, even if the chat screen is reachable some other way, and must stop and free a generation that is already running when a quiz starts.
3. **Visible state:** the AI tab may remain visible only as a disabled placeholder that explains why; the chat composables are never composed during the quiz.
4. **Known, accepted limitation (not hidden):** this is weaker than the Hub refusing a request. A learner can edit their own local database, and a device that has not yet synced a quiz start does not know about the attempt, so a determined learner can still reach the tutor during a quiz. The Hub cannot see or stop it. The Lead accepted this trade-off with the move to an on-device tutor. Teachers should know it, and no document may describe the lockout as server-enforced. Mitigations that stay inside the rules: the model never carries answer keys (`correct_answer` never reaches a client), and the tutor only knows the teacher's lesson chunks.

---

## 2. Timing & Auto-Submission Rules

1. **Global Time Limit:** Countdowns run on overall quiz duration (e.g., 20 minutes for 15 questions), not per-item timers.
2. **Synchronized Start:** `POST /api/quizzes/{id}/start` sets `started_at` from the Hub clock and the Hub emits `EVENT_QUIZ_START` (`start_epoch_ms`, `duration_seconds`). Clients compute their offset from `server_time` in `EVENT_HELLO_ACK` and count down on a monotonic clock. Only one quiz per classroom can be `ACTIVE`.
   * The teacher can close the quiz (`POST /api/quizzes/{id}/close`): the Hub emits `EVENT_QUIZ_CLOSED`, auto-submits and grades every `IN_PROGRESS` attempt, and clients lock input and submit. A learner who finished on time while offline (FR-4.5) is not penalised by the close: their queued work, if on time by the Hub's `started_at` and finished no later than 60 seconds after the close (the same network grace as the time limit, so a device that auto-submits on `EVENT_QUIZ_CLOSED` counts), replaces the Hub's auto-submit once. Any other stored attempt is never regraded.
3. **Visual Countdown Pill:**
   * Green: Remaining time > 5 minutes.
   * Yellow: Remaining time <= 5 minutes.
   * Red (Pulsing): Remaining time <= 2 minutes.
4. **Hard Timeout Auto-Submit:** When the countdown reaches `00:00`, the client UI must instantly lock all input fields and submit current answers without waiting for a user click.
5. **Remote/Suspension Mode (`rules/networking-and-lan.md` section 5):** `EVENT_QUIZ_START`'s live, tick-by-tick synced countdown is a classroom-LAN feature and is not relied upon over the Cloudflare Tunnel. A client connected remotely instead runs its own local countdown from `duration_seconds`, anchored to the `started_at` it fetches from the Hub (not a push), and still shows the same visual pill (rule 3) and hard-timeout auto-submit (rule 4). The Hub does not trust this local countdown for grading — section 3 rule 5's `submitted_at - started_at` check is still what decides whether a submission is on time, exactly as it already does for an in-school disconnection.

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
4. **Receipts and score release:** The Hub records the score in `quiz_attempts` and, unless the teacher chose to hold scores (`release_scores_immediately = false`), emits `EVENT_GRADE_CONFIRMED` and returns the receipt. While scores are held, the learner's receipt and pull carry `score: null`. Closing the quiz (`POST /api/quizzes/{id}/close`) releases them: the Hub emits `EVENT_GRADE_CONFIRMED` to each learner and the score arrives on the next pull. There is no separate release action.
5. **Shuffle:** when `shuffle_questions` is set, each learner gets a different question order; the Hub still grades by `question_id`, never by position.
