# Classroom layout, Group 5

## Palette implementation checkpoint

The user requested implementing the emerald/sky/amber concept before PR #280 merges. Mobile now uses emerald primary #059669, bright completion green #10B981, sky-blue actions/navigation #2563EB, amber #F59E0B with #D97706 alert icons, and the requested light-slate canvas/border/text values. Readable darker text and light-container variants accompany those colors. PrimaryDark #047857 keeps small success text legible. Secondary text uses #64748B on the #F8FAFC subtle surface, and AI retains its dedicated purple tokens.

Color.kt owns the values. Theme.kt maps Material secondary to sky blue. Class headers use emerald, class navigation and resource icons use blue, and homework status surfaces distinguish completed, queued and rejected work with an icon and text. This user-requested mobile palette differs from the current shared design-system tokens and requires Lead re-review/reconciliation, recorded on PR #280. The Lead was notified to hold merge for the palette revision.

The isolated app and instrumentation build passed on 11 November. A contrast regression test was added. At this checkpoint, the new palette's unit/lint run, physical normal/empty/offline/200% screenshots, final CI run and PR-body cleanup are pending. Earlier review evidence applies to the previous palette.

The Lead approved the earlier icon/thumbnail/replacement fixes and real-Hub evidence at df5532d. They explicitly accepted #276 as a staging-wide failure for this PR and assigned the stale opt-in video test to separate issue #281. Those baseline fixes stay in their own follow-ups.

## Current review follow-up — 11 November 2026

Work continues on `feat/266-mobile-classroom-layout-part-5`, PR #280. The Lead's action-icon corrections are implemented. The original white submitted thumbnail was an all-white test fixture; the updated fixture has a visible label and leaf shape. Mock and real-staging-Hub upload/receipt checks passed on Xiaomi 2311DRK48G / Android 16, including 200% text. The real Hub was built from staging `88f12b9`. Its assignment-authoring route returns 501, so only the assignment itself was seeded in a disposable database; class creation and enrollment used the real APIs.

The requested secondary-color polish uses existing `InfoLight`, `InfoDark` and `InfoBorder` tokens for the points badge and Add work button. Turn in keeps the primary green. This is a documented single-screen deviation from the original neutral points text and outlined action. No theme tokens, API, database or submission behavior change.

The user-requested UI UX Pro Max guidance was installed outside the repository and consulted for contextual Phosphor icons, accessible labels, consistent spacing and token-driven surfaces. L.A.R.A's design system, Classroom references and 52/56dp targets remain authoritative.

[Updated evidence and limitations](evidence/266-group-5/review-2026-11-11/README.md). Earlier preparation/publication notes below describe the original checkpoint.

Part of #266. Branch: `feat/266-mobile-classroom-layout-part-5`. Base: `origin/staging` `88f12b929f62102095759d2e20d5dbc4c89b3e71` (Group 3 PR #279 merged). No open PR overlapped this work at startup. Groups 1-3 are merged. Group 5 implementation and evidence were subsequently authorized for commit, push and a Draft PR targeting staging. Only the separately authorized skill update was committed and pushed as `69f514d`.

