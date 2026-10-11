-- ==============================================================================
-- L.A.R.A MASTER SERVER SQLITE SCHEMA (Authoritative Local Hub)
-- ==============================================================================
-- Used by: SQLx migrations in server/backend/src/db/migrations (Rust).
-- Security: holds answer keys (quiz_questions.correct_answer) and PIN hashes. Neither ever leaves this DB.
-- Conventions: ids are UUID v4 TEXT; all timestamps are epoch milliseconds; booleans are INTEGER 0/1.
--
-- PRAGMAs are NOT part of the migration. SQLx runs migrations inside a transaction, where
-- `PRAGMA journal_mode = WAL` fails. Set them on the connection options instead:
--   journal_mode = WAL, synchronous = NORMAL, foreign_keys = ON (every connection), busy_timeout = 5000.
--
-- Deletion policy: never DELETE users, classrooms or graded rows in production code. Deactivate users
-- (users.is_active) and archive classrooms (classrooms.archived_at) so grades are never lost.
-- Tables holding grades reference users with ON DELETE RESTRICT to enforce this.
-- ==============================================================================

-- 1. Users (Teachers & Pupils)
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY NOT NULL,
    lrn_or_id TEXT UNIQUE NOT NULL,            -- 12-digit LRN (pupil) or teacher/admin ID
    full_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('TEACHER', 'STUDENT', 'ADMIN')),   -- one ADMIN row, created by POST /api/admin/setup; never synced
    pin_hash TEXT NOT NULL,                    -- argon2id of the 4-digit PIN. Rate-limit logins; a leaked hash is brute-forceable.
    is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 2. Classrooms (Subjects / Sections)
CREATE TABLE IF NOT EXISTS classrooms (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,                        -- e.g. "Science 4"
    section TEXT NOT NULL,                     -- e.g. "Aguinaldo"
    class_code TEXT UNIQUE NOT NULL
        CHECK(length(class_code) = 6 AND class_code = upper(class_code)),   -- e.g. "K7M4QX", no 0/O/1/I
    teacher_id TEXT NOT NULL,                  -- the owner. Co-teachers are rows in classroom_teachers
    archived_at INTEGER,                       -- set at the end of the school year instead of deleting
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE RESTRICT
);

-- 3. Class Enrollments & Approval Gate
CREATE TABLE IF NOT EXISTS enrollments (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'ACTIVE', 'REJECTED', 'REMOVED')),
    joined_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(classroom_id, student_id)
);

