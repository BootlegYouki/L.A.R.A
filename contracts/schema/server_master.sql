-- ==============================================================================
-- L.A.R.A MASTER DEDICATED SERVER SQLITE SCHEMA (Authoritative Local Hub)
-- ==============================================================================
-- Technology Invariant: Embedded SQLite via SQLx (Rust) or Drizzle (Node).
-- Security Invariant: Stores master answer keys and the monotonic sync_revisions ledger.
-- Concurrency Best Practice: WAL mode + NORMAL synchronous + 5s busy timeout.
-- ==============================================================================

-- High-Concurrency Engine Pragmas
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;

-- 1. Users (Teachers & Pupils)
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY NOT NULL,              -- UUID v4
    lrn_or_id TEXT UNIQUE NOT NULL,            -- 12-digit DepEd LRN or Teacher ID
    full_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('TEACHER', 'STUDENT')),
    pin_hash TEXT NOT NULL,                    -- 4-digit PIN hash (bcrypt / argon2)
    created_at INTEGER NOT NULL,               -- Epoch ms
    updated_at INTEGER NOT NULL                -- Monotonic epoch ms
);

-- 2. Classrooms (Subjects / Sections)
CREATE TABLE IF NOT EXISTS classrooms (
    id TEXT PRIMARY KEY NOT NULL,              -- UUID v4
    name TEXT NOT NULL,                        -- e.g. "Science 4 (Agham 4)"
    section TEXT NOT NULL,                     -- e.g. "Aguinaldo"
    class_code TEXT UNIQUE NOT NULL,           -- 6-character alphanumeric code (e.g. "SCI4-AG")
    teacher_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 3. Class Enrollments & Approval Gate
CREATE TABLE IF NOT EXISTS enrollments (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'ACTIVE', 'REJECTED')),
    joined_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,                -- Tracks when teacher changes status PENDING -> ACTIVE
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
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
    FOREIGN KEY (author_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 6. Lesson Materials & Text Extraction
CREATE TABLE IF NOT EXISTS materials (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    file_type TEXT NOT NULL CHECK(file_type IN ('DOCUMENT', 'VIDEO', 'WORKSHEET')),
    file_path TEXT NOT NULL,                   -- Server local storage path
    file_size_bytes INTEGER NOT NULL,
    extracted_text TEXT,                       -- Pre-chunked plain text for SLM grounding
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 7. Assignments (DepEd Categorized)
CREATE TABLE IF NOT EXISTS assignments (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT NOT NULL,
    deped_category TEXT NOT NULL DEFAULT 'PERFORMANCE_TASK' CHECK(deped_category IN ('WRITTEN_WORK', 'PERFORMANCE_TASK', 'QUARTERLY_ASSESSMENT')),
    due_date INTEGER NOT NULL,                 -- Epoch ms
    max_points INTEGER NOT NULL DEFAULT 100,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 8. Assignment Submissions (CameraX photos / worksheets)
CREATE TABLE IF NOT EXISTS assignment_submissions (
    id TEXT PRIMARY KEY NOT NULL,
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- Server photo storage path
    file_type TEXT NOT NULL DEFAULT 'IMAGE',   -- 'IMAGE' | 'DOCUMENT'
    submitted_at INTEGER NOT NULL,
    score INTEGER,                             -- Nullable until graded
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,                -- Tracks when teacher updates grade/feedback
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(assignment_id, student_id)
);

-- 9. Quizzes (Assessments with DepEd Category & Synchronized Start)
CREATE TABLE IF NOT EXISTS quizzes (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT,
    deped_category TEXT NOT NULL DEFAULT 'WRITTEN_WORK' CHECK(deped_category IN ('WRITTEN_WORK', 'PERFORMANCE_TASK', 'QUARTERLY_ASSESSMENT')),
    time_limit_minutes INTEGER NOT NULL,       -- Global overall duration
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    started_at INTEGER,                        -- Authoritative epoch ms when teacher activated quiz
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 10. Quiz Questions (With Server Master Answer Key)
CREATE TABLE IF NOT EXISTS quiz_questions (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE', 'IDENTIFICATION')),
    options_json TEXT,                         -- JSON array of strings e.g. ["A", "B", "C", "D"]
    points INTEGER NOT NULL DEFAULT 1,
    image_path TEXT,                           -- Optional reference diagram path
    correct_answer TEXT NOT NULL,              -- Authoritative answer key (NEVER sent to student clients during test)
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE
);

-- 11. Student Quiz Attempts & Scores
CREATE TABLE IF NOT EXISTS quiz_attempts (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    submitted_at INTEGER NOT NULL,
    score INTEGER NOT NULL,
    total_points INTEGER NOT NULL,
    answers_json TEXT NOT NULL,                -- JSON object of student submitted answers
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, student_id)
);

-- 12. Socratic AI Chat History (Persistent Home & Class Conversations)
CREATE TABLE IF NOT EXISTS ai_chat_messages (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    material_id TEXT,                          -- Lesson module chunk context
    role TEXT NOT NULL CHECK(role IN ('USER', 'TUTOR')),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE SET NULL
);

-- 13. Delta-Sync Monotonic Changelog (Handles UPSERT and DELETE Actions)
CREATE TABLE IF NOT EXISTS sync_revisions (
    id TEXT PRIMARY KEY NOT NULL,
    entity_table TEXT NOT NULL,                -- e.g. 'announcements', 'materials', 'quizzes'
    entity_id TEXT NOT NULL,                   -- UUID of modified/deleted record
    action TEXT NOT NULL DEFAULT 'UPSERT' CHECK(action IN ('UPSERT', 'DELETE')),
    updated_at INTEGER NOT NULL                -- Monotonic epoch timestamp
);

-- ==============================================================================
-- COMPOSITE & COVERING INDEXES FOR HIGH-CONCURRENCY CLASSROOM QUERIES
-- ==============================================================================
CREATE INDEX IF NOT EXISTS idx_classrooms_code ON classrooms(class_code);
CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments(student_id);
CREATE INDEX IF NOT EXISTS idx_announcements_feed ON announcements(classroom_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_announcement_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_assignments_class_due ON assignments(classroom_id, due_date ASC);
CREATE INDEX IF NOT EXISTS idx_submissions_assign ON assignment_submissions(assignment_id, student_id);
CREATE INDEX IF NOT EXISTS idx_quizzes_class ON quizzes(classroom_id, status);
CREATE INDEX IF NOT EXISTS idx_quiz_questions_order ON quiz_questions(quiz_id, order_index ASC);
CREATE INDEX IF NOT EXISTS idx_quiz_attempts_quiz ON quiz_attempts(quiz_id, student_id);
CREATE INDEX IF NOT EXISTS idx_ai_chat_student ON ai_chat_messages(student_id, classroom_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_sync_revisions_composite ON sync_revisions(entity_table, updated_at ASC);
