# Classroom layout — Group 2: Join and Create

Part of #266. Group 2 is prepared locally on `feat/266-mobile-classroom-layout-part-2`. The user explicitly requested starting it while Group 1 PR #271 was still open. The branch was created from staging `b801ef7`, then included the reviewed Group 1 dependency `b08a44d` so the test APK retained the new Home. PR #271 merged during implementation; this branch was then fast-forwarded to staging `c4ac4b9` without altering any Group 2 working files. The user approved committing and publishing Group 2, including the Lead-requested Mobile skill file and VS Code setting. Do not close #266 from this part.

## Scope and references

Read the repository reference README and opened Student 30 and Teacher 3/4. Student 30 is a full-page Join form with account context, code instructions and a top-right action. Teacher 4 is a full-page Create form with fields and a top-right action. Teacher 3 is an empty Home screen, not an additional form; it is context only.

The screen inventory and ordered eight-group matrix remain in [Group 1 notes](classroom-layout-group1.md). This part changes only Join, Create and the existing review-before-create step. It does not implement class tabs, posting, To-do or settings.

## Implementation

| File | Purpose |
|---|---|
| `ui/components/ClassFormDialog.kt` | Shared full-window form using the existing native `ComponentDialog`/`ComposeView` pattern, green header, 52dp Cancel/back, 56dp top action, scrolling content and keyboard resizing; focused inputs reveal their text region |
| `ui/screens/enrollment/JoinClassDialog.kt` | Existing code draft, grouped formatting, validation, errors and submit callbacks rendered in the new structure |
| `ui/screens/enrollment/EnrollmentScreen.kt` / `LearnerEnrollmentNavigation.kt` | Pass the authenticated learner's display name from the existing cached-name flow; no new query or request |
| `ui/screens/teacher/classes/CreateClassDialog.kt` | Presentation of required name/section, existing review step and Edit details |
| `ui/screens/teacher/classes/ClassManagementHost.kt` | Route only the existing create action into the new form; reuse its current fields, review flag and `model.create` callback |
| `res/values/strings.xml` | English top-action labels, account/approval explanation and required-field help |
| `androidTest/.../ClassFormsDeviceTest.kt` | Synthetic live create/review/edit/join/pending flow plus explicit presentation states, hit bounds and keyboard checks |
| `androidTest/.../HomeNavigationDeviceTest.kt` | Existing entry-flow test updated for the required-field label and waiting for the action sheet before selecting its action |

No ViewModel, repository, API, schema or sync implementation changed. `ClassCode.canonicalOrNull`, `GroupedClassCode` and its cursor behavior are reused unchanged. The same code length/character rules, trimming, uppercase submission, 32-character draft allowance, busy gating and error mapping remain. Join cannot dismiss while busy, as before. Teacher Create retains its review-before-create step, blank-field gating, edit and cancellation callbacks. Other class-management dialogs retain their existing UI and behavior.

The shorter header says **Join**, but the nearby explanation states that the teacher must approve the request. A successful request still closes the form into the existing pending card; it does not grant class access. Only the learner's own cached name is shown, with an initials avatar. No LRN, email or photo is added.

Form fields are blank until the user types; there is no initial form-data fetch. Busy submission/refresh states use progress plus text, and offline/error messages use the existing banners. The surrounding Room-first Home and pending state remain mounted. No write is newly queued or retried by the form.

## Design deviations

| Reference | Deliberate L.A.R.A difference | Reason / preserved behavior |
|---|---|---|
| Student 30 | Six-character grouped `XXX-XXX` code rather than Google's longer code | Existing contract and code parser are unchanged |
| Student 30 | Join requests approval instead of opening the class immediately | Existing enrollment/teacher-approval requirement |
| Student 30 | Own display name and initials; no email, photo, Switch account or Help Center | Existing authenticated identity only; unsupported Google features omitted |
| Student 30 / Teacher 4 | Green header, Nunito, Phosphor back arrow and L.A.R.A fields | Approved tokens/assets; arrow is named Cancel through accessibility |
| Teacher 4 | Only required class name and section | Room, subject and level are not supported; section remains required as in the existing implementation |
| Teacher 4 | Review then Create, with Edit details | Preserve L.A.R.A's existing confirmation workflow rather than adding an immediate create path |
| Design-system 9.10 | Full-window form instead of a compact Join dialog | #266 and the user's phone references specify screen structure; validation and callbacks stay the same. Report for Lead review. |
| Both forms | Header grows for wrapped large text; body scrolls and window resizes for the keyboard | Preserve 52dp/56dp controls and prevent landscape keyboard panning from hiding the action |

