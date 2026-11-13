# Database and Sync Ledger (step 1.2, #2)

## Purpose
The Hub's one authoritative SQLite database. It is created and migrated automatically on every start, has a stable Hub identity for client resets, and has the single helper that writes the sync ledger. Sync pull and push (step 2.2) and backup and restore (step 4.4) build on this.

## Key files and entry points
| File | What it does |
|---|---|
| `backend/src/db/migrations/001_initial_schema.sql` | Byte-for-byte copy of `contracts/schema/server_master.sql` (LF line endings), all 19 tables and their indexes. Never edited once applied; later contract changes are new `00N_*.sql` files |
| `backend/src/db/migrations/003_audit_fields_and_uploaded_by.sql` | Contract #273 (issue #274): `created_at` on `enrollments`, `material_chunks`, `assignment_submissions` and `quiz_attempts`, `created_at` and `updated_at` on `classroom_teachers`, and `materials.uploaded_by`. Rebuilds each table with the contract's text and backfills from times already stored (see Gotchas) |
| `backend/src/db/migrations/004_quiz_close_columns.sql` | Contract #294 (for #11): `quizzes.closed_at` and `quiz_attempts.auto_submitted`, which the close replace-once rule (#293) needs. Rebuilds `quizzes` with its two children, `quiz_questions` and `quiz_attempts`. A quiz already `CLOSED` gets `closed_at` from `updated_at`, and `auto_submitted` is 0 everywhere |
| `backend/src/db/mod.rs` | `connect` (PRAGMAs on the connect options), `migrate`, `begin_immediate` (the write transaction), `load_hub_meta` (`HubMeta { hub_id, sync_epoch }`) |
| `backend/src/db/sync_revisions.rs` | `record_revision(&mut tx, Scope, table, id, Action, now_ms) -> seq`, pulled forward from #7 (TECH_SPEC Q5). `Scope::global()`, `Scope::class(id)`, `Scope::student(class_id, student_id)`; `Action::Upsert`, `Action::Delete` |
| `backend/src/lib.rs` | `serve`: connect, migrate, `load_hub_meta`, then `AppState.hub_id` / `sync_epoch` |
| `backend/.gitattributes` | Keeps migrations LF on every checkout |

## Data flow and state
* **Start:** `<data_dir>/lara.db` is created if missing, every pending migration runs (SQLx records them in `_sqlx_migrations`), then `load_hub_meta` inserts `hub_meta` `hub_id` (new UUID v4) and `sync_epoch = '1'` only if they are missing (`INSERT OR IGNORE`, in one transaction), and reads both back. Restarts keep the same `hub_id`, so clients do not see a new Hub. Any failure stops `serve` with `StartError::Database` or `StartError::Migrate`; the window shows "The Hub did not start".
* **Connection settings:** `journal_mode = WAL`, `synchronous = NORMAL`, `foreign_keys = ON`, `busy_timeout = 5000` on `SqliteConnectOptions`, so every pooled connection gets them (pool of 8).
* **Writes:** `let mut tx = db::begin_immediate(&app.db).await?;` then the change, then `record_revision(&mut tx, ...)`, then `tx.commit()`. A transaction dropped without `commit` rolls back, so an early `?` return leaves nothing half written.
* **Ledger:** `record_revision` is the only code that inserts into `sync_revisions`. `seq` comes from `AUTOINCREMENT` inside the write lock, so `seq` order is commit order. `created_at` is informational only, never the cursor.
* **Tables touched in this step:** `hub_meta` (first run). No route reads or writes data yet, and no events are emitted.

