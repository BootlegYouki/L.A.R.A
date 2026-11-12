-- Contract #273 (issue #274): audit columns and materials.uploaded_by. Each table is rebuilt with the
-- contract's text, as in 002, because ADD COLUMN would put the new column last and a NOT NULL column
-- needs a default. Backfill: enrollments.created_at from joined_at, classroom_teachers from added_at,
-- material_chunks.created_at from updated_at, assignment_submissions.created_at from submitted_at,
-- quiz_attempts.created_at from started_at, and materials.uploaded_by is the class owner (the Hub did
-- not record who uploaded before this).

ALTER TABLE enrollments RENAME TO enrollments_before_003;

CREATE TABLE IF NOT EXISTS enrollments (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'ACTIVE', 'REJECTED', 'REMOVED')),
    joined_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(classroom_id, student_id)
);

INSERT INTO enrollments (id, classroom_id, student_id, status, joined_at, created_at, updated_at)
SELECT id, classroom_id, student_id, status, joined_at, joined_at, updated_at
FROM enrollments_before_003;

DROP TABLE enrollments_before_003;

CREATE INDEX IF NOT EXISTS idx_enrollments_student ON enrollments(student_id, status);

ALTER TABLE classroom_teachers RENAME TO classroom_teachers_before_003;

CREATE TABLE IF NOT EXISTS classroom_teachers (
    classroom_id TEXT NOT NULL,
    teacher_id TEXT NOT NULL,
    added_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (classroom_id, teacher_id),
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (teacher_id) REFERENCES users(id) ON DELETE RESTRICT
);

INSERT INTO classroom_teachers (classroom_id, teacher_id, added_at, created_at, updated_at)
SELECT classroom_id, teacher_id, added_at, added_at, added_at
FROM classroom_teachers_before_003;

DROP TABLE classroom_teachers_before_003;

CREATE INDEX IF NOT EXISTS idx_classroom_teachers_teacher ON classroom_teachers(teacher_id);

ALTER TABLE assignment_submissions RENAME TO assignment_submissions_before_003;

CREATE TABLE IF NOT EXISTS assignment_submissions (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated so offline retries are idempotent
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- Server photo storage path (never sent to clients)
    file_type TEXT NOT NULL DEFAULT 'IMAGE' CHECK(file_type IN ('IMAGE', 'DOCUMENT')),
    submitted_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    score INTEGER CHECK(score IS NULL OR score >= 0),
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(assignment_id, student_id)
);

INSERT INTO assignment_submissions (id, assignment_id, student_id, file_path, file_type, submitted_at, created_at, received_at, score, teacher_feedback, updated_at)
SELECT id, assignment_id, student_id, file_path, file_type, submitted_at, submitted_at, received_at, score, teacher_feedback, updated_at
FROM assignment_submissions_before_003;

DROP TABLE assignment_submissions_before_003;

CREATE INDEX IF NOT EXISTS idx_submissions_student ON assignment_submissions(student_id);

ALTER TABLE quiz_attempts RENAME TO quiz_attempts_before_003;

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
    updated_at INTEGER NOT NULL,
    CHECK(status = 'IN_PROGRESS' OR (submitted_at IS NOT NULL AND score IS NOT NULL AND total_points IS NOT NULL)),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(quiz_id, student_id)                -- one attempt per learner per quiz (no retakes in v1)
);

INSERT INTO quiz_attempts (id, quiz_id, student_id, status, started_at, created_at, submitted_at, score, total_points, answers_json, updated_at)
SELECT id, quiz_id, student_id, status, started_at, started_at, submitted_at, score, total_points, answers_json, updated_at
FROM quiz_attempts_before_003;

DROP TABLE quiz_attempts_before_003;

CREATE INDEX IF NOT EXISTS idx_quiz_attempts_student ON quiz_attempts(student_id, status);

-- materials and material_chunks have children, and SQLite rewrites a child's REFERENCES clause when
-- its parent is renamed (even with legacy_alter_table while foreign_keys is on, and SQLx migrates in a
-- transaction where foreign_keys cannot be turned off). Renaming materials alone would leave
-- material_chunks pointing at the old copy, and dropping it would cascade into every chunk. So the
-- family is rebuilt together: materials, material_chunks and ai_chat_messages (whose text does not
-- change) are recreated pointing at the new tables, and the old copies are dropped child first, when
-- nothing references them any more.

ALTER TABLE materials RENAME TO materials_before_003;
ALTER TABLE material_chunks RENAME TO material_chunks_before_003;
ALTER TABLE ai_chat_messages RENAME TO ai_chat_messages_before_003;

CREATE TABLE IF NOT EXISTS materials (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    title TEXT NOT NULL,
    file_type TEXT NOT NULL CHECK(file_type IN ('DOCUMENT', 'VIDEO', 'WORKSHEET')),
    mime_type TEXT,                            -- e.g. application/pdf, video/mp4
    file_path TEXT NOT NULL,                   -- Server local storage path (never sent to clients)
    file_size_bytes INTEGER NOT NULL CHECK(file_size_bytes >= 0),
    uploaded_by TEXT NOT NULL,                  -- Hub sets this from the bearer token's user (owner or co-teacher); material_chunks inherit it through material_id
    topic_id TEXT,
    assignment_id TEXT,                        -- set when the file is an attachment of an assignment
    announcement_id TEXT,                      -- set when the file is an attachment of an announcement
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    FOREIGN KEY (uploaded_by) REFERENCES users(id) ON DELETE RESTRICT,
    FOREIGN KEY (topic_id) REFERENCES topics(id) ON DELETE SET NULL,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (announcement_id) REFERENCES announcements(id) ON DELETE CASCADE
);

INSERT INTO materials (id, classroom_id, title, file_type, mime_type, file_path, file_size_bytes, uploaded_by, topic_id, assignment_id, announcement_id, created_at, updated_at)
SELECT m.id, m.classroom_id, m.title, m.file_type, m.mime_type, m.file_path, m.file_size_bytes, (SELECT teacher_id FROM classrooms WHERE id = m.classroom_id), m.topic_id, m.assignment_id, m.announcement_id, m.created_at, m.updated_at
FROM materials_before_003 m;

CREATE TABLE IF NOT EXISTS material_chunks (
    id TEXT PRIMARY KEY NOT NULL,
    material_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    heading TEXT,
    text TEXT NOT NULL,
    token_estimate INTEGER,                    -- lets the prompt builder stay inside the 2048-token window
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    UNIQUE(material_id, order_index)
);

INSERT INTO material_chunks (id, material_id, order_index, heading, text, token_estimate, created_at, updated_at)
SELECT id, material_id, order_index, heading, text, token_estimate, updated_at, updated_at
FROM material_chunks_before_003;

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

INSERT INTO ai_chat_messages (id, classroom_id, student_id, material_id, chunk_id, language, role, content, created_at)
SELECT id, classroom_id, student_id, material_id, chunk_id, language, role, content, created_at
FROM ai_chat_messages_before_003;

DROP TABLE ai_chat_messages_before_003;
DROP TABLE material_chunks_before_003;
DROP TABLE materials_before_003;

CREATE INDEX IF NOT EXISTS idx_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_ai_chat_student ON ai_chat_messages(student_id, classroom_id, created_at ASC);
