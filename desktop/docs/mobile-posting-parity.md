# Mobile posting parity on Desktop

Desktop keeps its existing green theme, dialogs, tabs and cards while adopting the supported Mobile announcement and Classwork behavior. Only `desktop/` changes; no Server, Mobile or contract edits.

## Reference and scope

Compared Mobile `PostAnnouncement.kt`, `teacher/classwork/TeacherClasswork.kt`, its ViewModel/repository and `data/sync/MaterialUploadWorker.kt` with the current contracts. The announcement title/content/comments toggle already matched; Desktop now also links material uploads to existing posts. Classwork adds assignment/material/topic editing and confirmed deletion, topic ordering, nullable topic selection, assignment multiple attachments and material targets (standalone, assignment or announcement). Assignment-linked files appear under their assignment; post files appear under their announcement. Completed/cancelled upload jobs leave the status panel to keep the established layout compact.

Mobile and the Hub permit teacher/co-teacher announcement creation, not learner creation. Mobile has no announcement edit/delete composer, scheduled recipients or standalone Question/Reuse authoring to copy. Existing Desktop quiz creation remains; quiz topics are not added because the quiz sync schema does not carry them.

## Key files and data flow

- `ClassPage.tsx` reads its account/Hub SQLite mirror, opens the existing-style composers/editor and refreshes through normal sync after confirmed writes. `ClassworkEditDialog.tsx` confirms deletions; `classworkMutations.ts` validates teacher/class ownership and exact PATCH/DELETE receipts. DELETE success is the contract's `{success:true}`.
- `ClassAuthoringDialog.tsx` retains the returned assignment ID after a partial attachment failure. A retry patches that parent and skips File objects already committed to staging. `classworkCache.ts` waits up to 10 seconds for the first parent sync before native upload validation; a timeout retains the saved parent and form.
- `uploadQueue.ts` copies a File in at most 64 KiB chunks into native account-private staging. `uploads.rs` validates selected account, active teacher/co-teacher class, optional same-class parent/topic and the 250 MiB limit. It streams native multipart POSTs with progress and a 15-minute total deadline. No original source path or bearer token is persisted in a job. Tokens come from the existing native account credential context at upload start.
- Private job metadata/source files live beneath the app's existing OS account data directory, separated by Hub/account context hash and job UUID. Build/test files remain on G:. A per-job OS file lease prevents two app instances from modifying or starting the same live job.
- `UploadJobs.tsx` polls only the selected owner/class. A READY source survives restart; abandoned partial staging is cancelled/cleaned. Interrupted uploads become UNCERTAIN and keep their source. Success validates the receipt then removes the staged source; later recovery retries cleanup if an initial unlink failed. Failed jobs have Retry/Cancel. Upload callbacks trigger sync; queued files are never labelled posted before success.

## Verification — Windows, 2026-11-21

Final frontend: `npm test` **152 passed**, `npm run lint` passed, `npm run build` passed. Native `cargo test --offline --locked --lib -q`: **36 passed, 3 pre-existing opt-in tests ignored**. Embedded release QA binary built with `tauri/custom-protocol` and loaded `http://tauri.localhost/`, independent of the Vite dev server. Shared `python -m unittest discover tests`: **73 passed**; `scripts/verify_invariants.py` passed. The first shared-suite run lacked PyYAML on its process path; rerun used the existing G: dependency directory and passed.

Native app against the running real Rust Hub, isolated synthetic classroom `532b8c57-239f-4db6-b590-83a4bdc01072`:

| Check | Result |
|---|---|
| Announcement POST and linked PDF upload; sync displays attachment | PASS |
| Topic create/rename; material title/topic edit | PASS |
| Assignment with valid PDF + zero-byte file; retry failed remainder | PASS: one parent, first PDF once, two final attachments |
| Assignment title/points/topic edit | PASS |
| Topic deletion retains its work under No topic | PASS |
| Assignment/material confirmed deletion; sync removes them | PASS |
| Two native processes, one live staging lease | PASS: second cannot cancel/start the source or recover it as abandoned |
| Full process restart with READY and incomplete staging jobs | PASS: READY survives; incomplete job cancelled and payload removed |
| UI Retry on restarted READY job | PASS: real Hub POST, sync and source cleanup |
| Stop real Hub; cached classwork and offline staging | PASS: cached content remains; metadata controls disabled; source retained with uncertain status |
| Restore Hub; confirmed manual retry | PASS: reconnects and retained source uploads |
| Learner session and native isolation checks | PASS: no teacher controls/job panel; teacher-job reads/retries and learner staging rejected |
| Six exact owner/class job records inspected | PASS: no tokens/original source paths; successful/abandoned payloads removed |
| Final embedded UI | PASS: completed job history hidden; composers retain Desktop design |

The first smoke run targeted another synthetic classroom by mistake. Only its precisely identified synthetic post, material and unshared topic were removed, after checking no comments/other attachments/foreign work referenced them. Remaining tests asserted the dedicated classroom identity before mutations. Real-Hub deletion testing caught the incorrect `ok` receipt check; it was changed to `success` and the complete frontend suite plus native deletion flow were rerun.

Screenshots: [assignment composer](screenshots/mobile-parity-assignment.png), [announcement composer](screenshots/mobile-parity-announcement.png), [final classwork](screenshots/mobile-parity-classwork.png), [offline staging](screenshots/mobile-parity-offline.png), [learner view](screenshots/mobile-parity-learner.png).

## Limits and integration

Uploads are durable private copies, not resumable HTTP transfers. The Hub has no idempotency key: an uncertain accepted POST can duplicate a file if retried. Desktop requires a warning and explicit second confirmation instead of Mobile's automatic retry. Closing the app interrupts in-flight uploads; reopening restores a manual recovery action. Metadata creation/edits require the reachable Hub, while files may be staged offline. Assignment creation itself has no exactly-once guarantee if its POST response is lost before the ID is received.

No Linux/macOS run, physical separate-PC/WAN-unplugged test, 250 MiB hardware throughput test or installer upgrade/publication was performed here. This functional work remains local on `feat/desktop-classroom-authoring`; it is not merged or published during the announcement freeze without lead scheduling.