-- 4. Stream Announcements
CREATE TABLE IF NOT EXISTS announcements (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    allow_comments INTEGER NOT NULL DEFAULT 1 CHECK(allow_comments IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 5. Announcement Comments (Pupils & Teachers)
CREATE TABLE IF NOT EXISTS announcement_comments (
    id TEXT PRIMARY KEY NOT NULL,
    announcement_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (announcement_id) REFERENCES announcements(id) ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT
);

-- 6. Lesson Materials (files). Lesson text for the AI lives in material_chunks, not here.
CREATE TABLE IF NOT EXISTS materials (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    file_type TEXT NOT NULL CHECK(file_type IN ('DOCUMENT', 'VIDEO', 'WORKSHEET')),
    mime_type TEXT,                            -- e.g. application/pdf, video/mp4
    file_path TEXT NOT NULL,                   -- Server local storage path (never sent to clients)
    file_size_bytes INTEGER NOT NULL CHECK(file_size_bytes >= 0),
    topic_id TEXT,
    assignment_id TEXT,                        -- set when the file is an attachment of an assignment
    announcement_id TEXT,                      -- set when the file is an attachment of an announcement
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE SET NULL,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (announcement_id) REFERENCES announcements(id) ON DELETE CASCADE
);

-- 7. Pre-chunked lesson text for Socratic grounding (resolves ai_stream.grounded_chunk_id)
CREATE TABLE IF NOT EXISTS material_chunks (
    id TEXT PRIMARY KEY NOT NULL,
    material_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    heading TEXT,
    text TEXT NOT NULL,
    token_estimate INTEGER,                    -- lets the prompt builder stay inside the 2048-token window
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    UNIQUE(material_id, order_index)
);

-- 8. Assignments
CREATE TABLE IF NOT EXISTS assignments (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT NOT NULL DEFAULT '',
    due_date INTEGER NOT NULL,
    allow_late INTEGER NOT NULL DEFAULT 0 CHECK(allow_late IN (0, 1)),
    max_points INTEGER NOT NULL DEFAULT 100 CHECK(max_points > 0),
    topic_id TEXT REFERENCES topics(id) ON DELETE SET NULL,
    archived_at INTEGER,                       -- set by DELETE /api/assignments/{id}: hidden everywhere, rows and grades kept
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 9. Assignment Submissions (CameraX photos / documents)
CREATE TABLE IF NOT EXISTS assignment_submissions (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated so offline retries are idempotent
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- Server photo storage path (never sent to clients)
    file_type TEXT NOT NULL DEFAULT 'IMAGE' CHECK(file_type IN ('IMAGE', 'DOCUMENT')),
    submitted_at INTEGER NOT NULL,
    score INTEGER CHECK(score IS NULL OR score >= 0),
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(assignment_id, student_id)
);

-- 10. Quizzes (synchronized start)
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
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 11. Quiz Questions (with the master answer key)
CREATE TABLE IF NOT EXISTS quiz_questions (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE', 'IDENTIFICATION')),
    options_json TEXT,                         -- JSON array of strings, e.g. ["A", "B", "C", "D"]
    points INTEGER NOT NULL DEFAULT 1 CHECK(points > 0),
    image_path TEXT,
    correct_answer TEXT NOT NULL,              -- NEVER sent to pupils. Never selected by student-facing queries.
    synonyms_json TEXT,                        -- JSON array of accepted alternates for IDENTIFICATION
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, order_index)
);

-- 12. Student Quiz Attempts & Scores
-- A row is created by POST /api/quizzes/{id}/begin with status IN_PROGRESS. The AI lockout
-- (HTTP 403 / QUIZ_IN_PROGRESS) is enforced while a pupil has any IN_PROGRESS row.
CREATE TABLE IF NOT EXISTS quiz_attempts (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN ('IN_PROGRESS', 'SUBMITTED')),
    started_at INTEGER NOT NULL,
    submitted_at INTEGER,
    score INTEGER,
    total_points INTEGER,
    answers_json TEXT NOT NULL DEFAULT '[]',   -- JSON array of {question_id, selected_option}
    updated_at INTEGER NOT NULL,
    CHECK(status = 'IN_PROGRESS' OR (submitted_at IS NOT NULL AND score IS NOT NULL AND total_points IS NOT NULL)),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(quiz_id, student_id)                -- one attempt per pupil per quiz (no retakes in v1)
);

-- 13. Socratic AI Chat History (Hub-assisted conversations)
CREATE TABLE IF NOT EXISTS ai_chat_messages (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    material_id TEXT,
    chunk_id TEXT,                             -- the chunk the reply was grounded in (audit and tutor evaluation)
    language TEXT CHECK(language IS NULL OR language IN ('EN', 'FIL')),
    role TEXT NOT NULL CHECK(role IN ('USER', 'TUTOR')),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE SET NULL,
    FOREIGN KEY (chunk_id) REFERENCES material_chunks(id) ON DELETE SET NULL
);

-- 17. Co-teachers. classrooms.teacher_id stays the owner; every row here may also teach the class.
CREATE TABLE IF NOT EXISTS classroom_teachers (
    classroom_id TEXT NOT NULL,
    teacher_id TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    PRIMARY KEY (classroom_id, teacher_id),
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE RESTRICT
);

-- 18. Topics (group materials, assignments and quizzes in Classwork)
CREATE TABLE IF NOT EXISTS topics (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    name TEXT NOT NULL,                        -- e.g. "Unit 1 - Plants"
    order_index INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 19. Private comments: one thread per (assignment, learner), seen only by that learner and the class teachers.
-- Sync revisions for these rows carry student_id so classmates never receive them.
CREATE TABLE IF NOT EXISTS private_comments (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated when written offline
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,                  -- whose thread this is
    author_id TEXT NOT NULL,                   -- the learner or a teacher of the class
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE RESTRICT
);

-- 14. Delta-Sync Change Ledger
-- seq is the sync cursor. It is a strictly increasing integer, NOT a wall-clock time, so a wrong or
-- corrected Hub clock, two changes in the same millisecond, or a late commit can never make a client miss data.
-- Insert one row in the SAME transaction as every change a client must see (UPSERT or DELETE).
CREATE TABLE IF NOT EXISTS sync_revisions (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    classroom_id TEXT,                         -- NULL for global records (users)
    student_id TEXT,                           -- NULL = whole classroom; set = only that pupil and the teacher (submissions, attempts)
    entity_table TEXT NOT NULL,                -- e.g. 'announcements', 'materials', 'quizzes'
    entity_id TEXT NOT NULL,
    action TEXT NOT NULL DEFAULT 'UPSERT' CHECK(action IN ('UPSERT', 'DELETE')),
    created_at INTEGER NOT NULL                -- informational only, never used as the cursor
);

-- 15. Hub identity. hub_id never changes. sync_epoch is incremented on restore-from-backup (and when
-- old tombstones are pruned) so clients holding a cursor from before that point do a full resync.
CREATE TABLE IF NOT EXISTS hub_meta (
    key TEXT PRIMARY KEY NOT NULL,             -- 'hub_id', 'sync_epoch'
    value TEXT NOT NULL
);

-- 16. Auth Sessions (opaque bearer tokens issued by POST /api/auth/login)
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY NOT NULL,      -- SHA-256 of the token; the raw token is never stored
    user_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ==============================================================================
-- INDEXES (columns already covered by a UNIQUE constraint are not indexed twice)
-- ==============================================================================
CREATE INDEX IF NOT EXISTS idx_classrooms_teacher ON classrooms(teacher_id);
CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments(student_id, status);
CREATE INDEX IF NOT EXISTS idx_announcements_feed ON announcements(classroom_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_announcement_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_assignments_class_due ON assignments(classroom_id, due_date ASC);
CREATE INDEX IF NOT EXISTS idx_submissions_student ON assignment_submissions(student_id);
CREATE INDEX IF NOT EXISTS idx_quizzes_class ON quizzes(classroom_id, status);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_student ON quiz_attempts(student_id, status);
CREATE INDEX IF NOT EXISTS idx_ai_chat_student ON ai_chat_messages(student_id, classroom_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_sync_revisions_scope ON sync_revisions(classroom_id, seq);
CREATE INDEX IF NOT EXISTS idx_sync_revisions_entity ON sync_revisions(entity_table, entity_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expiry ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_classroom_teachers_teacher ON classroom_teachers(teacher_id);
CREATE INDEX IF NOT EXISTS idx_private_comments_thread ON private_comments(assignment_id, student_id, created_at ASC);

-- Integrity: a classroom can have at most one ACTIVE quiz, which is what GET /api/quizzes/active assumes.
CREATE UNIQUE INDEX IF NOT EXISTS uq_one_active_quiz_per_classroom ON quizzes(classroom_id) WHERE status = 'ACTIVE';
