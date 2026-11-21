-- Contract #331 (restore public class comments): announcements.allow_comments and the
-- idx_announcement_comments_order index. allow_comments sits mid-table in the contract, so
-- announcements is rebuilt with the contract's text, as in 002 to 004 (ADD COLUMN would put it last
-- and db::tests compares sqlite_master text exactly). Schema only: no route or behaviour uses it yet.
--
-- materials references announcements, and material_chunks and ai_chat_messages hang off materials.
-- SQLite rewrites a child's REFERENCES clause when its parent is renamed, and dropping an old parent
-- would cascade into its children (foreign_keys cannot be turned off inside SQLx's transaction). So the
-- whole family is rebuilt together and the old copies are dropped child first (see 003 and 004).
-- announcement_comments already exists (001) and is a child of announcements, so it joins the rebuild; only its index is new.
-- The same migration also corrects the users comment in sqlite_master ("4-digit" to "6-digit PIN",
-- contract 9c53739): rebuilding users would mean rebuilding every table that references it, and the
-- text is only a comment, so it is patched in place.
-- Existing announcements keep comments on (the contract default of 1). Indexes move with the old
-- tables and go with them, so they are created again at the end.

ALTER TABLE announcements RENAME TO announcements_before_005;
ALTER TABLE announcement_comments RENAME TO announcement_comments_before_005;
ALTER TABLE materials RENAME TO materials_before_005;
ALTER TABLE material_chunks RENAME TO material_chunks_before_005;
ALTER TABLE ai_chat_messages RENAME TO ai_chat_messages_before_005;

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

INSERT INTO announcements (id, classroom_id, title, content, allow_comments, created_at, updated_at)
SELECT id, classroom_id, title, content, 1, created_at, updated_at
FROM announcements_before_005;

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

INSERT INTO announcement_comments (id, announcement_id, author_id, content, created_at, updated_at)
SELECT id, announcement_id, author_id, content, created_at, updated_at
FROM announcement_comments_before_005;

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
SELECT id, classroom_id, title, file_type, mime_type, file_path, file_size_bytes, uploaded_by, topic_id, assignment_id, announcement_id, created_at, updated_at
FROM materials_before_005;

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
SELECT id, material_id, order_index, heading, text, token_estimate, created_at, updated_at
FROM material_chunks_before_005;

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
FROM ai_chat_messages_before_005;

DROP TABLE ai_chat_messages_before_005;
DROP TABLE material_chunks_before_005;
DROP TABLE materials_before_005;
DROP TABLE announcement_comments_before_005;
DROP TABLE announcements_before_005;

CREATE INDEX IF NOT EXISTS idx_announcements_feed ON announcements(classroom_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_announcement_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_ai_chat_student ON ai_chat_messages(student_id, classroom_id, created_at ASC);

PRAGMA writable_schema = ON;
UPDATE sqlite_master SET sql = replace(sql, 'argon2id of the 4-digit PIN', 'argon2id of the 6-digit PIN')
WHERE type = 'table' AND name = 'users';
PRAGMA writable_schema = OFF;
