# Announcements, assignments and grading (step 3.4, #54)

## Purpose
Teachers post to the class stream, turn comments on or off, set homework, grade what learners hand in, and fix or remove posts, assignments and lesson files. This implements PRD FR-2.1 (broadcasts), FR-2.2 (comment moderation), the teacher half of FR-3.5 (list and grade homework) and FR-3.6 (edit and delete). The contract routes are:
- `POST /api/announcements`, `PATCH` and `DELETE /api/announcements/{id}`, and `GET` and `POST /api/announcements/{id}/comments`
- `POST /api/assignments`, `PATCH` and `DELETE /api/assignments/{id}`, `GET /api/assignments/{id}/submissions` and `PATCH /api/submissions/{id}`
- `PATCH` and `DELETE /api/materials/{id}`
- the `EVENT_ANNOUNCEMENT_PUSH` event

Homework submit and the file routes are step 3.3 ([`materials_and_submissions.md`](./materials_and_submissions.md)).

## Key files
| File | Purpose |
|---|---|
| `backend/src/routes/announcements.rs` | Stream handlers: create (with the push after commit), edit, delete, comment list and comment post |
| `backend/src/routes/assignments.rs` | Create, edit, archive, the submission list and grading, next to 3.3's submit and file routes |
| `backend/src/routes/materials.rs` | Rename (title, topic) and delete, next to 3.3's upload and download |
| `backend/src/db/announcements.rs` | Announcement and comment rows and their revisions. `delete` writes tombstones for everything SQLite cascades |
| `backend/src/db/assignments.rs` | `insert`, `open_class` (the class of an assignment that is not archived), `update`, `archive` |
| `backend/src/db/submissions.rs` | `for_assignment`, `grading_target`, `grade` (3.4), beside 3.3's submit helpers |
| `backend/src/db/materials.rs` | `class_of`, `update`, `delete` (3.4), beside 3.3's `insert` |
| `backend/src/routes/body.rs` | `JsonBody` typed readers: `optional_text`, `non_empty_text`, `nullable_text`, `optional_bool`, `optional_int` |
| `backend/src/services/class_access.rs` | `require_teacher_writable`: teaches the class, and the class is not archived |
| `backend/tests/announcements.rs`, `tests/assignments.rs`, `tests/materials.rs` | Real-Hub flows, access rules, tombstones, the push, archived classes |
| `backend/tests/common/ws.rs` | `connect`, `next` and `nothing_within` for push tests (moved from `tests/websocket.rs`) |

## Data flow
Every write follows TECH_SPEC section 8:
1. The role guard.
2. One `begin_immediate` transaction that looks the row up (404), checks the class (`require_teacher_writable`), writes the row and its `record_revision`, and commits.
3. Only after the commit, the push or the file removal.

Sync pull already loads these tables, so a change reaches devices on their next pull. A `DELETE` revision becomes a `deleted` tombstone.

