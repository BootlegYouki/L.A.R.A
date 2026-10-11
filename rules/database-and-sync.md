# Database Schema & Delta-Sync Rules

This rule document governs all database implementations (Android Room, Desktop SQLite, and Server SQLite) and the delta-sync protocol.

All AI agents and contributors must follow these rules.

---

## 1. Single Source of Truth

The schema is defined **only** in `contracts/schema/`:

* `server_master.sql`: the Hub's authoritative database.
* `client_offline.sql`: the offline slice for Android Room and the Desktop client.

Never copy column lists into other documents. READMEs, the PRD and issues link to these files. A schema change is a `contract-change` PR (see `rules/team-workflow-and-prs.md`): update both SQL files, `contracts/openapi.yaml` if the API shape changes, `scripts/mock_hub.py` and `tests/` together.

Core tables share names and column meaning across server and clients: `users`, `classrooms`, `enrollments`, `announcements`, `announcement_comments`, `materials`, `material_chunks`, `assignments`, `assignment_submissions`, `quizzes`, `quiz_questions`, `quiz_attempts`, `ai_chat_messages`, `topics`, `private_comments`. Server-only: `sessions`, `sync_revisions`, `hub_meta`, `classroom_teachers` (clients get co-teachers as `Classroom.co_teacher_ids`). Client-only: `sync_state`.

### Conventions
* IDs are UUID v4 `TEXT`. Timestamps are epoch **milliseconds** `INTEGER`. Booleans are `INTEGER` 0/1.
* Network JSON keys are `snake_case` and match column names.
* Lesson text for the AI lives **only** in `material_chunks` (there is no `extracted_text` column).
* Grading is points only, like Google Classroom: `max_points` on `assignments` and quizzes, points earned on submissions and attempts. There are no categories, quarters or weights; each teacher applies their own grading system outside L.A.R.A.

---

## 2. What Never Reaches a Client Database

A learner can open their own phone's SQLite file, so the client schema is public to that learner. The server must never sync, and clients must never define columns or fields for:

| Never on a client | Why |
|---|---|
| `users.pin_hash` | A 4-digit PIN hash is brute-forced instantly |
| Other people's `lrn_or_id` | Learner Reference Numbers are personal data of minors. Only the signed-in user's own LRN and (for teachers) their roster's |
| `quiz_questions.correct_answer`, `synonyms_json` | Answer keys |
| `materials.file_path`, `assignment_submissions.file_path` (server paths) | Server disk layout. Clients use `download_url` and their own local paths |
| `sessions`, `hub_meta`, `sync_revisions` | Server internals |
| The `ADMIN` user row | The Hub admin exists only on the Hub |
| Another learner's `private_comments` | A private thread belongs to one learner and the class teachers; its revisions carry `student_id` |

Clients get other people's names through `PublicUser` (`id`, `full_name`, `role`) in the pull response.

### Client-specific columns
* `sync_status` (`'SYNCED'` | `'QUEUED_FOR_SYNC'`) on `assignment_submissions`, `quiz_attempts`, `announcement_comments` and `private_comments`: work created offline waiting to upload.
* `materials.local_file_path`: absolute path of the cached PDF or MP4.
* `sync_state` (key/value): `hub_id`, `sync_epoch`, `cursor`, `current_user_id`.

---

## 3. Delta-Sync Protocol

### 3.1 The cursor is a sequence number, never a clock time
`sync_revisions.seq` is `INTEGER PRIMARY KEY AUTOINCREMENT`. The Hub inserts one revision row **in the same transaction** as every change a client must see (`UPSERT` or `DELETE`). Using `updated_at` as a cursor is forbidden: the Hub laptop is offline and its clock can be wrong or corrected backwards, two changes can share a millisecond, and a transaction can commit after a pull finished. Any of these makes a client silently miss data.

Each revision has `classroom_id` (NULL for global records) and `student_id` (NULL means the whole classroom; set means only that learner and the teacher see it, for example homework submissions and quiz attempts). Pull filters on both.

### 3.2 Pull (`POST /api/sync/pull`)
1. Client sends `{ cursor, hub_id?, sync_epoch? }` (cursor 0 and no ids on the first sync). The learner is identified by the bearer token, not by a body field.
2. Hub returns the latest state of every entity changed after `cursor` in the caller's ACTIVE classrooms, `deleted` tombstones, `users` (`PublicUser`), `server_time`, `hub_id`, `sync_epoch`, `next_cursor`, `has_more`, `reset`.
3. Client applies the whole response **and** stores `next_cursor` in **one** local transaction (`@Transaction` in Room, `BEGIN`/`COMMIT` with plugin-sql). If anything fails, roll back and keep the old cursor.
4. If `has_more`, pull again immediately.
5. `server_time` is only for clock offset (quiz countdown). It is never the cursor.

