# Join details and Desktop regression — 22 November 2026

## Scope and source

Desktop #325 now retains the contract's `section` and `teacher_name` join-receipt fields through the TypeScript parser and native account cache. Pending Home cards show those details when supplied, including before class metadata arrives. A canonical cached class section takes precedence over the original join section. Legacy receipts remain readable, status-only updates retain existing display details, and none of these fields grants access to a class. No shared schema, Server, or Mobile files changed.

Native testing used the Desktop branch based on `a1c93d5` plus this patch and the **unchanged real Hub from staging `0f2c6731618c0c0e6dadd5b1d46c5cf5cad0ed19`**. Both ran on one Windows PC. Fresh synthetic Hub data was stored at `G:\Codex\LARA\work\325-staging-hub-data`; isolated teacher/learner WebView profiles and loopback aliases kept account contexts separate. Test accounts were the documented teacher and two learners. Session tokens were kept in memory and are absent from this evidence.

## #325 dependency still open

The current contract requires Join to return `status`, `classroom_id`, `section`, and `teacher_name`. Latest staging still returns only the first two: a real request returned HTTP 200 with `{"classroom_id":"40bb76bc-1dc4-4443-aab8-90f28378f67d","status":"PENDING"}`. `server/backend/src/routes/classrooms.rs` confirms those are the only serialized reply fields. Server #324 must supply the remaining fields before the teacher-name acceptance criterion can pass end to end. Desktop neither fabricates that name nor breaks existing status-only receipts. This patch is prepared; **#325 is not fully verified or closed**.

Frontend regressions prove the complete reply is retained, malformed display fields are rejected, HTML is escaped, the pending card is read-only, and old cached receipts still work. A native database close/reopen regression proves both display fields survive and are retained after a status-only update.

## Native demo regression

| Check | Result |
| --- | --- |
| Manual Hub selection and six-digit sign-in | Pass for teacher and learner through the native UI. |
| Teacher creates class | Pass; class opens and shows generated code. |
| Learner joins and pending status survives reload | Pass. Section appears after class metadata sync; teacher name remains blocked as above. |
| Teacher accepts learner | Pass; learner can open class without restarting. |
| Teacher announcement and material uploads | Pass through Desktop UI; announcement, PDF handout, and decodable video reached learner through real Hub sync. |
| Learner reading/playback and Save for Home | Pass; handout text opened, video played beyond one second, both saved locally. |
| Video return | Pass; opening from Classwork returns to Classwork. |
| Hub stopped | Pass; unreachable banner appears, saved handout opens, saved video plays. |
| Full learner-process restart with Hub stopped | Pass; cached class, handout, and saved video remain accessible. |
| Hub restarted | Pass; both windows detect the Hub automatically. See credential limitation below. |

Evidence: [pending](screenshots/325-pending-real-hub.png), [approved class](screenshots/325-approved-real-hub.png), [video after full offline restart](screenshots/325-cold-offline-video.png).

## #380 comment-thread acceptance review

The existing branch implementation was exercised without adding comment features in this patch:

- Teacher created an assignment in Desktop. Learner's private question reached the teacher's selected learner thread, and the teacher's reply reached the learner after sync.
- A second active learner's authenticated real-Hub GET returned an empty own thread, even when requesting the first learner's `student_id`. The teacher's scoped GET returned the original question and reply once each. Native tests separately reject foreign learner thread reads/writes, invisible classes, disabled comments, and archived writes.
- Learner queued one public class comment and one private assignment comment with the Hub stopped. Both persisted across a full native-process restart while still offline.
- After reconnect and reauthentication, the Hub stored exactly one of each queued comment; the native private thread showed Sent and stayed at one row after another sync.
- Teacher private replies correctly remain disabled while offline. Private reply updates use the existing 60-second periodic pull or explicit refresh; no private-comment realtime event is assumed.

Evidence: [private exchange](screenshots/380-real-private-thread.png). Native runtime assertions confirmed both queued rows after full offline restart, the private row's Sent state after delivery and another sync, and exactly one matching row in each real-Hub comment table.

These results support lead review of #380 on the shared branch. They do not mean it has merged or override its separate-feature scheduling requirement.

## Limits

The QA runtime could not persist credentials securely. Cached reading works after a cold offline restart, but sign-in was required again when the Hub returned before queued work could upload. Automatic Hub reconnection and automatic remembered sign-in are separate checks.

Validation passed: 194 frontend tests, frontend lint and TypeScript/Vite production build, native executable build, 52 native tests (3 existing opt-in tests ignored), 84 shared tests, and the offline invariant scanner. The new frontend and native persistence regressions were first observed failing before implementation.

No physical second PC, WAN-unplugged router, Linux/macOS, screen-reader audit, installer build, or old-MSI-to-new-MSI upgrade was run here. This report does not close the full #71 UX audit, quiz, AI, or remaining feature backlog. Prior QA Hub data was preserved separately from this fresh dataset, and the previous QA Hub was restored after the run.