- **Post an announcement:** after the commit, `Broker::send_to_classroom_students` sends `EVENT_ANNOUNCEMENT_PUSH` `{classroom_id, announcement_id, title, timestamp}` to the class's `ACTIVE` learners. Pending learners and teachers don't get it. A failed push is logged and the post still succeeds; the event is a hint and the pull is the truth. Edits don't push.
- **Delete an announcement:** SQLite's `ON DELETE CASCADE` removes its comments, its attachment materials and their chunks. `db::announcements::delete` writes a `DELETE` revision for each of those rows first, so devices drop them too. The attachment files are removed from disk after the commit.
- **Comment:** members only (`require_member`: the class's teachers and `ACTIVE` learners). The post is read inside the write transaction, so a comment on a post deleted a moment ago gets a 404 rather than a foreign-key error. `allow_comments = false` gives 403 `COMMENTS_DISABLED` to everyone, teachers included, as in the mock. Queued comments from phones still arrive through `POST /api/sync/push` (step 2.2) with the same rules.
- **Delete an assignment:** it is archived (`archived_at = updated_at = now`) and gets a `DELETE` revision. The row, its submissions and their grades stay. From then on every route treats it as missing (`assignments::open_class`): edit, delete, submit, the submission list and grading.
- **Grade:** `PATCH /api/submissions/{id}` sets `score`, `teacher_feedback` or both and keeps the rest. The revision is scoped to that learner and the class's teachers, as for 3.3's submit. Once a score is set, the learner's resubmission gets 409 `ALREADY_GRADED` (3.3).
- **Delete a material:** the row and its chunks (cascade) are removed, each with a `DELETE` revision, and the file goes after the commit. A file already missing from disk doesn't stop the delete.

## Who may do what
| Action | Who | Archived class |
|---|---|---|
| Post, edit, delete announcements; create, edit, delete assignments; grade; rename or delete materials | The class's teachers (owner or co-teacher) | 403 "This class is archived" |
| List submissions | The class's teachers | Allowed (read only) |
| Read comments | Class members | Allowed |
| Post a comment | Class members, when `allow_comments` is true | 403 "This class is archived" |

A learner calling a teacher route gets 403 before any lookup. A teacher of another class gets 403 "Not your classroom". A co-teacher removed from the class loses access on their next request, because access is checked on every request against `classroom_teachers`.

## Gotchas
- **Validation the mock doesn't do.** The mock stores any JSON value. The Hub stores rows under CHECK constraints and typed client models, so:
  - A key sent with the wrong type gets 400 (`title must be text`, `allow_late must be true or false`, `max_points must be a whole number`).
  - A blank `title` (announcement, assignment, material) or comment `content` gets 400 "cannot be empty".
  - `max_points` must be more than 0.
  - `topic_id` must name a topic in the same class (404 "Topic not found"). `null` clears it on a PATCH.
- **Scores follow Google Classroom** (AGENTS.md 1.4: Classroom is the default where the docs are silent). A score may go above `max_points`, but it may not be negative (schema CHECK) or `null`. There is no un-grade or return action (PRD FR-3.5). Feedback alone doesn't grade, so the learner may still resubmit.
- **Repeated deletes get 404:** a second `DELETE` of an announcement, an assignment or a material answers 404 and writes no second tombstone.
- **Edits always sync:** like the mock, a PATCH bumps `updated_at` and writes a revision even when nothing changed.
- **Attachments of an archived assignment stay:** they are rows with `assignment_id` set, and clients hide them with their archived assignment. They hold no grades and could be removed later if the Lead wants.

## Decisions for the Lead
These go beyond the mock and are listed in the PR:
1. Writes in an archived class get 403, as the 2.5 rules already do for enrollment. #54's acceptance asks this for posting and grading.
2. Wrong JSON types and blank titles get 400 instead of being stored.
3. Scores may go above `max_points` (Google Classroom); negative and `null` are refused.
4. An archived assignment gets 404 for the submission list and grading too. The mock still lists and grades it.
5. Deleting an announcement also writes tombstones for its comments, its attachments and their chunks, and removes the attachment files.

## Tests
- **`tests/announcements.rs`:**
  - post and pull
  - the push reaches active learners only
  - edits change only the given fields
  - a second delete gets 404 with one tombstone
  - delete takes comments and attachments with it
  - co-teachers and archived classes
  - outsiders, learners and bad bodies
  - members' comments
  - comments turned off
  - non-members
  - a comment on a deleted post
  - an archived class's comments
- **`tests/assignments.rs`:**
  - the mock's defaults
  - edit and clear a topic
  - invalid fields
  - archive keeps grades
  - a second archive gets 404
  - who may write
  - list and grade
  - score rules
  - grading a replaced submission id
  - a removed co-teacher
  - an archived class can list but not grade
  - an archived assignment is hidden from grading
  - learners and other teachers
- **`tests/materials.rs` (3.4 part):**
  - the mock's attach, rename and delete flow
  - a topic move
  - deleting a PDF removes its chunks
  - a missing file
  - who may write

## Manual check (WAN unplugged)
1. Start the Hub and sign in on the desktop app as `T-0001` (PIN `1234`). Create a class, and have learner `123456789012` join from a phone; approve them.
2. With the phone's app open, post an announcement. The phone shows it at once (push); a phone that was off gets it on the next sync.
3. Turn comments off on the post. The phone's comment box is refused with "Comments are turned off for this post".
4. Create an assignment worth 20 points. The learner photographs and submits homework. Open it from the teacher's submission list, grade it 18 with feedback, and the phone shows the grade after a sync.
5. Delete the assignment. It disappears from the phone after a sync, and the database still holds the graded submission.
6. Delete the announcement and a lesson file. Both disappear from the phone, and the file is gone from `<data_dir>/storage/materials`.
