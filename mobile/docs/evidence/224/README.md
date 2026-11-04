# MOBILE English-only cleanup — #224

Based on staging `8f70ad7`, after #209 and the English-only rule change #223 merged.

## Changes

Removed `values-tl`, the Settings language card and its unused labels, durable language store, app/container/ViewModel wiring, and Compose locale/popup overrides. All screens now resolve the bundled English resources through normal Android fallback. Existing language preferences are ignored; no account database or queued work is cleared. Chat-message language fields and learner input are unchanged.

The retired preference persistence test was removed. Settings ViewModel and queued-work cancellation/confirmation checks remain. The former Filipino approval test now covers English at 200% text. `SettingsScreenDeviceTest` checks English Settings under a Filipino resource configuration, absence of language controls, and writes `settings-english-fil-locale.png` in the test app's private files when run.

## Verification

Final mock-enabled build: BUILD SUCCESSFUL in 3m 36s. Debug and Release: 140 tests each, zero failures/errors/skips. Lint: zero errors, 24 existing warnings. App and instrumentation APKs assembled. Shared checks: 66 passed; invariants passed.

See `build-tests-lint.txt`, `shared-checks.txt`, `invariants.txt` and `test-counts.txt` for final results.

## Pending phone evidence

No phone was detected. The user requested automated checks first. The device tests have been compiled but not executed for this change. Still required: launch under a Filipino phone/system locale (including an upgrade with a former Filipino preference), normal/empty/offline Settings screenshots and 200% text review. Use a separate test package; do not replace or clear the user's installed app. Physical WAN-disconnected router, real Hub integration and budget-phone certification were not repeated for this resource-only cleanup. Historical evidence elsewhere is unchanged.
