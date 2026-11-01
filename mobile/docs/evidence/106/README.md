# MOBILE 2.6 validation

Validation date: 2026-11-01. Base: staging `56d4650` (merged contract PRs #196/#197 and mobile migration PR #198).

## Automated checks

- `mobile/gradlew.bat --max-workers=2 test lint assembleDebug assembleDebugAndroidTest`, with `LARA_SYNC_MOCK=1`, `LARA_APPROVAL_MOCK=1`, `LARA_SETTINGS_MOCK=1`: successful; 135 tests per Debug/Release variant, zero failures/skips. Lint: zero errors, 27 existing warnings. See `checks.txt`.
- `python -m unittest discover tests`: 68 tests passed against the merged shared source.
- `python scripts/verify_invariants.py`: offline/localization invariants passed.
- Physical Xiaomi 2311DRK48G, isolated package `org.lara.app.sprint26settings`: 43 tests passed on the final build; see `device-tests.txt`. The production package and its data were left untouched.

The phone groups cover 29 sync/migration tests; nine Settings, account-cache and actual-mock logout tests; four language/large-text/teacher-approval UI tests; and one REST approval test. Language UI tests use a 200% Compose text scale without changing the phone's global font setting. The queued-warning test invokes the real Settings ViewModel/repository and clicks the localized dialog actions; all queue/cache fixtures are synthetic.

## Normal-entry checks

- Learner header -> Settings, immediate English/Filipino changes, retained after cold launch.
- Cold launch with both test Hub USB routes removed: cached class opens, Settings is Filipino and reports the Hub unreachable.
- Cold launch in airplane mode with Hub routes removed: same offline behavior. Previous airplane-mode setting and USB routes restored afterward.
- Learner sign-out -> teacher sign-in -> teacher Settings; no previous learner mirror displayed.
- Teacher Change Hub -> discovery -> manual address -> responding Hub -> sign-in, without automatically reusing credentials.
- Learner signs in again and the original saved Science 4 class remains.

Screenshots show English teacher Settings and Filipino connected/offline Settings. The floating recording control is the phone's own overlay, not app UI. No credentials or learner identifiers are included.

## Remaining integration coverage

The local mock is reached through USB routes on custom ports; manual-address testing establishes REST/sign-in, while event tests explicitly use the fixture's WebSocket port. These checks do not replace testing against the real Rust Hub, a physical classroom router with its WAN unplugged, or vendor battery/3–4 GB hardware certification. Those remain integration/field follow-ups.
