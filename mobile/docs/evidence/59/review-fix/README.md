# PR #258 paused-download review fix

Prepared locally on `feat/59-mobile-3.3-video-offline`, based on reviewed commit `4783624b5438d8ff439cd5aa626da109ec93ace5`. The working tree was clean before this correction. No branch was recreated. No commit, push, PR update, ready-state change or review-thread resolution was performed.

Scope: the single blocker in the [lead's review](https://github.com/BootlegYouki/L.A.R.A/pull/258#issuecomment-6718940059).

## Corrected behavior

| Phase | Primary action | Callback |
| --- | --- | --- |
| DOWNLOADING | Cancel download (keep progress) | Existing `pause` callback |
| PAUSED | Exactly one Resume download button | Existing `save` callback |
| Other phases | Existing behavior preserved | Existing callbacks preserved |

Progress/status rendering remains visible for PAUSED. The extra secondary Resume button is removed. PAUSED at zero bytes also says Resume instead of Save for Home. Design tokens, primary target size and English resources are unchanged.

The only testability change makes the existing `DownloadControls` composable `internal`. Streaming, authentication, seeking, download/state transitions, HTTP 200 restart behavior, finalization, Room persistence, URL policy and lifecycle code are untouched.

## Compose regression coverage

The repository currently uses `ActivityScenario`, Compose `setContent`, and Android accessibility assertions for device UI tests. The new tests follow that convention, rendering the real `DownloadControls` with the actual `VideoDownload` state model. No new testing dependency or substitute UI was added.

- `ePausedDownloadShowsExactlyOneWorkingResumeAction` covers zero and 32,768 downloaded bytes. It checks Resume is visible, Cancel is absent, exactly one actionable button exists, and clicking it calls `save` once and never calls `pause`.
- `fDownloadingShowsOnlyWorkingPauseAction` checks Cancel is visible, Resume is absent, one actionable button exists, and clicking it calls `pause` once and never calls `save`.

Before the behavioral fix, the paused test failed with `Unexpected Cancel download (keep progress) control` ([recorded failure](paused-before.txt)). After the fix, both tests passed on the authorized **Infinix X6731 / Android 14 / SDK 34**, using the isolated `org.lara.app.sprint33video` app for Android user 0 ([device output](device-tests.txt)). Production app data and device radio/font settings were not changed.

## Verification

- Isolated app/instrumentation build: `gradlew.bat -I build/59-isolated.gradle assembleDebug assembleDebugAndroidTest` passed.
- Affected device methods: `adb -s <authorized-phone> shell am instrument --user 0 -w -r -e class org.lara.app.reading.VideoDeviceTest#ePausedDownloadShowsExactlyOneWorkingResumeAction,org.lara.app.reading.VideoDeviceTest#fDownloadingShowsOnlyWorkingPauseAction org.lara.app.sprint33video.test/androidx.test.runner.AndroidJUnitRunner` reported **OK (2 tests)**.
- Required full checks: `gradlew.bat -I build/59-test-env.gradle assembleDebug test lint --console=plain` reported **BUILD SUCCESSFUL** ([output](gradle-build-test-lint.txt)). All existing mock integration flags were enabled with the unchanged shared mock on the existing test ports. The init file only selects the short isolated Windows temp directory already documented in the parent evidence.
- Debug: **194 tests**, zero failures/errors/skips. Release: **194 tests**, zero failures/errors/skips ([counts](test-counts.txt)). These JVM tests are separate from the two executed device UI tests.
- Lint: **0 errors, 39 warnings**; existing warnings remain visible in build output/report.
- `python -m unittest discover tests`: **68 tests, OK** ([output](shared-tests.txt)).
- `python scripts/verify_invariants.py`: **PASSED** ([output](invariants.txt)).
- `git diff --check`: passed. Only the scoped Mobile files below changed; no data-layer files changed.

New text logs are normalized to UTF-8/LF with trailing whitespace removed; result content is retained. Existing PR evidence was preserved.

## Exact files changed

| Path | Purpose |
| --- | --- |
| `mobile/app/src/main/java/org/lara/app/ui/screens/classwork/VideoPlayerScreen.kt` | Phase-specific primary action and removal of duplicate button; internal visibility for the existing composable |
| `mobile/app/src/androidTest/java/org/lara/app/reading/VideoDeviceTest.kt` | Two Compose UI regression tests and their shared assertion helper |
| `mobile/docs/video_player.md` | Document corrected action behavior |
| `mobile/docs/evidence/59/review-fix/README.md` | This local review summary |
| `mobile/docs/evidence/59/review-fix/paused-before.txt` | Actual pre-fix device regression failure |
| `mobile/docs/evidence/59/review-fix/device-tests.txt` | Actual passing device tests |
| `mobile/docs/evidence/59/review-fix/gradle-build-test-lint.txt` | Actual full Android check output |
| `mobile/docs/evidence/59/review-fix/test-counts.txt` | Counts from final Debug/Release JUnit XML and lint report |
| `mobile/docs/evidence/59/review-fix/shared-tests.txt` | Shared suite output |
| `mobile/docs/evidence/59/review-fix/invariants.txt` | Invariant-check output |

## Limitations and lead clarification

No new integration limitation blocks this UI correction. The latest review explicitly defers real-Hub and WAN-unplugged checks to integration day and full playback click-through to the future decodable mock fixture. Those checks were not rerun or claimed here. Existing HTTP 200 fallback is approved; contract/mock changes remain Lead-owned. English-only is confirmed. Hosted CI can only validate the fix after a later authorized push; current PR head remains unchanged.

Suggested commit: `fix(mobile): show one resume action for paused downloads (#59)`

Suggested reply (not posted):

> Fixed the paused-state action: exactly one primary Resume button calls `save`; Cancel calls `pause` only while downloading. Added Compose UI regressions for paused downloads at zero/nonzero bytes and for downloading; both pass on Infinix X6731 / Android 14. The 388 JVM tests, lint, debug build and 68 shared tests also pass. Accepted streaming/download behavior is unchanged.
