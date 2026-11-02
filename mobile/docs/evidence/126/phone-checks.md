# MOBILE 2.7 physical-phone checks — 2026-11-02

Device: Xiaomi 2311DRK48G. Package: `org.lara.app.sprint27classes`, installed alongside the normal app. The test APK differs in application ID; implementation source matches this branch. Tests use synthetic accounts/classes against the unmodified local mock on USB routes 38080/38081. Co-teacher/learner fixtures were provisioned through existing mock APIs, not shared-source edits.

These are manual normal-entry checks, not a claim of an automated device-test suite.

| Check | Observed result |
| --- | --- |
| Create/review | Name/section entered, review shown before submission, class created with a formatted code. Cancelled preview-only forms did not create another class. |
| Code copy | Copied `XXX-XXX` code pasted into a test form and matched the expected synthetic code; form cancelled. |
| Rename | Owner changed name/section on the phone; Android PATCH completed and saved values updated. |
| Co-teacher add | Owner added a provisioned synthetic teacher; People showed the co-teacher. |
| Co-teacher restrictions | Signing in as that teacher showed People and the saved class but no Class settings, archive or manage-teacher controls. |
| Teacher display names | Final build showed seeded owner and synthetic co-teacher names from the local public-profile projection. |
| Learner removal | As co-teacher, Filipino warning explained access loss and retained past scores. Cancel kept the ACTIVE learner; confirm updated the teacher's saved roster to Access removed. Learner-side revocation now passes the subsequent rebased regression; a fresh phone pass is pending. |
| Co-teacher removal | Owner cancelled then confirmed the removal; the saved classroom's co-teacher row disappeared. |
| Archive | Owner cancelled then confirmed archive. The class stayed stored and, after the management-list fix, remained readable on a cold offline launch with no editing controls. |
| English/Filipino | Normal-entry forms, People, statuses and confirmations rendered in the selected app language. |
| 200% text | Filipino review dialog remained readable with visible confirm/cancel actions; Hub-unreachable management showed cached classes and disabled writes. Font scale was restored from 2.0 to the original 1.0 afterward. |
| Offline | Test Hub routes were removed for cold launch; cached class/People data remained available and writes were disabled. Routes restored afterward. |

Screenshots: `create-review.png`, `co-teacher-people.png`, `remove-confirm-filipino.png`, `review-filipino-200.png`, `offline-filipino-200.png`, `archived-read-only.png`. The reviewed final class dialogs use the white Surface token; Material's default purple tint was corrected during visual QA.

`memory-sample.txt` is one foreground sample (about 192 MiB total PSS) after these flows. It is not certification on a 3–4 GB phone or a full heap/battery profile.

## Subsequent rebase and integration coverage

Shared fixes #210/#211 are now merged in staging. The rebased `TeacherClassMockTest` and full 141-test Debug/Release suites pass, including removal after sync. The former shared blocker is resolved; the regression remains enabled. A final phone pass on this rebased build is still pending Lead review; the manual observations above describe the earlier physical-phone run.

Real Rust Hub, a physical WAN-disconnected classroom router, grade-history retention on that Hub, and budget-device battery/heap certification remain integration/field follow-ups.
