# Teacher class management — MOBILE 2.7 (#126)

Rebased on staging `30d19a4`, including shared fixes #210 (removed learner) and #211 (removed co-teacher). The removal-sync regression now passes; the branch is being prepared for final review and a phone pass on the rebased build.

`data/remote/TeacherClassApi.kt` implements the existing create, rename/archive, remove-learner and add/remove-co-teacher contracts. It uses `EnrollmentApi.connection` to reject a missing, expired, wrong-account or wrong-Hub session. `HubClient.manageClass` restricts methods and paths, validates path identifiers, sends bearer authentication and refuses redirects. No command is queued offline and no local optimistic write is made.

Successful commands must be followed by an authoritative Room/cache refresh using the existing sync/roster pipeline. Server authorization remains authoritative; upcoming screen controls must additionally hide owner-only actions for co-teachers and disable Hub-required actions offline.

## Screens and state

The teacher header action area opens `ClassManagementEntry`, a full-screen management surface under the authenticated session ViewModel owner. `ClassManagementViewModel` observes the existing Room class and roster repositories. Class selection and People remain usable offline. People lists teachers before learners and shows each saved enrollment status; teacher identifiers are the contract's user IDs, not invented account records.

The owner sees a Class settings tab with rename, archive and add-co-teacher actions. Only the owner sees co-teacher removal. Both teachers can remove ACTIVE learners. Archived classes are read-only for everyone. Management uses `AppContainer.managedClasses` with `RoomClassroomRepository(includeArchived = true)` so archived rows remain readable; the normal active-class repository keeps its existing behavior. Every destructive action has a consequence confirmation; removal states that previous scores are retained. Code copy uses the displayed `XXX-XXX` code and the local Android clipboard.

Changes use the existing per-account sync mutex for authoritative REST cache refresh, followed by a delta pull for public profile updates. Teacher names come from an account-scoped Room projection of IDs and display names; it exposes no PIN or LRN fields. `SavedRosterRefresher.refreshIfActive` skips roster fetching after archive while retaining its saved data. A command that succeeds but cannot refresh reports that separately and asks the user to refresh before repeating it. Failed or uncertain writes are not automatically retried. Hub errors and timeouts leave local data intact; an expired session returns to sign-in.

All new strings are bundled in English and Filipino. Screen/dialog resources use `PopupLanguage`, dialog bodies scroll for large text, primary actions are at least 56dp and other actions at least 52dp. Dialogs explicitly use the white Surface token, avoiding Material's default purple container tint. Loading uses the existing skeleton; empty and Hub-unreachable states keep the screen usable.

## Drive mockup adaptation

The teacher references in [VER 2. TEACHER SIDE](https://drive.google.com/drive/folders/1qiAbjnMx0KIoLf04xT0AyrDGuMxTnLlT), specifically **Pastel Education App Class Management Screens.png** and **Teacher Class Management Screens.png**, inform the management screen's presentation. Supported elements copied into Compose: rounded class cards, a highlighted class-code panel with copy action and approval guidance, grouped settings, initials avatars and People cards, rounded form fields, and a class-details review step before the create request. The review step does not contact the Hub until Create class is confirmed.

`ClassManagementCards.kt` supplies the reusable white cards, green subject tile and saved-person rows. Existing `LaraColors`, shapes, Nunito typography and bundled Phosphor icons remain authoritative. No remote assets, gradients or full mockup screenshots are used as app UI. The owl/landscape composites are not bundled as stretched screen backgrounds. Unsupported code regeneration, grade/subject/description fields, enrollment modes, visibility switches and delete-class actions are omitted. Existing owner/Hub/archive guards still apply. This is a supported-layout adaptation, not a pixel-identical copy of the concept artwork.

Initial UI build/lint and targeted regression output are saved in `evidence/126/ui-*.txt`. Subsequent physical-phone verification is recorded in `evidence/126/phone-checks.md` with screenshots, including Filipino at 200% text size and offline archived-class access. The removal-sync regression remains enabled and now passes against the updated shared mock.

## How to test

1. Start the mock Hub and sign in as `T-0001`, PIN `1234`. Open **Manage classes** in the teacher header action area.
2. Create a class with a name and section. Select it, copy its large code, and use that code to request enrollment from a synthetic learner account. Approve the learner through the existing pending-approval sheet.
3. Open People: teachers appear first, followed by saved learners and statuses. Remove an ACTIVE learner only after reading the confirmation. Their next sync must remove class access while the Hub retains previous scores.
4. As owner, open Class settings, rename the class and add a provisioned teacher by teacher ID. Sign in as that co-teacher: People is available but no Class settings or manage-teacher controls appear. Removing an ACTIVE learner remains permitted.
5. Confirm archive as owner: the class stays saved but all editing controls disappear. Grades and cached/queued work are retained.
6. Stop the Hub and reopen management: saved classes/people remain readable and writes are disabled with an explanation. Repeat in Filipino and at 200% text size.

## Validation status

Transport tests cover route/method restrictions, bearer authentication, redirects, path rejection, typed create/add/remove payloads and wrong-account rejection before sending. Policy tests cover owner/co-teacher/outsider and archive restrictions. The mock integration test exercises create -> learner join -> approval -> learner sync -> removal -> next sync.

The rebased full mock-enabled Debug and Release suites each ran 141 tests: all passed, zero skips. `TeacherClassMockTest` also passed in a separate targeted run. `ManagedClassArchiveTest` verifies archived management visibility/read-only permissions while the active list remains filtered. Shared fixes #210/#211 deliver removal rows after access is lost. Mobile also preserves a learner's own non-active enrollment as a notice when its classroom row is not accessible yet; this avoids inventing a parent row or violating Room foreign keys. The existing REST approval/reopen integration test covers the fresh PENDING case.

Shared source checks: 66 tests passed; offline/localization invariants passed. Current output is in `evidence/126/full-tests.txt`, `removal-regression.txt` and `shared-checks.txt`. Previous physical-phone checks remain documented in `phone-checks.md`; they covered create/review, Android PATCH rename/archive, clipboard, co-teacher controls, confirmations, EN/TL, 200% text and offline archived access in a separate package. A final phone pass on the rebased build is pending Lead review. Real Rust Hub, physical WAN-disconnected router and budget-device certification remain integration/field follow-ups. PR #209 is the review surface.
