# Server Technical Spec

> Owner: Jv Bialen, John Rey Calipes (Server team)  |  Reviewer: Lead Developer  |  Status: Approved  |  Last updated: 2026-10-26
> This is not a PRD. Product behavior lives in `docs/PRD.md` and `contracts/`. This document says how this team will build its part. Never redefine behavior here; link to the source instead.
> AI agents read this file as context in every session. Read [`TECH_SPEC_GUIDE.md`](../../docs/templates/TECH_SPEC_GUIDE.md) before filling it in. Keep the headings unchanged, use exact names and paths in backticks, and write "must" or "must not".

## 1. Scope and Non-Goals
**In scope:** the Hub (`server/`): the Rust backend implementing `contracts/openapi.yaml`, `contracts/events/*` and `contracts/schema/server_master.sql`, and the Tauri window (admin console, health dashboard).

| Sprint | PRD | Issues |
|---|---|---|
| 1 | §3.1 discovery and portal, §6.1 schema, §10 Day 0 install, §5.1 AI evaluation | #39, #1, #2, #3, #20, #73 |
| 2 | FR-1.0 to FR-1.4, §6.2 sync, §3.1 (3) WebSocket | #42, #44, #6, #7, #43, #121 |
| 3 | FR-2.1 to FR-2.4, FR-3.1 to FR-3.8 | #53, #10, #8, #54, #122 |
| 4 | FR-4.1 to FR-4.8, FR-5.2, §10 disaster recovery | #61, #12, #11, #13, #21 |
| 5 | §5.0, §5.4, FR-4.3 (Hub side), §1.1 admin console | #14, #16, #66, #101 |
| 6 | §8, §10 load | #17 |

**Non-goals (must not be built):** client screens; a Node backend, Prisma or any ORM; cloud sync, telemetry, crash reporting or update checks; a second admin account; per-grade AI prompts; grade categories, weights, rubrics or quarters; AI grading of photos; learners posting to the stream; deleting users, classrooms or graded rows; exporting learner data anywhere but a USB drive on the Hub PC; on-device inference (the Hub only serves the model file); seed accounts outside tests.

## 2. Architecture
One process on the Hub PC. The Tauri window starts the backend library on its own Tokio runtime; the `lara-hub` binary runs the same library headless (development, CI, tests).

```text
 server/src-tauri (window, USB, firewall fix)          server/ui (React pages)
        | lara_hub::serve(HubConfig)                          | HTTP to 127.0.0.1:8080, ADMIN session
        v                                                     v
 lara_hub  routes/* (Axum :8080) ---+          websocket/* (tokio-tungstenite :8081): Broker, QuizHub, Presence
           | AuthUser extractor     |                ^
           v                        v                |
           services/*  ->  db/* (SqlitePool, record_revision, pull_page), storage/*, ai/* (AiQueue -> llama-server)
           discovery/* (mDNS, UDP :8888)   network/* (PortHealth)
```

**Entry points:** `server/backend/src/lib.rs` `serve(config) -> HubHandle`; `server/backend/src/main.rs`; `server/src-tauri/src/main.rs`.

**Import rules (must):**
* Only `routes/*` and `websocket/*` touch HTTP or WebSocket types; they call `services/*`, `db/*`, `storage/*`, `ai/*`, never the reverse.
* `services/*` never imports `axum` or `tokio_tungstenite`.
* SQL lives only in `db/*` and `services/*`, always with bound parameters.
* Routes return `models/*` types, never DB row structs, so a new column cannot leak.
* Events go out through `Broker` only, after the transaction commits.
* `src-tauri` and `ui` never open the database; the window uses REST with an ADMIN session, so role guards apply. Tauri commands are for OS work only (USB drives, firewall fix, hub name).

