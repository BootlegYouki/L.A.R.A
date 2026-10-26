# MOBILE 1.1 validation evidence

Session date: 2026-10-26 (Asia/Manila). Branch `feat/23-mobile-scaffold`.
Starting HEAD and locally recorded `origin/staging` merge-base:
`e30c90eac5e7018cba187d0cbfdad59ab83cc8d0`. Working tree was clean.
Repository remote is `https://github.com/BootlegYouki/L.A.R.A.git`.

## Automated checks

| Command | Result | Evidence |
|---|---|---|
| `mobile\gradlew.bat assembleDebug test lint --console=plain` (from mobile, use `.\gradlew.bat`) | PASS, exit 0 | [Gradle output](gradle-checks.txt) |
| `python scripts/verify_invariants.py` | PASS, exit 0 | [Invariant output](invariants.txt) |
| `python -m unittest discover tests` | PASS, 66 tests | [Repository test output](repository-tests.txt) |
| `git diff --check` | PASS | No whitespace errors |
| Compare four theme files and six font files to shared originals | PASS | Byte-for-byte identical |
| English/Filipino resource key equality | PASS | Both directions checked |
| Changed and untracked source paths | PASS | All inside `mobile/` |

Commands ran with `GRADLE_USER_HOME=mobile/.gradle-user-home` and Python
`PYTHONUTF8=1`, `PYTHONDONTWRITEBYTECODE=1`. Without UTF-8, the initial invariant
run and four repository subprocess tests failed printing the success checkmark
to Windows cp1252. Re-running with UTF-8 passed without script changes.

Initial Android lint found an API-27-only navigation bar style attribute in the
minSdk-26 resources. Removed it and reran the build/tests/lint successfully.
Final lint: **0 errors, 8 warnings**: five dependency update notices, the explicitly
approved cleartext base-config, no custom launcher icon yet, and the Android-12+
data extraction rules recommendation (`allowBackup=false` is set). No warnings
were suppressed and no lint baseline was introduced. AGP also reports the newer
installed SDK XML format; the build succeeds. It installed its required licensed
Build Tools 34 automatically. compileSdk/targetSdk remain 35.

URL-policy suite: four JUnit methods, executed for both debug and release, zero
failures/errors/skips. Each method exercises a table of allowed ranges, boundaries,
lookalikes, malformed URLs, and secure URLs. Reports:
`app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/reports/tests/testReleaseUnitTest/index.html`.
Lint: `app/build/reports/lint-results-debug.html`.
APK: `app/build/outputs/apk/debug/app-debug.apk` (ignored build output).

APK SHA-256: `6ddbc1a03ad2de998cc328d53b048687fbb4a33def00ae2133475395ef27b303`.
Gradle wrapper JAR SHA-256:
`498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.

## Physical device

ADB recognizes phone model `2511FPC34G`, Android 16 / API 36.
After the user manually enabled airplane mode and disabled Wi-Fi, ADB read
`airplane_mode_on=1` and `wifi_on=0`. No device settings were changed by Codex.

**PASS:** after the user enabled Install via USB, installation succeeded. The
final APK cold launch reported `Status: ok`, `LaunchState: COLD`, total 615ms.
The initial device render exposed a blank navigation host; giving NavHost explicit
full-screen bounds fixed it. Rebuilt and reran tests/lint, reinstalled, cold-started,
and visually inspected the screenshots. The final app-specific crash buffer is empty.
The user manually opened About because this phone denies ADB input injection;
that destination was also captured and visually checked. The return button and
system Back are not separately device-verified. No device security settings were
changed by Codex. Initial install restrictions were resolved by the user.

- [Airplane-mode Home screenshot](airplane-home.png)
- [Airplane-mode About screenshot](airplane-about.png)
- [Device state and launch evidence](device-checks.txt)
- UI trees: `airplane-home.xml`, `airplane-about.xml`.

Home's primary action bounds are 182 physical pixels high at 520dpi (3.25 px/dp),
confirming 56dp. All UI strings have matching English/Filipino keys; Filipino
rendering on the phone and older Android versions were not manually tested.
A prior failed ADB-back attempt produced `airplane-return.xml`; it is not Back
navigation evidence and has been removed.

No mock-Hub UI integration test applies: MOBILE 1.1 has no network client,
discovery, login, DTOs or INTERNET permission. Repository tests exercise the mock
Hub independently. The placeholder does not claim real cached lessons exist.

## Reproduce offline evidence manually

1. Allow debug installation on the test phone; keep USB debugging connected.
2. From the repository root: `adb install -r mobile/app/build/outputs/apk/debug/app-debug.apk`.
3. Manually enable airplane mode, explicitly disable Wi-Fi, and unlock the phone.
4. Read `adb shell settings get global airplane_mode_on` (must be `1`) and
   `adb shell settings get global wifi_on` (must be `0`).
5. Cold-start with `adb shell am force-stop org.lara.app`, then
   `adb shell am start -W -n org.lara.app/.MainActivity`.
6. Check the calm disconnected placeholder, absence of a blocking error, and
   Home/About navigation. Capture with the phone's screenshot buttons or
   `adb shell screencap -p /data/local/tmp/lara-mobile-23-airplane.png`, then
   `adb pull /data/local/tmp/lara-mobile-23-airplane.png mobile/docs/evidence/23/airplane-home.png`.
7. For Filipino visual verification, manually select Filipino in device/app
   language settings, relaunch and capture again. A persistent in-app language
   toggle belongs to MOBILE 2.6.

## Proposed commit and PR (not created)

Commit: `feat(mobile): scaffold Android app and Hub URL policy (#23)`

PR title: `[MOBILE 1.1] Scaffold Android app, bundled theme and Hub URL policy`

Proposed body:

> Closes #23
>
> Adds the SDK-35/JDK-17 Android scaffold with Kotlin DSL, Compose/Room
> dependencies, type-safe placeholder navigation, bundled Nunito/theme assets,
> and English/Filipino disconnected and empty states. Startup requires no Hub.
>
> Implements the lead-approved XML cleartext correction and a tested literal-IPv4
> URL policy. MOBILE 1.2 owns endpoint validation; MOBILE 2.2 owns runtime
> HubClient enforcement. Room data, auth and full role navigation remain deferred.
>
> Validation: assembleDebug/test/lint pass (lint: zero errors, eight warnings),
> 66 repository tests and offline/localization guardrail pass. See this evidence
> directory for device results and the required airplane-mode screenshot.

No commit, push, PR, merge or history rewrite was performed.
