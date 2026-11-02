# Teacher class management — MOBILE 2.7 (#126)

Implementation in progress, based on staging `a95a076`, after mobile 2.1–2.6 merged.

`data/remote/TeacherClassApi.kt` implements the existing create, rename/archive, remove-learner and add/remove-co-teacher contracts. It uses `EnrollmentApi.connection` to reject a missing, expired, wrong-account or wrong-Hub session. `HubClient.manageClass` restricts methods and paths, validates path identifiers, sends bearer authentication and refuses redirects. No command is queued offline and no local optimistic write is made.

Successful commands must be followed by an authoritative Room/cache refresh using the existing sync/roster pipeline. Server authorization remains authoritative; upcoming screen controls must additionally hide owner-only actions for co-teachers and disable Hub-required actions offline.

Remaining work: Room-backed class selection and People/settings UI, localized forms/code-copy and confirmations, owner/read-only guards, authoritative refresh wiring, integration and UI/device tests, and acceptance evidence. This document does not claim the feature is complete.
