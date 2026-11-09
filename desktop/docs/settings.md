# Desktop Settings and sign-out

Settings shows the selected Hub's name, address and status. **Choose another Hub** reuses the existing discovery and address picker. Settings also shows the signed-in account and the version from the Tauri app config. The current PRD §5.3 and design system use English across the app. Settings adds its copy to the English resources and keeps the existing Hub preference format.

Sign-out counts queued rows across the four uploadable tables for the selected account using the same native context guard as sync. The confirmation stays disabled until the count is available. After confirmation, the app pauses sync and invalidates outstanding account callbacks. It clears the local session through the existing credential cleanup and sends the contracted logout request when it has a reachable Hub and token. If the Hub cannot be notified, the sign-in screen explains that the device signed out locally. The account's SQLite database stays available for the next sign-in, with queued work intact.

## How to test

1. Sign into a synthetic account and open **Settings**. Check the Hub address, status and version. Open **Choose another Hub** and select a discovered Hub or enter its address.
2. Select **Sign out**, then **Cancel**. Check that Settings still shows your account. Confirm sign-out and check that the sign-in screen appears.
3. Use a test account with queued work. Stop the test Hub, then sign out from Settings. Check the item count and the notice that the Hub could not be told. Close and reopen Desktop, then bring back the Hub and sign into the same account. Check that its queued work remains.

## Verification

- 95 frontend tests, lint, TypeScript/Vite build, and optimized Tauri build passed.
- 24 native tests passed and 3 existing opt-in OS/network tests were skipped. 67 shared tests and the offline invariants passed.
- In the Windows production window, online logout on both the mock and real Hub made the previous bearer return 401 and cleared the native restore pointer. Hub switching was exercised through Settings.
- With the real Hub stopped, a synthetic queued submission was counted in the warning. Cancel preserved the account and row. Confirming cleared the local session and showed the offline notice. The app remained signed out after a full process restart. After the same learner signed back in, the original row was still queued and counted again.
- WAN unplugging, multi-device operation, and other platforms were not exercised. The submission was a local test fixture for retention, so this does not verify photo upload.