## 3. Key Decisions
| Decision | Choice | Why | Rejected alternative |
|---|---|---|---|
| Process model | Backend is a library (`lara_hub`) hosted by Tauri; `lara-hub` binary for headless runs | One installer and process; dashboard reads `Broker`/`AiQueue` directly; tests start the real server | Tauri sidecar process (IPC, harder recovery) |
| HTTP | Axum + `tower-http` (`ServeFile`, `TraceLayer`, `DefaultBodyLimit`) | `AGENTS.md` §1.4; `ServeFile` does Range/206/416 | Hand-written Range parsing |
| WebSocket | `tokio-tungstenite`, own listener on `:8081` | Port fixed by `contracts/events/README.md` | Axum `ws` on `:8080` |
| SQL access | SQLx runtime queries (`query_as::<_, T>`) with unit tests on a temp DB | `cargo check` in CI needs no DB or `.sqlx/` cache | `query!` macros |
| Migrations | `db/migrations/001_initial_schema.sql` = `server_master.sql`; later changes are new files | Issue #2 names the file; SQLx rejects edited migrations | One file per table group (`server/README.md`) |
| PRAGMAs | On `SqliteConnectOptions`: WAL, `synchronous = NORMAL`, `foreign_keys = ON`, `busy_timeout = 5000` | `rules/database-and-sync.md` §5 | PRAGMAs in a migration |
| Writes | `BEGIN IMMEDIATE`, short, no network or file `.await` inside | One SQLite writer; deferred upgrades hit `SQLITE_BUSY` | Deferred `BEGIN` |
| Sync ledger | `record_revision(&mut tx, ...)` in the same transaction as every visible change | Rule §3.1; one place to get scope right | SQL triggers (cannot know `student_id` scope) |
| Pull paging | 500 `sync_revisions` rows per page; `has_more`; `next_cursor` = last `seq` scanned | Bounds responses for 3 GB phones; `has_more` is in the contract | One unbounded response (the mock) |
| Wire types | Structs named exactly as `openapi.yaml` schemas, in `models/*` | One name per thing; a student type without `correct_answer` cannot leak it | One struct with `skip_serializing_if` |
| Errors | `ApiError { status, code: ErrorCode, message }` → envelope | Contract envelope everywhere; exact code strings in one enum | Ad-hoc tuples |
| Auth | argon2id PINs; 32-byte random hex token, SHA-256 stored; 12 h expiry; `LoginLimiter` 5 per minute per `lrn_or_id` | `server/AGENTS.md`, `lara-hub-rust` skill; mock sessions last 12 h | bcrypt; JWT (no revocation) |
| Clock | All time from `AppState.clock` (`SystemClock`, `FakeClock` in tests) | Time-limit and expiry rules testable | `SystemTime::now()` scattered |
| Broker | Bounded `mpsc::channel(64)` per connection, `try_send`, drop when full | A slow phone never blocks others | Unbounded channels |
| Files | `<data_dir>/storage/{materials,submissions,tmp}/<uuid>`; stream to `tmp/`, then rename | Paths never from client names; partial uploads never visible | Blobs in SQLite |
| Bandwidth | `BandwidthLimiter`: 2.0 MB/s bucket per signed-in user, per client IP on the public portal | `rules/networking-and-lan.md` §3 (per client, all streams) | Per connection |
| Hub name | `<data_dir>/hub_config.json`, set from the window | Beacon `name`; no column for it | New `hub_meta` key (contract change) |
| Firewall | The three named rules in `rules/networking-and-lan.md` §4.1; `ufw`/`firewalld` on Linux | Rules file outranks `server/README.md` | README's combined rule |
| Hub window UI | React + Vite + Tailwind with `design-system/desktop/tailwind.theme.ts`, Nunito and Phosphor from `design-system/assets/`, strings in `server/ui/src/i18n/{en,fil}.json` | Same tokens as desktop; bilingual rule applies | Separate UI kit; inline strings |
| Logging | `tracing`; `TraceLayer` logs method and path, never the query | Media routes carry `?token=` | Default URI logging |

## 4. File Map
```text
server/backend/
  Cargo.toml          lib lara_hub + bin lara-hub. No GUI deps
  src/lib.rs          serve, AppState, HubHandle. No route logic
  src/main.rs         headless entry (env -> HubConfig)
  src/config.rs       HubConfig, hub_config.json
  src/error.rs        ApiError, ErrorCode
  src/clock.rs        Clock, SystemClock, FakeClock
  src/auth/           AuthUser, Role, require_role, PIN hashing, tokens, LoginLimiter
  src/models/         wire types per area (quiz.rs, classroom.rs, sync.rs, ...). No SQL
  src/routes/         one file per area: auth, admin, classrooms, sync, topics, announcements, materials,
                      video_stream, assignments, private_comments, quizzes, quiz_control, quiz_submit,
                      export, backup, model, portal. Guards and calls only
  src/websocket/      mod.rs (listener, handshake), broker, events, presence, quiz_hub, ai_stream
  src/services/       class_access, grading_engine, document_parser, gradebook_exporter, backup_manager, model_file
  src/db/             mod.rs (connect, migrate, hub_meta), sync_revisions.rs, one repository file per area
  src/db/migrations/  001_initial_schema.sql, then 00N_*.sql. Never edit an applied file
  src/storage/        file layout, streamed uploads. No DB access
  src/middleware/     rate_limit.rs (BandwidthLimiter)
  src/discovery/      mdns.rs, udp.rs. No DB access
  src/network/        health.rs, firewall_check.rs
  src/ai/             llama_server_manager, queue, prompt, guardrails. Never contains prompt text
  static/portal/      /download page and assets, bundled
  tests/              integration tests on TestHub (temp data_dir, free ports, mock seed accounts)
  bin/                llama-server binaries per OS
server/src-tauri/     window; Tauri commands admin/*, usb_detector.rs. Never opens the DB
                      windows/hooks.nsh, linux/postinst.sh: installer firewall rules (1.4)
server/ui/            React pages: Setup, Login, AdminUsers, Dashboard, Export/Backup; i18n/en.json, fil.json
server/ai_eval/       prompts.json, run_eval.py (spike #73 only, not shipped)
```

