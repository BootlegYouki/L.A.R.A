# Settings — MOBILE 2.6 (#106), work in progress

Started from staging 272dfe4 after Mobile 2.5 PR #195 merged. Settings is available from the learner and teacher headers, including offline. The layout uses existing tokens, a small card shadow, borders and consistent spacing; it does not redesign the role graphs.

## Key files and data flow

- AppLanguageStore persists English/Filipino independently of account/Hub state. AppLanguage supplies localized resources to Compose without recreating the activity. Language splitting is disabled so both languages stay bundled offline.
- SettingsHost / SettingsViewModel / SettingsScreen own the language rows, saved Hub information, version/about and sign-out dialog. Hub discovery and manual IP entry reuse the existing discovery screen. A selected Hub returns to sign-in so credentials are never reused against another address without authentication.
- SettingsRepository checks the current account under the sync mutex and counts its four QUEUED_FOR_SYNC tables. A positive count requires explicit confirmation; a larger new count requires confirmation again. Local token clearing happens before best-effort authenticated POST /api/auth/logout. An unreachable Hub does not prevent local sign-out.
- No saved or queued rows, files, profiles or cursors are deleted. current_user_id remains the offline cache owner. The existing AuthRepository different-user guard remains; sign-out does not erase an account's cache to enable a different account to inherit it. Signing back into the same account preserves its work.
- WorkManager's session lookup now requires an existing token, so a retained cache owner alone does not start signed-out sync. Foreground role/session disposal stops its subscriptions as before.

## Checkpoint verification

The first full build/test/lint/APK pass succeeded. After adding Settings-specific coverage, all seven targeted JVM tests passed with LARA_SETTINGS_MOCK=1: five persistence/queue/language cases, authenticated logout/no redirects, and actual mock logout/token revocation/same-account sign-in with an intact queued comment.

Final full-suite validation, actual Settings phone UI, offline relaunch and the latest auth-contract regression checks remain pending. The connected phone is available for those checks when this work resumes. This is a development checkpoint, not a completion claim or PR.

## Discussion #194 handoff

The Lead requests a separate mobile PR based on contract/sync-reject-reason (#196): Room version/migration and REJECTED/reason columns must land with the shared contract. That work must stay out of this Settings feature PR. New staging 902c660 also contains auth-contract #197; incorporate and check it before finishing Settings. The older roster enrollment_id requirement is already implemented.
