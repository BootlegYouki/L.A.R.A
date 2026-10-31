# Mobile 2.4 approval UI fixes

## Changes

- Added localized, pulsing loading skeletons for the class panel and approval sheet.
- Dark-green approval actions: white on dark green 6.58:1; dark green on light-green badge 5.84:1.
- Explicit white sheet and 50% navy scrim; no inherited lavender surface.
- Fully expanded sheet with scrolling title/banner/roster and a fixed Cancel footer. The long Filipino offline message no longer hides all roster controls at 200% text size.

## Verification

- Full Android test/lint and both debug APK builds passed on an isolated copy of the branch. Only application ID and test-app label differ from source.
- Debug and release unit tests: 84 discovered each, 83 passed, one opt-in live-protocol test skipped, zero failures. The live protocol already passed during the preceding 2.3-to-2.4 integration recheck.
- Final phone regression: OK (2 tests), 14.222 seconds on Xiaomi 2311DRK48G. One English case and one Filipino / 200% font-size case.
- Each case verifies localized loading, failed decision rollback with a friendly error, visible and disabled offline Accept/Decline, reachable Cancel, successful rejection, automatic opening on a join event and successful approval.
- Screenshots were inspected visually. They contain synthetic name/LRN values only. The test refreshes the phone's accessibility cache and waits for updated states so stale UI nodes cannot produce false assertions.
- Offline/localization invariants passed. Shared contracts and mock tests passed (66 tests) in the preceding integration recheck; no shared or protocol code changed in this UI fix.

## Limits and isolation

The phone runs org.lara.app.sprint24fixed, labelled LARA 2.4 fixed. The regular org.lara.app was not replaced, cleared or uninstalled. Test resources apply language and text size within the test activity and restore its original configuration; phone-wide settings are unchanged.

These tests inject synthetic saved-roster fixtures into the production view model and UI. They do not create production enrollment identities, implement sync or prove fresh-install data delivery. The authoritative-row producer and final newly synced learner-unlock/offline-relaunch flow remain a Mobile 2.5 integration requirement. A physical classroom router test with WAN disconnected was not performed. No 2.4 PR has been opened.