## 5. Names (one name per thing)
| Name | Kind | Responsibility |
|---|---|---|
| `lara_hub` / `lara-hub` | crate / binary | Backend library / headless runner |
| `HubConfig` | struct | Ports (overridable in tests only), `data_dir` (`LARA_DATA_DIR`), `model_path` (`MODEL_PATH`), hub name |
| `AppState` / `HubHandle` | struct | Shared state (`SqlitePool`, `Broker`, `AiQueue`, `Storage`, `Clock`, limiters) / running Hub with `shutdown()` |
| `ApiError`, `ErrorCode` | struct, enum | Error envelope with the contract's code strings |
| `AuthUser`, `Role`, `require_role` | extractor, enum, fn | Bearer (or `?token=` on media routes only) → `{ id, role }`; 403 on wrong role |
| `class_access::require_teacher`, `require_owner`, `require_member` | fn | Mock's `owned_classroom`, `owned_classroom(owner_only)`, `member_of`; "teaches" = owner or `classroom_teachers` row |
| `LoginLimiter`, `BandwidthLimiter` | struct | Login attempts per `lrn_or_id`; 2.0 MB/s per client |
| `record_revision`, `pull_page` | fn (`db::sync_revisions`) | Write one revision in the caller's transaction; read one visible page after a cursor |
| `Broker` | struct | `send_to_user`, `send_to_classroom_students`, `send_to_teacher` (owner and co-teachers) |
| `ServerEvent`, `ClientEvent` | enum | Every frame, tagged `event` = `EVENT_*` |
| `Presence`, `QuizHub` | struct | `EVENT_PRESENCE`; quiz start/close fan-out and close-time auto-submit |
| `grading_engine::grade` | fn | Pure scoring by `question_id` |
| `Storage` | struct | Paths and streamed writes under `data_dir/storage` |
| `document_parser::extract_chunks` | fn | PDF/DOCX/TXT → ordered `material_chunks` |
| `GradebookExporter`, `BackupManager` | struct | Export; `.lara-backup` snapshot and restore |
| `LlamaServerManager`, `AiQueue`, `PromptBuilder` | struct | Child process; bounded FIFO over slots; fills `contracts/ai/socratic_system_prompt.txt` |
| `guardrails::check_quiz_lockout` | fn | `QUIZ_IN_PROGRESS` while the learner has an `IN_PROGRESS` attempt |
| `PortHealth`, `TestHub` | struct | Port bind/reachability; integration-test server |
| `StartError` | enum | Why `serve` could not start (data dir, database, migrations, port bind) |
| `Sessions` (`auth`) | struct | `user_for_token` for the extractor and the WebSocket handshake; in memory and test-seeded until 2.0 moves it to the `sessions` table |
| `discovery::lan_ip`, `lan_address` | fn, Tauri command | IPv4 of the default-route adapter; the window's address display |
| Wire types | structs | Exactly the `openapi.yaml` schema names (`AuthSession`, `PublicUser`, `Classroom`, `StudentQuizResponse`, `TeacherQuiz`, `GradeReceipt`, `SyncPullResponse`, ...) |
| `<Entity>Input` | structs | Inline request bodies, following `TeacherQuizInput` (`AnnouncementInput`, `AssignmentInput`, ...) |

## 6. Contract Usage
Every route except `/download`, register and login answers 401 `UNAUTHORIZED` without a valid token; every route can answer 403 `FORBIDDEN` (wrong role or class) and 400 `BAD_REQUEST` (missing field, bad JSON); unknown paths answer 404 `ROUTE_NOT_FOUND`. **On peer gone:** a transaction either committed or rolled back, and client-generated ids make the retry idempotent.

