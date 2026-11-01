# Settings — MOBILE 2.6 (#106)

The branch includes merged staging 56d4650: contracts #196/#197 and mobile migration #198. Its feature diff is Settings, safe session/account handling and a small visual cleanup; no shared files or APIs are redefined.

## Screen and language

Settings is available from learner and teacher headers and from sign-in when a saved session needs reauthentication. It shows English/Filipino choices, saved Hub name/address/status, a change-Hub action, version/about and sign out. Existing tokens, rounded white cards, subtle borders/shadow and consistent spacing add depth without changing the role layouts. All actions are at least 52dp, with 56dp primary/confirmation actions. Content and the warning body scroll at large text sizes.

AppLanguageStore serializes durable preference writes. AppLanguage supplies localized resources to Compose immediately, without recreating the activity. PopupLanguage reapplies the language and text scale inside dialog/sheet Android owners; without it, those owners restored the device's English locale even when the page was Filipino. Both language packs stay bundled, independent of Hub/account state, so selection works offline and persists on cold launch.

## Safe sign-out and Hub changes

SettingsRepository checks the current account under the sync mutex and counts its four QUEUED_FOR_SYNC tables. A positive count requires confirmation; a larger new count requires another confirmation. Cancel preserves the session and clears the pending action. Local token clearing happens before best-effort authenticated POST /api/auth/logout. Missing Hub/connection failures do not prevent local sign-out. No saved/rejected/queued rows, files, profiles or cursors are deleted.

Change Hub uses the same sign-out/queue confirmation before opening the existing discovery/manual-IP screen. The page explains that it signs out the current account. Canceling a queued-work warning cannot leave a later ordinary sign-out pointed at discovery. A responding Hub returns to sign-in; old credentials are never automatically reused against a new address.

## Account cache isolation

AccountDatabases keeps the original lara.db with its existing current_user_id owner. Other accounts use separate app-private Room files named with a SHA-256 of the server user ID, with independent profiles, mirror rows and sync cursors. An empty original file can be claimed once; unowned nonempty data is retained without assigning it to a new account. No file is renamed, copied between users, wiped or destructively migrated.

AuthRepository accepts an account-specific DAO provider. A different account is refused while a token is still active; after explicit sign-out it can authenticate into its own cache. Returning to the old account reopens its original saved and queued work. Same-user Hub-address renewal remains supported. The existing fixed-DAO constructor remains for repository tests/adapters.

AppContainer shares language, endpoint, discovery, tokens and scheduling services, while authenticated repositories are scoped to the selected account's database. Each account container is reused so foreground actions and WorkManager share one sync mutex. DeltaSyncWorker prepares and syncs only the token's active account. No token means no signed-out upload. The session host gives each login a separate ViewModelStore and clears it on logout; session-generation checks prevent late callbacks from signing out a newer session. Reauthentication retains only the saved identity needed to access sign-out controls, not a grant to open a different Hub.

## Login-contract compatibility

Registration trims before validating, rejects blank/overlong input and limits the name field to 100 characters, matching #197. Learner IDs remain 12-digit LRNs. The existing Retry-After countdown blocks early submits and does not retry automatically. Admin responses/HTTP 403 are rejected before storage.

## Validation and evidence

Shared tests exercise all four queue types, changed warning counts, offline token clearing, file/profile/cursor retention, language persistence, existing-mirror ownership, account isolation across process-like reopen, concurrent cache preparation and unowned-data safety. The actual-mock test revokes the old token, switches learner -> teacher -> learner, and checks the original queue and separate cursors. ViewModel tests cover canceled/pending Hub-change intent, stale sessions and the login-contract bounds/wait.

Final validation: 135 JVM tests per Debug/Release variant (zero failures/skips), lint with zero errors and 27 existing warnings, both APK builds successful, 68 shared tests and offline/localization invariants passed, and 43 physical-phone tests passed. Evidence is in `evidence/106/`: build/lint output, phone test output and final-build Settings screenshots. Device checks use the separate `org.lara.app.sprint26settings` package and synthetic data, leaving the main installed app untouched. Manual checks cover both role entry points, immediate English/Filipino changes, cold-launch language retention with the test Hub disconnected and in airplane mode, sign-out, teacher account switching, Change Hub/manual address returning to sign-in, and return to the learner's preserved class. Phone connection settings are restored after airplane-mode testing.

The mock Hub runs locally with USB forwarding; this does not certify a physical WAN-disconnected classroom router or the real Rust Hub. Manual address entry validates REST connection/sign-in; live events use the explicitly configured test WebSocket port because the fixture uses nonstandard ports. Vendor battery behavior and 3–4 GB certification remain field follow-ups. No Settings PR has been opened yet.

## Key files

`ui/screens/settings/` contains the screen and state/actions; `ui/theme/AppLanguage.kt` owns resource localization including popup owners. `AppLanguageStore` persists the preference, `SettingsRepository` protects queued work and signs out, and `AccountDatabases`/`AppContainer` select the account cache and services. `AuthHost` scopes session ViewModels and connects Settings to sign-in/discovery.

## How to test

1. Connect to the mock Hub and sign in with a seed learner or teacher (PIN `1234`). Open **Settings** from the screen header.
2. Select **Filipino**, then **English**. Labels should update immediately. Select Filipino again, close/reopen the app and check it is remembered.
3. Disconnect from the Hub or enable airplane mode. Reopen the app and Settings: saved classes remain available, the offline message appears, and language/sign-out controls still work. Restore the connection afterward.
4. With synthetic queued work, select **Sign out**. Check the count, cancel and confirm the session remains. Repeat and confirm; sign in as the same user and check queued work is retained. Automated device tests seed all four queue types without touching the normal app's data.
5. Sign out and sign in as the other role. Its cache must not show the previous account's private data. Return to the first account and check its saved class/work remains.
6. Select **Change Hub**, confirm if there is queued work, and enter the Hub's address. A responding Hub should return to sign-in. Check Hub name/address/status and the version/about section in both languages, including at 200% text size.
