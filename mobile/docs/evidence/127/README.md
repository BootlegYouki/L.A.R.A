# MOBILE 3.5 verification

Base: `origin/staging` `c9e2a01` (includes MOBILE 3.4 through #332). Feature branch: `mobile/3.5-classwork-authoring`. Host: 8 GB Intel Mac, JDK 17, Android SDK 35. Gradle uses one worker, in-process Kotlin, and a 1536 MiB Java heap.

## Environments and limits

- Shared checks use the existing temporary Python environment with PyYAML; no shared files changed.
- The authoring integration test uses an unchanged imported `scripts/mock_hub.py` on 38080/38081. The pre-existing Python service on 8080 is left alone.
- Physical device: **OPPO CPH2179, Android 10**, not the requested Infinix. No emulator was started.
- Phone traffic uses **ADB reverse USB**, not Wi-Fi. The disposable proxy on 38082 forwards to 38080, throttles material bodies to 4 MiB/s, and injects one full-body 503 before forwarding the retry. This proves a controlled retry, not a WAN-disconnected classroom or vendor battery-kill behavior.
- The device test uses the real authoring component/repository/worker with a synthetic authenticated teacher session. It temporarily replaces and restores encrypted-session and endpoint preferences, without clearing existing app data. UI screenshots use an Activity component harness with system insets; they are not screenshots of normal login/navigation. The harness sets offline and 200% font states explicitly.
- A 220 MiB **synthetic `.mp4` payload** stresses transfer memory. It is not encoded/playable video, and does not establish real-Hub media-sniffing compatibility. Samples use Android `Runtime` and native-heap APIs, not Android Studio Profiler. Do not describe them as an Android Studio profiler screenshot.
- Mobile's existing four-digit sign-in validator conflicts with six-digit Mock Hub seed PINs. Tests bypass that validator with contract-level test authentication only. Production sign-in is unchanged; normal seeded sign-in is not verified here.
- No prebuilt real Hub was found locally. No Rust build was started. SERVER 3.4 (#54) and 3.5 (#122) are closed, but this PR's real-Hub verification remains **NOT TESTED**.
- No router/uplink was controlled. WAN-disconnected evidence is **NOT TESTED**.

## Reproduce

1. From the repository root run `python3 mobile/docs/evidence/127/isolated-mock.py`.
2. In another terminal run `python3 mobile/docs/evidence/127/upload-proxy.py` (one fresh proxy instance per injected-failure device run).
3. Enable the optional unit integration test with `LARA_AUTHORING_MOCK=1` and use the memory-conscious Gradle command in [classwork-authoring.md](../../classwork-authoring.md).
4. Build `assembleDebug assembleDebugAndroidTest`, install the ARM64 APK and test APK using `adb install -r`, and map `adb reverse tcp:38082 tcp:38082` plus `adb reverse tcp:38081 tcp:38081`.
5. Run `adb shell am instrument -w -e class org.lara.app.teacher.ClassworkAuthoringDeviceTest org.lara.app.test/androidx.test.runner.AndroidJUnitRunner`. Keep the phone unlocked. Only export the `authoring-*.png` and `authoring-memory.csv` test artifacts from app-private storage; never export the whole account directory.

## Scope notes

The user explicitly requested bilingual authoring despite the English-only repository rule. English/Filipino parity and format placeholders for authoring strings are checked by `AuthoringResourcesTest`. `app/lint.xml` exempts only the existing default resource file from MissingTranslation noise for unrelated English-only screens; the unit parity test enforces this feature's new keys. This does not translate the old app or add a language selector.

The Lead's freeze announcement #297 says not to start new step issues. This work follows the user's explicit request for #127; the PR targets staging and is not merged by the agent.

## Confirmed automated results

- Final app build: PASS (`final-app-build.log`). It includes the screenshot-driven correction to use the green progress track explicitly.
- Unit tests: PASS in debug and release: 210 tests per variant, zero failures, six pre-existing opt-in tests skipped. All five new authoring test cases ran, including Mock Hub integration (`unit-summary.log`). The command enables `LARA_AUTHORING_MOCK=1`.
- Shared contracts/Mock Hub suite: PASS, 72 tests (`shared-tests.log`).
- Offline invariant guardrail: PASS (`invariants.log`).
- Full `test lint` command: FAIL because lint reports three pre-existing errors in untouched `MaterialReader.kt`/`VideoPlayerScreen.kt` (`lint.txt`, `gradle-test-lint.log`). The earlier Mobile 3.4 verification document records the same three errors. No lint error was suppressed for those files.
- The first default-temp-path run hit four previously documented macOS path-alias assertions. Using `/private/tmp` passed both variants without changing those tests or implementation.

## Physical large-upload result

PASS: `phone-large-upload.log` reports `OK (1 test)`. The same test asserts receipt of the 220 MiB upload and rejects a 251 MiB content URI without adding another work request. `upload-proxy.log` records a full-body 503 and a successful retry, each reading 230,687,386 multipart bytes. The original large payload is 230,686,720 bytes; the remainder is multipart metadata.

251 Runtime/API samples during the transfer: **Java used heap peaked at 15.35 MiB**, native allocated heap at 15.54 MiB, combined sampled allocation peak **30.15 MiB**. See `large-upload-memory.csv`, `memory-summary.log`, and the Android `dumpsys meminfo` snapshot. These numbers establish the tested component/worker budget, not a 40-learner load test or Android Studio Profiler capture.

The large-upload progress screenshot was captured before a visual-only track-color correction. It exposed Material's default lavender track, which was then explicitly changed to L.A.R.A. green tokens. Final screen evidence is labelled separately; the worker/streaming implementation is unchanged by that correction.

## Interrupted screen verification

The phone created an assignment through the form. The subsequent automated screen run stopped during file-picker dismissal, and the revised device-test driver was not run before the session ended. Its expanded edit/delete/topic flow, final green-track screenshot, actual Hub-unreachable UI, 200% text screenshot, and normal learner-app sync remain **NOT TESTED**. The earlier upload screenshot shows the pre-correction track; the final app build contains the green-token correction. Unit and shared test results above were captured before that visual-only correction.
