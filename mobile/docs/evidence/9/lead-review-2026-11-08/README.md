# Lead review verification — 2026-11-08

Based on staging `87f4413` and its #257 homework contract. This supersedes the original Room-v2 and universal-APK notes in the November 7 evidence.

## Local checks

- `./gradlew test lint assembleRelease` passed. Debug and Release each ran 166 tests with zero failures or skips. All three mock-backed test flags were enabled.
- Python 3.11.9 ran all 68 shared contract/mock tests successfully. Native Windows host, JDK 17.0.18 and Android SDK 35.
- The exact changed-area, branch-flow and AI co-author shell steps from the workflows passed under Git Bash. Only Mobile is changed, so the workflow skips Desktop and Server jobs.
- The offline invariant scanner passed. The exact Android job command `./gradlew lintDebug testDebugUnitTest --stacktrace` also passed in 5m 21s.
- Offline turn-in timestamps, transport serialization, Hub receipt storage, permanent late/graded rejection and queue retention are tested against the updated mock.
- Real v2 database upgrade preserves a queued photo reference, turn-in time and session key. Existing v1 migration fixtures pass through both migrations. Native v1→v3 migration and three native scanner regressions also passed.

The first focused run caught a schema-version assertion that still expected v2. After correcting it, the full suite passed. The first benchmark could not trigger the Compose shutter with framework text lookup. Traversing its accessibility nodes fixed the harness, and the complete benchmark passed in 9.643 seconds.

## 3 GB emulator budget

The Lead explicitly accepted a 3 GB emulator for this PR. AOSP Android 9 / API 28, x86_64, 720×1280, 3072 MiB configured RAM, two cores, SwiftShader graphics and Google AEHD 2.2 hardware acceleration. Android reported 3,149,860,864 bytes physical memory after kernel reservations. Android Emulator 37.2.13.

| Measurement | Result |
|---|---|
| Peak combined Java/native allocated heap | 143,334,624 bytes, 136.69 MiB, sampled every 5 ms |
| Camera JPEG compression | 13.367 ms, 17,539 bytes |
| 12 MP scanned JPEG compression | 48.358 ms, 117,546 bytes |
| Direct 12 MP compression, three runs | 90.579 / 72.445 / 60.094 ms, 127,282 bytes each |
| Camera normalize, detect and render | 508.001 ms |
| 12 MP normalize, detect and render | 1,582.569 ms |

`HomeworkBudgetDeviceTest` drives the actual CameraCaptureScreen shutter, then runs normalize/detection/enhancement/JPEG compression. It also processes a synthetic 4000×3000 document and measures direct compression three times. A background sampler includes native allocations because Java heap alone excludes bitmap/OpenCV memory. Assertions enforce combined allocated heap below 250 MiB and JPEG compression below 500 ms. Processing/scan times are reported separately. The emulator supplies the camera scene, and the larger fixture is synthetic.

Raw output is in `budget-device-test.txt` and `budget-capture-report.txt`. `native-regressions.txt` records four further native tests.

### Reproduce the emulator test

Install Android SDK emulator and `system-images;android-28;default;x86_64`, configure an AVD with 3072 MiB RAM, and launch it with hardware acceleration. Use an isolated test app to preserve existing device data. Save this Gradle init file outside the repo.

```groovy
allprojects { p ->
    p.afterEvaluate {
        def android = p.extensions.findByName('android')
        if (android != null && p.name == 'app') {
            android.defaultConfig.applicationId = 'org.lara.app.sprint32homework'
            android.splits.abi.reset()
            android.splits.abi.include('x86_64')
        }
    }
}
```

Build with `./gradlew -I <init-file> assembleDebug assembleDebugAndroidTest`, install the x86_64 app APK and instrumentation APK on that AVD, then run the following with its serial.

```sh
adb -s emulator-5556 shell am instrument -w -r -e class org.lara.app.reading.HomeworkBudgetDeviceTest org.lara.app.sprint32homework.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5556 shell run-as org.lara.app.sprint32homework cat files/budget-capture-report.txt
```

## ARM-only release APKs

| Unsigned release APK | Bytes | MiB | Packaged native ABI |
|---|---|---|---|
| app-arm64-v8a-release-unsigned.apk | 20,197,165 | 19.26 | arm64-v8a |
| app-armeabi-v7a-release-unsigned.apk | 18,487,291 | 17.63 | armeabi-v7a |

`release-apks.json` includes SHA-256 hashes and inspected native-library ABI directories. Default builds produce these two splits, with universal APK generation disabled. The temporary emulator override applies only to its test build. Production release signing remains part of distribution setup.

## Remaining follow-ups

Per the Lead's review, GitHub Actions billing and real-Hub/isolated-Wi-Fi testing are handled by the Lead and integration day. Physical 3–4 GB phones and additional lighting conditions remain follow-ups beyond this accepted emulator measurement. The local Windows checks do not establish a passing Ubuntu-hosted Actions run.
