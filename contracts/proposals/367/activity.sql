-- PROPOSED ONLY: #367. Not part of server_master.sql and not an applied migration.
-- Hub-local operational diagnostics. No client sync revisions or personal data.
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
