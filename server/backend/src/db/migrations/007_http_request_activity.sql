-- Preserve safe history and the highest sequence, including already-pruned rows.
CREATE TEMP TABLE activity_sequence_backup AS SELECT seq FROM sqlite_sequence WHERE name='hub_activity_events';
DROP INDEX idx_hub_activity_events_occurred_at;
ALTER TABLE hub_activity_events RENAME TO hub_activity_events_previous;
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
        'PIN_RESET', 'PIN_RESET_FAILED', 'LOG_GAP', 'HTTP_REQUEST'
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
    duration_ms INTEGER CHECK (duration_ms BETWEEN 0 AND 86400000),
    http_method TEXT CHECK (http_method IN ('GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS', 'CONNECT', 'TRACE', 'OTHER')),
    endpoint TEXT CHECK (endpoint IN (
        '/',
        '/api/admin/activity-logs',
        '/api/admin/setup',
        '/api/admin/users',
        '/api/admin/users/{id}/reset-pin',
        '/api/announcements',
        '/api/announcements/{id}',
        '/api/announcements/{id}/comments',
        '/api/assignments',
        '/api/assignments/{id}',
        '/api/assignments/{id}/private-comments',
        '/api/assignments/{id}/submissions',
        '/api/assignments/{id}/submit',
        '/api/auth/login',
        '/api/auth/logout',
        '/api/auth/register',
        '/api/backup',
        '/api/classrooms',
        '/api/classrooms/join',
        '/api/classrooms/{id}',
        '/api/classrooms/{id}/approve',
        '/api/classrooms/{id}/reject',
        '/api/classrooms/{id}/remove',
        '/api/classrooms/{id}/roster',
        '/api/classrooms/{id}/teachers',
        '/api/classrooms/{id}/teachers/{teacher_id}',
        '/api/export/class-record',
        '/api/materials',
        '/api/materials/{id}',
        '/api/materials/{id}/chunks',
        '/api/materials/{id}/download',
        '/api/materials/{id}/stream',
        '/api/quizzes',
        '/api/quizzes/active',
        '/api/quizzes/{id}',
        '/api/quizzes/{id}/begin',
        '/api/quizzes/{id}/close',
        '/api/quizzes/{id}/results',
        '/api/quizzes/{id}/start',
        '/api/quizzes/{id}/submit',
        '/api/restore',
        '/api/submissions/{id}',
        '/api/submissions/{id}/file',
        '/api/sync/pull',
        '/api/sync/push',
        '/api/topics',
        '/api/topics/{id}',
        '/download',
        '/download/assets/{*path}',
        '/download/{file}',
        'UNMATCHED'
    )),
    status_code INTEGER CHECK (status_code BETWEEN 100 AND 599),
    CHECK ((event_code = 'HTTP_REQUEST' AND service = 'HTTP' AND http_method IS NOT NULL AND endpoint IS NOT NULL AND status_code IS NOT NULL AND duration_ms IS NOT NULL)
        OR (event_code <> 'HTTP_REQUEST' AND http_method IS NULL AND endpoint IS NULL AND status_code IS NULL))
);
CREATE INDEX idx_hub_activity_events_occurred_at ON hub_activity_events(occurred_at);
INSERT INTO hub_activity_events (seq,occurred_at,event_code,severity,result,service,reason,item_count,duration_ms)
SELECT seq,occurred_at,event_code,severity,result,service,reason,item_count,duration_ms FROM hub_activity_events_previous;
UPDATE sqlite_sequence SET seq=MAX(seq,COALESCE((SELECT seq FROM activity_sequence_backup),0)) WHERE name='hub_activity_events';
INSERT INTO sqlite_sequence(name,seq) SELECT 'hub_activity_events',seq FROM activity_sequence_backup WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name='hub_activity_events');
DROP TABLE hub_activity_events_previous;
DROP TABLE activity_sequence_backup;
