# PR #164: MOBILE 1.2 design review fixes

Date: 2026-10-28 (Asia/Manila). Branch: `feat/4-mobile-discovery`.
Existing PR: https://github.com/BootlegYouki/L.A.R.A/pull/164, targeting staging.
Preflight: clean working tree, origin BootlegYouki/L.A.R.A, HEAD
`21c95626c450fa9c1e2ae7e51d6090d98fddffab`. No branch creation or base changes.
The complete Issue #4, PR review/comments and review threads were read; there
were no inline review comments or review threads. No PR was updated.

## Files changed

Modified production files: `DiscoveryScreen.kt`, `ui/theme/Color.kt`, and the
English/Filipino `strings.xml` resources. Added: `DiscoveryFeedback.kt`, bundled
`ic_check_circle.xml` / `ic_warning_circle.xml`, and `DiscoveryFeedbackTest.kt`.
Documentation: `mobile/docs/discovery.md` and this evidence directory.
[Exact modified/new file inventory](scope.txt).

## Seven requested corrections

| Review item | Implementation |
| --- | --- |
| 1. Banners | Amber warning fill/border, Warning Circle icon and canonical warning banner ink for unavailable, disconnected and interrupted states; green Check Circle success after RESPONDING only. Bold title through colon followed by one sentence; polite live region. |
| 2. Hub pill | White bordered pill, 10dp colored dot plus localized Searching / Connected / Not connected. No discovered row can imply success. |
| 3. Input | Label above, 2dp border with green focus and red invalid states, icon/message below, ASCII keyboard for dot/colon, Done checks the existing validator. |
| 4. Spacing | Existing spacing tokens separate sections, label, field, feedback, helper and button; 52dp secondary / 56dp primary minimums retained. |
| 5. Empty state | Encouraging English/Filipino text immediately under nearby-Hubs heading, plus scan placeholders while searching; retry/manual fallback remain usable. |
| 6. Order | Discovered Hubs and Scan again precede manual input in the scrollable list. |
| 7. Roadmap | Removed account-update sentence and unused discovery_scope strings in both languages. |