| Contract route or event | Our module | On error codes (beyond the common ones) | On timeout or Hub unreachable (peer gone) |
|---|---|---|---|
| `GET /download` | `routes/portal.rs` | 404 missing installer | Range resume |
| `/api/auth/register`, `/login`, `/logout` | `routes/auth.rs` | 409 `ALREADY_EXISTS` (Q8); 401 wrong LRN or PIN; limit (Q7) | Client logs in again |
| `/api/admin/setup`, `/api/admin/users`, `.../reset-pin` | `routes/admin.rs` | 409 `ADMIN_EXISTS`; setup 403 if not loopback; 409 `ALREADY_EXISTS`; 404 | Same |
| `/api/classrooms` (GET, POST), `/join` | `routes/classrooms.rs` | 404 `CLASS_CODE_INVALID` | Join returns the existing enrollment |
| `/api/classrooms/{id}` PATCH, `/remove`, `/teachers`, `/teachers/{teacher_id}`, `/roster`, `/approve`, `/reject` | `routes/classrooms.rs`, `services/class_access.rs` | 403 not owner; 404 enrollment or teacher; archived class (Q9) | Events after commit; pull recovers |
| `/api/sync/pull` | `routes/sync.rs`, `db/sync_revisions.rs` | `reset: true` on `hub_id`/`sync_epoch` mismatch | Read only; client keeps its cursor |
| `/api/sync/push` | `routes/sync.rs` | Per item `REJECTED`: `TIME_LIMIT_EXCEEDED`, `FORBIDDEN`, `COMMENTS_DISABLED`, `NOT_FOUND` | One transaction per item; a committed id answers `SYNCED` again |
| `/api/topics`, `/api/topics/{id}` | `routes/topics.rs` | 404 | Delete clears `topic_id` in the same transaction |
| `/api/announcements`, `/{id}`, `/{id}/comments` | `routes/announcements.rs` | 403 `COMMENTS_DISABLED`; 404 | Push after commit |
| `POST /api/materials` | `routes/materials.rs`, `storage/*`, `document_parser` | 413 `FILE_TOO_LARGE` over 250 MB | Temp file deleted; nothing committed |
| `/api/materials/{id}` PATCH/DELETE, `/download`, `/chunks` | `routes/materials.rs` | 404 | Range resume |
| `/api/materials/{id}/stream` | `routes/video_stream.rs`, `BandwidthLimiter` | 404; 416 | Bucket released |
| `/api/model`, `/api/model/file` | `routes/model.rs` | `available: false`; 404 no model; 416 | Range resume; client checks `sha256` |
| `/api/assignments`, `/{id}`, `/{id}/submissions` | `routes/assignments.rs` | 404 (archived counts as missing) | Delete archives |
| `/api/assignments/{id}/private-comments` | `routes/private_comments.rs` | 400 teacher without `student_id`; 404 | Client id makes retry idempotent |
| `/api/assignments/{id}/submit`, `/api/submissions/{id}`, `/file` | `routes/assignments.rs`, `storage/*` | 400 missing `submission_id` or file; 404; size cap (Q10) | Old file kept until the new one commits |
| `/api/quizzes`, `/active`, `/{id}` GET/PATCH, `/begin` | `routes/quizzes.rs` | 404; 409 `QUIZ_NOT_DRAFT`; 409 `QUIZ_CLOSED` (Q8) | Begin returns the existing attempt |
| `/api/quizzes/{id}/start`, `/close`, `/results` | `routes/quiz_control.rs`, `quiz_hub.rs` | Non-draft or second active quiz (Q1) | Close auto-submits all `IN_PROGRESS` in one transaction, then emits |
| `/api/quizzes/{id}/submit` | `routes/quiz_submit.rs`, `grading_engine` | 422 `TIME_LIMIT_EXCEEDED`; 404 | Already submitted returns the stored receipt |
| `/api/export/class-record` | `routes/export.rs`, `GradebookExporter` | 404; caller (Q6); USB missing (Q10) | Temp name, renamed on the drive |
| `/api/backup`, `/api/restore` | `routes/backup.rs`, `BackupManager` | Checksum mismatch (Q10) | DB swapped only after verification; `sync_epoch` bumped |
| `EVENT_HELLO` / `EVENT_HELLO_ACK` | `websocket/mod.rs` | Bad token: close `4401`; frame before hello: `EVENT_ERROR` `UNAUTHORIZED` | No hello in 10 s: close |
| `EVENT_JOIN_REQUEST`, `EVENT_JOIN_APPROVAL`, `EVENT_ANNOUNCEMENT_PUSH` | routes → `Broker` | — | Missed event: next pull |
| `EVENT_QUIZ_START`, `EVENT_QUIZ_CLOSED`, `EVENT_GRADE_CONFIRMED` | `quiz_hub.rs`, `quiz_submit.rs` | Grade event not sent while scores are held | Status and score in next pull |
| `EVENT_QUIZ_SUBMIT` (in) | `quiz_hub.rs` → REST submit path | `EVENT_ERROR` (Q2); frame `student_id` ignored | Client uses REST or push |
| `EVENT_PRESENCE` | `presence.rs` | — | `OFFLINE` on close or drop |
| `EVENT_AI_CHAT_REQUEST` (in), `EVENT_QUEUE_STATUS`, `EVENT_AI_TOKEN_STREAM` | `ai_stream.rs`, `ai/*` | `QUIZ_IN_PROGRESS`, `AI_QUEUE_FULL`, `AI_NOT_AVAILABLE` | Queued request removed; slot cancelled |
| `EVENT_ERROR` | `websocket/events.rs` | Only the codes in `error.json` | — |

**Mock flows we build against first** (`py scripts/mock_hub.py`, probed 2026-10-24): Sprint 1: `GET /download` (200 HTML), unknown route (404 `ROUTE_NOT_FOUND`), no token (401), beacon keys `app`, `http_port`, `ip`, `name`, `version`, `ws_port`. Sprint 2: wrong PIN (401), duplicate register (409 `ALREADY_EXISTS`), join `K7M-4QX` → `PENDING` → teacher gets `EVENT_JOIN_REQUEST` → approve → learner gets `EVENT_JOIN_APPROVAL` `ACTIVE`, pull at 0 (all seed data), pull at `next_cursor` (empty).

## 7. Data and Offline Behavior
* **Storage:** `<data_dir>/lara.db` (WAL), `<data_dir>/storage/`, `<data_dir>/hub_config.json`, model at `model_path`. `data_dir` = Tauri app data dir, or `LARA_DATA_DIR`.
* **First run:** create `hub_meta.hub_id` and `sync_epoch = 1` if missing; `hub_id` never changes. No seed accounts: the admin comes from `POST /api/admin/setup`.
* **Revisions (rule §3.1):** every visible change calls `record_revision` in the same `BEGIN IMMEDIATE` transaction. Scope: `classroom_id` for class data, `NULL` for users; `student_id` for `enrollments`, `assignment_submissions`, `quiz_attempts`, `private_comments`. Deletes and assignment archives write `DELETE`. SQLite serialises writers and `seq` is allocated inside the write, so `seq` order is commit order and a pull cannot skip a late-committing row.
* **Pull:** `pull_page` in one read transaction: `seq > cursor`, `classroom_id` in the caller's visible classes (teacher: teaches; learner: `ACTIVE`), and `student_id` null, the caller, or a class the caller teaches. Latest revision per entity, rows loaded as `models` types, `users` as `PublicUser` (own LRN; teachers also their roster's). `reset` on `hub_id`/`sync_epoch` mismatch, then cursor 0. Learners never get `DRAFT` quizzes or held scores. Visibility changes: Q3.
* **`sync_status` lifecycle:** the Hub never stores it; it decides it. Client `QUEUED_FOR_SYNC` → push or multipart submit → receipt `SYNCED` (stored, or already stored by the same author) or `REJECTED` + reason. An id that exists with another author is `REJECTED` `FORBIDDEN`.
* **Epoch:** restore and tombstone pruning bump `sync_epoch` in the same transaction. Pruning is not scheduled (Q11).
* **When a Hub dependency fails:** model missing or `llama-server` crashed → `available: false`, `AI_NOT_AVAILABLE`, dashboard warning, restart with backoff, all else keeps serving. USB removed → export or backup error, no partial file. Port bind failure → `PortHealth` shows it; other listeners still run. DB busy past 5 s → error envelope (code Q10), never a panic. Stuck socket → dropped by `Broker`.

