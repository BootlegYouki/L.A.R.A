# Standalone Mobile 2.4 after contract #192

## Changes and isolation

The branch is updated from staging after #192 added required RosterEntry.enrollment_id. Teachers bootstrap classes using GET /api/classrooms, then cache profiles/enrollments using the roster's canonical IDs. No sync source, worker, scheduler, dependency or cursor producer was imported from 2.5.

A learner's own GET /api/classrooms already reports enrollment_status. That authenticated, account-and-Hub-bound response now supplies a saved class-access projection; it does not fabricate a learner enrollment ID. Pending notices and approval events alone cannot unlock access.

Both phone packages were new test installations: org.lara.app.sprint24restlearner and org.lara.app.sprint24restteacher. Their disposable synthetic data and the dedicated mock were reset once to repeat the exact seed flow with the teacher foregrounded. The regular org.lara.app, earlier sprint test apps, and their saved/queued work were untouched.

## Automated checks

- Full test/lint and both debug APKs passed. Final UI build: BUILD SUCCESSFUL in 1m 47s.
- Debug and release: 93 tests passed each, zero failures or skipped tests; the actual mock test was enabled.
- Shared contracts/mock tests: 66 passed (20.431 seconds). Offline/localization guardrail passed after the new empty-state strings.
- Connected Xiaomi 2311DRK48G: OK (3 tests), 15.708 seconds. One actual REST-to-Room/reopen/approval/rejection test and English plus Filipino-at-200%-text production approval UI cases.
- REST test starts with empty persistent test databases, gets the teacher class via REST, inserts a new pending enrollment using enrollment_id, checks matching server identity, reopens pending and approved caches, and asserts no sync cursor or fabricated learner enrollment row exists.

## Exact fresh phone flow

One physical phone ran two isolated role apps; the PC acted as a second learner client when sending the join request, allowing the teacher app to remain foregrounded for its event. This is not a two-physical-phone test.

1. Fresh learner app signed in as the seed learner ending 013. Its own class list was initially empty.
2. Fresh teacher app signed in as T-0001. Science 4 and pending count zero arrived from GET /api/classrooms and the REST roster before any join request.
3. The PC logged in as the same seed learner and submitted K7M-4QX. While the teacher app remained foregrounded, this exact learner's join event automatically opened the sheet showing Ana Reyes. The row's numeric seed LRN was inspected but its screenshot was not published.
4. The learner app observed its saved pending class through its own REST list. Removed only USB reverse ports 28080/28081 and cold-launched both apps. Learner pending status and teacher pending count one remained visible without blocking errors.
5. Restored the test forwards, opened the teacher's saved pending request and tapped Accept. The learner app became Ready to open through its own authenticated REST refresh, then opened Science 4.
6. Another offline cold launch retained the approved learner's access and opened the class shell. Stream content is a later feature, so the existing shell placeholder is not a claim to have downloaded posts.
7. Restored the test forwards. The fresh-flow route trace contains zero /api/sync/ calls. It records the class-list GET, roster GET, join POST and approve POST.

An earlier attempt was backgrounded during the event and was discarded rather than treated as a code failure; the controlled fresh run above is the evidence. Foreground connections intentionally stop when the user switches away.

## Limits

All accounts/data were synthetic. Committed screenshots contain class/status data, not tokens, PINs or numeric learner identifiers. The two apps differ from production only in application ID and displayed app label; no fixture database was inserted into the normal phone flow.

USB-local Hub-unreachable tests are not a physical classroom Wi-Fi test with WAN unplugged. Real Rust Hub integration, exhaustive screen-reader testing and certification on all 3–4 GB target phones remain unverified. The optional all-pending-list redesign is not implemented; the existing one-at-a-time sheet is retained.
