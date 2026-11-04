# MOBILE English-only cleanup — #224

Based on staging `8f70ad7`, after #209 and the English-only rule change #223 merged.

## Changes

Removed `values-tl`, the Settings language card and its unused labels, durable language store, app/container/ViewModel wiring, and Compose locale/popup overrides. All screens now resolve the bundled English resources through normal Android fallback. Existing language preferences are ignored; no account database or queued work is cleared. Chat-message language fields and learner input are unchanged.

The retired preference persistence test was removed. Settings ViewModel and queued-work cancellation/confirmation checks remain. The former Filipino approval test now covers English at 200% text. `SettingsScreenDeviceTest` checks English Settings under a Filipino resource configuration, absence of language controls, and writes `settings-english-fil-locale.png` in the test app's private files when run.

## Verification

Final mock-enabled build: BUILD SUCCESSFUL in 3m 36s. Debug and Release: 140 tests each, zero failures/errors/skips. Lint: zero errors, 24 existing warnings. App and instrumentation APKs assembled. Shared checks: 66 passed; invariants passed.

See `build-tests-lint.txt`, `shared-checks.txt`, `invariants.txt` and `test-counts.txt` for final results.

## Final phone verification — 2026-11-04

Connected Xiaomi 2311DRK48G, separate `org.lara.app.english224` package. The original `org.lara.app` was not replaced, cleared or uninstalled.

- Final phone instrumentation: **8 tests passed**, 20.616 seconds. English Settings under Filipino resources, offline queued-work warning cancel/confirm with the queued row retained, four Settings persistence/sign-out behaviors, and English teacher approval rollback/offline/success at normal and 200% text.
- Android's per-app locale was set to `fil` and `tl` for cold launches. Settings remained English without language controls. The exact old `app_language.xml` preference (`language=tl`) was seeded in the isolated package and remained present and ignored; the synthetic teacher session survived. This is a legacy-preference fixture, not installation of an old APK. The phone's overall system language was not changed.
- With the local mock stopped, the cached account reopened without a blocking dialog and Settings remained usable. System font scale was temporarily set to 2.0; scrolling reached Sign out. Font scale restored to its original 1.0 afterward.
- Screenshot review found one stale offline banner reference to language settings. The final build removes that reference. After this wording-only fix, Debug/Release Settings ViewModel tests passed (2 each), lint passed (0 errors/24 existing warnings), both APKs built, and all 8 phone tests were rerun successfully. Full pre-wording-change suites remain recorded above.

Screens were visually inspected: `settings-connected-tl.png` (real mock-connected Settings), `settings-empty-offline-fil.png` (production Settings composable with no saved Hub fixture), `settings-offline-tl.png` (actual stopped-Hub state), and `settings-offline-200-top.png` / `settings-offline-200-bottom.png` (real 200% system text). The large-text screen scrolls; a partially clipped offscreen heading in the bottom screenshot is expected scroll positioning.

`device-tests.txt`, `cold-launch-checks.txt`, `offline-checks.txt` and `final-wording-build.txt` record results. USB reverse routes and test-app locales were restored after testing; the test app remains installed. Real Rust Hub, physical WAN-disconnected router and budget-phone certification were not repeated for this UI/resource cleanup.
