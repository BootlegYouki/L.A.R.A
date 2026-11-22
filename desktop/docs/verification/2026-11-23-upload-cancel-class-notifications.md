# Upload cancellation and combined class notifications

Verified on 23 November 2026 against the Desktop-only branch based on staging 056e793.

## Behavior

- Cancelling a local upload hides its task and persists that dismissal. An in-flight request retains an uncertain remote outcome internally. Old uncertain tasks have a Dismiss action; dismissing never deletes a material already accepted by the Hub.
- Dismissed tasks cannot be retried, including after restart. Worker persistence preserves the dismissal, and stale frontend polling cannot restore the row. Only the task-owned staged payload is removed.
- Teacher sidebar class badges sum pending join requests and unread learner private comments. People and Classwork keep their separate counts. Opening a learner thread clears its unread contribution, while pending approvals remain until handled.
- Teacher notification polling runs while on Home as well as inside a class. Counts remain scoped to the signed-in teacher and visible, non-archived classes.

## Automated verification

- Frontend: 224 tests across 47 files passed.
- Native: 62 passed, 3 opt-in tests ignored.
- Shared compatibility checks: 92 passed; shared files were not modified.
- ESLint, TypeScript/Vite production build, native release QA build, and offline invariant guard passed. Vite retained its existing large-chunk advisory.
- Independent code review found no critical or important actionable findings.

## Native runtime verification

Used an isolated WebView profile and synthetic accounts on the local real QA Hub. The user's running application and remote Hub were not changed.

1. While the teacher remained on Home, one pending approval plus two learner private comments produced a sidebar count of 3. Inside the class, People showed 1 and Classwork showed 2.
2. Opening the learner thread reduced the sidebar to 1. Accepting the pending learner removed that contribution. A later private comment appeared on Home without manual refresh.
3. A transport gate held a real upload request before forwarding. Cancelling cleared its row, persisted dismissal, and blocked retry.
4. A second real upload was accepted by the Hub, but its response was deliberately dropped. Dismiss cleared the uncertain notice. The accepted PDF remained downloadable and byte-identical to the original. Exactly two upload POSTs were observed across both cases, with no duplicate retries.
5. Cold restarting the native QA app preserved both dismissals and blocked retries. Neither task reappeared. The accepted PDF and unread-only sidebar/Classwork counts remained intact.

Original PDF SHA-256: `7951893ee04b9662ba914c65c119c6ae6d0d565db41a8a59d5270d62fa0d8103`.

This verifies an unbundled QA executable. No installer was published or installed for this change.