No extra tech-lead decision is needed to preserve the existing business rules. The intentional full-page layout deviation is documented for review.

## Evidence and validation

[Reference/before/after comparison and state captures](evidence/266-group-2/README.md).

- Infinix X6731, Android 14. Updates installed with `adb install -r`; no uninstall or data clear. Original font scale 0.85 and rotation settings 0/0 are restored after tests.
- Mock Hub live Create → Review → Edit → Create, invalid Join, lowercase valid code, and pending notice persistence: passed.
- Freshly built current-staging Rust Hub (`b801ef7`, isolated data under `mobile/build/group2-realhub-data`) ran the same real form flow: passed. Test records were created through the app, not inserted into the database.
- Actual Android 200% portrait live flow: passed. Enforced landscape state fixtures use Android 200% font scale and assert width greater than height, header bounds/gutters, 52dp/56dp hit height, disabled actions and code-field reachability with the keyboard visible.
- Empty, offline, busy, request-error and pasted-code captures are explicitly injected presentation fixtures using production Composables, not fabricated server events. Live pending approval is asserted in the existing persisted notice flow.
- `JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=C:\Temp` with `gradlew.bat test lint assembleDebug assembleDebugAndroidTest`; UTF-8 repository tests and invariant checks. Exact final results are recorded with the evidence.

Final unit results: debug and release each have 195 passes, six opt-in skips and zero failures/errors. Lint has zero errors and 43 warnings. Repository tests: 69 passed. The invariant scanner is also called directly on the uncommitted diff and new source files because its standalone script scans committed history.

The initial baseline automation hit an accessibility timing race, so it was not counted as a pass. The subsequent baseline Create/review run passed. Landscape testing caught the keyboard panning the dialog header above the screen. Visual inspection also caught a code field that accessibility still reported as reachable while its text was obscured. The form now uses the app's existing native full-window dialog pattern, native resize mode, a 64dp default header, and focused-text-region scrolling rather than trying to fit the entire padded field. The final landscape keyboard capture visibly shows the full grouped code and Join action. An incorrect accessibility constant in the new test was corrected before the final matching APK/test run.

Not verified: spoken TalkBack, a WAN-unplugged classroom-router run, 3–4GB memory profiling, and unrelated video/camera workflows. USB forwarding verifies Hub reachability changes, not LAN discovery. No new feature is implemented to fill a reference gap.

## Repeat checks

Use only a synthetic test installation. Start the mock Hub, forward device 8080/8081 to its HTTP/WebSocket ports, build/install both APKs, then run:

```powershell
adb shell am instrument -w -e class org.lara.app.reading.ClassFormsDeviceTest -e formsHub 127.0.0.1:8080 -e formsStates true org.lara.app.test/androidx.test.runner.AndroidJUnitRunner
```

For short-height states, select `#formStates` and pass `-e formsStates true -e formsLandscape true`; set Android font scale to 2.0 and restore it afterward. The live flow signs in only as synthetic seed accounts, refuses to replace an unrelated session, and refuses sign-out when queued work requires confirmation. It creates uniquely named test classes and leaves the learner request pending.

## Draft PR description

Title: `feat(mobile): align join and create class forms with Classroom layout`

Part of #266

Join and Create now use full-page forms with a top action and scrolling body, matching Student 30 / Teacher 4 while retaining L.A.R.A tokens. Join shows the learner's own cached name and explains teacher approval. Create retains required name/section, review and edit before calling the existing creation action. All parsers, ViewModels, repositories and network behavior are unchanged.

Screens: Join, Create and Create review. See the linked comparison index for original references, before/after captures, states and real-Hub evidence. Teacher 3 is empty Home context only. Design deviations and functionality preservation are listed above.

Validation: include the final build/unit/lint, repository test, invariant and targeted physical-device logs. Device: Infinix X6731 / Android 14; actual 200% font, landscape and keyboard checks. Known limitations: spoken TalkBack, WAN-unplugged router testing and unrelated media/camera workflows not repeated.

Group 1 is merged; wait for user review before publishing Group 2. Do not mark Groups 3–8 complete or close the issue. Unrelated `.vscode/settings.json` and a newly appeared empty `.agents/skills/lara-mobile/SKILL.md` were left untouched.
