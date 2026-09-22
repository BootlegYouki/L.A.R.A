-- ==============================================================================
-- L.A.R.A CLIENT OFFLINE SQLITE SCHEMA (Android Room & Desktop Client)
-- ==============================================================================
-- Technology Invariant: Android Room (Kotlin) & @tauri-apps/plugin-sql (Desktop).
-- Security Invariant: Strips correct_answer to prevent student cheating.
-- Offline Invariant: Adds sync_status and local_file_path for home study mode.
-- Concurrency Best Practice: WAL mode + NORMAL synchronous + 5s busy timeout.
-- ==============================================================================

-- High-Performance Client Pragmas
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;

-- 1. Users (Local Profile)
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY NOT NULL,              -- UUID v4
    lrn_or_id TEXT UNIQUE NOT NULL,            -- 12-digit DepEd LRN or Teacher ID
    full_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('TEACHER', 'STUDENT')),
    pin_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 2. Classrooms (Enrolled Subjects)
CREATE TABLE IF NOT EXISTS classrooms (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    section TEXT NOT NULL,
    class_code TEXT NOT NULL,
    teacher_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 3. Enrollments Status
CREATE TABLE IF NOT EXISTS enrollments (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'ACTIVE', 'REJECTED')),
    joined_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    UNIQUE(classroom_id, student_id)
);

-- 4. Cached Announcements
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

-- 5. Announcement Comments (With Offline Sync Queue for Pupil Comments)
CREATE TABLE IF NOT EXISTS announcement_comments (
    id TEXT PRIMARY KEY NOT NULL,
    announcement_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'SYNCED' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC')),
    FOREIGN KEY (announcement_id) REFERENCES announcements(id) ON DELETE CASCADE
);

-- 6. Lesson Materials & Offline Disk Cache
CREATE TABLE IF NOT EXISTS materials (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    file_type TEXT NOT NULL CHECK(file_type IN ('DOCUMENT', 'VIDEO', 'WORKSHEET')),
    file_size_bytes INTEGER NOT NULL,
    extracted_text TEXT,                       -- Pre-chunked plain text for SLM grounding
    download_url TEXT,                         -- Hub server relative URL
    local_file_path TEXT,                      -- CLIENT SPECIFIC: Path on phone/laptop storage (null if not downloaded)
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
    due_date INTEGER NOT NULL,
    max_points INTEGER NOT NULL DEFAULT 100,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 8. Assignment Submissions (With Offline Sync Queue)
CREATE TABLE IF NOT EXISTS assignment_submissions (
    id TEXT PRIMARY KEY NOT NULL,
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- Local image path on device storage
    file_type TEXT NOT NULL DEFAULT 'IMAGE',
    submitted_at INTEGER NOT NULL,
    score INTEGER,                             -- Graded score receipt from server
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC')),
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    UNIQUE(assignment_id, student_id)
);

-- 9. Quizzes
CREATE TABLE IF NOT EXISTS quizzes (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT,
    deped_category TEXT NOT NULL DEFAULT 'WRITTEN_WORK' CHECK(deped_category IN ('WRITTEN_WORK', 'PERFORMANCE_TASK', 'QUARTERLY_ASSESSMENT')),
    time_limit_minutes INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    started_at INTEGER,                        -- Server synchronized epoch ms
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 10. Quiz Questions (STRICT ANTI-CHEAT: correct_answer is completely omitted)
CREATE TABLE IF NOT EXISTS quiz_questions (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE', 'IDENTIFICATION')),
    options_json TEXT,                         -- JSON array of strings e.g. ["A", "B", "C", "D"]
    points INTEGER NOT NULL DEFAULT 1,
    image_path TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE
);

-- 11. Student Quiz Attempts (With Offline Sync Queue)
CREATE TABLE IF NOT EXISTS quiz_attempts (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    submitted_at INTEGER NOT NULL,
    score INTEGER NOT NULL DEFAULT 0,          -- Graded score receipt from server
    total_points INTEGER NOT NULL DEFAULT 0,
    answers_json TEXT NOT NULL,                -- JSON object of student answers
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC')),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, student_id)
);

-- 12. Local Socratic AI Chat History (Persistent Home & School Study)
CREATE TABLE IF NOT EXISTS ai_chat_messages (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    material_id TEXT,
    role TEXT NOT NULL CHECK(role IN ('USER', 'TUTOR')),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- ==============================================================================
-- COMPOSITE & COVERING INDEXES FOR FAST OFFLINE QUERIES
-- ==============================================================================
CREATE INDEX IF NOT EXISTS idx_client_enrollments ON enrollments(classroom_id, student_id);
CREATE INDEX IF NOT EXISTS idx_client_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_client_announcements_feed ON announcements(classroom_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_client_assignments_due ON assignments(classroom_id, due_date ASC);
CREATE INDEX IF NOT EXISTS idx_client_questions_order ON quiz_questions(quiz_id, order_index ASC);
CREATE INDEX IF NOT EXISTS idx_client_submissions_sync ON assignment_submissions(sync_status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_attempts_sync ON quiz_attempts(sync_status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_ai_chat ON ai_chat_messages(classroom_id, created_at ASC);
