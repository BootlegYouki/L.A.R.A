# Signed-in Windows title (#415)

The native main-window caption and HTML document title follow the active account for the selected Hub:

- Teacher: `L.A.R.A. — Teacher`
- Student: `L.A.R.A. — Student`
- No active account: `L.A.R.A.`

Only the role is included; names and account identifiers are omitted. The existing icon is preserved. The effect depends only on the role, so ordinary comment/content sync does not rewrite the title. The only added native permission is `core:window:allow-set-title` for the existing main window. Browser previews retain the document-title fallback.

Verification: the new account-transition regression failed before implementation, then passed with the fix. All 216 frontend tests, lint, production frontend/native builds, normal Windows NSIS bundle and offline invariant guard pass. A native QA build with an explicit separate WebView data directory signed in to the existing local real Hub using synthetic teacher and student accounts. Reading the actual Windows `MainWindowTitle` and document title confirmed both role labels and reset after each sign-out. That temporary QA configuration is outside the repo; the normal installer was saved separately first.

No installer upgrade, user-app replacement, new prerelease publication or Linux/macOS caption verification is claimed.