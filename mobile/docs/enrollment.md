# Class enrollment - MOBILE 2.3 with the approved 2.4 REST bridge

The merged 2.3 work supplies grouped code entry, durable request notices, learner navigation and the optional canonical producer/session boundaries. Mobile 2.4 now adds independent REST-backed access so approval does not have to wait for 2.5. Earlier 2.3-only evidence remains under `evidence/50/`; current standalone approval evidence is under `evidence/51/rest-independent/`.

`AuthHost` opens My classes for learners. The grouped field preserves IME edits unchanged and applies XXX-XXX grouping only as a visual transformation; submission sends the canonical six-character code. Errors and states are localized in English and Filipino.

`EnrollmentViewModel` refreshes the authenticated own classroom list on foreground/HELLO and matching approval events. `ClassroomSnapshot` carries the source Hub address. The 2.4 callback saves class metadata and membership status through `RoomLearnerClassAccess`, with current-account and source-address checks. Screens observe the resulting Room projection, not the network response.

`RoomEnrollmentNoticeRepository` separately persists request/denial metadata under `enrollment_notice:<user-id>:<classroom-id>`. Notices do not create enrollments or grant access. Restrictive notices block opening, including during startup until notice loading finishes. ACTIVE events request an authoritative refresh.

`LearnerClass` can represent a canonical enrollment or the learner's REST-verified membership status without an ID. Only verified ACTIVE access without a restrictive notice enables Open class. No synthetic enrollment ID is used. A completed own REST snapshot supersedes the list projection, including an empty list after lost access. The REST snapshot is scoped to user and Hub address and remains readable offline.

Open class enters the existing class shell with the selected class name; All classes and Android Back return to My classes. Stream/Classwork content remains with its own feature steps. A fresh installation can now join, wait, be approved and open the class shell without delta sync. The canonical 2.5 producer remains optional at `EnrollmentRepository.refresh` and `EnrollmentSessionSource`; it is not implemented in 2.4.

Test an unknown code, valid K7M-4QX, pending offline relaunch, matching approval and class opening, and approved offline relaunch. Room tests cover account/Hub boundaries, no fabricated enrollment rows, authoritative empty lists and transaction rollback. See `teacher-approvals.md` for the REST contract and current evidence.