## Gotchas and edge cases
* **PRAGMAs in a migration fail:** SQLx wraps each migration in a transaction, and `journal_mode = WAL` cannot change inside one. They stay on the connect options.
* **Deferred `BEGIN` + write = `SQLITE_BUSY`:** a transaction that reads first and then tries to write can fail at once instead of waiting for `busy_timeout`. Always use `begin_immediate` for writes; `pool.begin()` is for read-only snapshots.
* **Line endings change the checksum:** SQLx compares a checksum of each applied migration. With `core.autocrlf=true`, a Windows checkout would embed CRLF and a Linux one LF, so a `lara.db` (or a backup) from one build would fail to open on the other with `VersionMismatch`. `backend/.gitattributes` pins `src/db/migrations/*.sql` to LF. The parity test strips `\r` from the contract for the same reason.
* **Editing `001_initial_schema.sql` breaks every existing Hub** (checksum mismatch). A contract change gets a new migration file; the parity test then compares all migrations together against the updated contract.
* **`sync_epoch` is read once at start.** Restore (step 4.4) bumps it in `hub_meta` and must refresh `AppState` (the Hub restarts its pool on restore anyway).
* **Rebuilding a table that other tables reference (003):** SQLite rewrites a child's `REFERENCES` clause when its parent is renamed, even with `legacy_alter_table`, while `foreign_keys` is on, and it cannot be turned off inside SQLx's migration transaction. Renaming `materials` alone left `material_chunks` pointing at the old copy, and dropping that copy cascaded into every chunk. 003 renames `materials`, `material_chunks` and `ai_chat_messages` together, recreates all three from the contract (so they point at each other again), copies the rows, then drops the old copies child first. `upgrading_backfills_the_audit_fields_and_keeps_every_row` fails if a chunk or a tutor message's `chunk_id` is lost.
  * 004 does the same for `quizzes`, `quiz_questions` and `quiz_attempts`.
  * **Indexes go with the old copy:** a renamed table keeps its indexes under their old names, so 004 creates `idx_quizzes_class`, `idx_quiz_attempts_student` and `uq_one_active_quiz_per_classroom` only after dropping the old copies. Created earlier, `IF NOT EXISTS` would skip them, and they would vanish with the old tables. The parity test and `upgrading_adds_the_close_columns_and_keeps_every_quiz_row` both catch a missing index.
* **Audit fields stay on the Hub:** `uploaded_by` and the new `created_at` columns are never in a response or a pull (the mock strips them too). A resubmission under a new `submission_id` keeps the first hand-in's `created_at`; a learner who asks to join again keeps their enrollment's `created_at`.
* **Forward references:** `materials` references `topics` and `assignments`, which are created later in the file. SQLite allows this; foreign keys are checked when rows are written, not when tables are created.

## Tests
| Test | Proves |
|---|---|
| `db::tests::migrations_reproduce_the_server_master_contract` | `sqlite_master` (tables, indexes, autoindexes, statement text) of a migrated DB equals the contract loaded into a second DB; table count equals the contract's `CREATE TABLE` count (19) |
| `db::tests::upgrading_backfills_the_audit_fields_and_keeps_every_row` | A Hub on migrations 001-002 with a class, co-teacher, material, chunk, tutor message, submission and quiz attempt upgrades with every row kept, each new column backfilled as #274 lists (`uploaded_by` = class owner), the tutor message still pointing at its chunk, and no broken foreign keys |
| `db::tests::upgrading_adds_the_close_columns_and_keeps_every_quiz_row` | A Hub on migrations 001-003 with a closed quiz, an active quiz, their questions and attempts upgrades with every row kept: `closed_at` only on the closed quiz (from `updated_at`), `auto_submitted` 0, the one-active-quiz index back, deletes still cascading from the new `quizzes`, and no broken foreign keys |
| `tests/audit_fields.rs` | Upload records the signed-in teacher (a co-teacher, not the owner) and never sends it; chunks, enrollments and co-teacher rows record when they were made; a resubmission keeps the first `created_at` |
| `db::tests::pragmas_hold_on_every_pooled_connection` | `wal`, `foreign_keys = 1`, `busy_timeout = 5000` on two pooled connections |
| `db::tests::first_run_creates_hub_meta_and_restarts_keep_it` | UUID `hub_id`, epoch 1, same values after reconnect, exactly two rows |
| `db::tests::write_transaction_rolls_back_unless_committed` | Dropped `begin_immediate` transaction leaves nothing; committed one persists |
| `db::sync_revisions::tests::record_revision_stores_scope_and_returns_increasing_seq` | Global, class and student scopes stored as NULL/values; `seq` increases |
| `db::sync_revisions::tests::concurrent_writers_and_an_open_reader_all_succeed` | WAL under load: 8 writers x 50 commits with an open reader; the reader is not blocked and keeps its snapshot; 400 unique `seq`. In rollback-journal mode this test fails with `database is locked` |
| `tests/database.rs` `hub_id_survives_a_restart` | The real `serve` stores `hub_id` in `hub_meta` and keeps it across restarts |
