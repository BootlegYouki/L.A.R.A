# Lesson files and homework (step 3.3, #53)

Teachers upload lesson files and post attachments, and learners hand in homework photos and documents. Both go to the Hub disk and are downloaded back over the classroom network. This implements PRD FR-2.4, FR-3.1, FR-3.3 (upload only), FR-3.5 (submit and open the file) and FR-3.8 (attachments) with the contract routes `POST /api/materials`, `GET /api/materials/{id}/download`, `POST /api/assignments/{id}/submit` and `GET /api/submissions/{id}/file`. Text chunking (3.1), the video stream and the 2.0 MB/s cap (3.2), and the submission list and grading (3.4) build on this.

## Key files

| File | Purpose |
|---|---|
| `backend/src/storage/mod.rs` | `Storage`: the folders under `<data_dir>/storage`, streamed temp uploads with the 250 MB cap, commit by rename, path checks. No database or HTTP types |
| `backend/src/storage/format.rs` | `FileFormat`: identifies PDF, JPEG, PNG, MP4 and WebM from the first bytes, and DOCX from the ZIP's file list |
| `backend/src/routes/upload.rs` | The one multipart reader for both upload routes; `FILE_TOO_LARGE` handling |
| `backend/src/routes/files.rs` | `serve_stored`: `ServeFile` with each route's `Ranges` rule, the `If-Range` check, `nosniff`, and a 404 when the file is gone |
| `backend/src/routes/materials.rs` | Upload and download handlers |
| `backend/src/routes/assignments.rs` | Submit and submission-file handlers |
| `backend/src/db/materials.rs`, `db/submissions.rs` | Rows and their `sync_revisions` entries |
| `backend/src/auth/mod.rs` | `MediaUser`: `AuthUser` that also accepts `?token=`, on the two download routes only |
| `backend/src/services/class_access.rs` | `require_member`, the mock's `member_of`: class teachers and `ACTIVE` learners |
| `backend/tests/materials.rs`, `tests/submissions.rs`, `tests/common/uploads.rs` | Real-Hub flows, access rules, cleanup checks, a raw-socket 300 MB upload |

## Data flow

1. **Receive.** `read_form` reads the multipart body in any part order.
   - A declared `Content-Length` over the limit gets 413 before anything is read.
   - The `file` part streams into `storage/tmp/<uuid>` and is counted. Past 250 MB it stops with 413 `FILE_TOO_LARGE` and the temp file is deleted.
   - Text parts are limited to 4 KiB, and parts the route does not use are skipped.
2. **Identify.** The format comes from the file's own bytes. The client's file name and part `Content-Type` are ignored.
   - The first 16 bytes give PDF, JPEG, PNG, MP4 or WebM. MP4 also needs an MP4 brand after `ftyp`, so QuickTime `.mov`, HEIC and AVIF photos and `.m4a` audio are refused.
   - A ZIP is DOCX only if its file list (read from the last 1 MiB) names `[Content_Types].xml` and `word/document.xml`. An APK, a spreadsheet or any other ZIP is refused.
   - `VIDEO` takes MP4 or WebM. `DOCUMENT` and `WORKSHEET` take PDF, DOCX, JPEG or PNG.
   - Homework takes JPEG or PNG (`IMAGE`) or PDF or DOCX (`DOCUMENT`).
   - Anything else gets 400.
3. **Place.** The complete file is synced to disk and renamed to `materials/<uuid>.<ext>` or `submissions/<uuid>.<ext>`. The database stores that relative name in `file_path`, and `mime_type` for materials. The extension is how the file route knows a submission's type, since that table has no `mime_type` column.
4. **Commit.** A short `begin_immediate` transaction re-checks access, inserts or updates the row, and records its revision:
   - materials: `Scope::class`, so everyone in the class pulls it;
   - submissions: `Scope::student`, so only that learner and the class's teachers pull it.

   If any check refuses, the transaction rolls back and dropping `StoredFile` deletes the renamed file. No file I/O happens under the write lock.
