# Class enrollment - MOBILE 2.3 (#50)

## Feature scope and branch

This issue-only branch extends `3471861` and contains learner enrollment work only. Mobile 2.4 teacher controls and Mobile 2.5 sync are not included in its PR to staging.

A learner now enters My classes after sign-in. Join class opens the grouped XXX-XXX field; requests send the canonical six-character code. Invalid local or Hub codes show the English/Filipino message. PENDING, ACTIVE, REJECTED and REMOVED notices are saved in Room through the existing key/value table. A request notice never creates a made-up enrollment ID.

## Data flow

`AuthHost` composes `LearnerEnrollmentNavigation` for learners only. `EnrollmentConnectionViewModel` supplies the foreground HELLO/backoff session in cached-only builds; an available shared session can be injected instead. `EnrollmentViewModel` watches canonical Room classes and durable request notices, reconciles GET classroom statuses and requests the enrollment producer refresh. It listens while a class is open as well as on the class list.

`RoomEnrollmentNoticeRepository` stores `enrollment_notice:<user-id>:<classroom-id>` values. Notices carry the observed Hub identity when available and are hidden when that identity no longer matches. An initial receipt received before the first canonical Hub snapshot can be unbound; it still grants no access. A later status refresh binds it. Records contain class/status metadata, never tokens or LRNs. Account checks protect writes.

Only an ACTIVE canonical enrollment with no restrictive request notice enables Open class. Access remains closed until notice loading completes, so a stale ACTIVE cache cannot bypass a saved denial during startup. REJECTED/REMOVED notices disable access and return an already-open class to My classes. A live ACTIVE event requests authoritative refresh instead of granting access by itself.

Open class enters the existing learner navigation shell with the selected class name. All classes and Android Back return to the class list. Stream/Classwork content belongs to its later sprint and remains the existing shell here; it is not new enrollment behavior.

## Explicit Mobile 2.5 dependency

The join/list/event responses omit canonical enrollment IDs. This PR deliberately does not implement sync or fabricate those IDs. `EnrollmentRepository.refresh(userId)` is the producer boundary: the cached Room adapter returns false without a producer. Mobile 2.5 supplies its pull callback to `RoomEnrollmentRepository` and can supply the shared socket session.

Without 2.5 data, joining and saved pending/status notices work, but an approval remains waiting for its saved class data and cannot open a class. When 2.5 writes the authoritative ACTIVE enrollment, the Room observer enables access immediately. This is the dependency to state in the PR; it is not a claim that this branch alone produces canonical membership rows.

## Validation and how to test

Automated checks cover canonical code requests, friendly invalid-code state, notice persistence after database reopening, account/Hub scoping, no invented enrollments, notice-loading access guards, approval after canonical arrival, foreign-user event filtering, removal persistence and stopping event handling. Full command outputs and phone results are in `evidence/50/`.

1. Start the mock Hub and sign in as the join-code learner using the seed account from the root guide. My classes must be visible, including Join class.
2. Try a valid-format unknown code. The dialog stays open with a friendly invalid-code message. Enter K7M-4QX and submit. A PENDING card appears.
3. Stop the Hub and restart the app. The pending card stays visible and the join action is disabled with an offline message; no blocking loader is shown.
4. With the 2.5 producer integrated, approve from the teacher session. The card becomes available without manual refresh. Open class enters the selected class shell.
5. Stop the Hub again and restart. The approved class remains available from Room and still opens. Check Filipino strings using the app language override.

The isolated feature branch and the separate 2.5-backed phone build are reported distinctly in the evidence. No shared contract changes are made, and no 2.4 PR is opened as part of this work.

The grouped field now preserves the IME edit unchanged and applies grouping only as a visual transformation. This fixes the phone-discovered character reordering/dropping when typing the display hyphen. Class-code normalization happens at submission. Dialogs use the 24dp token, and enrollment action/back labels use the documented dark-green token for text contrast.

For the 2.5 producer, construct RoomEnrollmentRepository with sync::pull as its refresh callback. Its shared session can implement EnrollmentSessionSource and be passed as sharedSession to LearnerEnrollmentNavigation; that prevents a second foreground socket. These adapters belong to the dependent integration, not this issue-only PR.
