# Account migration 004

Issue #320 registers the existing immutable audit migration for account-scoped databases as well as the plugin database. Opening an existing account upgrades its SQLx ledger from 003 to 004. Migration files and shared contracts are unchanged.

Sync supplies the new required created_at values using the same sources as migration 004: joined_at for enrollments, updated_at (or the existing zero fallback) for chunks, and submitted_at for submissions. Existing upsert behavior preserves created_at on updates. Quiz grades do not create attempts; local attempt test fixtures now include created_at.

## Verification (Windows, 19 November 2026)

The regression test failed before registration because created_at was absent. A full-contract sync page failed after applying 004 to the test database but before fixing normalization. Both pass after the fix.

The native upgrade test builds a checksummed 001-003 account database, seeds an enrollment and cursor, closes it, and opens it twice through account::database. The enrollment backfills created_at from joined_at, all four tables have required audit columns, and the cursor survives. The sync regression checks first-insert timestamps stay unchanged on a later pull.

Native suite: 26 passed, three existing opt-in skips. Frontend: 98 passed. Frontend build and lint passed. Offline invariants and 72 shared Python tests passed with PYTHONUTF8=1 (the default Windows console encoding initially failed).

Real Hub built from staging c9e2a01: synthetic sign-in, class creation, learner join/approval, PDF upload and initial pull succeeded. A temporary native harness applied that real pull through sync::apply into an account database, closed/reopened the connection, and verified enrollment/chunk audit values and the saved cursor. That additional test passed and is not included in the production source. No tokens are recorded here.

This verifies native persistence and real-Hub payload compatibility. It does not establish an MSI in-place upgrade, a full UI/process restart, Linux behavior or physical LAN/WAN operation; those remain separate checks. No screens changed.