## 8. Code Patterns
**Handler: guard, short transaction with revision, emit after commit.**
```rust
pub async fn create_announcement(State(app): State<AppState>, user: AuthUser,
                                 Json(body): Json<AnnouncementInput>) -> Result<Json<Announcement>, ApiError> {
    require_role(&user, Role::Teacher)?;
    class_access::require_teacher(&app.db, &user, &body.classroom_id).await?;
    let now = app.clock.now_ms();
    let mut tx = app.db.begin_with("BEGIN IMMEDIATE").await?;
    let ann = db::announcements::insert(&mut tx, &body, now).await?;
    record_revision(&mut tx, Scope::class(&ann.classroom_id), "announcements", &ann.id, Action::Upsert, now).await?;
    tx.commit().await?;                                   // nothing after this may fail the write
    app.broker.send_to_classroom_students(&ann.classroom_id, &ServerEvent::announcement_push(&ann, now));
    Ok(Json(ann))
}
```
**Revision helper (the only writer of `sync_revisions`).**
```rust
pub async fn record_revision(tx: &mut SqliteConnection, scope: Scope, table: &'static str, id: &str,
                             action: Action, now_ms: i64) -> Result<i64, sqlx::Error> {
    sqlx::query_scalar("INSERT INTO sync_revisions (classroom_id, student_id, entity_table, entity_id, action, created_at)
                        VALUES (?, ?, ?, ?, ?, ?) RETURNING seq")
        .bind(scope.classroom_id).bind(scope.student_id).bind(table).bind(id)
        .bind(action.as_str()).bind(now_ms)               // created_at is informational, never the cursor
        .fetch_one(&mut *tx).await
}
```
**Student types cannot carry the key.**
```rust
#[derive(Serialize)]
pub struct StudentQuestion { pub id: String, pub order_index: i64, pub question_text: String,
                             pub question_type: QuestionType, pub options: Vec<String>, pub points: i64 }
// No correct_answer or synonyms field. TeacherQuestion is a separate struct.
// tests/redaction.rs scans every learner route's JSON for both names.
```
**Broker never blocks.**
```rust
pub fn send_to_user(&self, user_id: &str, ev: &ServerEvent) {
    let Ok(frame) = serde_json::to_string(ev) else { tracing::error!("event failed to serialize"); return };
    let frame = Arc::new(frame);
    for conn in self.registry.read().get(user_id).into_iter().flatten() {
        if conn.tx.try_send(frame.clone()).is_err() { conn.close_slow(); }   // full or gone: drop that client only
    }
}
```
**Upload: stream to tmp, count, rename, then open the transaction.**
```rust
let tmp = app.storage.tmp_path();                         // generated name, never the client's
let mut out = tokio::fs::File::create(&tmp).await?; let mut n = 0u64;
while let Some(chunk) = field.chunk().await? {
    n += chunk.len() as u64;
    if n > MAX_UPLOAD_BYTES { drop(out); tokio::fs::remove_file(&tmp).await.ok(); return Err(ApiError::file_too_large()); }
    out.write_all(&chunk).await?;
}
let path = app.storage.commit(tmp, StorageKind::Material, &material_id).await?;
```
**Integration test against the real server.**
```rust
#[tokio::test]
async fn unknown_route_is_route_not_found() {
    let hub = TestHub::start().await;                     // temp data_dir, free ports, mock seed accounts
    let (status, body) = hub.get("/api/nope", None).await;
    assert_eq!((status, body["error"]["code"].as_str()), (404, Some("ROUTE_NOT_FOUND")));
}
```

## 9. Pitfalls
* **PRAGMA in a migration** fails (WAL in a transaction). Set them on `SqliteConnectOptions`, which applies `foreign_keys` to every pooled connection.
* **`SQLITE_BUSY` despite `busy_timeout`:** a deferred transaction that reads then writes. Always `BEGIN IMMEDIATE`.
* **Write lock across I/O** (upload body, WebSocket send, `llama-server`) stalls every learner. Slow work first, then the transaction, then emit.
* **Axum's 2 MB body limit** rejects uploads silently. Raise `DefaultBodyLimit` on upload routes only; never `field.bytes()` a video.
* **Loopback check** for `/api/admin/setup` needs `into_make_service_with_connect_info::<SocketAddr>()`, then `ip.is_loopback()`.
* **`?token=` in logs:** log the path only. Accept `?token=` only on `/download`, `/api/materials/{id}/stream`, `/api/submissions/{id}/file`, `/api/model/file`.
* **Blocking the runtime:** argon2 (40 logins at once), PDF extraction and spreadsheet writing run in `spawn_blocking`.
* **Wrong beacon IP:** virtual adapters (VirtualBox, Hyper-V, VPN). Pick the interface with the default route, show it in the window, let the admin choose if several.
* **Windows Public profile** blocks 8080/8081/8888. Installer adds the rules; `PortHealth` detects and offers the fix.
* **Hub clock can be wrong:** clock time is never a cursor or an ordering key; only the time-limit check uses it, with 60 s grace.
* **Restore while serving:** `HubHandle` closes the pool before swapping `lara.db`; backup uses `VACUUM INTO` so it is consistent while running.
* **No `.unwrap()`/`.expect()` in production paths, no `panic = "abort"`, no `target-cpu=native`.**

