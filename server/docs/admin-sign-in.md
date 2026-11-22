# Hub sign-in and sign-out

The sign-in page uses a centered, single-column form with the existing Nunito
font, green action and bundled Phosphor icons. It has a short instruction, six
masked PIN fields and a Show/Hide PIN toggle. PIN entry, paste, validation,
authentication errors and the unavailable-Hub state retain their existing
behavior. The toggle changes visibility only; no credentials are persisted.
The sign-in form has no first-run setup button. Initial setup remains available
on a fresh Hub through the existing setup contract.

Selecting Sign out opens a native HTML dialog before sending logout. Cancel is
focused first. Cancel or Escape closes it and returns focus to Sign out, keeping
the account session and current page. Confirming sends the existing logout request
once; both buttons are disabled and Escape is ignored while it is pending. A
failed request still clears the local session, as before. Session expiry also
clears the confirmation. Signing out does not stop the Hub service.

Implementation: `ui/src/pages/AdminUsers.tsx`, `ui/src/index.css` and the English
strings in `ui/src/i18n/en.json`. No REST, schema or backend changes.

Browser acceptance in `ui/tests/accounts-pagination.cjs` covers masked/revealed
PIN entry, short/wrong PINs, offline login, minimum window sizing, Cancel,
Escape, focus return, no logout before confirmation, one confirmed request,
pending controls and cleared PIN after sign-out. The existing admin console
browser suite also confirms sign-out explicitly.

Verified on Windows, 22 November 2026: UI production build, focused browser
acceptance, 84 shared contract tests, offline invariants and scoped whitespace
checks passed on the PR branch. The native build and real-Hub import checks
passed in the original development worktree; native interaction was not automated
on this UI-only port. The running Hub was preserved.

Preview images use synthetic accounts:

- [Sign in](evidence/accounts-pagination/sign-in.png)
- [Sign-out confirmation](evidence/accounts-pagination/sign-out-confirmation.png)