The latest available Announcements (#238 and #242), issue #266 and PR #279's review decisions were recovered. The Lead still requires screen-state evidence and a real-staging-Hub main-flow check before opening a PR. #276's schema-parity failure and #122's unfinished server authoring are accepted baselines, not investigated or changed here. The user-edited `.agents/skills/lara-mobile/SKILL.md` was reviewed without changes and committed separately as `69f514d` (`docs(mobile): refine mobile skill verification workflow`). It is included on the Group 5 branch; no implementation or evidence file was staged with it.

## Scope and file map

- `HomeworkScreen.kt`: due date, title, points and instructions above a bounded, scrollable Your work summary. Its header opens a scrollable bottom sheet containing the existing photo preview, submission/rejection status, photo replacement and Turn in controls. Add work opens a source sheet containing only camera/scanner capture and image selection. Turn in asks for confirmation before calling the existing ViewModel action; cancelling leaves Room and the draft unchanged.
- `CameraCaptureScreen.kt`, `DocumentReviewScreen.kt`: consistent Phosphor back control and title row. Existing CameraX lifecycle, permission fallback, framing guide, corner adjustments, preview/enhancement, full-photo fallback and image processing are unchanged.
- `values/strings.xml`: six English UI strings for the sheets, points and confirmation.
- `AssignmentUiDeviceTest.kt`: opt-in tests confined to `org.lara.app.group5`; synthetic Room states, real camera capture, image-import processing, selector launch, confirmation cancellation, target sizing, offline queue persistence and an actual mock-Hub receipt. The live check shares the application's container with WorkManager, just as production does.

No ViewModel, repository, worker, navigation graph, schema, contract, API, permission declaration or dependency changed. `HomeworkScreen` remains called by the existing learner Classwork assignment row. The existing class shell still owns its header, offline banner and bottom navigation.

## Lead review follow-up

The Add work sources use bundled Phosphor Regular `camera` and `image` icons. Your work uses `caret-up` to open and `caret-down` to close. These vectors are exported from the repository's bundled font, with the same coordinate transform as existing drawables.

The white area in the original `live-mock-submitted.png` is the test's entirely white synthetic source image. The live fixture now draws a labelled page and leaf shape, and asserts that the synced local file still decodes. Updated device evidence shows the visible submitted image.

Add work remains available after an ungraded submission is Turned in. Choosing a photo creates a draft only. Confirming Turn in replaces the existing submission using its current ID, then queues that replacement. It does not create a second submission. `HomeworkRepositoryTest.resubmissionUsesTheExistingIdentity` covers this behavior. Graded work stays read-only, and no Unsubmit action is introduced.

## Persistence and failure behavior

The same Room submission Flow supplies the UI. Draft and scan state still belong to `HomeworkViewModel`/SavedStateHandle. Capture and image selection still pass through its existing normalize/detect/render/compress pipeline. Confirmation calls `turnIn()` once; the repository saves the photo reference and Hub binding transactionally as `QUEUED_FOR_SYNC`, and existing workers upload it. Uploaded/queued/rejected states retain their exact existing wording and both permanent rejection reasons. Ungraded work can still be replaced; graded work exposes no Add work or Turn in action. No unsubmit operation is introduced.

The unchanged shared banner indicates an unreachable Hub. Empty photo hints, submission loading skeleton, saving progress, processing failures and scan/camera fallbacks remain. The summary and sheets scroll so enlarged text does not force actions outside an unscrollable panel. Back dismisses the active camera/scan review before the work panel; closing or cancelling review preserves previous work.

## Design deviations

- Follow Student 23-28's details / Your work / Add work / confirmation sequence using L.A.R.A tokens, Nunito and bundled Phosphor. Keep the existing class shell and navigation rather than add a new navigation route or replace the shell.
- Your work is a compact lower panel opening a modal bottom sheet, rather than Classroom's draggable full-height work surface. It contains only implemented actions and grows/scrolls with content. No empty private-comment area or unsupported controls are shown.
- Add work offers the existing camera with automatic scan review and Choose a photo. No separate scanner implementation, Drive, links, document/general attachments, Create, Mark as done, comments or Unsubmit. Gallery selection retains Android's image-only system selector.
- The supplied references show comments, a close-after-due-date notice and Unsubmit. Preserve existing submission behavior instead: the Hub decides eligibility; offline Turn in remains available and late/graded rejections explain what happened. Do not add deadline-based client blocking.
- Show the assignment's existing maximum points as `Points: n`. Preserve the existing graded/read-only behavior without building the later grade/feedback feature.
- Queued and rejected states have text plus an icon, not color alone. The existing long status explanations wrap instead of using Classroom's short status text.
- Scanner/camera retain the existing framing and review workflow. Only their header layout changes; there is no matching Classroom camera/scanner screenshot among Student 23-28.

## Deferred work

Group 4 is deferred: teacher announcement posting (#60) and assignment/material/topic authoring (#127) are not implemented on staging. Issue #266 redesigns existing screens; it does not authorize those features or placeholder forms. Group 5's private comments await #129. Group 6 is not started. No later-group or backend implementation is included.

## Verification and proposed PR details

Proposed title: **feat(mobile): align learner assignment and turn-in with Classroom**. Use **Part of #266**, not Closes. Summary: learner assignment details now lead into Your work, an image-only Add work sheet and confirmed offline-capable Turn in; existing persistence and upload behavior remain intact.

[Reference / BEFORE / AFTER comparisons, logs, commands, results and limitations](evidence/266-group-5/README.md).

The implementation is prepared for review, but the required real-staging-Hub main-flow check is **pending before PR submission**, not waived. No running real Hub was available; the located executable was not verifiably tied to current staging sources. Rust was not rebuilt or troubleshot for this UI pass. Retain this unchecked in the PR checklist unless it is completed or the Lead explicitly accepts the limitation. The known #276 failure also remains visible in the full unit gate.

## Final PR readiness review

The implementation diff stays within the requested existing assignment/photo workflow. All recorded production/test-source hashes still match the working files, screenshot/reference links resolve, design deviations cover unsupported Classroom actions, and whitespace checks pass. Lead announcements were checked again and are unchanged; there is no overlapping open PR.

No reusable verified real-staging-Hub setup was available: no lara-hub process or listener on 8080/8081, and the known executable has no verified current-staging build provenance. Its associated source checkout differs from current staging. No Rust build, infrastructure repair or real-Hub data mutation was attempted. Real-Hub verification stays pending as requested. This mandatory #242 check must be completed or explicitly accepted by the Lead before submission is treated as fully ready.

#276 remains the acknowledged, disclosed schema-parity baseline; it was not rerun or reinvestigated during this readiness review. No new Group 5 implementation/evidence blocker was found. The earlier skill-only push did not include Group 5 implementation. Subsequent user authorization covers a separate implementation/evidence commit and a Draft PR only.

The Draft PR includes both skill commit `69f514d` and the Group 5 implementation/evidence commit. It uses Part of #266, does not close the issue, and stays in draft with real-staging-Hub verification pending. No Rust rebuild, unrelated fix, merge or next-group work is authorized.
