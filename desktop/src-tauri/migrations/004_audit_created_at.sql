-- Migration 004: Add created_at audit fields (Contract #273)
-- Pattern: Rebuild tables to add NOT NULL created_at column with backfill
-- Following mobile's approach from PR #306

-- 1. enrollments: created_at ← joined_at
CREATE TABLE enrollments_new (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'ACTIVE', 'REJECTED', 'REMOVED')),
    joined_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE,
    UNIQUE(classroom_id, student_id)
);

INSERT INTO enrollments_new (id, classroom_id, student_id, status, joined_at, created_at, updated_at)
SELECT id, classroom_id, student_id, status, joined_at, joined_at, updated_at FROM enrollments;

DROP TABLE enrollments;
ALTER TABLE enrollments_new RENAME TO enrollments;
CREATE INDEX IF NOT EXISTS idx_client_enrollments_student ON enrollments(student_id, status);

-- 2. material_chunks: created_at ← updated_at
CREATE TABLE material_chunks_new (
    id TEXT PRIMARY KEY NOT NULL,
    material_id TEXT NOT NULL,
    order_index INTEGER NOT NULL,
    heading TEXT,
    text TEXT NOT NULL,
    token_estimate INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (material_id) REFERENCES materials(id) ON DELETE CASCADE,
    UNIQUE(material_id, order_index)
);

INSERT INTO material_chunks_new (id, material_id, order_index, heading, text, token_estimate, created_at, updated_at)
SELECT id, material_id, order_index, heading, text, token_estimate, updated_at, updated_at FROM material_chunks;

DROP TABLE material_chunks;
ALTER TABLE material_chunks_new RENAME TO material_chunks;

-- 3. assignment_submissions: created_at ← submitted_at
CREATE TABLE assignment_submissions_new (
    id TEXT PRIMARY KEY NOT NULL,
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,
    file_type TEXT NOT NULL DEFAULT 'IMAGE' CHECK(file_type IN ('IMAGE', 'DOCUMENT')),
    submitted_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    received_at INTEGER,
    score INTEGER,
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC', 'REJECTED')),
    sync_reject_reason TEXT,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    UNIQUE(assignment_id, student_id)
);

INSERT INTO assignment_submissions_new (id, assignment_id, student_id, file_path, file_type, submitted_at, created_at, received_at, score, teacher_feedback, updated_at, sync_status, sync_reject_reason)
SELECT id, assignment_id, student_id, file_path, file_type, submitted_at, submitted_at, received_at, score, teacher_feedback, updated_at, sync_status, sync_reject_reason FROM assignment_submissions;

DROP TABLE assignment_submissions;
ALTER TABLE assignment_submissions_new RENAME TO assignment_submissions;
CREATE INDEX IF NOT EXISTS idx_client_submissions_sync ON assignment_submissions(sync_status, submitted_at DESC);

-- 4. quiz_attempts: created_at ← started_at
CREATE TABLE quiz_attempts_new (
    id TEXT PRIMARY KEY NOT NULL,
    quiz_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK(status IN ('IN_PROGRESS', 'SUBMITTED')),
    started_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    submitted_at INTEGER,
    score INTEGER,
    total_points INTEGER,
    answers_json TEXT NOT NULL DEFAULT '[]',
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC', 'REJECTED')),
    sync_reject_reason TEXT,
    CHECK(status = 'IN_PROGRESS' OR submitted_at IS NOT NULL),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, student_id)
);

INSERT INTO quiz_attempts_new (id, quiz_id, student_id, status, started_at, created_at, submitted_at, score, total_points, answers_json, updated_at, sync_status, sync_reject_reason)
SELECT id, quiz_id, student_id, status, started_at, started_at, submitted_at, score, total_points, answers_json, updated_at, sync_status, sync_reject_reason FROM quiz_attempts;

DROP TABLE quiz_attempts;
ALTER TABLE quiz_attempts_new RENAME TO quiz_attempts;
CREATE INDEX IF NOT EXISTS idx_client_attempts_sync ON quiz_attempts(sync_status, submitted_at DESC);
