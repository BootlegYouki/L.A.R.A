# Class management (step 2.5, #121)

Teachers keep a class tidy the way Google Classroom does: fix its name, close it at year end, remove a learner who joined by mistake, and share the class with another teacher. This implements PRD FR-1.4.

## Key files

| File | Purpose |
|---|---|
| `backend/src/routes/classrooms.rs` | `update`, `remove`, `add_teacher` and `remove_teacher` handlers |
| `backend/src/db/classrooms.rs` | `update`, `add_co_teacher`, `remove_co_teacher` and the enrollment transitions in `decide`; each writes its revisions in the same transaction |
| `backend/src/services/class_access.rs` | `require_teacher` (owner or co-teacher), `require_owner` and `require_writable` |
| `backend/src/db/sync_revisions.rs` | Delivers a removed co-teacher's copy of the class and refuses pushes to an archived class |
| `backend/tests/classrooms.rs`, `backend/tests/sync.rs` | The real routes over HTTP and WebSocket, then what each device pulls afterwards |

## Who can do what

| Action | Route | Owner | Co-teacher | Archived class |
|---|---|---|---|---|
| Rename or section change | `PATCH /api/classrooms/{id}` | Yes | 403 | 403, unless the same request clears the archive |
| Archive or clear it | `PATCH /api/classrooms/{id}` | Yes | 403 | Allowed |
| Remove a learner | `POST /api/classrooms/{id}/remove` | Yes | Yes | 403 |
| Add or remove a co-teacher | `POST` and `DELETE /api/classrooms/{id}/teachers...` | Yes | 403 | Allowed |

Learners and teachers from other classes get 403 on all of these. An unknown class gets 404.

## Data flow

- **Rename and archive:** a field not sent stays as it was. A wrong type answers 400 and changes nothing. A change writes one class-scoped revision, so learners pull the new name or `archived_at`.
- **Archived classes:** reads, the roster and sync pulls keep working. Join, approve, reject, remove and rename answer 403. A sync push of new or edited work gets a `REJECTED` receipt with reason `FORBIDDEN`.
- **Remove:** `ACTIVE` becomes `REMOVED`, the row stays, and no submission, attempt or score is touched. After the commit the learner gets `EVENT_JOIN_APPROVAL` with status `REMOVED`. The class leaves their `GET /api/classrooms` and their pull, except for their own `REMOVED` enrollment. They can ask to join again, which reuses the same enrollment row.
- **Adding a co-teacher:** the owner sends a teacher's `lrn_or_id`. No such active teacher answers 404, including a learner's LRN or a deactivated teacher.
- **Both co-teacher changes** write two revisions, class first. The class-wide one updates `co_teacher_ids` for everyone. The copy addressed to that teacher (`Scope::student`, with the teacher's id in `student_id`) tells sync the class just appeared for them, so older rows come along, or just left them.

## Gotchas

- **Retries do nothing.** Removing a `REMOVED` learner, adding an existing co-teacher, removing a teacher who isn't one, archiving an archived class or sending the current values all answer 200 with no revision and no event. A retried archive keeps the first `archived_at`. Adding the owner as their own co-teacher does the same.
- **A saved push still gets its receipt after archiving.** `class_archived` runs after the retry check, so a device whose response was lost isn't told its saved work failed.
- **`pull_page` checks the latest revision of a row, not only the one on the page.** A removed co-teacher can't see later class-wide revisions of the class row, so that check used to drop their copy too, and a teacher who was offline during the removal never learned about it. A revision addressed to the caller now counts on its own.

## Decisions for the Lead to check

1. **Teacher management on an archived class is allowed.** The issue says every write route answers 403, but `tests/test_mock_hub.py::test_removed_learner_loses_the_class_and_co_teacher_gains_it` removes a co-teacher from a class it has just archived, and the contract lists 403 only for remove, approve and reject. The Hub follows the test and the contract.
2. **Renaming an archived class is refused.** The mock allows it, but the contract says an archived class is read-only for everyone, and no mock test renames one. Clearing the archive in the same request is allowed, or the class could never be reopened.
3. **Pushes to an archived class are refused.** The mock doesn't check this. It follows from the issue's "every write route" rule.

## Not covered yet

The routes for posting and grading (`POST /api/announcements`, assignments and quizzes) are still stubs that answer 501, so these acceptance items can't be proved here:

- **"A co-teacher can post and grade":** today's proof is that a co-teacher passes `require_teacher` on the roster, approve, reject and remove routes. [SERVER 3.4] #54 and the other steps that build those routes must use `class_access::require_teacher`, and `require_writable` for writes.
- **`test_removed_learner_loses_the_class_and_co_teacher_gains_it` against the real Hub:** its `POST /api/announcements` step can't pass until #54. `backend/tests/classrooms.rs` and `backend/tests/sync.rs` cover the rest of that flow on the real routes.

## Offline behavior

Nothing here calls an external service or adds a dependency. A device that misses the event learns about a removal, rename or archive on its next pull, including after a Hub restart. These checks use loopback networking. A classroom router with its WAN cable unplugged and real clients remain integration-day checks.

## How to test (no programming required)

Ask a developer to start this branch's Hub with two teacher accounts (one is the owner) and one learner account, and point the test clients at it. The client screens for these actions come from the desktop and mobile teams. Until they exist, the developer sends the requests from `backend/tests/classrooms.rs`.

1. Unplug the router's WAN cable, keeping classroom Wi-Fi on. As the owner, create a class and approve the learner.
2. Rename the class. The learner sees the new name after refreshing.
3. Add the second teacher by their ID. That teacher now sees the class and its roster, but can't rename it, archive it or manage teachers.
4. As the second teacher, remove the learner. The learner's class disappears straight away if connected, or on the next refresh if not. The owner still sees the learner on the roster as removed.
5. On the learner's device, enter the Class Code again. The owner sees a new pending request. Approve it.
6. As the owner, archive the class. The learner can still open it. Approving, removing and renaming are refused.
7. Disconnect the second teacher's device. As the owner, remove that teacher, then rename the class. Reconnect: the class should leave that teacher's list.
8. As the owner, clear the archive. Changes work again.

## Verification

From `server/backend/`, run `cargo check --all-targets` and `cargo test`. From the repository root, run `py -m unittest discover tests` and `py scripts/verify_invariants.py`, with `PYTHONUTF8=1` on Windows. The PR holds the output.
