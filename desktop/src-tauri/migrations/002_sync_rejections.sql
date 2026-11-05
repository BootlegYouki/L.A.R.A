-- Adopt the Lead-approved REJECTED receipt fields without modifying migration 1.
-- Each rebuilt table is a leaf; its parent rows and queued payloads remain intact.

CREATE TABLE announcement_comments_upgrade (
    id TEXT PRIMARY KEY NOT NULL,
    announcement_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'SYNCED' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC', 'REJECTED')),
    sync_reject_reason TEXT, -- Hub receipt reason when sync_status = 'REJECTED' (e.g. TIME_LIMIT_EXCEEDED); NULL otherwise
    FOREIGN KEY (announcement_id) REFERENCES announcements(id) ON DELETE CASCADE
);
INSERT INTO announcement_comments_upgrade (id, announcement_id, author_id, content, created_at, updated_at, sync_status) SELECT id, announcement_id, author_id, content, created_at, updated_at, sync_status FROM announcement_comments;
DROP TABLE announcement_comments;
ALTER TABLE announcement_comments_upgrade RENAME TO announcement_comments;

CREATE TABLE assignment_submissions_upgrade (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated UUID; reused on every retry (idempotent upload)
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- local image path on device storage
    file_type TEXT NOT NULL DEFAULT 'IMAGE' CHECK(file_type IN ('IMAGE', 'DOCUMENT')),
    submitted_at INTEGER NOT NULL,
    score INTEGER,                             -- graded score received from the Hub
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC', 'REJECTED')),
    sync_reject_reason TEXT, -- Hub receipt reason when sync_status = 'REJECTED' (e.g. TIME_LIMIT_EXCEEDED); NULL otherwise
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    UNIQUE(assignment_id, student_id)
);
INSERT INTO assignment_submissions_upgrade (id, assignment_id, student_id, file_path, file_type, submitted_at, score, teacher_feedback, updated_at, sync_status) SELECT id, assignment_id, student_id, file_path, file_type, submitted_at, score, teacher_feedback, updated_at, sync_status FROM assignment_submissions;
DROP TABLE assignment_submissions;
ALTER TABLE assignment_submissions_upgrade RENAME TO assignment_submissions;

CREATE TABLE quiz_attempts_upgrade (
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
    sync_status TEXT NOT NULL DEFAULT 'QUEUED_FOR_SYNC' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC', 'REJECTED')),
    sync_reject_reason TEXT, -- Hub receipt reason when sync_status = 'REJECTED' (e.g. TIME_LIMIT_EXCEEDED); NULL otherwise
    CHECK(status = 'IN_PROGRESS' OR submitted_at IS NOT NULL),
    FOREIGN KEY (quiz_id) REFERENCES quizzes(id) ON DELETE CASCADE,
    UNIQUE(quiz_id, student_id)
);
INSERT INTO quiz_attempts_upgrade (id, quiz_id, student_id, status, started_at, submitted_at, score, total_points, answers_json, updated_at, sync_status) SELECT id, quiz_id, student_id, status, started_at, submitted_at, score, total_points, answers_json, updated_at, sync_status FROM quiz_attempts;
DROP TABLE quiz_attempts;
ALTER TABLE quiz_attempts_upgrade RENAME TO quiz_attempts;

CREATE TABLE private_comments_upgrade (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated UUID when written offline
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    sync_status TEXT NOT NULL DEFAULT 'SYNCED' CHECK(sync_status IN ('SYNCED', 'QUEUED_FOR_SYNC', 'REJECTED')),
    sync_reject_reason TEXT, -- Hub receipt reason when sync_status = 'REJECTED' (e.g. TIME_LIMIT_EXCEEDED); NULL otherwise
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE
);
INSERT INTO private_comments_upgrade (id, assignment_id, student_id, author_id, content, created_at, updated_at, sync_status) SELECT id, assignment_id, student_id, author_id, content, created_at, updated_at, sync_status FROM private_comments;
DROP TABLE private_comments;
ALTER TABLE private_comments_upgrade RENAME TO private_comments;

CREATE INDEX IF NOT EXISTS idx_client_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_client_submissions_sync ON assignment_submissions(sync_status, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_attempts_sync ON quiz_attempts(sync_status, submitted_at DESC);
