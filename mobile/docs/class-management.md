# Teacher class management — MOBILE 2.7 (#126)

Implementation in progress, based on staging `a95a076`, after mobile 2.1–2.6 merged.

`data/remote/TeacherClassApi.kt` implements the existing create, rename/archive, remove-learner and add/remove-co-teacher contracts. It uses `EnrollmentApi.connection` to reject a missing, expired, wrong-account or wrong-Hub session. `HubClient.manageClass` restricts methods and paths, validates path identifiers, sends bearer authentication and refuses redirects. No command is queued offline and no local optimistic write is made.

Successful commands must be followed by an authoritative Room/cache refresh using the existing sync/roster pipeline. Server authorization remains authoritative; upcoming screen controls must additionally hide owner-only actions for co-teachers and disable Hub-required actions offline.

## Screens and state

The teacher header action area opens `ClassManagementEntry`, a full-screen management surface under the authenticated session ViewModel owner. `ClassManagementViewModel` observes the existing Room class and roster repositories. Class selection and People remain usable offline. People lists teachers before learners and shows each saved enrollment status; teacher identifiers are the contract's user IDs, not invented account records.

The owner sees a Class settings tab with rename, archive and add-co-teacher actions. Only the owner sees co-teacher removal. Both teachers can remove ACTIVE learners. Archived classes are read-only for everyone. Every destructive action has a consequence confirmation; removal states that previous scores are retained. Code copy uses the displayed `XXX-XXX` code and the local Android clipboard.

Changes use the existing per-account sync mutex for authoritative REST cache refresh. `SavedRosterRefresher.refreshIfActive` skips roster fetching after archive while retaining its saved data. A command that succeeds but cannot refresh reports that separately and asks the user to refresh before repeating it. Failed or uncertain writes are not automatically retried. Hub errors and timeouts leave local data intact; an expired session returns to sign-in.

All new strings are bundled in English and Filipino. Screen/dialog resources use `PopupLanguage`, dialog bodies scroll for large text, primary actions are at least 56dp and other actions at least 52dp. Loading uses the existing skeleton; empty and Hub-unreachable states keep the screen usable.

## How to test

1. Start the mock Hub and sign in as `T-0001`, PIN `1234`. Open **Manage classes** in the teacher header action area.
2. Create a class with a name and section. Select it, copy its large code, and use that code to request enrollment from a synthetic learner account. Approve the learner through the existing pending-approval sheet.
3. Open People: teachers appear first, followed by saved learners and statuses. Remove an ACTIVE learner only after reading the confirmation. Their next sync must remove class access while the Hub retains previous scores.
4. As owner, open Class settings, rename the class and add a provisioned teacher by teacher ID. Sign in as that co-teacher: People is available but no Class settings or manage-teacher controls appear. Removing an ACTIVE learner remains permitted.
5. Confirm archive as owner: the class stays saved but all editing controls disappear. Grades and cached/queued work are retained.
6. Stop the Hub and reopen management: saved classes/people remain readable and writes are disabled with an explanation. Repeat in Filipino and at 200% text size.

## Validation status

Transport tests cover route/method restrictions, bearer authentication, redirects, path rejection, typed create/add/remove payloads and wrong-account rejection before sending. Policy tests cover owner/co-teacher/outsider and archive restrictions. The mock integration test exercises create -> learner join -> approval -> learner sync -> removal -> next sync.

The full mock-enabled Debug suite ran 140 tests: 139 passed, one failed. `TeacherClassMockTest` proves that removal succeeds and the learner's REST class list loses the class, but their next delta pull retains the cached ACTIVE enrollment. The mock's `sync_pull` filters ledger entries by currently visible classes before returning the REMOVED enrollment. A learner who is no longer ACTIVE cannot receive that revision. This is a Lead-owned shared mock/sync visibility blocker; no client status is invented, cached work is not deleted and the regression remains enabled. MOBILE 2.7 is not complete until the learner loses access after sync.

Shared source checks: 66 tests passed, offline/localization invariants passed. Build/lint evidence and the failing regression are recorded under `evidence/126/`. No new phone UI evidence is claimed yet. Android PATCH rename/archive, clipboard, actual co-teacher navigation, large-text dialogs and visual checks still require device/emulator verification. Real Rust Hub, physical WAN-disconnected router and budget-phone battery/heap certification remain integration/field follow-ups. No 2.7 PR has been opened.
