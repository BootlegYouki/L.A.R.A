# Delta-sync pull and push (#7)

The Hub supplies the offline mirror defined by PRD section 6.2 and the existing sync contract, including contract-change #218. This feature adds no routes, events, columns, dependencies or screens.

## Key files and data flow

| File | Responsibility |
|---|---|
| `backend/src/routes/sync.rs` | Bearer authentication, request validation, read transaction for pull, one write transaction per pushed comment |
| `backend/src/db/sync_revisions.rs` | `record_revision`, `pull_page`, scoped entity reads, comment persistence, `bump_sync_epoch` |
| `backend/src/models/sync.rs` | Explicit contract response types; quiz metadata has no question or answer-key fields |
| `backend/tests/sync.rs` | Real HTTP requests against temporary SQLite databases; visibility, paging, privacy, rollback and retry checks |

Pull reads Hub identity, class membership, ledger and entities in one SQLite read transaction. The cursor is the last scanned `sync_revisions.seq`, regardless of timestamps or record visibility. It scans at most 500 ordinary revisions per page and looks ahead one revision to set `has_more`. Invisible revisions still advance the cursor. Selected entities are deduplicated and loaded in their current state, including the latest delete/recreation decision, rather than replaying stale versions.

Learners see only ACTIVE classes and their own enrollment, submissions, attempts and private threads. Teachers see classes they own or co-teach. A removed learner still gets their enrollment; a removed co-teacher still gets the class row. Public profiles exclude the Hub admin, PIN data and other people's identifiers, except a teacher's own roster. File records contain public download URLs instead of disk paths. Quiz bodies and keys are never selected; draft metadata and held scores stay hidden from learners. Pull receipts include `quiz_id` and `student_id` from #218.

When a page reaches a visibility revision addressed to the caller, it also loads that class's older revisions at or before the requested cursor. Those records are delivered in their current visible state. This snapshot is included with the visibility revision because the scalar cursor cannot page backwards. A newly visible class can therefore exceed the ordinary 500-revision page bound. Clients apply the response and cursor in one local transaction. A later page does not repeat the snapshot unless another visibility change occurs.

Push is learner-only. Each stream or private comment has its own `BEGIN IMMEDIATE` transaction: check current enrollment and row ownership, store the comment, record its revision, commit, then collect its receipt. A row cannot move to a different parent or overwrite another author's row. An identical retry returns `SYNCED` without another write. Expected validation failures return individual `REJECTED` receipts (`BAD_REQUEST`, `FORBIDDEN` or `COMMENTS_DISABLED`). An unexpected database failure returns HTTP 500; the failing item's changes roll back and earlier committed items survive. The client retries the whole request using the same IDs.

Quiz attempts in push are graded first, one receipt each, through the quiz submit path (step 4.2, [`quiz_grading.md`](./quiz_grading.md)). Homework files use the multipart submission route.

## Sync-safe values (bug #290)
The desktop app checks every row it pulls (`desktop/src-tauri/src/sync.rs` `valid`). If one row fails, it refuses the whole page and keeps its cursor, so it fails the same way on every retry, and everyone who pulls that row stops syncing. So the Hub never stores a value the desktop refuses:

