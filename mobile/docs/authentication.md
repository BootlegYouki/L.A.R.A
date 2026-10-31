# Authentication — MOBILE 2.2 (#49)

## Purpose and flow

The app opens the last encrypted session and its Room profile before showing any network screen. A cached profile opens its learner or teacher graph even with the Hub unavailable or a token past its server expiry. Token expiry limits authenticated Hub requests; it does not erase downloaded classroom work.

New sessions use the saved Sprint 1.2 Hub endpoint. Find classroom Hub opens the existing discovery screen, including its manual IPv4 entry. A responding Hub returns to the sign-in screen. The app calls only the contract login or learner-registration POST routes; teacher accounts are created on the Hub. ADMIN responses are rejected before profile/token persistence.

## Key files

- `data/remote/HubClient.kt`: bounded auth transport, LAN URL validation, timeouts, redirects disabled. No body, credential, or token logging.
- `data/remote/AuthApi.kt`: contract request/response DTOs and role validation.
- `data/remote/TokenStore.kt`: Android Keystore AES-GCM encryption; only ciphertext and IV are stored in private preferences. Backup remains disabled. The approved Tech Spec section 14 decision supersedes the issue's older EncryptedSharedPreferences wording.
- `data/local/dao/AuthDao.kt`: profile/current-user writes in one Room transaction. No schema/table/column change.
- `data/repository/AuthRepository.kt`: network authentication, Room profile cache, encrypted session binding to Hub/user, offline restoration.
- `ui/screens/auth/`: login/learner registration, 56dp keypad, transient PIN state, ViewModel request state, session host.
- `MainActivity.kt`: starts `AuthHost`; authenticated state feeds the existing 2.1 shell.

## Safety and boundaries

PIN values exist only during the form/request and are cleared on submission. They are not saved in instance state, Room, preferences, or logs. All UI text has English/Filipino resources. Password boxes are read-only masked views of keypad position.

The session host collects Sprint 1.3 `QuizRepository.hasInProgressAttempt(userId)`, starting locked until Room emits. Learner AI stays unavailable during any cached active attempt. The role shell reads its offline state independently of auth; a public portal probe supplies only reachability, never authentication or authorization.

When the selected Hub address differs from the encrypted session address, restoration returns to sign-in. A successful login for the same saved user and matching Room current-user ID accepts the new address and renews the encrypted token/address binding without deleting cached or queued work. A different user is still refused while another cache/session is present; the settings/sync issues own safe queued-work handling for account changes. This issue does not implement sign-out, clearing classroom work, delta sync, enrollment, classroom screens, or remote tunnel configuration.

## Validation

Run `gradlew.bat test lint assembleDebug` and the root repository suite/invariant scanner. `AuthIntegrationTest` exercises the real HTTP transport and Room DAO using a synthetic loopback server: login, registration, teacher role, admin rejection, wrong-PIN/rate-limit errors, redirect rejection, and cached restoration with the server stopped. It uses an in-memory token-store fake. Real Keystore persistence and offline relaunch were verified on the connected phone; see `evidence/49/README.md`. The opt-in `MockAuthDeviceTest` exercises seeded roles and registration on that phone using the actual mock Hub.

For manual mock-Hub testing, run `python scripts/mock_hub.py`. On a USB phone use ADB reverse for port 8080 and manual `127.0.0.1:8080`, or use the PC's LAN IP over classroom Wi-Fi. Sign in with the documented synthetic seeded accounts, force-stop, stop the Hub and relaunch. Verify the role shell opens with the amber banner. A newly registered learner goes to the learner shell; joining a class is a later issue. Check wrong PIN, admin rejection, Filipino and large fonts separately. Never attach unredacted hierarchy dumps containing entered IDs or auth request logs.
