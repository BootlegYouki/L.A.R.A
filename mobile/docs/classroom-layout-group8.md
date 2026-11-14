# Group 8 inventory and implementation — Part of #266

Base: staging `2a861ba` (Group 7 merged). Working branch: `mobile/266-group8-lara-screens`. Publication is authorized as a normal ready-for-review PR targeting staging, using `Part of #266`; no merge or automatic issue closure is authorized.

## Initial inventory (before production edits)

| Existing surface | Already meets the design | Improvement needed |
| --- | --- | --- |
| Sign-in / learner registration | English resource labels, masked four-digit custom PIN keypad, transient credential state, busy/rate-limit gating, existing Hub selection and registration callbacks | Shared header, shorter hierarchy above fields, explicit field help, keyboard/focus transitions to PIN, scroll/large-text layout, nearby feedback |
| Session restoration | Existing local-session flow and skeleton | Match app header and insets; preserve restoration logic |
| Hub discovery / selection / manual connection | Lifecycle-aware discovery, manual fallback, status text, validation, retry, token colors | Shared header, keyboard-aware scroll, manual entry reachable before a growing nearby list, consistent cards/rows |
| Teacher join approval | Existing bottom sheet, sequential pending request, existing card, vertical Accept/Decline, cached/loading/offline/error states | Clear hierarchy and request explanation; explicit busy feedback; retain authorization and one-at-a-time behavior |
| PDF/image/text handout reader | Bounded native rendering, cached chunks, page/zoom controls, retry, offline cached files | Consistent lesson detail header, clearer fallback feedback, controls reachable at large text/short heights |
| Video / Save for home | Existing player lifecycle, fullscreen, transport/seek, local playback, progress/pause/resume/removal and offline/error states | Consistent detail header, grouped Save for home hierarchy and feedback. Fullscreen mechanics already fit and should remain unchanged |

Group 8 has no direct Classroom equivalent. Groups 1–7 provide ClassroomTopBar, ClassroomSurface, action rows, banners and form input scrolling. No new destinations, API calls, schema fields, account settings, authentication modes or media features are in scope.

## Skill and source review

Local UI UX Pro Max guidance was consulted for visible labels, keyboard choice, clear errors, focus visibility and feedback. Its Compose keyboard/focus searches returned no verified match, so the existing repository input-scroll modifier and native platform APIs supply implementation guidance. Repository tokens/components and 52dp/56dp targets override generic guidance. The local skill and all associated installation files, scripts, caches and configuration are excluded from publication.

Updated Tech Lead instruction, 13 November 2026: real-Hub testing was not performed and is not required for this UI-only scope, superseding the earlier real-Hub gate for this PR. No real-Hub pass is claimed. Issue #276 remains open; the final suites reproduce the audit-column mismatch. Matched staging/workspace auth runs are recorded separately.

Implementation details are below; final counts, raw captures and limitations are recorded in `evidence/266-group-8/README.md`. Groups 4 and 6 remain outside this group; their unfinished underlying features must not be manufactured here.

## Implementation

- Authentication reuses the production top bar, action row, surface, error banner and input-scroll modifier. Clear ID/LRN labels and helper text distinguish teacher IDs from registration LRNs. Per the user's follow-up, sign-in and learner registration share a standard masked, four-digit numeric-password system keyboard field. No app-rendered numeric keypad remains. Credentials remain transient, PIN clears after submission/mode change, and busy/rate-limit gating is preserved. The primary action stays in a 56dp-minimum footer, hidden while the IME is open; the app bar also hides while a landscape IME is open so enlarged PIN text has usable space; ID/LRN and name use Next; PIN Done/Enter submits through the same form/busy/rate-limit gate as the button, and Back dismisses the keyboard. New errors bring the existing announced banner into view. No authentication repository or authorization changes.
- Discovery reuses the same components and places the existing manual fallback before nearby results so discovery growth cannot bury it. Checking hides the keyboard; existing discovery lifecycle, validation, callbacks and retries remain.
- Approval reuses Group 7's person card, adds explanatory and busy text, and retains sequential review, existing teacher-visible data and callbacks.
- Handouts and videos share a small `ClassroomDetailHeader` nested under existing class chrome. Reader controls scroll at short heights; cached rendering/fallback/page/zoom implementations remain. Video transport, Media3 ownership, fullscreen and download behavior remain; Save for Home controls have a shared surface and clearer hierarchy.
- The new Broadcast vector comes from the bundled Phosphor font, following existing vector conventions. The unused Backspace vector and keypad-only string resources were removed after a source-reference search. Nunito, repository colors, spacing and shapes remain authoritative.

Design decisions/deviations: there is no direct Classroom screenshot for these L.A.R.A-only screens. Manual connection precedes discovery results; authentication uses a keyboard-aware footer; lesson detail headers own no extra system insets. These adapt existing components rather than introducing a theme or destination. The shared native PIN field is an explicit user-requested deviation from design-system section 5.2's individual PIN boxes; PIN authentication and registration semantics remain unchanged.

The evidence fixtures deliberately use an isolated application ID (`org.lara.app.group8`) and synthetic account/media data. Reader/video fixtures include the existing class app bar; they do not claim to be screenshots of every navigation-shell element. No learner credentials, PIN digits or tokens belong in evidence.


## Final available verification

The six-case normal phone/mock run passed; both corrected 200% four-case layout suites passed. A separate blank-PIN capture test passed at the original size and both 200% orientations. There are 131 final raw captures, and SHA-256 checks confirm all 36 before captures are unchanged. Original phone font/rotation/stay-awake settings were restored. See [the evidence index](evidence/266-group-8/README.md) for raw logs, comparison runs, design deviations and capture links.

Debug and Release each finished with 194 passed, six skipped and one schema-parity failure matching open #276; no tests were suppressed. Lint passed with zero errors and 48 warnings, Debug assembly passed, and shared tests (71) and offline invariants passed. The earlier auth timeout did not reproduce in matched untouched-staging/current-workspace focused runs, nor in the final full suites; its cause is unproven and it is not labeled a baseline failure.

This PR is intended to finish the existing-screen redesign scope of #266. Groups 4 and 6 will not be implemented under MOBILE 3.1.2. Group 4 announcement posting belongs to [MOBILE 3.4, #60](https://github.com/BootlegYouki/L.A.R.A/issues/60); its materials/assignments/topics/attachments belong to [MOBILE 3.5, #127](https://github.com/BootlegYouki/L.A.R.A/issues/127). Group 6 To-do/Due soon belongs to [MOBILE 3.7, #129](https://github.com/BootlegYouki/L.A.R.A/issues/129). Their UI will accompany those features using the established design system. They are deferred, not implemented or abandoned. This PR uses `Part of #266` and does not close the issue automatically.

Real-Hub testing was not performed and is not required for this UI-only change under the updated Tech Lead instruction. Ready for review does not mean required checks passed: the schema-parity failure remains disclosed, CI and Lead review are required, and no merge or bypass is authorized.
