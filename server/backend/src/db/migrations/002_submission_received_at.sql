-- Contract #257: assignment_submissions gains received_at (when the Hub stored the file), between
-- submitted_at and score. ADD COLUMN would put it last and a NOT NULL column needs a default, and
-- the schema must read exactly like contracts/schema/server_master.sql (db::tests), so the table is
-- rebuilt with the contract's text. No other table references it. Rows stored before this change
-- used their arrival time as submitted_at, so that is their received_at.
ALTER TABLE assignment_submissions RENAME TO assignment_submissions_before_002;

CREATE TABLE IF NOT EXISTS assignment_submissions (
    id TEXT PRIMARY KEY NOT NULL,              -- client-generated so offline retries are idempotent
    assignment_id TEXT NOT NULL,
    student_id TEXT NOT NULL,
    file_path TEXT NOT NULL,                   -- Server photo storage path (never sent to clients)
    file_type TEXT NOT NULL DEFAULT 'IMAGE' CHECK(file_type IN ('IMAGE', 'DOCUMENT')),
    submitted_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    score INTEGER CHECK(score IS NULL OR score >= 0),
    teacher_feedback TEXT,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (assignment_id) REFERENCES assignments(id) ON DELETE CASCADE,
    FOREIGN KEY (student_id) REFERENCES users(id) ON DELETE RESTRICT,
    UNIQUE(assignment_id, student_id)
);

INSERT INTO assignment_submissions
    (id, assignment_id, student_id, file_path, file_type, submitted_at, received_at, score, teacher_feedback, updated_at)
SELECT id, assignment_id, student_id, file_path, file_type, submitted_at, submitted_at, score, teacher_feedback, updated_at
FROM assignment_submissions_before_002;

-- Its index moved with the old table and goes with it.
DROP TABLE assignment_submissions_before_002;

CREATE INDEX IF NOT EXISTS idx_submissions_student ON assignment_submissions(student_id);
