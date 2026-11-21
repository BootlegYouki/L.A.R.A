# Migration checksum compatibility

Issue #334: Windows installations can contain SQLx checksums for CRLF migration SQL while a clean checkout embeds LF. SQLx otherwise rejects an equivalent historical migration before opening saved classes.

The native migrations module owns the unchanged SQL list shared by account and plugin migration registration. Before migration validation it transactionally normalizes successful ledger entries only when the stored SHA384 equals the LF or CRLF variant of that exact bundled SQL. Unknown checksums and unknown migrations remain subject to SQLx validation; no migration SQL is rewritten or re-executed by the compatibility check.

Account databases run the check before their Migrator. The frontend calls prepare_client_database before Database.load so the plugin database is prepared at its app_config_dir path, matching tauri-plugin-sql. A failed preparation rejects the open promise and retains the existing retry behavior. Fresh databases have no ledger to normalize.

Regression coverage creates a historical 001-003 account with the opposite newline checksum, cached class/enrollment, queued comment and cursor; upgrade and reopening preserve them. Plugin preparation covers all-LF and all-CRLF histories and rejects an unrelated checksum without changing it. Frontend tests prevent plugin loading if native preparation fails.

Verification: 99 frontend tests, build and lint passed. Native suite: 27 passed, three existing opt-in skips. Shared Python suite: 72 passed; offline invariants passed.

No screen, API, schema or migration-file changes. Full MSI old-to-new testing and Linux runtime verification remain separate.

## Installed Windows upgrade verification (19 November 2026)

Built NSIS from isolated commit f34c3d4, whose migration 002 SHA384 starts 63422f46d44d67e3 (LF), and installed over the existing backed-up Windows EXE installation whose account ledger held a81c7393c576df1f (CRLF). Installer exit code was 0. The installed executable, not a dev window, was launched for every check.

Both the native account cache and an explicit plugin database preparation/load passed. Old sessions had expired, so the synthetic learner reauthenticated against the existing real staging Hub, then opened the cached Class Page QA class and handout. After stopping the Hub and fully restarting the installed process, the class, handout and saved video reopened; video playback advanced past one second.

All 10 original database files passed integrity_check and foreign_key_check. Counts in original cached tables were unchanged; only migration-ledger and sync bookkeeping counts were excluded. Existing account databases migrate when opened, not all at startup. The pre-test app/data backup remains on G:.

[Installed handout](screenshots/334-installed-handout.png), [handout after offline restart](screenshots/334-installed-offline-handout.png), [saved video after offline restart](screenshots/334-installed-offline-video.png).

The separate exact 6f0c2cb MSI-to-current-MSI upgrade test, Linux runtime and physical WAN-unplugged tests remain unverified. The failed 25d402a prerelease stays flagged; this installer is a local review build pending PR approval.