5. **Homework rules (contract #257),** checked in the mock's order (`submission_create` in `scripts/mock_hub.py`):
   1. **Identical retry:** the same `submission_id` with the same bytes gets the stored row back unchanged, even after grading or the due date. The upload's SHA-256 is computed while it streams; the stored file is read back only when its size matches, before the transaction. Inside it, the row must still name the file compared.
   2. **Graded is final:** any other file once a score exists gets 409 `ALREADY_GRADED`.
   3. **Late:** with `allow_late` off, work turned in after `due_date` gets 409 `LATE_SUBMISSION`.

   The optional `turned_in_at` field is when the learner pressed Turn in (epoch ms). `submitted_at` is `min(turned_in_at, arrival)`, so work finished offline before the deadline still counts and a device clock ahead of the Hub cannot postdate it; without the field it is the arrival. `received_at` is always the arrival.
6. **Replace.** Before grading, a new file replaces the learner's earlier one. Under the same id the row is updated; under a new id the old row is deleted with a `DELETE` revision (same learner and teachers) and the row takes the new id. The old file is deleted only after the commit, so a failed resubmission still serves the work handed in before.
7. **Serve.** `ServeFile` streams from disk. Both routes send `X-Content-Type-Options: nosniff`. Every file sent counts against the user's 2.0 MB/s (step 3.2, `streaming_rate_limit.md`).
   - Material downloads are `application/octet-stream`, as the contract and the mock send them.
   - **VIDEO downloads resume** (contract #259): `Range` gets 206 with `Content-Range`, a range past the end gets 416 with `Content-Range: bytes */<size>`, and every answer carries `Accept-Ranges: bytes` and an `ETag`. With `If-Range`, a tag that no longer matches gets the whole file as 200, so Save for Home starts over when the file changed. `ServeFile` ignores `If-Range`, so `serve_stored` checks it and drops `Range` when it is stale. The `ETag` is a hash of the stored name; stored files are never changed in place, so it changes only with the file.
   - **Other material downloads** come whole (200) whatever `Range` asks for, with `Accept-Ranges: none` (the contract makes `Range` VIDEO only).
   - Submission files answer `Range` as `ServeFile` does; the contract says nothing more for them.
   - Submission files carry their real type, as the mock does, so image views can show the photo.

On start, the Hub creates the folders and empties `tmp/`. Anything there is an upload a crash interrupted.

## Who may do what

| Route | Allowed | Refused |
|---|---|---|
| `POST /api/materials` | Owner or co-teacher of `classroom_id` | Learner 403 `Teacher only`; other teacher 403; unknown class 404; archived class 403 `This class is archived`; `topic_id`/`assignment_id`/`announcement_id` not in that class (or an archived assignment) 404 |
| `GET /api/materials/{id}/download` | Class teachers and `ACTIVE` learners, also in an archived class | No or bad token 401; anyone else 403 `Not your class`; unknown id 404 |
| `GET /api/materials/{id}/stream` | The same members, for a VIDEO only | As `/download`; a material that is not a video 404 `Not a video` (#259). Streaming itself is step 3.2 (`streaming_rate_limit.md`) |
| `POST /api/assignments/{id}/submit` | `ACTIVE` learners of the class | Teacher 403 `Learners only`; non-member 403 (checked before the body is read); unknown or archived assignment 404; archived class 403 except a retry of stored work (checked before the body is read when the learner has nothing stored); a `submission_id` used for other work 403; graded work 409 `ALREADY_GRADED`; late work 409 `LATE_SUBMISSION`; a `turned_in_at` that is not an integer 400 |
| `GET /api/submissions/{id}/file` | The owning learner and the class's teachers | Classmate 403 `Not your submission`; other teacher or admin 403; unknown id 404 |

## Gotchas

- The root `.gitignore` drops every `storage/` folder (runtime uploads), so `server/backend/.gitignore` re-includes `src/storage/`, as it already does for `src/models/`.
- `?token=` is accepted only through `MediaUser`, on the media routes (`/download`, `/stream`, the submission file). `TraceLayer` logs the path only, so the token never reaches a log.
- Axum's 2 MB default body limit is raised only on the two upload routes (`UPLOAD_BODY_LIMIT`, the cap plus 1 MiB).
- The Hub may answer 413 while a client is still sending, then close the connection. Clients see the 413 or a reset; both mean "too large or not sent", and a retry with the same `submission_id` is safe. A Windows client that is still sending usually sees only the reset: Winsock drops an answer it already received when the connection is reset under it. So apps should check the 250 MB cap before uploading. The tests accept the reset on Windows only (`assert_file_too_large`); on Linux they check the 413.
- `assignment_submissions.received_at` came with migration `002`, which rebuilds the table: `ADD COLUMN` would put the column last, and the schema must read exactly like `server_master.sql`. Rows from before keep their arrival time, `submitted_at`, as `received_at`.
- A row whose file is missing from disk answers 404 `NOT_FOUND`, not 500, and logs the entity id.

## Decisions for the Lead

These are calls where the contract and mock are silent. Each is listed in the PR.

1. **Formats, from the file's bytes:** as in Data flow step 2. An unsupported file gets 400 `BAD_REQUEST`, since the contract has no code for it. Two calls follow PRD FR-3.1 and FR-3.3 strictly: an iPhone `.mov` video is refused (MP4 or WebM only), and so is any ZIP that is not a Word document.
2. **Placeholder bytes in the mock tests:** settled by the Lead in #257, which gave those flows real `%PDF` and JPEG headers. `submission_id` is not required to be a UUID, so `sub-1` works.
3. **Homework cap (TECH_SPEC Q10):** the same 250 MB as materials.
4. **Archived class:** uploads and new homework get 403. A retry of a stored submission (same id, learner and assignment) gets 200 with the stored row, unchanged, following #235's sync-push rule.
5. **Submission id clash:** an id that already names another learner's or assignment's work gets 403. The mock would overwrite it.
6. **Late work, grades and retries:** settled by the Lead in contract #257 (Data flow step 5). The Hub records `received_at` as well as `submitted_at`.
7. **Content types:** see Data flow step 6.
8. **Empty optional ids** (`topic_id=""`) count as not sent, and an empty `submission_id` counts as missing.

## Acceptance evidence

| Criterion | Evidence |
|---|---|
| Flows match the mock for upload, download and resubmission | `teacher_uploads_a_pdf_and_gets_the_contract_material`, `attachments_link_to_their_post`, `members_download_the_bytes_with_a_token`, `a_learner_submits_a_photo_and_only_they_and_the_teachers_see_it`, `before_grading_new_work_replaces_the_old_row_and_its_id` |
| Contract #257: late work, graded work, identical retries, `turned_in_at` and `received_at` | `late_work_is_refused_unless_the_assignment_allows_it`, `graded_work_cannot_be_replaced`, `an_identical_retry_gets_the_stored_row_even_after_grading_and_the_deadline`, `a_submission_records_when_it_was_turned_in_and_when_it_arrived`; migration: `migrations_reproduce_the_server_master_contract`, `upgrading_keeps_handed_in_work_and_fills_received_at` |
| Contract #259: resumable VIDEO downloads, whole other files, `/stream` refuses a non-video | `a_video_download_resumes_with_range_and_if_range`, `a_document_download_sends_the_whole_file_whatever_the_range`, `the_stream_route_refuses_a_material_that_is_not_a_video` |
| A 300 MB upload is rejected without filling the disk | `a_300_mb_upload_is_refused_and_leaves_no_file`, with a declared `Content-Length` and chunked: 413, no row, no revision, empty `tmp/` and `materials/` |
| `cargo clippy --all-targets -- -D warnings && cargo test` pass | In the PR (Windows 11, 2026-11-09) |
| Log or screenshot against the Hub with the WAN unplugged | Not done yet. Manual steps below |

## Manual check (WAN unplugged)

1. Start the Hub (`cargo run --bin lara-hub` in `backend/`).
2. Sign in a teacher and a learner, create a class, and approve the learner.
3. Add one row to `assignments`, because the assignment routes are step 3.4.
4. As the teacher, upload a real PDF and a short MP4:
   ```
   curl -H "Authorization: Bearer $T" -F classroom_id=$C -F title=Lesson -F file_type=DOCUMENT -F file=@lesson.pdf http://<hub>:8080/api/materials
   ```
5. As the learner, download both. Then download part of the MP4 (`$V`), and try the same on the PDF (`$M`):
   ```
   curl -i -r 0-99 -o part http://<hub>:8080/api/materials/$V/download?token=$L
   curl -i -r 0-99 -o whole http://<hub>:8080/api/materials/$M/download?token=$L
   ```
   Expect 206 and 100 bytes for the video, with an `ETag`. The PDF comes whole (200): `Range` is for videos only.
6. Submit a real phone photo:
   ```
   curl -H "Authorization: Bearer $L" -F submission_id=$(uuidgen) -F file=@photo.jpg http://<hub>:8080/api/assignments/$A/submit
   ```
   Submit again with a second photo, then open `/api/submissions/<id>/file` as the teacher. It must show the second photo.
7. Try the refusals: a `.txt` file renamed to `.pdf`, a learner from another class, and an upload over 250 MB.
