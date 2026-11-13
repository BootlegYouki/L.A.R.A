-- Contract #294 (for #11 [SERVER 4.1]): quizzes.closed_at and quiz_attempts.auto_submitted, the two
-- columns the close replace-once rule needs (contract #293). Both sit mid-table in the contract, so the
-- tables are rebuilt with the contract's text, as in 002 and 003.
--
-- quiz_questions and quiz_attempts reference quizzes, and SQLite rewrites a child's REFERENCES clause
-- when its parent is renamed (see 003). Renaming quizzes alone would leave both children pointing at the
-- old copy, and dropping it would cascade into every question and attempt. So the family is rebuilt
-- together, and the old copies are dropped child first, when nothing references them any more. The
-- indexes move with the old tables and go with them, so they are created again at the end; created
-- earlier, IF NOT EXISTS would skip them because the old copies still hold the names.
--
-- Backfill: closed_at is updated_at for a quiz already CLOSED, the closest time the Hub kept (there was
-- no /close before 4.1, so only a quiz closed by hand has that status). auto_submitted is 0 everywhere:
-- nothing was auto-submitted before 4.1.

ALTER TABLE quizzes RENAME TO quizzes_before_004;
ALTER TABLE quiz_questions RENAME TO quiz_questions_before_004;
ALTER TABLE quiz_attempts RENAME TO quiz_attempts_before_004;

CREATE TABLE IF NOT EXISTS quizzes (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT,
    topic_id TEXT,
    time_limit_minutes INTEGER NOT NULL CHECK(time_limit_minutes > 0),   -- global duration, not per item
    shuffle_questions INTEGER NOT NULL DEFAULT 0 CHECK(shuffle_questions IN (0, 1)),
    release_scores_immediately INTEGER NOT NULL DEFAULT 1 CHECK(release_scores_immediately IN (0, 1)),
    scores_released_at INTEGER,                -- set when the teacher releases held scores
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    started_at INTEGER,                        -- authoritative server epoch ms when the teacher started the quiz
    closed_at INTEGER,                         -- server epoch ms when the teacher closed it; queued work replacing an auto-submit must finish before this
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

INSERT INTO quizzes (id, classroom_id, title, instructions, topic_id, time_limit_minutes, shuffle_questions, release_scores_immediately, scores_released_at, status, started_at, closed_at, created_at, updated_at)
SELECT id, classroom_id, title, instructions, topic_id, time_limit_minutes, shuffle_questions, release_scores_immediately, scores_released_at, status, started_at, CASE WHEN status = 'CLOSED' THEN updated_at END, created_at, updated_at
FROM quizzes_before_004;

CREATE TABLE IF NOT EXISTS quiz_questions (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE', 'IDENTIFICATION')),
    options_json TEXT,                         -- JSON array of strings, e.g. ["A", "B", "C", "D"]
    points INTEGER NOT NULL DEFAULT 1 CHECK(points > 0),
    image_path TEXT,
    correct_answer TEXT NOT NULL,              -- NEVER sent to learners. Never selected by student-facing queries.
    synonyms_json TEXT,                        -- JSON array of accepted alternates for IDENTIFICATION
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, order_index)
);

INSERT INTO quiz_questions (id, quiz_id, order_index, question_text, question_type, options_json, points, image_path, correct_answer, synonyms_json, created_at, updated_at)
SELECT id, quiz_id, order_index, question_text, question_type, options_json, points, image_path, correct_answer, synonyms_json, created_at, updated_at
FROM quiz_questions_before_004;

CREATE TABLE IF NOT EXISTS quiz_attempts (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN ('IN_PROGRESS', 'SUBMITTED')),
    started_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    submitted_at INTEGER,
    score INTEGER,
    total_points INTEGER,
    answers_json TEXT NOT NULL DEFAULT '[]',   -- JSON array of {question_id, selected_option}
    auto_submitted INTEGER NOT NULL DEFAULT 0 CHECK(auto_submitted IN (0, 1)),  -- 1 when /close submitted it; back to 0 once on-time queued work replaces it (once only)
    updated_at INTEGER NOT NULL,
    CHECK(status = 'IN_PROGRESS' OR (submitted_at IS NOT NULL AND score IS NOT NULL AND total_points IS NOT NULL)),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(quiz_id, student_id)                -- one attempt per learner per quiz (no retakes in v1)
);

INSERT INTO quiz_attempts (id, quiz_id, student_id, status, started_at, created_at, submitted_at, score, total_points, answers_json, auto_submitted, updated_at)
SELECT id, quiz_id, student_id, status, started_at, created_at, submitted_at, score, total_points, answers_json, 0, updated_at
FROM quiz_attempts_before_004;

DROP TABLE quiz_attempts_before_004;
DROP TABLE quiz_questions_before_004;
DROP TABLE quizzes_before_004;

CREATE INDEX IF NOT EXISTS idx_quizzes_class ON quizzes(classroom_id, status);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_student ON quiz_attempts(student_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_one_active_quiz_per_classroom ON quizzes(classroom_id) WHERE status = 'ACTIVE';
