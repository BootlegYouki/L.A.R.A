# MOBILE 3.1.1 — phone layout evidence

Branch `codex/244-mobile-3.1.1-classroom-ui`, started from staging `3d0836d` (merged #243), refreshed to `886b061` before the PR. The staging refresh changed no mobile files.

## Before / after

Physical Xiaomi 2311DRK48G, isolated `org.lara.app.sprint31reading`. The original `org.lara.app` is untouched. Before images were captured before changing production UI; after images use the final components on the same phone. `ClassroomUiEvidenceTest` supplies synthetic state to production screen components, not drawn mockups. Reader before images are the merged 3.1 phone evidence. Whole-app captures are identified separately below.

| Screen / state | Before | After |
|---|---|---|
| Sign-in | [Before](before/signin.png) | [After](after/signin.png) |
| Choose-Hub step | [Before](before/signin-hub-step.png) | [After](after/signin-hub-step.png) |
| Hub connection | [Before](before/discovery.png) | [After](after/discovery.png) |
| Home cards | [Before](before/home.png) | [After](after/home.png) |
| Home offline | [Before](before/home-offline.png) | [After](after/home-offline.png) |
| Home loading | [Before](before/home-loading.png) | [After](after/home-loading.png) |
| Home empty | [Before](before/home-empty.png) | [After](after/home-empty.png) |
| Waiting for approval | [Before](before/waiting.png) | [After](after/waiting.png) |
| Join dialog | [Before](before/join.png) | [After](after/join.png) |
| Class shell | [Before](before/class-shell.png) | [After](after/class-shell.png) |
| Stream | [Before](before/stream.png) | [After](after/stream.png) |
| Classwork | [Before](before/classwork.png) | [After](after/classwork.png) |
| PDF reader | [Before](before/pdf-page-one.png) | [After](after/pdf-page-one.png) |
| DOCX text | [Before](before/docx-text.png) | [After](after/docx-text.png) |
| Pending approval sheet | [Before](before/pending.png) | [After](after/pending.png) |
| People card components | [Before](before/people.png) | [After](after/people.png) |
| Managed-class card component | [Before](before/manage-class.png) | [After](after/manage-class.png) |
| Teacher class screen, whole app | [Before](before/teacher-screen.png) | [After](after/teacher-screen.png) |
| Management entry, whole app | [Before](before/manage-screen.png) | [After](after/manage-screen.png) |
| Settings | [Before](before/settings.png) | [After](after/settings.png) |
| Settings offline | [Before](before/settings-offline.png) | [After](after/settings-offline.png) |

The People and managed-class component pairs deliberately retain the existing working card design, now shared through ClassroomSurface. The old People link led to a placeholder; the updated link uses existing management roster cards, with global creation/code controls omitted from that embedded view. No new People endpoint or management capability was added.

Additional after screenshots cover [keyboard-open comments](after/comment-keyboard.png), [calm text fallback](after/lesson-text-fallback.png), Stream/Classwork at 200% text, empty reading screens and loading. Valid PDF/image rendering uses local synthetic fixtures. The unchanged mock's placeholder file is not a valid PDF, and must not be represented as a passing real binary download.

## Reference and design interpretations

Read-only comparison with the installed Google Classroom Android Home and teacher Stream informed the structure; no Google screenshot, account photo or real classroom content is included here. A separately accessible learner reference account was not available, so learner placement follows design-system section 9.12. L.A.R.A keeps its green app bar, Nunito, Phosphor and four bottom destinations.

The issue requests dark-green Home card headers; these sit above white status/footer areas, rather than only the small tile in the desktop reference. Heights grow for long names/large text. Bottom navigation and teacher tools temporarily yield space to the keyboard, then return. The offline state remains visible as a compact icon/text line while typing. The full management page retains all existing controls; its embedded People layout shows only the selected class roster. Grades and other unbuilt destinations remain outside this issue.

## Verification record

Full mobile test/lint output is in `test-lint.txt` (146 Debug and 146 Release tests, no failures or skips); shared contract/mock output is in `shared-checks.txt` (67 passed); isolated APK output is in `phone-build.txt`. `device-tests.txt` records three passing phone scenarios, including native readers, offline comments, first-name privacy, 200% text, a full teacher-shell keyboard-open Save-button bounds assertion, calm text fallback and the screen-capture matrix. No tests were disabled. A test that assumed the third handout was initially visible was corrected to scroll to it after cards grew; the keyboard assertion passed even in that initial failed run.

Real Hub: unchanged backend source from staging, on PC Wi-Fi `192.168.100.2`, with separate synthetic data. Passed automatic discovery without ADB forwarding; teacher sign-in and Home card; selected class header; existing People roster with teachers/learners; Settings; learner sign-in, Home and approved class entry; cached cold offline Home/class; reconnect after Hub restart. Whole-app images use `after/real-*.png`. The Hub's unavailable announcement/material routes remain a shared integration limit, not a mobile workaround.

Synthetic fixture tests change only the isolated test app's synthetic account state; prior queued accounts remain on disk. The final three phone scenarios passed in 40.885 seconds. A separate selected-class management check confirmed that Create class stays open while typing, then canceled the form without creating a class. See [management keyboard](after/manage-form-keyboard.png).

Not claimed: physical WAN-unplugged router, 3–4 GB/vendor battery certification, exhaustive TalkBack, or real-Hub populated Stream/material routes that are not implemented on staging.


Device cleanup restored USB stay-awake to its original 0. System font scale remains 1.0. No ADB forwarding remains. The test Hub stays running. Earlier offline/reconnected Home captures and teacher-home-live.png precede the final Home app-bar wording polish; real-teacher-home.png and real-learner-home.png show the final wording. All class-screen layouts are the final layout.
