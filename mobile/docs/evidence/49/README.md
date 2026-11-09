# MOBILE 2.2 validation (#49)

## Automated

- Android `test lint assembleDebug assembleDebugAndroidTest`: BUILD SUCCESSFUL (4m 33s, address-change review fix); `android-checks.txt`.
- 43 debug unit tests passed, including seven auth/Room/HTTP integration tests. Both build variants run the unit suite.
- Root contract/mock-Hub suite: 66 passed (`repository-tests.txt`, synthetic media tokens redacted).
- Opt-in Android device test: passed (`device-auth-test.txt`); seeded teacher and learner authentication plus new synthetic learner registration against the repository mock Hub. It does not overwrite the installed learner session.
- Room exported schema remains unchanged: AuthDao adds queries only.

## Physical phone (2026-10-31)

Device: Xiaomi 2311DRK48G, Android app installed over USB ADB. This is not a 3-4 GB budget-phone memory validation.

- The normal MainActivity starts AuthHost; no temporary source injection was used.
- Discovery found the local mock Hub. Direct LAN probing was unreachable from this phone; manual `127.0.0.1:8080` with `adb reverse tcp:8080 tcp:8080` succeeded and returned to sign-in.
- Wrong seeded learner PIN showed the friendly error and retained the ID while clearing the PIN.
- Correct seeded learner credentials opened the real learner graph.
- Private session preferences contained only encrypted data and IV; no plaintext token/profile/PIN keys.
- Stopped this session's mock Hub, force-stopped the app, relaunched: cached learner session opened the learner graph with the amber Hub-unreachable banner. Screenshot: `learner-offline.png`.

The screenshot contains only the empty shell and connection feedback. No authentication request logs, credentials, tokens, or hierarchy dumps are attached.

## Limits

Teacher and learner auth plus registration were additionally tested on the phone through AuthApi against the mock Hub; teacher navigation and registration form interaction were not manually repeated because the installed app retains the learner session and settings owns account switching. Registration/teacher behavior against the real classroom Hub, Filipino device rendering, TalkBack, large fonts, app process death during PIN entry, lockout transition on a real timed quiz, and 3-4 GB device memory remain manual checks. No classroom sync/content was fabricated for the screenshot.

The initial injected-only 2.1 boundary is now wired to actual authentication. No Figma-preview code is included.
## Final issue #49 audit

| Requirement | Implementation/evidence |
| --- | --- |
| Learner and teacher login, learner-only registration | Contract DTOs in AuthApi; six local HTTP/Room tests and the opt-in phone test cover seeded roles and registration |
| 56dp PIN keypad | AuthScreen digit and delete buttons have minimum 56dp height; PIN boxes are 52 by at least 60dp |
| Encrypted token storage | Platform Android Keystore AES-GCM TokenStore; approved TECH_SPEC section 14 decision replaces the issue's outdated EncryptedSharedPreferences instruction |
| Cached profile/offline relaunch | AuthDao saves profile and current_user_id transactionally; physical phone was force-stopped with mock Hub stopped and restored its learner graph |
| Role routing and assessment lockout | AuthHost selects the existing 2.1 graphs from a validated Room profile and collects the 1.3 active-attempt flow, initially locked |
| English and Filipino; friendly wrong-PIN feedback | Matching resources; wrong PIN exercised through the actual phone UI |
| Loading, error, empty and offline states | Restoration skeleton, busy primary action, friendly error banner, blank registration/login forms and existing empty shell, amber offline shell banner |
| Documentation | authentication.md, navigation.md, evidence/49/ |

The final audit also refreshes the rate-limit countdown clock, keeps labels above fields, and returns from Discovery with Android Back. No contract/schema changes or dependencies were added. The branch includes merged Mobile 1.1, 1.2, 1.3 and 2.1 through current staging. Different-user switching and sign-out remain owned by settings/sync; verified same-user reauthentication may renew a changed Hub address without erasing queued classroom work.

The original audit built both APKs and ran the opt-in device API test. The address-change review fix reran mobile tests, lint, the app build and all 66 root tests.

## Lead review: Hub address changes

- `AuthRepository.restore` still requires sign-in when the selected address differs from the saved address.
- A verified login with the same saved user ID and matching Room current-user ID renews the encrypted session at the new REST base; it does not clear classroom or queued work.
- Different-user logins remain rejected with 409, preserving the stored token, profile and current-user ID.
- Regression test: sign in on local endpoint A, restore on B returns null, sign in as the same user on B, restore on B succeeds, and another user on B is refused without being cached.
- `test lint assembleDebug`: BUILD SUCCESSFUL. All seven auth integration tests pass; debug suite now totals 43 tests. Root suite: 66 passed.
- Installed the final review build on the connected phone and recaptured `learner-offline.png` after the cached offline shell had finished rendering. The screenshot is now current.
