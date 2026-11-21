# Authentication — MOBILE 2.2 (#49)

## Purpose and flow

The app opens the last encrypted session and its Room profile before showing any network screen. A cached profile opens its learner or teacher graph even with the Hub unavailable or a token past its server expiry. Token expiry limits authenticated Hub requests; it does not erase downloaded classroom work.

New sessions use the saved Hub endpoint. Connect to the Hub, then sign in with the ID/LRN and six-digit PIN provided by the Hub admin. The English sign-in screen tells users without credentials: “Ask your teacher or Hub admin for an account”. The Hub admin resets forgotten PINs in the Hub dashboard. ADMIN responses are rejected before profile or token persistence.

## Key files

- `data/remote/HubClient.kt`: bounded auth transport, LAN URL validation, timeouts, redirects disabled. No body, credential, or token logging.
- `data/remote/AuthApi.kt`: contract request/response DTOs and role validation.
- `data/remote/TokenStore.kt`: Android Keystore AES-GCM encryption; only ciphertext and IV are stored in private preferences. Backup remains disabled. The approved Tech Spec section 14 decision supersedes the issue's older EncryptedSharedPreferences wording.
- `data/local/dao/AuthDao.kt`: profile/current-user writes in one Room transaction. No schema/table/column change.
- `data/repository/AuthRepository.kt`: network authentication, Room profile cache, encrypted session binding to Hub/user, offline restoration.
- `ui/screens/auth/`: sign-in, masked numeric-password field, transient PIN state, ViewModel request state and session host.
- `MainActivity.kt`: starts `AuthHost`; authenticated state feeds the existing 2.1 shell.

## Safety and boundaries

PIN values exist only during the form and request. They clear on submission and are never saved in instance state, Room, preferences or logs. The English form uses numeric password semantics, six ASCII digits and ID/LRN → PIN focus order. The button and keyboard share the same validation and rate-limit gate.

The session host collects Sprint 1.3 `QuizRepository.hasInProgressAttempt(userId)`, starting locked until Room emits. Learner AI stays unavailable during any cached active attempt. The role shell reads its offline state independently of auth; a public portal probe supplies only reachability, never authentication or authorization.

When the selected Hub address differs from the encrypted session address, restoration returns to sign-in. A successful login for the same saved user and matching Room current-user ID accepts the new address and renews the encrypted token/address binding without deleting cached or queued work.

Mobile 2.6 now supplies explicit safe sign-out and account-specific Room caches. A different user is refused while another token is active; after sign-out, it authenticates into its own private mirror/cursor. The original lara.db remains assigned to its owner and other accounts use hashed file names. Returning to an account restores its retained work, including queued/rejected rows. No cache is erased or shared to enable account switching. The sign-in screen offers Settings for a saved session that needs reauthentication, so its owner can sign out without first renewing that session. See settings.md.

Each login owns a separate ViewModelStore. Disposal cancels its jobs, stale callbacks cannot reset a later login and Retry-After prevents early retries.

## Validation

Run `gradlew.bat test lint assembleDebug`. Auth integration coverage should use Hub-created synthetic accounts. Earlier registration evidence is historical. The implementation follow-up is #382.

For manual mock Hub testing, use documented synthetic teacher and learner accounts. Verify sign-in, cached relaunch with the Hub stopped, wrong PIN, admin rejection and large text. A learner signs in before using the class code.

## English-only UI (#224)

UI strings are bundled in `values/strings.xml`; there is no language selector or locale override. Android falls back to English on Filipino system locales. Historical evidence may show the retired Filipino UI. Learner-entered chat language data is unchanged.

## Six-digit PIN contract (#326)

`AuthPin` centralizes the six ASCII digit rule for the form, ViewModel and API.
Old four-digit accounts require a Hub admin reset; Mobile never pads a PIN.
See [dependency verification, pending-class behavior and validation](auth-pin-pending-join.md).