| Value | Rule (the desktop's) | Where the Hub enforces it |
|---|---|---|
| An id a client made: stream comment, private comment, homework `submission_id` | A hyphenated UUID in any letter case, not the nil UUID (`models::sync::is_client_uuid`) | Push: a `REJECTED` `BAD_REQUEST` receipt for that item alone. Homework: 400 "submission_id must be a UUID" |
| A pushed comment's `created_at` | 0 to 2^53−1 (`MAX_SAFE_INT`) | Push: `REJECTED` `BAD_REQUEST` |
| Text a client pulls | At most 1,000,000 bytes, counted in UTF-8 (`MAX_TEXT_BYTES`) | Push: `REJECTED` `BAD_REQUEST`. REST: the `JsonBody` text readers, class create and the admin's teacher form answer 400 "`<field>` is too long" |

- Ids are stored as sent, letter case included, so the receipt and the client's own row match.
- Upload form text fields (a material's title, a homework form) are already capped at 4 KB by `read_form`.
- Numbers on REST (due dates, points, scores, order) were capped in #54, #122 and #61.
- `tests/sync_safe_values.rs` covers both push kinds, the homework id and every REST route that stores pulled text.

## Reset and offline behavior

Pull reads `hub_meta` from the current transaction, so it observes an epoch change without a process restart. A supplied Hub ID or epoch mismatch returns `reset: true` and starts from cursor zero. The client preserves queued/rejected work when rebuilding its mirror.

`bump_sync_epoch` is the shared hook for restore and tombstone pruning: call it inside the same transaction as that operation. Restore remains owned by #21. Tombstone retention is still unspecified, so this issue adds no pruning schedule and deletes no ledger history.

There are no outbound network calls. If a client loses the Hub, it retains its cursor and queued work. A lost push response is recovered by retrying the same IDs; a lost pull response is recovered by requesting the same cursor. No transaction spans network or file operations. This backend issue adds no user interface; clients translate status/error codes through their English dictionaries.

## How to test (no programming required)

Prerequisites: the project is downloaded and Rust is installed. Tests create disposable databases and do not change a school's data.

1. Open `server/backend` in File Explorer. Type `powershell` in its address bar and press Enter.
2. Paste `cargo test --test sync -- --nocapture` and press Enter.
3. Wait for the final `test result: ok` line. Every listed test must say `ok`. The `reconnect pull` lines report record counts and elapsed time; each page must take less than one second on this machine.
4. Run `cargo check --all-targets`, then `cargo test`. Both must finish without errors.
5. For a physical offline check, repeat step 2 with the router's WAN cable unplugged while the PC stays connected to the classroom network. Record the output and the network setup for the PR. After the first build, the cached test binary can also be run directly if Cargo needs an uncached development dependency.

The automated walkthrough creates a class, joins and approves learners, adds a co-teacher, pulls older lessons once, hides private work from classmates, and simulates a database failure during push. Its retry verifies earlier saved comments remain intact. It also tests a backward clock, a write that commits after a pull, tombstones, epoch reset and three pages of 1,200 new records.

## Acceptance evidence

| Criterion | Test |
|---|---|
| Same-millisecond, backward-clock and late-commit writes are not missed | `cursor_never_misses_same_millisecond_backwards_clock_or_late_commit` |
| Reconnect pull under one second; 1,200 revisions in three pages | `reconnect_pulls_1200_revisions_in_three_pages_without_gaps` |
| Failure leaves no half-written item; earlier items return SYNCED on retry | `push_failure_keeps_earlier_items_and_retry_does_not_duplicate_them` |
| Approved learner and added co-teacher receive older class records once | `newly_visible_class_arrives_with_older_rows_for_learner_and_co_teacher_once` |
| Identity mismatch and epoch changes reset the cursor | `first_pull_reset_and_epoch_use_the_committed_database_identity` |
| Private scopes, public profiles, metadata-only quizzes, held scores and no secret fields | `pull_redacts_secrets_and_scopes_every_supported_entity`, `push_validation_ownership_and_quiz_attempts_stay_separate` |
| Tombstones and invisible pages preserve cursor progress | `tombstones_latest_state_and_invisible_pages_advance_the_sequence` |

## Verification recorded on 2026-11-03

On the development Windows PC, after bringing in merged contract-change #218:

| Command | Result |
|---|---|
| `cargo check --all-targets` in `server/backend` | Exit 0, `Finished dev profile` |
| `cargo test` in `server/backend` | Exit 0; 56 unit tests and 83 integration tests passed (139 total), no failures |
| `cargo test --test sync -- --nocapture` | Exit 0; all 9 sync integration tests passed |
| `python -m unittest discover tests` from the root | Exit 0; 69 tests passed against the mock and contracts |
| `python scripts/verify_invariants.py` from the root | Exit 0; offline invariants and localization passed |
| `git diff --check` | Exit 0 |

The `py` launcher could not locate Python in the sandbox; Python commands used the installed `Python313/python.exe` directly with `PYTHONUTF8=1`. Rust commands printed a rustup path-canonicalization warning for the user directory, but no Rust compilation warnings or errors remained.

Targeted sync output (the full Rust suite subsequently passed the same nine tests):

```text
reconnect pull: 500 records in 388.5067ms
reconnect pull: 500 records in 304.8861ms
reconnect pull: 200 records in 149.4606ms
test result: ok. 9 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out
```

Shared suite and guardrail output:

```text
Ran 69 tests in 25.485s
OK
All L.A.R.A offline invariants and localization rules PASSED.
```

Localhost tests do not establish physical WAN isolation, classroom-router behavior or 40-device performance; record those separately on the actual Hub. The shared Python suite runs against the unmodified mock; topics, materials, assignment and quiz mutation routes on the real Hub remain later issues, so this feature's Rust tests seed those rows directly with scoped revisions. Client UI behavior with the Hub stopped still needs a physical client check; this server test suite verifies SQLite transactions, idempotent retries, and retained sessions after a Hub restart.