## 10. Build Order
Four issues' `Depends on` lines disagree with their step numbers (2.1 needs 2.4; 3.1, 3.2 need 3.3; 4.1 needs 4.5 and 4.2; 4.2 needs 4.5). This order follows the dependencies (Q4).

| Step | Issue | Builds (folders/files) | Needs first |
|---|---|---|---|
| 0.1 | #36 | `server/docs/TECH_SPEC.md` | PR #35 |
| 1.0 | #39 | `Cargo.toml`, `lib.rs` (`serve`, `AppState`, `HubHandle`), `main.rs`, `config.rs`, `error.rs` (with `ROUTE_NOT_FOUND` fallback), `clock.rs`, `routes/mod.rs`, `websocket/mod.rs`, `tests/` `TestHub`, `src-tauri/src/main.rs`, `ui/package.json` with tokens and i18n | 0.1 |
| 1.1 | #1 | `discovery/mdns.rs`, `discovery/udp.rs` | 1.0 |
| 1.2 | #2 | `db/mod.rs`, `001_initial_schema.sql`, schema-parity test, **`db/sync_revisions.rs` `record_revision`** (Q5) | 1.0 |
| 1.3 | #3 | `routes/portal.rs`, `static/portal/index.html` (bilingual guide) | 1.0 |
| 1.4 | #20 | `tauri.conf.json`, `build.rs`, installer firewall hooks, `network/firewall_check.rs` | 1.0 |
| 1.5 | #73 | `ai_eval/*`, `docs/benchmarks/ai_model_evaluation.md`, `server/docs/hub_hardware.md` | 0.1 |
| 2.0 | #42 | `auth/*`, `routes/auth.rs`, `models/user.rs` | 1.0, 1.2 |
| 2.4 | #44 | `websocket/broker.rs`, `events.rs`, `presence.rs`, full handshake | 1.0, 2.0 |
| 2.1 | #6 | `routes/classrooms.rs` (create, list, join, roster, approve, reject), `models/classroom.rs`, **`services/class_access.rs`** (Q5) | 2.0, 2.4 |
| 2.2 | #7 | `routes/sync.rs`, `pull_page`, `models/sync.rs`, push handler (later steps add item kinds to it) | 2.0, 2.1 |
| 2.3 | #43 | `routes/admin.rs`, `src-tauri/src/admin/*`, `ui/src/pages/AdminUsers.tsx`, Setup, Login | 2.0 |
| 2.5 | #121 | Rename, archive, remove, co-teachers in `classrooms.rs` and `class_access` | 2.1 |
| 3.3 | #53 | `storage/*`, upload and download in `materials.rs`, submit and file in `assignments.rs` | 1.0, 2.0 |
| 3.1 | #10 | `services/document_parser.rs`, chunks route | 3.3 |
| 3.2 | #8 | `routes/video_stream.rs`, `middleware/rate_limit.rs`, then applied to all download routes and the portal | 3.3 |
| 3.4 | #54 | `routes/announcements.rs`, rest of `assignments.rs`, grading, comment push | 2.0, 2.4 |
| 3.5 | #122 | `routes/topics.rs`, `routes/private_comments.rs`, private-comment push | 3.4, 2.2 |
| 4.5 | #61 | `routes/quizzes.rs`, `models/quiz.rs`, `tests/redaction.rs` | 2.0 |
| 4.2 | #12 | `services/grading_engine.rs`, `routes/quiz_submit.rs`, attempt push | 4.5 |
| 4.1 | #11 | `websocket/quiz_hub.rs`, `routes/quiz_control.rs` | 4.5, 4.2, 2.4 |
| 4.3 | #13 | `GradebookExporter`, `routes/export.rs`, `src-tauri/src/usb_detector.rs` | 4.2, 3.4 |
| 4.4 | #21 | `BackupManager`, `routes/backup.rs` | 1.2, 3.3 |
| 5.1 | #14 | `ai/llama_server_manager.rs`, `ai/queue.rs`, `websocket/ai_stream.rs`, **`ai/guardrails.rs` `check_quiz_lockout`** so AI is never open during a quiz (Q5) | 2.4, 1.5 |
| 5.2 | #16 | `ai/prompt.rs`, the rest of `ai/guardrails.rs` | 5.1, 3.1 |
| 5.3 | #66 | `network/health.rs`, `ui/src/pages/Dashboard.tsx` | 2.4, 5.1 |
| 5.4 | #101 | `routes/model.rs`, `services/model_file.rs` | 1.5, 3.2, model-file contract change |
| 6.1 | #17 | 40-device load test and report | Sprint 5 (Q12) |

