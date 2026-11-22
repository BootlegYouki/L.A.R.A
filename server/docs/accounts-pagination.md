# Accounts pagination

The Accounts table shows 10 accounts per page by default. The admin can choose
10, 25 or 50 rows and navigate with Previous/Next. The footer shows the visible
range, matching account count and current page.

The Accounts view fits the window height. Rows scroll inside a keyboard-focusable
table region with sticky column headings; pagination remains below the scroll
area. Changing the page or filters scrolls the table back to the top, while
background refresh preserves its position. At short window heights the account
controls also scroll within their own area so all actions remain reachable.
Overview and Activity logs retain their existing layouts.

`ui/src/pages/AdminUsers.tsx` filters the complete loaded account list before
slicing the current page. Search, role and row-count changes return to page 1.
Background refresh preserves the current page unless the matching list shrinks
past it, in which case it moves to the last valid page. A failed refresh retains
the last loaded accounts and allows browsing them; account writes still require
the Hub. Signing out resets pagination.

The existing five-second account refresh and `GET /api/admin/users` contract are
unchanged. Pagination is local to the console; no new requests, parameters or
database changes are introduced. Row actions keep the selected account's ID.
Labels live in `ui/src/i18n/en.json`; controls use existing tokens and 40px targets.

Verification: `npm.cmd run build` and `node tests/accounts-pagination.cjs` from
`server/ui`. The browser check uses synthetic REST responses and accounts;
it does not change the running Hub's accounts. It covers boundaries, keyboard
navigation, filtering across all pages, page-size changes, stable polling,
offline browsing, shrinking lists, row actions and 720/1100px layouts.

Verified on Windows on 22 November 2026: UI production build, browser acceptance,
88 shared contract/mock/schema tests and offline invariants passed. The backend
is unchanged; its full check/test suite passed immediately before this UI change.
Screenshots are in `docs/evidence/accounts-pagination/`.
The native build with `tauri/custom-protocol` also passed. Restart the Hub to
load the rebuilt executable; the running Hub and its accounts were preserved.

The scrolling follow-up browser check passes at 720x480, 720x640, 960x640 and
1100x900: no document scrolling, reachable table rows, sticky headings, visible
pagination and controls at least 40px high. Screenshots use synthetic accounts.
