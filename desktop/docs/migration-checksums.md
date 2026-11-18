# Migration checksum compatibility

Issue #334: Windows installations can contain SQLx checksums for CRLF migration SQL while a clean checkout embeds LF. SQLx otherwise rejects an equivalent historical migration before opening saved classes.

The native migrations module owns the unchanged SQL list shared by account and plugin migration registration. Before migration validation it transactionally normalizes successful ledger entries only when the stored SHA384 equals the LF or CRLF variant of that exact bundled SQL. Unknown checksums and unknown migrations remain subject to SQLx validation; no migration SQL is rewritten or re-executed by the compatibility check.

Account databases run the check before their Migrator. The frontend calls prepare_client_database before Database.load so the plugin database is prepared at its app_config_dir path, matching tauri-plugin-sql. A failed preparation rejects the open promise and retains the existing retry behavior. Fresh databases have no ledger to normalize.

Regression coverage creates a historical 001-003 account with the opposite newline checksum, cached class/enrollment, queued comment and cursor; upgrade and reopening preserve them. Plugin preparation covers all-LF and all-CRLF histories and rejects an unrelated checksum without changing it. Frontend tests prevent plugin loading if native preparation fails.

Verification: 99 frontend tests, build and lint passed. Native suite: 27 passed, three existing opt-in skips. Shared suite and installer walkthrough results are recorded below when completed.

No screen, API, schema or migration-file changes. Full MSI old-to-new testing and Linux runtime verification remain separate.
