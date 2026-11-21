# Six-digit PIN verification (#339)

Date: 2026-11-20. Windows development PC; local Hub and Chromium. Base: `staging` at `15e9c9a`, including contract change #327. No school database used.

## Behavior

Setup, registration, account creation and PIN reset require six ASCII digits. Login uses the same validator. Leading zeros are kept. Argon2id settings are unchanged. Four-digit account hashes are left intact until the admin resets the PIN; existing sessions remain valid until expiry or reset, and reset revokes all sessions atomically. Applied SQL migrations are unchanged.

Test accounts now use PIN `123456`. Existing test accounts are not overwritten by a restart. Use a fresh test data directory or reset their PINs.

## Passing checks

```text
server/backend: cargo check --all-targets
Finished dev profile

server/backend: cargo test --test six_digit_pin --test auth --test admin_setup --test admin_users --test seed
admin_setup: 13 passed
admin_users: 5 passed
auth: 18 passed
seed: 3 passed
six_digit_pin: 2 passed

server/ui: npm.cmd run build
TypeScript and Vite build passed; 24 modules transformed

root: scripts/verify_invariants.py
All L.A.R.A offline invariants PASSED

root: python -m unittest discover tests
Ran 72 tests in 15.589s
OK

root: node server/ui/tests/admin-console.cjs real
PASS setup/login and admin-only account list
PASS teacher and learner creation, one-time PIN, login, role guards
PASS PIN reset and old/new login checks
PASS unreachable Hub keeps accounts and draft, disables writes and recovers
PASS empty state, desktop targets, sign-out, reload, memory-only secrets and local requests
```

The browser test also checks six password fields, refusal of a four-digit setup PIN, six-digit paste and focus moving to the last field. Only the Tauri address bridge is simulated; account operations use the real Hub. Screenshots: `issue-43-real-loading.png`, `issue-43-real-accounts.png`, `issue-43-real-offline.png` in this folder. The offline dialog was visually checked: all six boxes and the Hub-unavailable message fit.

Python checks use `PYTHONUTF8=1` and `uv run --python 3.12 --with pyyaml python`; the system Python aliases are unavailable and the first attempt without UTF-8/PyYAML failed for environment reasons. The configured run above passes.

## Full Rust suite

Default `cargo test` hit insufficient disk space during debug/PDB generation. A serial retry hit a corrupt PDB from that failure. A retry with `CARGO_PROFILE_TEST_DEBUG=0`, `CARGO_INCREMENTAL=0`, and `cargo test -j 2` built successfully without changing repository settings. Its library tests reported 112 passed and one failed: `db::tests::migrations_reproduce_the_server_master_contract`.

That pre-existing test compares raw `sqlite_master.sql` strings, including comments: the merged contract says `argon2id of the 6-digit PIN`, while the checksum-protected initial migration says `argon2id of the 4-digit PIN`. Both versions already exist at base commit `15e9c9a`; this change edits neither file nor the comparison test. The schemas' PIN storage is still `TEXT NOT NULL`. Do not edit the applied migration to repair this comment mismatch.

Continuing past that test exposed another existing scaffold assumption: `every_scaffolded_contract_route_is_registered` picks a teacher for `POST /api/sync/push` (the mock decorator has no role), but the built route requires a learner and answers `403 Learners only`. The role-selection code and sync handler are unchanged by #339; only the scaffold's seed PINs change. This belongs with the scaffold/mock dependency cleanup in #345.

All remaining Rust tests, including integration and doc tests, pass with the same environment and `cargo test -j 2 --no-fail-fast -- --skip db::tests::migrations_reproduce_the_server_master_contract --skip every_scaffolded_contract_route_is_registered` (exit 0). The two exclusions above are explicit; the unfiltered full suite is not green.

Native Tauri window and a physical classroom router were not exercised; the local browser test covers the changed admin forms and simulated Hub loss.
