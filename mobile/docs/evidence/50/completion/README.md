# MOBILE 2.3 completion verification

## Independent PR source

The PR extends the original `50-mobile-2.3-class-enrollment` branch. It contains no Mobile 2.4 teacher implementation, WorkManager, SyncRepository, sync transport or 2.5 cursor/reset code.

Final `test lint assembleDebug assembleDebugAndroidTest`: passed in 1m 34s. Debug and release each ran 69 tests with zero failures and zero skipped. All 66 shared contract/mock-Hub tests passed. The invariant guard is checked on the final committed diff.

New coverage includes durable pending/removal notices after reopening Room, account/Hub scoping, no fabricated enrollment rows, waiting for notice loading before enabling access, foreign-learner event filtering, approval after canonical arrival, removal/restart denial, stopping event collection, friendly wrong-code state and grouped-input offset regression cases.

The phone exposed a real input bug: editing the formatted field could reorder/drop characters around the hyphen. The final field preserves the IME text unchanged and formats only its display. Both raw and grouped codes submit the same canonical value. Action/back controls use the existing dark-green token for readable text contrast; primary targets remain 56dp and secondary targets at least 52dp.

## Separate integration verification

A detached test copy starts from Mobile 2.5 `37894dc` and applies the same enrollment feature files plus the documented producer/shared-session adapters. It is not the PR source and no later sprint implementation is brought into the PR. Full debug/release integration suites each discovered 104 tests, with zero failures and the two opt-in JVM-only mock tests skipped in that run. The actual mock-Hub flow was tested through the phone UI instead.

Adapter details: inject `sync::pull` into RoomEnrollmentRepository; let the existing shared sync session implement EnrollmentSessionSource; pass it as sharedSession from AuthHost. These supply canonical Room data and avoid a duplicate socket. The feature-only Room adapter explicitly returns false when no producer is supplied.

## Physical phone

Device: Xiaomi 2311DRK48G. Installed only the isolated `org.lara.app.sprint23fixed` variant (LARA 2.3 fixed). Original `org.lara.app` and saved work were not replaced, cleared or uninstalled. The mock seed learner and class code from the issue were used; no real learner data is in these screenshots.

Verified on the actual visible UI:

1. Learner sign-in opens My classes and its Join a class action.
2. Unknown code ZZZ-ZZZ keeps the dialog open with the friendly English error.
3. Typing K7M-4QX preserves the exact grouped code; submit closes the dialog and saves the PENDING card.
4. With only the USB Hub routes removed, force-stop/cold launch still shows the pending class. Join is disabled, and no blocking dialog/loader appears.
5. Restore the Hub route and approve from the teacher session. Without manual refresh the card becomes Ready to open and exposes Open class after the 2.5 producer saves the ACTIVE enrollment.
6. Open class enters the selected Science 4 learner shell; All classes returns to the list.
7. Remove the Hub routes again and cold-launch. The approved class stays available and opens offline.
8. Per-app Filipino locale (`fil`, with the repository values-tl resources) renders the class list, join dialog and friendly invalid-code error in Filipino. The app locale override and USB routes were restored afterward.

Some flow screenshots precede the final token-only contrast adjustment; invalid-code-en, invalid-code-fil and approved-final show the final controls. They demonstrate enrollment behavior, not future Stream/Classwork content, which remains the existing shell.

## Dependency and limits

The only canonical enrollment producer is Mobile 2.5. POST join, classroom list and approval events do not provide an enrollment ID. The feature persists request/status notices without fabricating membership rows; until an authoritative ACTIVE Room row arrives, an approval cannot open a class. The PR must state this dependency explicitly. It can be reviewed and merged as the completed enrollment feature with that data dependency, without claiming a standalone sync implementation.

Phone evidence uses the PC mock through USB reverse, so no external service is in the tested data path. This does not certify a physical classroom router with its WAN unplugged, every budget-phone model, exhaustive accessibility scenarios or the future producer implementation. Existing kapt/FK-index warnings remain; no shared schema or new index is introduced to silence them.

No 2.4 PR is opened. Only the 2.3 PR is requested; after it is created, wait for the Lead to review/merge.