## 11. Dependencies on Other Teams
* **Clients** use `scripts/mock_hub.py` until each step merges to `staging`; nothing we build waits for them. Integration day: their apps against the real Hub; differences become bugs.
* **Lead:** answers before the steps that need them (Q3, Q4, Q5, Q7 before 1.2 to 2.2; Q13 before 2.3; Q1 and Q17 before 4.1); the model-file `contract-change` before 5.4.
* **Desktop:** none; we reuse `design-system/desktop/tailwind.theme.ts`, not their code. **Mobile:** a budget phone on integration day for the Range and 2 MB/s checks (3.2).

## 12. Risks and Unknowns
No sprint calendar exists yet, so each decision date is tied to a step.

| Risk or spike | Owner | Decision date | Fallback |
|---|---|---|---|
| Model quality and speed on the Hub PC (#73) | John Rey Calipes | End of 1.5, before 5.1 | Smaller model, fewer slots; AI stays optional (`AI_NOT_AVAILABLE`) |
| Tauri hosting the Axum runtime on Windows and Linux (#39) | Jv Bialen | During 1.0 | Tauri spawns `lara-hub` as a sidecar; same library |
| mDNS with several network adapters (#1) | John Rey Calipes | During 1.1 | UDP beacon and manual IP |
| Zero-CLI installer with firewall rules (#20) | John Rey Calipes | During 1.4 | Window's auto-fix runs the rules elevated |
| argon2 cost under 40 simultaneous logins (#42) | Jv Bialen | During 2.0, measured on the Hub PC | Lower memory cost within OWASP minimums |
| Pull page size 500 on real data (#7) | Jv Bialen | During 2.2, confirmed in 6.1 | Tune the constant |
| Pure-Rust PDF/DOCX text extraction (#10) | John Rey Calipes | During 3.1 | PDF and TXT only; DOCX gets no chunks |
| Token bucket accuracy at 2.0 MB/s with 40 streams (#8) | Jv Bialen | During 3.2 | Coarser chunks; measure on the router |
| SQLite write contention, 40 submits in a second (#11) | Jv Bialen | Measured in 4.2, confirmed in 6.1 | One transaction per close batch |
| USB detection on Windows and Linux (#13) | John Rey Calipes | During 4.3 | Admin picks a folder |
| `llama-server` binaries per OS and installer size (#14) | John Rey Calipes | Before 5.1 | Ship the binary beside the installer |

## 13. Commands and Verification
```bash
# server/backend/ (what CI runs)
cargo check --all-targets && cargo test
cargo run --bin lara-hub                  # headless Hub, data in $LARA_DATA_DIR
# server/ui/
npm run build
# repo root (this team's PCs: `py` and PYTHONUTF8=1)
python3 -m unittest discover tests && python3 scripts/verify_invariants.py
python3 scripts/mock_hub.py               # reference behavior
```
* **Unit tests:** `grading_engine`, `class_access`, `record_revision` scope, `pull_page` paging and visibility, token hashing, both limiters, every `ServerEvent` against `contracts/events/*.json`, `ui/src/i18n` key parity.
* **Integration tests (`TestHub`):** each `tests/test_mock_hub.py` flow ported one-to-one, plus redaction scans. The Python suite runs only against the mock until Q14.
* **Integration day:** clients on `staging` against the real Hub, router WAN unplugged.

| Deliverable | Check that proves it |
|---|---|
| 1.0 Scaffold | `TestHub` starts; `GET /api/nope` → 404 `ROUTE_NOT_FOUND`; bad WS token → close `4401`; window opens (screenshot) |
| 1.1 Discovery | Test receives a beacon with the six keys; `dns-sd -B _lara._tcp` (Windows) or `avahi-browse _lara._tcp` (Linux) lists the Hub |
| 1.2 Schema | Migrated temp DB's `sqlite_master` equals `server_master.sql` loaded into another; `journal_mode` = `wal` and `foreign_keys` = 1 on two pooled connections |
| 1.3 Portal | Phone on the router opens `/download` with WAN unplugged, in English and Filipino; test finds no external URL in the HTML |
| 1.4 Installer | Fresh Windows VM installs in under 3 minutes; `netsh advfirewall firewall show rule name=all` lists the three rules; Linux `ufw status` |
| 1.5 AI spike | `docs/benchmarks/ai_model_evaluation.md`: pass rates, TTFT, tokens/s at 1, 2, 4 slots on the Hub PC, labelled developer-scored |
| 2.0 Auth | Register, duplicate 409, login, wrong PIN 401, logout kills the token, 6th attempt in a minute limited; DB holds only token hashes |
| 2.4 Broker | A client that stops reading is dropped while another receives within 300 ms; teacher gets `EVENT_PRESENCE` `ONLINE` then `OFFLINE` |
| 2.1 Enrollment | Ported `test_join_approve_flow_pushes_websocket_events`, `test_bad_class_code` |
| 2.2 Sync | Ported cursor, first-pull, reset and no-leak tests; 1,200 revisions pull in 3 pages with no gaps or duplicates |
| 2.3 Admin console | Ported `test_admin_is_its_own_account_and_setup_runs_once`, `test_admin_routes_are_admin_only`; non-loopback setup → 403; screenshots in both languages |
| 2.5 Class management | Ported `test_removed_learner_loses_the_class_and_co_teacher_gains_it` |

## 14. Open Questions
Raised with the Lead; none is guessed in code. Until answered, the Hub matches `scripts/mock_hub.py`. A **Call** is the Server team's answer for the PR #36 review; a call that needs a `contract-change` is built only after that PR merges, and the Hub matches the mock until then.
1. **Quiz start:** which code for starting a non-`DRAFT` quiz or a second `ACTIVE` one? The mock allows both; the unique index makes the real Hub fail. **Call:** 409 `QUIZ_NOT_DRAFT` for a non-`DRAFT` quiz; 409 `QUIZ_IN_PROGRESS` for a second `ACTIVE` quiz in the class, message naming the active one. No new code; a `contract-change` documents both on `/start` and updates the mock. Needed before 4.1.
2. **`EVENT_ERROR` has no `TIME_LIMIT_EXCEEDED`** (the mock sends `BAD_REQUEST`). `EVENT_QUIZ_SUBMIT` has no `started_at`; we use the Hub's attempt row and ignore its `student_id`.
3. **Sync visibility:** a learner approved after their cursor never gets the class's older rows; a removed learner's pull omits the `REMOVED` enrollment. Proposed: snapshot newly visible classes, and send the enrollment for classes no longer visible. Also `GradeReceipt` in a teacher's pull has no `student_id` or `quiz_id`. **Call:** take the proposal. When the caller's enrollment turns `ACTIVE` after their cursor, `pull_page` adds a snapshot of that class's current visible rows; rows whose `student_id` is the caller are always visible, so the `REMOVED` enrollment arrives and the client clears the class. A `contract-change` adds optional `student_id` and `quiz_id` to `GradeReceipt`. Needed before 2.2.
4. **Step numbers vs `Depends on`:** confirm section 10's order or renumber. **Call:** keep section 10's order and the step numbers as labels (renumbering breaks issue and PR links); correct the `Depends on` lines of #6, #10, #8, #11 and #12 to match.
5. **Shared code in later issues:** `record_revision` (#7) is needed from 1.2, `class_access` (#121) from 2.1, `check_quiz_lockout` (#16) from 5.1. We create each in the earlier step. **Call:** confirmed. The earlier PR says "pulls forward X from #N"; the later issue extends it.
6. **Who may export:** the mock allows teachers only; the contract says "teacher / Hub console" and FR-5.2 puts it in the Hub window (signed in as `ADMIN`). See design system 9.16 #3. **Call:** both. `TEACHER` for classes they teach, `ADMIN` for any class. `target_path` is required and must be a USB drive detected on the Hub PC, else 400: returning the file as the response body would put learner data on a client device, against section 1's non-goals. A `contract-change` makes `target_path` required, or the Lead drops the non-goal. Needed before 4.3.
7. **Login limit:** no status or code in the contract (no 429), and does it count failed attempts only? **Call:** failed attempts only, 5 per minute per `lrn_or_id`, reset by a successful login. Over the limit: 429 `TOO_MANY_ATTEMPTS` with `Retry-After`, so clients show "wait a minute" instead of "wrong PIN". A `contract-change` adds both; needed before 2.0.
8. **Mock codes missing from the contract:** `ALREADY_EXISTS`, `QUIZ_CLOSED`, `ROUTE_NOT_FOUND`. We use them as the mock does. The 1.0 scaffold adds `501 NOT_IMPLEMENTED` for area stubs; it disappears as each step replaces its stubs, so no client relies on it.
9. **Archived class writes:** "read-only for everyone" has no error code.
10. **Undefined limits and codes:** homework size cap (Axum's 2 MB default would reject documents); USB missing or read-only; restore checksum mismatch; DB busy or internal error (mock: `MOCK_INTERNAL`).
11. **Tombstone retention window** is undefined, so pruning is not scheduled.
12. **#17 targets `tests/stress/`** (Lead-owned). Move it to `server/`?
13. **Hub window layout:** no section 9 anatomy or showcase entry covers its screens, and staging's `rules/ui-and-accessibility.md` §1 says layout is not free design.
14. **Python suite against the real Hub:** `tests/test_mock_hub.py` starts the mock itself. A `LARA_HUB_URL` switch in `tests/hub_client.py` would prove parity.
15. **`EVENT_HELLO.last_event_id`:** no event has an id. We plan to ignore it and rely on pull.
16. **`quizzes.scores_released_at`** says "teacher releases"; the rules make closing the only release. We plan to set it at close when scores were held.
17. **Time-limit start:** the mock trusts the client's `started_at`. We plan to use the Hub's `quiz_attempts.started_at` (from `begin`), else `quizzes.started_at`.

## 15. Invariants Checklist
- [x] No internet or CDN dependency (fonts and icons come from `design-system/assets/`): sections 3, 4
- [x] Offline state defined for every screen: the Hub window shows port and AI health (section 7); the Hub keeps every client retry idempotent
- [x] English and Filipino strings for all UI text: portal and `server/ui/src/i18n/{en,fil}.json`, parity test
- [x] Touch targets at least 52dp on phones (56dp primary and quiz options), 40px buttons on desktop: Hub window on desktop tokens; portal at 52dp with a 56dp download button
- [x] `correct_answer` never stored on a client: separate student types, `tests/redaction.rs`
- [x] AI unavailable during an active quiz: `check_quiz_lockout` from step 5.1
