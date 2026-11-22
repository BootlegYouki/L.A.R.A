# Hub sign-in layout

The admin sign-in now uses a two-column console entrance: a navy school
administration panel identifies accounts, activity logs and resource monitoring;
the white form panel holds the existing six-digit PIN input and sign-in action.
This replaces the earlier single narrow card.

Key files: `ui/src/pages/AdminUsers.tsx`, `ui/src/index.css`, and
`ui/src/i18n/en.json`. The layout uses bundled Nunito, Phosphor and existing
design tokens. Authentication, setup, session handling and REST calls are
unchanged. The form retains paste, Show/Hide PIN, disabled pending controls,
validation, wrong-PIN and Hub-unavailable feedback.

At short window heights, the introduction reduces to resource names so the
full form fits 720 x 480. Below 640px wide the form occupies one column.
The existing `accounts-pagination.cjs` browser suite passed at the supported
window sizes, including sign-in failure/recovery, PIN visibility, confirmed
sign-out and keyboard focus. The UI production build passed. Browser evidence
uses synthetic replies; the user's accounts and database were not changed.

See [960 x 640](evidence/accounts-pagination/sign-in.png) and
[720 x 480](evidence/accounts-pagination/sign-in-720x480.png).
