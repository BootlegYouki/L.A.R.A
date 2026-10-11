---
name: lara-hub-rust
description: Rust patterns for the L.A.R.A Local Hub (Axum, Tokio, SQLx on SQLite, WebSocket broker): connection options and WAL, sync_revisions in the same transaction, multipart uploads past Axum's 2 MB default, HTTP Range streaming with a per-client 2 MB/s cap, bearer auth, and a WebSocket broker that never blocks. Use when working in server/.
---

# L.A.R.A Hub: Axum, SQLx, SQLite

Read `AGENTS.md` and `server/AGENTS.md` first, then the contract you implement. `scripts/mock_hub.py` and `tests/test_mock_hub.py` are the acceptance reference: your behavior must match them. Where this skill and a contract or rule disagree, the contract or rule wins. For general Rust idiom see the `rust-skills` skill.

## 1. SQLite with SQLx

* PRAGMAs go on the **connection options**, never in a migration (SQLx runs migrations inside a transaction, where `journal_mode = WAL` fails):
  ```rust
  let opts = SqliteConnectOptions::from_str("sqlite://lara.db")?
      .create_if_missing(true)
      .journal_mode(SqliteJournalMode::Wal)
      .synchronous(SqliteSynchronous::Normal)   // in WAL mode this is durable enough and much faster than FULL
      .foreign_keys(true)                       // must be set on EVERY connection
      .busy_timeout(Duration::from_secs(5));
  let pool = SqlitePoolOptions::new().max_connections(8).connect_with(opts).await?;
  sqlx::migrate!().run(&pool).await?;
  ```
* **Every change a client must see writes a `sync_revisions` row in the same transaction** (with `classroom_id` and `student_id` scope). Insert the change and the revision, then commit. The cursor is `sync_revisions.seq`, never `updated_at`.
* SQLite allows one writer. A transaction that starts as a read and then writes can fail with `SQLITE_BUSY` even with a timeout. Start write transactions with `BEGIN IMMEDIATE` (`pool.begin_with("BEGIN IMMEDIATE")`) and keep them short.
* **Never hold a transaction (or the write lock) across a network call, a file upload or a WebSocket send.** Do the slow thing first, then open the transaction.
* Never delete users, classrooms or graded rows. Deactivate or archive.
* Bump `hub_meta.sync_epoch` on restore from backup and when tombstones are pruned.

## 2. Uploads: Axum's default limit is 2 MB

* Axum rejects request bodies over **2 MB** by default (this includes `Multipart`, `Json`, `String`). Raise it on the upload routes only:
  ```rust
  .route("/api/materials", post(upload_material).layer(DefaultBodyLimit::max(250 * 1024 * 1024)))
  ```
* Do not call `field.bytes()` on a video: that buffers 250 MB in RAM. Read `field.chunk().await` and write each chunk to a temp file with `tokio::fs`, counting bytes. Past 250 MB stop, delete the temp file and return `FILE_TOO_LARGE`. Move the file into `storage/` with a generated name only after it is complete.
* Never trust the client's file name or content type. Homework resubmission is idempotent on `(assignment_id, student_id)` and keeps the old file until the new one is committed.

## 3. Range streaming and the 2.0 MB/s cap

* Do not hand-write Range parsing. `tower_http::services::ServeFile` and `ServeDir` handle a single `Range` request with `206 Partial Content` and `Content-Range`, and answer `416` for multi-range requests, which players do not send.
* The cap is **per client** (per token), not per connection. Wrap the response body in a stream that sleeps between chunks to hold the client at 2.0 MB/s, with one shared bucket per token. Test two streams from one learner and 20 learners at once.
* Media routes accept `?token=` because HTML5 video cannot send headers. Only media routes do.
* Downloads of handouts and the AI model file must also honor Range so an interrupted transfer resumes.

## 4. Auth

* Hash PINs with **argon2id**. Rate-limit logins (5 per minute per LRN). Issue random opaque tokens and store only their SHA-256; sessions expire in 12 hours.
* Bearer extractor on every route except `/download`, register and login. Authorize by role **and class ownership** on the server: a teacher sees only their classes, a learner only their own submissions and attempts.
* Errors use the envelope `{ "error": { "code", "message" } }` with the documented codes.
* Never log PINs, tokens or LRNs, and never return `pin_hash`, another learner's LRN, `correct_answer` or a server file path. Student response types are separate structs that cannot contain `correct_answer`; add a test that scans every student route's JSON.

## 5. WebSocket broker (`:8081`)

* A second listener on its own port. First frame is `EVENT_HELLO` with the token; answer `EVENT_HELLO_ACK` with `hub_id`, `sync_epoch` and server time. Close with code `4401` for a bad token.
* One **bounded** `mpsc` channel per connection. If a client is slow or gone, drop it. A slow phone must never delay the others or block a route.
* Offer `send_to_user`, `send_to_classroom_students` and `send_to_teacher`. Validate every frame you emit against `contracts/events/*.json` in a unit test.
* Quiz start fans out to 40 devices within 300 ms of each other; measure it.

## 6. Async hygiene

* No blocking file or CPU work on the async runtime: use `tokio::fs`, or `spawn_blocking` for PDF text extraction and spreadsheet writing.
* No `.unwrap()` in production paths. Convert errors into the envelope. A broken socket or a timeout must never crash the Hub or corrupt SQLite.

## 7. Before you open a PR

```bash
cd server/backend && cargo check --all-targets && cargo test      # what CI runs
python3 ../../scripts/verify_invariants.py && python3 -m unittest discover ../../tests
```
Compare your behavior with the mock hub flows for the routes you touched, and write what differs in the PR.
