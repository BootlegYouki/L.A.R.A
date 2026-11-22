# Class discussion and homework

Stream posts use a collapsed comment entry. Opening it reads the selected account's SQLite thread; public comments and learner private replies queue with a stable UUID. Teacher private replies first check the cached thread scope and then use authenticated REST while the Hub is reachable. Switching account, assignment, or private recipient resets the draft and aborts a pending request; late responses cannot update the next thread.

`comments.rs` enforces active enrollment, visible class membership, archive state and disabled public comments before queuing. A teacher cannot queue a private reply through IPC. Migration 005 adds the contract's nullable announcement author UUID. Names are resolved only from that UUID; legacy posts do not claim the current teacher as their author.

Announcement and assignment attachments are managed in create/edit dialogs. A created parent is retained if file staging fails, so retrying queues remaining files instead of posting a duplicate. Stream cards do not expose a separate Add attachment action.

`homework.rs` stages selected photos/PDF/DOCX files in bounded chunks under the selected native account directory. Finish validates file headers and writes `assignment_submissions` with `QUEUED_FOR_SYNC`. The original UUID and Turn in time survive retries and restart. Queued and graded work cannot be overwritten. A foreground App worker flushes learner work on reconnect; failed or malformed responses leave it queued, successful scoped receipts mark it synced, and `LATE_SUBMISSION`/`ALREADY_GRADED` and explicit `FORBIDDEN`/`NOT_FOUND` become visible permanent rejections. Original local files remain available after acknowledgement. Native app data uses the configured Tauri account root, not the repository directory.

Verification: desktop unit suite, production build, and lint pass. Native tests cover thread scope/permissions, queued idempotency, file header checks and homework ownership/archives; run `cargo test --lib` from `src-tauri`. Native UI and real-Hub offline/reconnect verification are separate from unit tests and are not implied by them.

Teacher grading uses the published PATCH submission route. Saving a grade makes the score and feedback visible to the learner immediately. The Gradebook reads the locally synchronized scores; class record export remains a Hub dependency and has no active desktop control.
