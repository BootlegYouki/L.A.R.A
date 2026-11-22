# Bulk learner account import

This is the bulk creation follow-up described in issue #381. It does not remove public registration; that is separate account-contract work. The signed-in Hub admin opens **Accounts → Import learners** to create up to 500 learner accounts from one CSV or Excel `.xlsx` file. Accounts join classes through the existing enrollment flow.

## Files and data flow

- `ui/src/pages/ImportLearners.tsx` owns the preview, progress, result and credential-download session. `AdminDialog.tsx` supplies the shared accessible modal and focus restoration.
- `ui/src/learnerImport.ts` implements parsing, validation, existing-account skips, cryptographic PIN generation, sequential creation, reconciliation and export. Parsing executes in `learnerImport.worker.ts` so the window remains responsive. The feature is loaded only when opened.
- `ui/src/importFiles.ts` downloads files in browser development and invokes `save_import_file` in the native window. `src-tauri/src/import_files.rs` authenticates the admin session, opens the native Save dialog, checks the session again, and writes only the selected import file. IPC accepts three fixed filenames and at most 1 MiB of output; no arbitrary destination path is accepted from JavaScript.

Files are parsed entirely on the Hub PC. SheetJS CE 0.20.3 is vendored in `ui/vendor/` and bundled at build time. Neither parsing nor file saving makes an external request. No new REST endpoints, database columns or migrations are introduced.

## Roster format

Use exactly two headers in the first row: `LRN,Full name`. Excel must contain a worksheet named `Learners`; other worksheets are ignored. Both template formats are available in the dialog. Excel's 500 LRN entry cells are formatted as text to preserve leading zeros.

[Demo learners.xlsx](examples/Demo%20learners.xlsx) contains 20 fictional learners
for demonstrations. Its unique twelve-digit LRNs are text values with leading
zeros. Importer tests validate the committed workbook; it contains no real learner data.

- Maximum file size: 5 MiB. Maximum learner count: 500 nonblank rows. Split larger rosters into separate files.
- LRN: exactly 12 ASCII digits after trimming whitespace. Never pad an incomplete LRN or guess digits lost by Excel.
- Full name: 1–100 characters after trimming whitespace. Unicode names and quoted CSV commas are preserved.
- Entirely blank rows are ignored. Missing/extra headers, formulas and repeated LRNs within the same file are errors. Correct the file and choose it again before creation.
- Any existing account with the same LRN is skipped, regardless of role or active state. The import never edits a name, resets an existing PIN or reactivates an account.

The preview identifies errors by original file row. All errors must be fixed before creation. The existing account list is refreshed immediately before each run to catch accounts created since the preview.

## Creation, interruption and credentials

The importer calls the existing admin-only `POST /api/admin/users` once per new account with role `STUDENT`. Each receives a cryptographically generated six-digit PIN, including possible leading zeros. Rejection sampling avoids modulo bias; PINs are distinct within the import. Existing backend hashing, account authorization and atomic account/revision transactions are reused.

Confirmed successes remain visible if a later request fails. **Check and continue** refreshes accounts before attempting remaining work. A response lost after commit is not evidence of failure: that row stays unconfirmed if the account now exists and is excluded from credentials. Use the existing **Reset PIN** action for it. If reconciliation shows no account, the row retries using its original generated PIN. A concurrent `ALREADY_EXISTS` response skips the row without altering the existing account.

**Download credentials CSV** includes only accounts whose creation was confirmed, with Full name, LRN and PIN columns. UTF-8 BOM and CSV quoting support Unicode and commas. When opening the CSV in Excel, import LRN and PIN as text; CSV quoting alone does not prevent Excel from removing leading zeros. Give each learner only their own credentials.

PINs and the import roster exist only in component memory. No browser storage or server-side plaintext credential store is used. Closing asks the admin to record credentials before clearing them. Sign-out, expiry and window closure remove the import session. Unmounting terminates the parsing worker and prevents further creation requests. An already-sent request can still commit; recovering credentials after closure requires the existing Reset PIN flow. Native export checks session validity again after the Save dialog so expiry during file selection stops the write.

## Verification

From `server/ui/`:

```text
npm run test:import
npm run build
```

`tests/learner-import.test.cjs` covers parser boundaries, templates, Unicode, leading zeros, formulas, duplicates, invalid data, generated PINs, sequential results, existing accounts, conflicts, authorization failure, cancellation and lost-response reconciliation.

The browser test uses an isolated real Rust Hub on ephemeral ports and a temporary database. It redirects only the test browser's requests, and never writes to a Hub already running on 8080. Build the fixture from `server/backend/`:

```text
cargo build --example admin_console_fixture
```

Start the UI development server, then run `npm run test:import:browser` from `server/ui/`. Supply Playwright through `LARA_PLAYWRIGHT_PACKAGE` if it is installed outside the UI project; `LARA_CHROMIUM_EXECUTABLE` selects an existing browser. Screenshots in `docs/evidence/account-import/` use synthetic accounts and do not display PINs. Downloads containing test credentials are removed from the temporary test directory.

Browser acceptance covers Excel/CSV upload and preview, disabled creation for invalid rows, template and credential downloads, learner login with generated credentials, duplicate name/PIN preservation, a deliberately lost response after a real commit, continuation, confirmed-only export, dismissal, sign-out and absence of external requests or browser credential storage. Native Save dialog behavior requires a separate desktop check; the browser exercises the browser download path.
