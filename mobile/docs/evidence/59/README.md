# Issue #59 review evidence

Date: 8 November 2026 (Asia/Manila). Working branch: `feat/59-mobile-3.3-video-offline`.
Base: `86204fef6979e175aa158c512e98e0d1abced4ef`, the merge of MOBILE 3.2 PR #252 into staging. See feature-branch history for the implementation commit. The user authorized publication after implementation and phone verification. No merge was performed.

## Preflight and decisions

- [Issue #59](https://github.com/BootlegYouki/L.A.R.A/issues/59) is open; no comments were present when inspected. MOBILE 2.5 #52 is closed and prerequisite [PR #252](https://github.com/BootlegYouki/L.A.R.A/pull/252) is merged. No other Mobile feature PR was open when checked. `git fetch origin` succeeded before branching; the initial checkout was clean.
- Read [discussion #242](https://github.com/BootlegYouki/L.A.R.A/discussions/242), [#194](https://github.com/BootlegYouki/L.A.R.A/discussions/194), [#215](https://github.com/BootlegYouki/L.A.R.A/discussions/215), their comments, and the current discussion list. #242 is the latest applicable process announcement. Also read #52, #9 and PR #252, including the lead's final review requesting a large-font bottom-nav label fix.
- Server [PR #254](https://github.com/BootlegYouki/L.A.R.A/pull/254) was OPEN/DRAFT when inspected. Its branch was not merged or used for integration.
- Missing `.agents/skills/lara-mobile/SKILL.md`: user authorized existing Android skills after `lara-architect`, and directed that the missing skill be a separate process follow-up.
- Current repository instructions require English-only UI (#223/#224), while the original master prompt requested Filipino. After the conflict was reported, the user explicitly chose the current English-only repository policy. No translation suppression or language toggle was added.
- Files stay inside `mobile/`. The local `.gitignore` narrowly includes source packages named `media`, which the root runtime-media pattern otherwise hides.

Text logs are normalized to UTF-8/LF with trailing whitespace removed; test output content is retained.

## Automated evidence

Final result: debug build passed; 194 Debug and 194 Release tests passed with zero failures, errors or skips (388 total). Each variant includes 28 media tests. Lint passed with zero errors and 39 warnings. After the phone connected, four physical-device tests passed on the Infinix X6731 / Android 14, including actual airplane-mode playback and seeking. See the [phone report](./phone/README.md) for results and screenshots.

| Evidence | Meaning |
| --- | --- |
| [gradle-build-test-lint.txt](./gradle-build-test-lint.txt) | Actual debug build, Debug/Release tests and lint output |
| [test-counts.txt](./test-counts.txt) | Counts read from the final JUnit XML, including failures and skips |
| [lint.txt](./lint.txt) | Actual lint text report |
| [shared-tests.txt](./shared-tests.txt) | 68 contract/mock/guardrail tests passed |
| [invariants.txt](./invariants.txt) | Repository offline invariant check passed |
| [mock-ranges.txt](./mock-ranges.txt) | Unchanged Python mock: auth, forward/backward bounded 206 requests, 401 and 416 |
| [real-hub.txt](./real-hub.txt) | Actual real-Hub launch attempt: `cargo` is unavailable |
| [device.txt](./device.txt) | Initial absent-device state and emulator launch limitation; superseded by the phone run |
| [phone/README.md](./phone/README.md) | Four passed physical-device tests, screenshots, memory samples and restored settings |
| [device-build.txt](./device-build.txt) | Final isolated app/instrumentation build output |
| [artifact-info.json](./artifact-info.json) | Isolated APK IDs, paths, sizes, SHA-256 and synthetic fixture hash |
| [changed-files.txt](./changed-files.txt) | Complete changed/new Mobile file list |

The media tests cover bounded continuous reads and forward/backward byte seeks through Media3's real `OkHttpDataSource`, bearer headers, expired/wrong-account credentials, redirects, 200-on-resume, If-Range, interruption, cancellation including a blocked body, storage failure, invalid ranges, truncated bodies, final-byte equality, material-version races, duplicate saves/pruning, Room receipt, stale paths and local-first selection with no token. ViewModel tests cover retry, pause, duplicate save suppression and stop/start cleanup. `VideoMockIntegrationTest` runs the actual Mobile transport against the unchanged Python mock.

## Reproduce the Windows checks

Use the repository's existing Java 17 and Android SDK setup. Start separate unchanged `MockHubServer` instances for HTTP/WS ports `18080/18081` (sync), `28080/28081` (approvals) and `38080/38081` (settings). From a root terminal, use this Python form for each pair:

```python
import sys, time
sys.path.insert(0, 'scripts')
from mock_hub import MockHubServer
hub = MockHubServer(http_port=18080, ws_port=18081, udp_port=18888)
hub.start()
try:
    while True:
        time.sleep(1)
finally:
    hub.stop()
```

Run the exact task set from `mobile/`:

```powershell
$env:GRADLE_USER_HOME = 'C:/Users/DJ Rapmasta/.gradle'
$env:LARA_SYNC_MOCK = '1'
$env:LARA_SETTINGS_MOCK = '1'
$env:LARA_APPROVAL_MOCK = '1'
.\gradlew.bat -I build/59-test-env.gradle assembleDebug test lint --console=plain
```

This machine's default temp directory uses an 8.3 short username. Several existing tests compare canonical and noncanonical paths, and SQLite's native test driver also hits Windows path-length limits. The generated local Gradle init file changes only the test JVM's `java.io.tmpdir` to a short, isolated writable directory (recorded in `environment.txt`). It changes no production code, tests, assertions or lint rules. Equivalent init file:

```groovy
allprojects { p ->
    p.tasks.withType(Test).configureEach {
        systemProperty 'java.io.tmpdir', '<your-short-test-temp-directory>'
    }
}
```

The first runs exposed Windows temp-path issues, missing extra mock instances, and the conflicting translation requirement. Those were resolved by the documented test environment and the user's English-only decision. An image-decoder diagnostic comes from existing deliberately invalid image fixtures; passing JUnit XML determines the result. Existing kapt/Room and dependency-update warnings remain visible; no checks are suppressed.

Root checks need UTF-8 for emoji output and subprocess decoding on Windows:

```powershell
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
python scripts/verify_invariants.py
python -m unittest discover tests
```

The independent raw mock probe is `python mobile/tools/video_mock_probe.py`; run it with `28080/28081` free. It creates and stops its own fresh mock without editing the shared implementation.

## Device test procedure

`VideoDeviceTest` refuses to run over the production package. Build `org.lara.app.sprint33video` using this local Gradle init file, leaving the repository application ID unchanged:

```groovy
allprojects { p ->
    p.afterEvaluate {
        def android = p.extensions.findByName('android')
        if (android != null && p.name == 'app') {
            android.defaultConfig.applicationId = 'org.lara.app.sprint33video'
        }
    }
}
```

Build with `gradlew.bat -I <init-file> assembleDebug assembleDebugAndroidTest`. Verify the authorized serial, model, SDK and ABI with `adb devices -l` and `adb -s <serial> shell getprop`. Install the matching ARM APK and instrumentation APK with `adb -s <serial> install -r <apk>`. Never uninstall or clear the production app. Run these methods in order with `am instrument -w -r -e class <class>#<method> org.lara.app.sprint33video.test/androidx.test.runner.AndroidJUnitRunner`:

1. Class `org.lara.app.reading.VideoDeviceTest`, method `aStreamSeekInterruptResumeAndSave`: synthetic loopback HTTP fixture, rendered native H.264 frame, seeks to 15s/3s, bounded authenticated requests, cancel at 32 KiB, If-Range resume, full SHA-256 and Room path.
2. Enable airplane mode and disable Wi-Fi on the test device, preserving original settings for restoration. Cold-run method `bSavedVideoRendersAndSeeksWithoutHub`: native local-file rendering/seeking with stopped fixture server and expired session. Read `files/video-device-offline.txt` through `run-as`; it records the actual airplane-mode setting.
3. Method `cEnglishAndLargeFontControls`: actual player screen at normal and 200% font scale, 56dp play target, English controls, screenshots. Read `files/video-ui-en-1.png` / `video-ui-en-2.png` using `run-as`. Restore original device radio settings afterward.
4. Method `dSaveForHomeUiShowsProgressAndPlaysLocal`: tap Save for Home on the actual player screen with a throttled fixture, capture visible progress, verify Room/local SHA-256 and play the saved file.

The test asset `app/src/androidTest/assets/video-lesson.mp4` is a generated 20-second 320×180 H.264 test pattern (no people, lesson content or private data). Generation used temporary FFmpeg tooling, `testsrc2=size=320x180:rate=24`, `-t 20 -c:v libx264 -pix_fmt yuv420p -movflags +faststart -g 24`. It is used only by tests, never bundled in the production app. Native fixture results must be labelled separately from mock-Hub or real-Hub results.

The temporary mock processes started for verification were stopped afterward. Production app data and router settings were untouched; the phone's temporary airplane/Wi-Fi changes were restored to their original values.

## Not verified and integration blockers

- **Device checks:** native rendering, seeking, interrupted save/resume, Room persistence, airplane-mode playback, English/large-font controls and UI Save for Home passed on the connected Infinix X6731 / Android 14. The phone reports about 11.2 GiB physical memory. Allocation samples are not peak or budget-phone measurements; those remain unverified. The earlier emulator acceleration failure remains an environment note.
- **Mock video decoding and real resume:** the mock's `/stream` supplies ASCII zero bytes, and `/download` supplies a 29-byte PDF placeholder even for VIDEO. It ignores download Range. This is recorded in `mock-ranges.txt` and exercised by `VideoMockIntegrationTest`; Mobile correctly refuses to mark that response as a saved video. Seeking byte ranges passed; decoding a lesson video against this mock is not claimable. Lead/Server follow-up: supply a permitted decodable fixture and matching downloadable bytes in a separate shared-contract/mock change.
- **Real Rust Hub:** the documented staging launch was attempted with `LARA_TEST_SEED=1` and an isolated Mobile build data directory, but `cargo` is not installed/available. No real-Hub response was observed. Source inspection of `server/backend/src/routes/materials.rs` at the base revision finds stubs; that is not an observed `501`. Run the real smoke flow after a Rust toolchain/Hub is available. Keep #254 out of this branch.
- **WAN-unplugged LAN streaming/saving:** not run. No router setting or WAN cable was changed. Keep Ethernet/Wi-Fi on the same router, disconnect only its WAN cable manually, and test streaming, seeking and saving from the PC's LAN IP (`192.168.100.12` when inspected; recheck before use). This is distinct from airplane-mode local playback. The current mock bytes cannot prove native video playback; repeat after decodable fixtures or real material routes are available.
- **Physical 3–4 GB phone memory/vendor behavior:** not measured. The player has a bounded buffer, but a passing native heap/battery measurement is still required on appropriate hardware.

Implementation, automated checks and physical-phone playback/UI checks can be reviewed. Real-Hub, WAN-unplugged LAN and target-budget-hardware acceptance remain open. There are no fabricated screenshots or claimed WAN results.

## Suggested commit and PR

Commit: `feat(mobile): stream lesson videos and resume offline saves (#59)`

PR title: `[MOBILE 3.3] Add authenticated video playback and Save for Home`

Suggested description is in [PR_DRAFT.md](./PR_DRAFT.md). Review all Not Verified entries before deciding readiness. The user subsequently requested a PR; its draft status keeps the outstanding integration checks explicit.
