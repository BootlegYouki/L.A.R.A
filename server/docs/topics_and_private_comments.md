# Topics and private comments (step 3.5, #122)

## Purpose
- **Topics (PRD FR-3.7):** teachers group classwork under topics, for example "Unit 1: Plants". Deleting a topic keeps its work.
- **Private comments (PRD FR-3.8):** a learner and the class teachers exchange comments on one assignment, and classmates never see them.

The contract routes are `POST /api/topics`, `PATCH` and `DELETE /api/topics/{id}`, and `GET` and `POST /api/assignments/{id}/private-comments`. The sync half was already built in step 2.2, and this step adds the REST routes:
- pull sends `topics` and `private_comments`;
- `POST /api/sync/push` takes private comments a learner wrote offline (`sync_revisions::push_private_comment`).

## Key files
| File | Purpose |
|---|---|
| `backend/src/routes/topics.rs` | Create, rename or reorder, delete |
| `backend/src/db/topics.rs` | `insert` (new topics go last), `class_of`, `update`, `delete` (unfiles the work) |
| `backend/src/routes/private_comments.rs` | `whose_thread` decides which thread a caller may read or write; the list and post handlers |
| `backend/src/db/private_comments.rs` | `enrollment_status`, `thread`, `insert` |
| `backend/tests/topics.rs`, `tests/private_comments.rs` | Real-Hub flows, including the mock's two acceptance tests, ported |

## Data flow
Every write is the role guard, then one `begin_immediate` transaction (lookup, class check, row, `record_revision`), then commit, as in step 3.4.

- **New topic:** `order_index` is one past the class's highest, read inside the write transaction. Two topics created at once still get different places.
- **Rename or reorder:** PATCH sets `name` and `order_index` as given. The other topics keep their places, as in the mock.
- **Delete a topic:**
  - Materials, open assignments and quizzes under it lose the topic. Each gets an `UPSERT` revision, so devices unfile them.
  - The topic gets a `DELETE` tombstone.
  - Quizzes need an explicit UPDATE, because `quizzes.topic_id` has no foreign key (#177).
- **Private comment:**
  - The revision is scoped to the learner (`Scope::student`), so only that learner and the class teachers pull it.
  - The REST id is a new UUID. A device's queued comments keep their own ids through sync push, so a repeat push stores them once.

## Who may do what
| Action | Who | Archived class |
|---|---|---|
| Create, rename, reorder, delete topics | The class's teachers (owner or co-teacher) | 403 "This class is archived" |
| Read a private thread | The learner (their own; `ACTIVE`), or a class teacher naming any learner with an enrollment in the class | Allowed |
| Write a private comment | The learner (their own; `ACTIVE`), or a class teacher naming an `ACTIVE` learner | 403 "This class is archived" |

- **A teacher must send `student_id`** (400 "Missing: student_id"). A learner's `student_id` is ignored: they always get their own thread.
- **A thread on an archived assignment is gone** (404 "Assignment not found"), the same as every other route.

## Gotchas
- **An archived assignment keeps its tombstone when its topic is deleted.**
  - The mock re-sends every assignment the topic had, archived ones included.
  - On the Hub that `UPSERT` would become the archived assignment's newest revision. Pull skips archived assignments, so a device that missed the archive would never drop it.
  - So archived assignments get no revision. SQLite's `ON DELETE SET NULL` clears their `topic_id` silently. `deleting_a_topic_never_brings_back_an_archived_assignment` fails without this guard.
- **The named learner must be in the class.**
  - The mock accepts any `student_id`. On the Hub, an unknown id would reach the `users` foreign key and answer 500, so it is 404 "Learner not found in this class".
  - A removed or pending learner's thread can still be read, but only an `ACTIVE` learner's thread takes new comments.
- **`order_index` must be 0 to 9007199254740991,** the desktop's integer range (as for 3.4's numbers). A new topic after one moved to the top place gets that same top place instead of 2^53, which every desktop of the class would refuse (review of #122). `a_new_topic_never_goes_past_the_largest_place` covers it.
- **Quizzes' topic is not in their pull row yet.** `QuizMetadata` has no `topic_id` until the quiz step (4.5). The quiz still gets its revision when its topic is deleted, so the next pull refreshes it.

## Tests
- **`tests/topics.rs`:**
  - topics go last, and topics created at once get distinct places
  - rename and reorder
  - field checks
  - who may write
  - delete keeps the work
  - an archived assignment is never brought back
  - deleting twice
  - delete in an archived class
- **`tests/private_comments.rs`:**
  - the mock's acceptance flow
  - a learner can't write into a classmate's thread
  - a teacher writes only to a learner of the class
  - a removed co-teacher loses access
  - outsiders and bad requests
  - an archived assignment has no thread
  - pushing the same queued comment twice stores it once

## Manual check (WAN unplugged)
1. Start the Hub. As teacher `T-0001` (PIN `1234`), create a class, have learner `123456789012` join, and approve them. Create an assignment.
2. Create topics "Unit 1" and "Unit 2": they come back with places 1 and 2. Move the assignment into "Unit 1" (`PATCH /api/assignments/{id}` `{"topic_id": ...}`).
3. Delete "Unit 1". The assignment is still there, with no topic, and the learner's next sync shows it unfiled.
4. As the learner, post a private comment on the assignment. As the teacher, read it with `?student_id=<learner id>` and reply.
5. Sign in a second learner of the class. Their sync and their own thread show neither comment.
