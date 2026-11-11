# Group 2 evidence — Join and Create

All application images are unedited captures from the physical Infinix X6731 / Android 14, with synthetic accounts. Original Classroom references are untouched.

| Changed screen | Classroom reference | Before L.A.R.A. | After L.A.R.A. |
|---|---|---|---|
| Learner Join | [Student 30](../../../../design-system/mobile/google-classroom-reference/student/30-%20CLASS%20CODE.jpg) | [Join dialog](before/join.png) | [Join form](after/realhub/join-filled.png) |
| Teacher Create | [Teacher 4](../../../../design-system/mobile/google-classroom-reference/teacher/4-%20CREATE%20CLASS.jpg) | [Filled dialog](before/create-filled.png) | [Filled form](after/realhub/create-filled.png) |
| Teacher review | Teacher 4 structure; L.A.R.A keeps its existing review step | [Review dialog](before/create-review.png) | [Review form](after/realhub/create-review.png) |

Teacher 3 was opened and depicts empty Home, not a create form. Student 30 includes unsupported Google account actions that are deliberately omitted.

The Join baseline is the accepted Group 1 capture of the unchanged dialog (`b08a44d` source). Create/filled/review baselines were captured in this session on the installed Group 1 APK before installing Group 2. The first unsuccessful baseline automation produced no accepted evidence; the subsequent baseline Create/review test passed.

## What the captures prove

- `after/realhub/`: real form interactions against the freshly built staging Rust Hub. The teacher reviews/edits then creates a class; a learner enters an invalid code, corrects it, and sends a valid join request. `pending.png` is a live result whose PENDING notice is also asserted in local persistence.
- `after/mock/`: the same live workflow against the mock. The first fixture run also has explicitly supplied empty/offline/busy/error states, not live server errors.
- `after/portrait/`: production-component state fixtures, including pasted grouped code and the keyboard.
- `after/font200/`: live form workflow with Android font scale 2.0.
- `after/landscape200/`: enforced landscape fixtures at actual Android font scale 2.0; screenshots assert landscape dimensions and the form actions' screen bounds/gutters and 52dp/56dp minimum touch height. The keyboard capture also verifies that the editable code field remains reachable.

`fixture-*` names identify deliberately supplied presentation states. They prove rendering and enabled/disabled behavior, not live empty/error responses. Other phase captures predate the final compact-header refinement; the real-Hub and landscape captures are refreshed for the final implementation. Synthetic class names/codes vary per run. Tests do not auto-approve the learner.

See [implementation notes and Design deviations](../../classroom-layout-group2.md). No pixel-perfect claim is made. Top actions, required fields, review/edit, code validation and pending approval are preserved using existing callbacks. Full-page layout is a documented deviation from design-system 9.10's compact-dialog text.

Validation logs in this directory contain the final Android build/test/lint, repository tests, invariant scan, mock/real-Hub device runs and responsive checks. Spoken TalkBack and WAN-unplugged classroom-network tests are not claimed.

- [Final unit tests and lint](validation.log): debug/release each 195 passed, six opt-in skips; zero failures. Lint: zero errors, 43 warnings.
- [APK and device-test build](device-build.log)
- [Repository tests](contract-tests.log): 69 passed
- [Real-Hub create/review/join/pending flow](realhub-device.log)
- [Real-Hub live flow at Android 200%](font200-flow.log)
- [Landscape 200% fixtures and keyboard](landscape200.log)
- [Existing Home navigation regression](home-navigation.log)
- [Mock-Hub forms and states](mock-device.log)
