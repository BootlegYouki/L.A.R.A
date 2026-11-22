# HTTP endpoints in Activity logs

The recorder covers requests to the Hub's HTTP listener, including successful,
rejected, unsupported-method and failed requests. The table lists the exact
sanitized endpoint templates from `backend/src/services/request_activity.rs`.
“Included” covers all methods unless the row states otherwise; it does not mean
every method is a supported API operation. Actual resource IDs are never logged.

| Endpoint | Included in Activity logs | Excluded |
|---|---|---|
| `/` | All methods | None |
| `/api/admin/activity-logs` | None | All methods |
| `/api/admin/setup` | All methods | None |
| `/api/admin/users` | All methods except GET/OPTIONS; includes POST account creation | GET and OPTIONS |
| `/api/admin/users/{id}/reset-pin` | All methods; includes POST PIN resets | None |
| `/api/announcements` | All methods | None |
| `/api/announcements/{id}` | All methods | None |
| `/api/announcements/{id}/comments` | All methods | None |
| `/api/assignments` | All methods | None |
| `/api/assignments/{id}` | All methods | None |
| `/api/assignments/{id}/private-comments` | All methods | None |
| `/api/assignments/{id}/submissions` | All methods | None |
| `/api/assignments/{id}/submit` | All methods | None |
| `/api/auth/login` | All methods | None |
| `/api/auth/logout` | All methods | None |
| `/api/auth/register` | All methods | None |
| `/api/backup` | All methods | None |
| `/api/classrooms` | All methods | None |
| `/api/classrooms/join` | All methods | None |
| `/api/classrooms/{id}` | All methods | None |
| `/api/classrooms/{id}/approve` | All methods | None |
| `/api/classrooms/{id}/reject` | All methods | None |
| `/api/classrooms/{id}/remove` | All methods | None |
| `/api/classrooms/{id}/roster` | All methods | None |
| `/api/classrooms/{id}/teachers` | All methods | None |
| `/api/classrooms/{id}/teachers/{teacher_id}` | All methods | None |
| `/api/export/class-record` | All methods | None |
| `/api/materials` | All methods; includes document/video uploads | None |
| `/api/materials/{id}` | All methods | None |
| `/api/materials/{id}/chunks` | All methods; reads extracted lesson text | None |
| `/api/materials/{id}/download` | All methods | None |
| `/api/materials/{id}/stream` | All methods | None |
| `/api/quizzes` | All methods | None |
| `/api/quizzes/active` | All methods | None |
| `/api/quizzes/{id}` | All methods | None |
| `/api/quizzes/{id}/begin` | All methods | None |
| `/api/quizzes/{id}/close` | All methods | None |
| `/api/quizzes/{id}/results` | All methods | None |
| `/api/quizzes/{id}/start` | All methods | None |
| `/api/quizzes/{id}/submit` | All methods | None |
| `/api/restore` | All methods | None |
| `/api/submissions/{id}` | All methods | None |
| `/api/submissions/{id}/file` | All methods | None |
| `/api/sync/pull` | All methods | None |
| `/api/sync/push` | All methods | None |
| `/api/topics` | All methods | None |
| `/api/topics/{id}` | All methods | None |
| `/download` | All methods | None |
| `/download/assets/{*path}` | All methods | None |
| `/download/{file}` | All methods | None |
| Unknown paths | All methods, shown as `UNMATCHED` | Raw path omitted for privacy |

The two exclusions also hide matching older HTTP request entries from the viewer
without deleting stored history. Account polling continues every five seconds.
Operational events such as account creation, PIN reset and document text
processing remain recorded separately. Background document processing has no
separate HTTP endpoint; it emits document processing success/failure events.

HTTP entries contain only method, sanitized endpoint, status and duration to
response headers. Bodies, PINs, tokens, LRNs and file contents are not recorded.
Individual WebSocket frames on port 8081 and native monitoring IPC calls are not
HTTP request entries. Recording uses the existing bounded queue and retention;
history can report gaps if the recorder is overloaded or unavailable.
