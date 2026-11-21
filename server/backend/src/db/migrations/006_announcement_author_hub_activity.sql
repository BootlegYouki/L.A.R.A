-- Contract #331 follow-up (#388 announcements.author_id, #370 Hub activity history): the migrated schema
-- must reproduce contracts/schema/server_master.sql again. Schema only: no recorder, route or behaviour.
--
-- author_id sits after classroom_id in the contract, so announcements is rebuilt with the contract's
-- text and its whole family with it, exactly as in 005 (see there for why the old copies are renamed,
-- refilled and dropped child first). Existing announcements get author_id NULL (legacy teacher posts).
-- hub_activity_events and hub_activity_state are new Hub-only tables, copied from the contract.

ALTER TABLE announcements RENAME TO announcements_before_006;
ALTER TABLE announcement_comments RENAME TO announcement_comments_before_006;
ALTER TABLE materials RENAME TO materials_before_006;
ALTER TABLE material_chunks RENAME TO material_chunks_before_006;
ALTER TABLE ai_chat_messages RENAME TO ai_chat_messages_before_006;

CREATE TABLE IF NOT EXISTS announcements (
    id TEXT PRIMARY KEY NOT NULL,
    classroom_id TEXT NOT NULL,
    author_id TEXT,                            -- authenticated author UUID; NULL for legacy teacher posts
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    allow_comments INTEGER NOT NULL DEFAULT 1 CHECK(allow_comments IN (0, 1)),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (classroom_id) REFERENCES classrooms(id) ON DELETE CASCADE
);

INSERT INTO announcements (id, classroom_id, title, content, allow_comments, created_at, updated_at)
SELECT id, classroom_id, title, content, allow_comments, created_at, updated_at
FROM announcements_before_006;

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
FROM announcement_comments_before_006;

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
FROM materials_before_006;

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
FROM material_chunks_before_006;

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
FROM ai_chat_messages_before_006;

DROP TABLE ai_chat_messages_before_006;
DROP TABLE material_chunks_before_006;
DROP TABLE materials_before_006;
DROP TABLE announcement_comments_before_006;
DROP TABLE announcements_before_006;

CREATE INDEX IF NOT EXISTS idx_announcements_feed ON announcements(classroom_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_announcement_comments_order ON announcement_comments(announcement_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_materials_class_type ON materials(classroom_id, file_type);
CREATE INDEX IF NOT EXISTS idx_ai_chat_student ON ai_chat_messages(student_id, classroom_id, created_at ASC);

CREATE TABLE hub_activity_events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT CHECK (seq BETWEEN 1 AND 9007199254740991),
    occurred_at INTEGER NOT NULL CHECK (occurred_at BETWEEN 0 AND 9007199254740991),
    event_code TEXT NOT NULL CHECK (event_code IN (
        'HUB_STARTED', 'HUB_STOPPING', 'UNCLEAN_RESTART',
        'SERVICE_UNAVAILABLE', 'SERVICE_RECOVERED', 'CONNECTION_PROBLEM',
        'DOCUMENT_UPLOADED', 'DOCUMENT_UPLOAD_FAILED',
        'DOCUMENT_PROCESSED', 'DOCUMENT_PROCESSING_FAILED',
        'QUIZ_STARTED', 'QUIZ_START_FAILED', 'QUIZ_CLOSED', 'QUIZ_CLOSE_FAILED',
        'ACCOUNT_CREATED', 'ACCOUNT_CREATE_FAILED',
        'PIN_RESET', 'PIN_RESET_FAILED', 'LOG_GAP'
    )),
    severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    result TEXT NOT NULL CHECK (result IN ('SUCCEEDED', 'FAILED', 'DEGRADED')),
    service TEXT NOT NULL CHECK (service IN (
        'HUB', 'DATABASE', 'HTTP', 'WEBSOCKET', 'DISCOVERY',
        'STORAGE', 'DOCUMENTS', 'QUIZZES', 'ACCOUNTS', 'ACTIVITY_LOG'
    )),
    reason TEXT CHECK (reason IN (
        'PORT_IN_USE', 'NETWORK_UNAVAILABLE', 'TIMEOUT', 'STORAGE_UNAVAILABLE',
        'DATABASE_UNAVAILABLE', 'UNREADABLE_DOCUMENT', 'NO_TEXT_LAYER',
        'SIZE_LIMIT', 'INVALID_INPUT', 'NOT_ALLOWED', 'CONFLICT',
        'QUEUE_FULL', 'WRITE_FAILED', 'REPEATED_EVENT', 'UNCLEAN_EXIT', 'INTERNAL_FAILURE'
    )),
    item_count INTEGER CHECK (item_count BETWEEN 0 AND 1000000),
    duration_ms INTEGER CHECK (duration_ms BETWEEN 0 AND 86400000)
);
CREATE INDEX idx_hub_activity_events_occurred_at ON hub_activity_events(occurred_at);

-- One row; history_id is a fresh UUID on initialization/restore. Never synced.
CREATE TABLE hub_activity_state (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    history_id TEXT NOT NULL CHECK (length(history_id) = 36),
    pruned_through_seq INTEGER NOT NULL DEFAULT 0 CHECK (pruned_through_seq BETWEEN 0 AND 9007199254740991),
    dropped_total INTEGER NOT NULL DEFAULT 0 CHECK (dropped_total BETWEEN 0 AND 9007199254740991),
    clean_shutdown INTEGER NOT NULL DEFAULT 1 CHECK (clean_shutdown IN (0, 1))
);
