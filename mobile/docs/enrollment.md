# Class enrollment - MOBILE 2.3/2.4 integrated with 2.5 sync

The merged 2.3 work supplies grouped code entry, durable request notices and learner navigation. Mobile 2.4 used a temporary REST-backed access cache so it could merge independently. Following the Lead's #52 carry-over comment, 2.5 removes that cache and reads learner access from canonical synced enrollments. Earlier standalone evidence remains under `evidence/50/` and `evidence/51/rest-independent/`; current integration evidence is under `evidence/52/latest-rest-24/`.

`AuthHost` opens My classes for learners. The grouped field preserves IME edits unchanged and applies XXX-XXX grouping only as a visual transformation; submission sends the canonical six-character code. Errors and states are localized in English.

`EnrollmentViewModel` refreshes own request notices on foreground/HELLO and matching approval events, then calls `EnrollmentRepository.refresh` to pull canonical data. The REST fetch/notice save and sync share a mutex. Screens observe Room enrollments, never the REST class response as an access grant. Sync updates the own request notice in the same transaction as the enrollment and cursor so a stale PENDING notice cannot hide an approval.

`RoomEnrollmentNoticeRepository` separately persists request/denial metadata under `enrollment_notice:<user-id>:<classroom-id>`. Notices do not create enrollments or grant access. Restrictive notices block opening, including during startup until notice loading finishes. ACTIVE events request an authoritative refresh.

`LearnerClass` requires a canonical enrollment. Only its ACTIVE status without a restrictive notice enables Open class. No synthetic enrollment ID is used. `LaraDatabase` clears every legacy `rest_class_access:<user>` metadata key on open, including upgrade from 2.4, without changing schema or deleting enrollments, notices, cursors or queued work. There is no production writer or reader for that key in 2.5.

Open class enters the existing class shell with the selected class name; All classes and Android Back return to My classes. Stream/Classwork content remains with its own feature steps. A fresh installation joins, waits, receives canonical data after approval, and opens the class shell. Both role controls share `SyncSessionViewModel` through `EnrollmentSessionSource`.

Test an unknown code, valid K7M-4QX, pending offline relaunch, matching approval and class opening, and approved offline relaunch. Shared Room tests cover legacy-key cleanup on reopen, queue retention, canonical-only grants, notice reconciliation and transaction rollback. See `teacher-approvals.md` for the teacher REST bootstrap.

## English-only UI (#224)

UI strings are bundled in `values/strings.xml`; there is no language selector or locale override. Android falls back to English on Filipino system locales. Historical evidence may show the retired Filipino UI. Learner-entered chat language data is unchanged.
