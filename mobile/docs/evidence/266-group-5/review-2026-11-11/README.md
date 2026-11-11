# Part 5 review follow-up

Branch `feat/266-mobile-classroom-layout-part-5`, PR #280, part of #266. Review fixes are based on `f023c99`; the base staging revision is `88f12b929f62102095759d2e20d5dbc4c89b3e71`.

## Changes and review answers

- Take a photo and Choose a photo use the bundled Phosphor Regular camera and image glyphs. Your work opens with caret-up and closes with caret-down. Vectors are exported from the existing bundled font.
- The previous blank submitted preview was the live test's completely white image. The fixture now contains a visible synthetic label and leaf shape. The test asserts that the synced file still decodes. The final screenshot visibly shows it.
- Add work creates a replacement draft. Confirming Turn in reuses the existing submission ID, replacing the ungraded submission. It does not create a second submission. Existing repository regression coverage passes, and the real Hub retained one submission across repeated turn-ins. Graded work remains read-only.
- The user requested a secondary accent. The points badge and Add work button use existing InfoLight/InfoDark/InfoBorder tokens. Primary actions remain green. InfoDark on InfoLight has a calculated 5.17:1 contrast ratio.
- The user's later emerald/sky/amber palette is a separate visual concept outside this PR. No new shared tokens or global theme changes are included here.

## Phone checks

Physical Xiaomi 2311DRK48G, Android 16. Separate `org.lara.app.group5` application. Production `org.lara.app` was not replaced or cleared. Synthetic accounts and images only.

| Final build check | Result | Raw output |
| --- | --- | --- |
| Mock Hub assignment, Add work, scan/import, confirmed turn-in and receipt | 1 passed, 14.736 s | [Mock](mock-device.txt) |
| Real staging Hub learner flow | 1 passed, 15.768 s | [Real Hub](real-hub-device.txt) |
| Real Hub flow at actual Android font_scale 2.0 | 1 passed, 16.172 s | [200%](font200-device.txt) |
| Normal app entry through class, Classwork and assignment with Hub stopped | Cached assignment, status and Add work remain visible | [Offline](normal-entry-offline-assignment.png) |

The live test invokes the existing HomeworkScreen and real ViewModel in the class shell. It opens both sheets, imports a labelled synthetic image, reviews it, confirms Turn in and verifies SYNCED, received_at and a readable retained file. The normal-entry offline screenshot separately verifies the actual navigation route, showing Science 4 in the real class header. Generic “Your classroom” headers in injected test screenshots belong to the fixture shell.

Two later offline-sheet screenshots were interrupted by the app leaving the foreground. They were discarded and are excluded from this evidence. No claim is made for those captures.

The phone's original font_scale 1.0 and USB stay-awake value 0 were restored. Temporary USB reverse mappings were removed and task-owned Hub processes stopped.

## Final screenshots

The [original evidence index](../README.md) retains the original Classroom / BEFORE / AFTER comparisons. These unedited captures supersede the touched assignment and work-sheet AFTER states.

| State | Mock Hub | Real Hub | 200% real Hub |
| --- | --- | --- | --- |
| Assignment and blue accent | [Mock](review-mock-live-mock-assignment.png) | [Real](review-real-hub-live-mock-assignment.png) | [200%](review-font200-live-mock-assignment.png) |
| Your work | [Mock](review-mock-live-work-empty.png) | [Real](review-real-hub-live-work-empty.png) | [200%](review-font200-live-work-empty.png) |
| Camera/image icons | [Mock](review-mock-live-add-work.png) | [Real](review-real-hub-live-add-work.png) | [200%](review-font200-live-add-work.png) |
| Visible submitted photo and close caret | [Mock](review-mock-live-mock-submitted.png) | [Real](review-real-hub-live-mock-submitted.png) | [200%](review-font200-live-mock-submitted.png) |
| Collapsed summary and open caret | [Mock](review-mock-live-mock-submitted-collapsed.png) | [Real](review-real-hub-live-mock-submitted-collapsed.png) | [200%](review-font200-live-mock-submitted-collapsed.png) |

“Empty” in the reused capture filename identifies the pre-upload work-sheet checkpoint. Repeated runs can already have the previous synced photo at that checkpoint.

## Real Hub provenance and fixture

[Build log](hub-build.txt) and [provenance](real-hub-provenance.json). Built with `cargo build --locked --bin lara-hub` from an isolated, unmodified staging checkout. It ran with LARA_TEST_SEED=1 and a disposable data directory. Teacher/learner login, classroom creation, enrollment and approval used real APIs. POST /api/assignments still returned 501, so a synthetic assignment plus its sync revision was inserted in that disposable database. No Server code or school data was changed.

The phone then synchronized that assignment and uploaded through the real learner submission route. This qualifies the learner flow against the real receiver, but does not establish teacher authoring or a WAN-unplugged classroom-router run. USB reverse carried REST and WebSocket traffic.

## Automated checks

- [Isolated final app/test build](build.txt) passed.
- [Final test/lint command](test-lint.txt) ran with LARA_SYNC_MOCK, LARA_APPROVAL_MOCK and LARA_SETTINGS_MOCK enabled. Each variant ran 201 tests, with 199 passing, two failing and no skips. The 20 homework/scanner tests passed in both variants. [Counts](test-counts.json).
- Lint passed with zero errors and 22 warnings on this Windows/JDK17/SDK35 environment.
- All [69 shared tests](shared-tests.txt) and the [offline invariant check](invariants.txt) passed.
- [Source hashes](tested-source-sha256.json) identify the tested screen, fixture and four new drawables.

The full unit gate remains failing for two untouched baseline files:

1. [SchemaParityTest](SchemaParityTest.xml), tracked by #276, because the merged audit-field contract is ahead of Room.
2. [VideoMockIntegrationTest](VideoMockIntegrationTest.xml), enabled by LARA_SYNC_MOCK=1, still asserts that the mock stream is all zero bytes and that saving it must fail. The mock now serves a decodable video after #259. The test file is identical to staging and was not changed in this UI PR.

No failing assertion was suppressed or converted into a pass. Hosted CI must be checked on the published revision.

## Reproduction

Build with the existing `../device.init.gradle`. Install the ARM app and instrumentation APK. For the mock, use `adb reverse tcp:8080 tcp:18080` and `adb reverse tcp:8081 tcp:18081`. For the real Hub running on host ports 8080/8081, reverse each port to itself. Then run the existing live test with the desired screenshot phase.

```text
adb shell am instrument -w -r -e class org.lara.app.reading.AssignmentUiDeviceTest#liveMockAssignmentTurnIn -e assignmentHub true -e assignmentPhase review-real-hub org.lara.app.group5.test/androidx.test.runner.AndroidJUnitRunner
```

The fixture still names its shared capture points `live-mock-*` when used against a real Hub. The phase prefix, output folder, provenance and raw runner logs distinguish the backend.

## Remaining limits

No new physical camera/permission rerun, TalkBack, landscape, low-memory qualification or WAN-unplugged Wi-Fi test was performed for this icon/accent pass. Camera/scanner code and submission persistence are unchanged. The original Part 5 evidence covers the earlier camera flow. Parts 4, 6, 7 and 8 remain outside this PR.