### 3.3 Reset (new Hub, restore, pruning)
* `hub_id` is generated once per Hub. `sync_epoch` is incremented when a backup is restored or when old tombstones are pruned.
* If the client's `hub_id` or `sync_epoch` differs from the Hub's, the response has `reset: true`. The client wipes its mirrored tables, **keeping rows still `QUEUED_FOR_SYNC`**, and applies the response (which starts from cursor 0).
* The same applies if a client was offline longer than the tombstone retention window (the Hub bumps the epoch when it prunes).

### 3.4 Push (`POST /api/sync/push`)
* Client uploads rows with `sync_status = 'QUEUED_FOR_SYNC'`: quiz attempts, stream comments and private comments. Homework photos use `POST /api/assignments/{id}/submit` (multipart) with the client-generated `submission_id`, so retries are idempotent.
* Hub validates and grades server-side, writes `sync_revisions` in the same transaction, and returns one receipt per item (`SYNCED` or `REJECTED` + reason such as `TIME_LIMIT_EXCEEDED`).
* Client marks `SYNCED` only from a receipt. A `REJECTED` row stays visible to the learner with a kind explanation and is never silently deleted.

### 3.5 Conflict policy
Server is authoritative for classroom metadata, rosters and grades. The client is authoritative for its own drafts, queued answers and homework photos until the Hub acknowledges them.

---

## 4. Integrity & Deletion Rules

* **Never delete users, classrooms or graded rows in production code.** Deactivate users (`is_active`) and archive classrooms (`archived_at`). Grade-bearing tables reference `users` with `ON DELETE RESTRICT`; deleting a classroom cascades and would erase grades.
* **Deleting an assignment archives it** (`assignments.archived_at`): clients get a `DELETE` tombstone, the gradebook and export skip it, and its submissions and grades stay on the Hub. Materials, topics and announcements hold no grades and are really deleted. A removed learner's enrollment becomes `REMOVED`; nothing of theirs is deleted.
* **Every class-scoped route checks membership on the Hub:** the class's teachers and its `ACTIVE` learners only, and a learner only their own submissions, attempts and private comments. This covers reads (materials, chunks, comments, quizzes) as well as writes, and the queued rows in `POST /api/sync/push`. A pushed row may never overwrite a row another user wrote.
* **"Teaches the class" means the owner (`classrooms.teacher_id`) or a row in `classroom_teachers`.** Every teacher route authorises against that, except rename, archive and managing teachers, which are owner only.
* At most one `ACTIVE` quiz per classroom (partial unique index `uq_one_active_quiz_per_classroom`). `GET /api/quizzes/active` relies on it.
* A `quiz_attempts` row exists from quiz start with `status = 'IN_PROGRESS'`; `submitted_at`, `score` and `total_points` are NULL until `SUBMITTED` (enforced by a CHECK). The AI quiz lockout is enforced while the learner has an `IN_PROGRESS` row.
* One attempt per learner per quiz in v1 (`UNIQUE(quiz_id, student_id)`).

---

## 5. Technology Stack Invariants

* **Android:** Android Room (SQLite).
* **Server:** **SQLx (Rust)** with SQLite. The plan of record is Rust; do not introduce a Node backend.
* **Desktop:** `@tauri-apps/plugin-sql`.
* **Forbidden:** Prisma or any ORM that bloats the binary.
* **PRAGMAs are connection settings, not migrations.** SQLx runs migrations in a transaction where `PRAGMA journal_mode = WAL` fails, and Room manages journal mode itself. Server connection options: WAL, `synchronous = NORMAL`, `foreign_keys = ON` on **every** connection, `busy_timeout = 5000`. Desktop: enable `foreign_keys` when opening the plugin connection.
* Room cannot express CHECK constraints, partial indexes or AUTOINCREMENT from entities. Mirror columns, nullability and defaults exactly and keep the CHECK rules in the repository layer.

---

## 6. Offline-First Default Access (Home Study Mode)

The client applications are offline-first by default:

* **Zero blocking screens:** launched without Wi-Fi or away from the Hub, the app never shows a blocking "No Connection" dialog.
* **Full local read access:** browse enrolled classrooms, read announcements and lesson text chunks, view cached PDFs and play downloaded videos.
* **Offline homework capture:** photos taken at home are stored with `sync_status = 'QUEUED_FOR_SYNC'` and upload when the Hub is reachable.
* **Graceful AI layering:**
  * Capable devices (mobile with at least 6 GB RAM, laptops with at least 4 GB RAM) with the model installed: the tutor works 100% offline.
  * Low-RAM devices or no model: everything else works, and the AI sheet says *"L.A.R.A AI is available when connected to the classroom Hub."*
