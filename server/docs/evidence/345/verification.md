# Real-Hub test cleanup verification (#345)

Date: 2026-11-20. Windows development PC. Changes are developed on top of #339 (`9e740b8`) and are uncommitted. The existing #339 PR is unchanged.

## Changes and scope

- Scaffold tests start the real Hub with `HubConfig::test_seed = true`, the seed path used by `LARA_TEST_SEED=1`, and sign in its synthetic accounts through HTTP. A 38-operation contract role matrix replaces the Python-source parser; registration checks also probe unauthenticated requests. The previous teacher-token assumption for learner-only sync push is fixed.
- Seed names are `TEST_ACCOUNTS`, `TEST_PIN` and `test_accounts`; newly created accounts use six digits. A seeded restart preserves an admin PIN reset and keeps one copy of each account. Normal startup remains unseeded.
- The browser runner starts only the seeded real Hub, using an isolated data directory. It checks login, create/reset, role restrictions, six-digit validation and paste, loading, empty and Hub-unreachable states, recovery and memory-only secrets.
- Active server source comments and documentation refer to contracts and real-Hub verification. Older evidence and AI evaluation results are retained as history. No shared contracts, scripts, root tests, CI or applied migrations are changed.

## Simulator-absent verification

A disposable source copy was created under the Windows temporary directory with `server/` and `contracts/` sources and the shared `scripts/fixtures/lesson-video.mp4` test asset. It contains no `scripts/mock_hub.py`. Rust checks run from the copied `server/backend/`; the browser runner also runs from the copy, using the Hub binary built there and the local Vite UI.

`Test-Path scripts/mock_hub.py` in that source copy returns `False`. The source checkout's Lead-owned files remain intact. Logs are under `server/backend/target/issue-345-validation/` in the working checkout.

To avoid the Windows debug-symbol disk/PDB issue seen during #339, checks use `CARGO_PROFILE_DEV_DEBUG=0`, `CARGO_PROFILE_TEST_DEBUG=0` and `CARGO_INCREMENTAL=0`, with the checkout's target directory reused. No repository build settings change.

```text
cargo check --all-targets
Finished dev profile [unoptimized] in 37.68s
exit 0

cargo clippy --all-targets -- -D warnings
Finished dev profile [unoptimized] in 10.29s
exit 0

cargo build --bin lara-hub
Finished dev profile [unoptimized] in 17.38s
exit 0

cargo test -j 2 --no-fail-fast
374 passed; 1 failed across all targets
scaffold: 11 passed; seed: 3 passed; streaming: 13 passed
exit 101: db::tests::migrations_reproduce_the_server_master_contract

npm.cmd run build (server/ui)
TypeScript passed; Vite transformed 24 modules and built successfully
exit 0

verify_invariants.py
All L.A.R.A offline invariants PASSED

python -m unittest discover tests
Ran 72 tests in 14.461s
OK

node <temporary source>/server/ui/tests/admin-console.cjs
PASS setup/login and admin-only account list
PASS teacher and learner creation, one-time PIN, login, role guards
PASS PIN reset and old/new login checks
PASS unreachable Hub keeps accounts and draft, disables writes and recovers
PASS empty state, desktop targets, sign-out, reload, memory-only secrets and local requests
```

Shared Python checks use `PYTHONUTF8=1` and `uv run --python 3.12 --with pyyaml python`. They run against the current shared files; their removal/replacement remains in Lead-owned #344.

The browser checks use the existing Playwright installation and Chromium, with `LARA_HUB_BINARY` pointing to the tested Hub binary. Only `lan_address` is simulated. Nonlocal HTTP requests are blocked. Screenshots in this folder contain synthetic test accounts: `accounts.png`, `loading.png`, `empty.png`, `offline.png`. The offline dialog was visually inspected; six input boxes, the retained draft and the Hub-unavailable banner fit.

## Original acceptance blocker (resolved 2026-11-21)

The original unfiltered Rust run was not green. Its remaining failure predated #345: `db::tests::migrations_reproduce_the_server_master_contract` compared raw `sqlite_master.sql`, including comments. The contract says `argon2id of the 6-digit PIN`; the checksum-protected initial migration says `argon2id of the 4-digit PIN`. The column is `TEXT NOT NULL` in both. An applied migration must not be edited to repair this comment mismatch.

The follow-up below resolves the schema comparison in the server test, without editing the contract or applied migrations. #339 has merged through PR #361. #344 must merge after or together with this server cleanup.

Native Tauri interaction and a physical classroom router with the WAN unplugged were not exercised. The local browser run verifies Hub loss and recovery; it does not establish physical network isolation.

## Pre-PR revalidation (2026-11-21)

The cleanup branch starts at staging `02e6bec5`, after #361. The original checkout and its index remain untouched; historical AI results and the native lockfile refresh are excluded from this PR.

`db/mod.rs` now strips SQL line/block comments only for schema comparison while preserving quoted SQL. A regression test checks comment removal, escaped quotes, quoted identifiers and differences in executable constraints/defaults. No applied migration or contract changed.

A fresh disposable source snapshot contains `server/backend/`, the installer source files used by firewall tests, `contracts/` and the shared video fixture. It has no `scripts/mock_hub.py`. An initial snapshot omitted the installer scripts and failed the two fixture-reading firewall tests; after adding those unchanged files, the full suite below passed.

```text
Test-Path <snapshot>/scripts/mock_hub.py
False
cargo check --all-targets
Finished dev profile in 17.58s; exit 0
cargo clippy --all-targets -- -D warnings
Finished dev profile in 14.88s; exit 0
cargo test -j 2 --no-fail-fast
376 passed; 0 failed across all targets; exit 0
npm.cmd run build
TypeScript passed; Vite transformed 24 modules; exit 0
python scripts/verify_invariants.py
All L.A.R.A offline invariants PASSED
python -m unittest discover tests
Ran 73 tests in 10.627s; OK
```

Rust commands used `CARGO_PROFILE_DEV_DEBUG=0`, `CARGO_PROFILE_TEST_DEBUG=0`, `CARGO_INCREMENTAL=0` and the existing target cache. Logs are in the temporary `lara-pr-345-logs` directory. Shared Python checks ran from the isolated worktree with `PYTHONUTF8=1`; Python 3.12 and PyYAML were provided by uv.

The prior real-Hub browser results and four screenshots above remain the browser evidence; that runner was not repeated during this pre-PR pass. No product UI source changes are included. Physical router isolation, a second device, installer execution and native admin interactions remain not verified.
