-- ==============================================================================
-- L.A.R.A CLIENT OFFLINE SQLITE SCHEMA (Android Room & Desktop @tauri-apps/plugin-sql)
-- ==============================================================================
-- This is the offline SLICE of the server schema for the classes the user belongs to.
-- Security: contains NO pin_hash, NO classmates' LRN, NO correct_answer, NO server file paths.
-- A pupil can read their own phone's SQLite file, so anything stored here is public to that pupil.
-- Offline invariant: sync_status and local_file_path support home study mode.
--
-- Room cannot express CHECK constraints, partial indexes or AUTOINCREMENT from entities. Mirror the
-- columns, nullability and defaults in the entities and keep the CHECK rules in the repository layer.
-- PRAGMAs are set in code (Room: WAL is the default; plugin-sql: foreign_keys = ON on open), not in migrations.
-- ==============================================================================

-- 1. Users (people you can see: yourself, your teacher, classmates' display names)
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY NOT NULL,
    lrn_or_id TEXT,                            -- only filled for the signed-in user and, for teachers, their roster
    full_name TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('TEACHER', 'STUDENT')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 2. Classrooms
CREATE TABLE IF NOT EXISTS classrooms (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    section TEXT NOT NULL,
    class_code TEXT NOT NULL,
    teacher_id TEXT NOT NULL,
    archived_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 3. Enrollments
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

-- 4. Announcements
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

-- 5. Announcement Comments (authors resolve through users; no FK because the author row may sync later)
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

-- 6. Materials (metadata plus the cached file location)
CREATE TABLE IF NOT EXISTS materials (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    file_type TEXT NOT NULL CHECK(file_type IN ('DOCUMENT', 'VIDEO', 'WORKSHEET')),
    mime_type TEXT,
    file_size_bytes INTEGER NOT NULL,
    download_url TEXT,                         -- Hub relative URL, e.g. /api/materials/{id}/download
    local_file_path TEXT,                      -- CLIENT ONLY: cached file on phone/laptop (NULL if not downloaded)
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 7. Lesson text chunks for offline reading and the local Socratic tutor
CREATE TABLE IF NOT EXISTS material_chunks (
    id TEXT PRIMARY KEY NOT NULL,
    material_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    heading TEXT,
    text TEXT NOT NULL,
    token_estimate INTEGER,
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
    max_points INTEGER NOT NULL DEFAULT 100,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 9. Assignment Submissions (offline queue for homework photos)
CREATE TABLE IF NOT EXISTS assignment_submissions (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated UUID; reused on every retry (idempotent upload)
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- local image path on device storage
    file_type TEXT NOT NULL DEFAULT 'IMAGE' CHECK(file_type IN ('IMAGE', 'DOCUMENT')),
    submitted_at INTEGER NOT NULL,
    score INTEGER,                             -- graded score received from the Hub
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC')),
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    UNIQUE(assignment_id, student_id)
);

-- 10. Quizzes (metadata; questions are fetched when the teacher starts the quiz)
CREATE TABLE IF NOT EXISTS quizzes (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    instructions TEXT,
    time_limit_minutes INTEGER NOT NULL,
    shuffle_questions INTEGER NOT NULL DEFAULT 0 CHECK(shuffle_questions IN (0, 1)),
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    started_at INTEGER,                        -- server synchronized epoch ms
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 11. Quiz Questions (NO correct_answer, NO synonyms: the answer key never reaches a pupil device)
CREATE TABLE IF NOT EXISTS quiz_questions (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    question_text TEXT NOT NULL,
    question_type TEXT NOT NULL CHECK(question_type IN ('MULTIPLE_CHOICE', 'TRUE_FALSE', 'IDENTIFICATION')),
    options_json TEXT,
    points INTEGER NOT NULL DEFAULT 1,
    image_path TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, order_index)
);

-- 12. Student Quiz Attempts (offline sync queue)
-- Created at quiz start (IN_PROGRESS). On finish it becomes SUBMITTED + QUEUED_FOR_SYNC until the Hub confirms.
CREATE TABLE IF NOT EXISTS quiz_attempts (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN ('IN_PROGRESS', 'SUBMITTED')),
    started_at INTEGER NOT NULL,
    submitted_at INTEGER,
    score INTEGER,                             -- Hub receipt (NULL until graded or while scores are held)
    total_points INTEGER,
    answers_json TEXT NOT NULL DEFAULT '[]',
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC')),
    CHECK(status = 'IN_PROGRESS' OR submitted_at IS NOT NULL),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, student_id)
);

-- 13. Local Socratic AI Chat History (home study and school)
CREATE TABLE IF NOT EXISTS ai_chat_messages (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    material_id TEXT,
    chunk_id TEXT,
    language TEXT CHECK(language IS NULL OR language IN ('EN', 'FIL')),
    role TEXT NOT NULL CHECK(role IN ('USER', 'TUTOR')),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

-- 14. Sync state (CLIENT ONLY key/value). Keys: 'hub_id', 'sync_epoch', 'cursor' (last next_cursor), 'current_user_id'.
-- Write 'cursor' in the same transaction that applies the pulled records. If the Hub reports reset=true,
-- wipe the mirrored tables (never rows still QUEUED_FOR_SYNC) and pull again from cursor 0.
CREATE TABLE IF NOT EXISTS sync_state (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- ==============================================================================
-- INDEXES
-- ==============================================================================
CREATE INDEX IF NOT EXISTS idx_client_enrollments_student ON enrollments(student_id, status);
CREATE INDEX IF NOT EXISTS idx_client_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_client_announcements_feed ON announcements(classroom_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_client_assignments_due ON assignments(classroom_id, due_date ASC);
CREATE INDEX IF NOT EXISTS idx_client_submissions_sync ON assignment_submissions(sync_status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_attempts_sync ON quiz_attempts(sync_status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_ai_chat ON ai_chat_messages(classroom_id, created_at ASC);