All edits are inside mobile/. No networking, validation, endpoint, persistence,
ViewModel, navigation, manifest, dependencies or shared design tokens changed.
`ui/theme/Color.kt` now mirrors the existing canonical warning banner ink
(#93370D, design system 1.3); this is not a newly designed color. Mandatory 4dp
spacing-grid values take precedence over off-grid showcase CSS spacing, as
explained in [discovery documentation](../../../discovery.md).

## Before / after evidence

All screenshots are real device captures. Model 2511FPC34G, Android 16/API 36,
1268x2756 screenshot size. The initial locked-device black capture was replaced
with a fresh unlocked-device capture before APK replacement. Existing older
screenshots were left intact.

- [Before: invalid address](before-device.png), [UI tree](before-device.xml).
- Existing before states: [unavailable](../manual-unreachable.png),
  [responding](../manual-success.png), [disconnected](../discovery-offline.png).
- [After: scanning and discovered Hub](after-discovery.png), [UI tree](after-discovery.xml).
- [After: verified response and green Connected pill](after-success.png), [UI tree](after-success.xml).
- [After: validation below input](after-invalid.png), [UI tree](after-invalid.xml).
- [After: unavailable port, amber warning](after-unavailable.png), [UI tree](after-unavailable.xml).
- [After: Wi-Fi off, disconnected and empty](after-offline.png), [UI tree](after-offline.xml).
- [After: Filipino offline screen](after-filipino.png), [UI tree](after-filipino.xml).

The fresh before/after invalid state uses the same phone and empty input.
Old unavailable/success screenshots are from the same model but earlier runs,
so they are comparable states rather than pixel-identical reproductions.

## Automated verification

| Check | Result / evidence |
| --- | --- |
| assembleDebug, test, lint, lintDebug, testDebugUnitTest | PASS, BUILD SUCCESSFUL; [final Gradle output](gradle-checks-final.txt) |
| Unit tests | PASS, 16 debug + 16 release executions, 0 failures/errors; [suite summary](unit-results.txt) |
| Android lint | PASS, 0 errors, 4 warnings; [warning list](lint-summary.txt) |
| Repository tests | PASS, 66 tests; [output](repository-tests.txt) |
| Invariant/localization scanner | PASS; [output](invariants.txt) |
| git diff --check | PASS |

The three new tests exercise beacon transitions: discovery alone cannot imply
connection, only a successful probe enables Connected, and loss/interruption do
not imply Searching. Existing URL-policy, beacon/parser/expiry, probe and
ViewModel lifecycle tests still pass. No tests were removed or relaxed.

Gradle from mobile/ (JDK 17, existing SDK and cache):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
$env:ANDROID_USER_HOME = "$env:USERPROFILE/.android"
$env:GRADLE_USER_HOME = "$PWD/.gradle-user-home"
.\gradlew.bat assembleDebug test lint lintDebug testDebugUnitTest --offline --console=plain '-Pkotlin.compiler.execution.strategy=in-process'
```

Python checks from root used `$env:PYTHONUTF8='1'` to avoid the Windows console
encoding error on the scanner's Unicode status symbols. An initial sandbox build
could not access Android's user directory; normal approved execution succeeded.
The first successful build fell back from the Kotlin daemon after a local path
error; the final run uses a command-line in-process setting, with no Gradle config
change. APKs, Gradle reports and caches remain ignored generated files.

## Device and mock verification

APK update with `adb install -r` succeeded and retained app data. Cold launch
returned Status ok, TotalTime 799ms / WaitTime 802ms. Automatic same-router UDP
Hub discovery was observed, followed by a successful public /download probe after
the user tapped the discovered row. [Device log](device-log.txt) includes multicast
lock lifecycle and observed discovery timing. Timing samples are not a guarantee.
The user also checked 192.168.100.12:18082 and the app showed the amber
unavailable banner and muted Not connected pill. With Wi-Fi manually switched
off, it cleared discovered Hubs and showed disconnected feedback and the
encouraging empty-state message without a blocking dialog. This is Wi-Fi-off
coverage, not a WAN-unplugged classroom test or an airplane-mode test.

Filipino rendering was checked using Android's canonical `fil` per-app locale
and a cold launch. (An initial `tl` command still rendered English, so it was not
accepted as Filipino evidence.) The final Filipino screenshot shows localized
pill/banner, heading, empty state, label, helper and buttons without clipping on
the tested phone. The original app locales `[]` (system default) were restored.
Published logs are normalized to UTF-8/LF; device evidence retains only the
LaraDiscovery tag, excluding unrelated phone-app runtime logs.
The connected phone blocks ADB input injection; taps/typing were performed by the
user, without changing device security settings.

Default mock startup on 8080 failed WinError 10013, including outside sandbox:
[startup output](mock-hub.txt). The unchanged MockHubServer class was started with
HTTP 18080 / WS 18081 and UDP 8888: [mock log](mock-alternate.txt). No firewall,
shared script or OS networking configuration was modified. Its beacon advertised
192.168.100.12:18080, which the phone discovered. This verifies LAN operation;
physical WAN disconnection has not been claimed. The temporary mock was stopped
after testing.

The installed emulator could not boot: its Android hypervisor driver is missing.
See [emulator diagnostics](emulator-stdout.txt). No driver or BIOS changes were
attempted. Emulator-specific 10.0.2.2:8080 behavior remains unverified this run.
The emulator cannot replace real-device multicast reliability testing.

## Remaining verification limits

Not run: successful manual check on the updated APK (discovered-row success was
verified; manual validation and unused-port failure were verified), interrupted
scanner fault injection on a device, emulator 10.0.2.2:8080, real-Hub mDNS,
Transsion/realme reliability, smaller physical screens/larger system fonts,
Filipino success/invalid states, airplane mode and physical WAN disconnection.
Existing automated networking/validation tests cover their original edge cases.
No current automated check is failing. The four lint warnings are existing
manifest/cleartext/backup/icon concerns; none was suppressed.

## Human review handoff

Suggested commit: `fix(mobile): address discovery design review (#4)`

Suggested reviewer response:

> Addressed all seven requested design corrections: warning/success banners,
> labeled Hub beacon, input label/border/error treatment, spacing, empty state,
> nearby-Hubs-first ordering and removal of discovery roadmap copy. Networking
> and endpoint validation remain unchanged. Before/after captures and exact
> verification results are in mobile/docs/evidence/4/review-fixes/. Android build,
> 16 unit tests per variant, lint, 66 repository tests and localization/invariants
> pass. Emulator startup is blocked by its missing acceleration driver; real
> phone evidence covers discovery, responding Hub, invalid input, unavailable port,
> Wi-Fi-off and Filipino offline layout. Connected here denotes verified portal reachability,
> not account authentication.

Reviewer procedure:

1. Run the unchanged mock (default 8080, or documented alternate ports if blocked).
2. Build/install the APK without clearing app data. Confirm nearby Hubs precede
   manual entry and discovery alone shows no connected status.
3. Select a Hub: expect green banner and Connected pill only after portal response.
4. Check blank/malformed input: expect red outline and icon/message below input.
5. Check an unused port: expect amber unavailable feedback. Disconnect classroom
   Wi-Fi: expect amber disconnected feedback and no stale Connected status.
6. Stop the mock and allow the ten-second advertisement lease to expire; retry
   to clear an old probe result. Expect calm empty text, rescan and manual fallback.
7. Repeat in Filipino, with a narrow screen / larger fonts, and WAN unplugged.

At the initial review handoff, no commit, push, PR update, thread resolution or
merge had been performed. The requested
`.agents/skills/lara-mobile/SKILL.md` remains a separate follow-up PR after these
fixes are reviewed and merged; it is intentionally absent from this change.
