# Real-Hub development and acceptance (#345)

The shared contracts define behavior. Server runtime checks exercise the Rust Hub itself with temporary SQLite databases and local sockets. No Python simulator is started or read by server tests.

## Test accounts and startup

From `server/backend/`, set `LARA_DATA_DIR` to a disposable folder and `LARA_TEST_SEED=1`, then run `cargo run --bin lara-hub`. In PowerShell:

```powershell
$env:LARA_DATA_DIR = Join-Path $env:TEMP 'lara-development'
$env:LARA_TEST_SEED = '1'
cargo run --bin lara-hub
```

Never set the seed flag on a school Hub or use its data folder for tests. Startup creates only missing synthetic accounts: admin `ADMIN-0001`, teacher `T-0001`, learners `123456789012` and `123456789013`. Their initial PIN is `123456`; no class, class code, enrollment or post is prebuilt. Create classroom content through the REST routes or apps. Restarting keeps existing accounts and PIN resets; an old test database needs a PIN reset or a fresh disposable folder.

`backend/src/db/seed.rs` owns `TEST_ACCOUNTS`, `TEST_PIN` and `test_accounts`. `HubConfig::test_seed` defaults to false. The headless entry point enables it only when `LARA_TEST_SEED` equals `1`, before listeners open. Seeding inserts accounts and their allowed sync revisions in one transaction; the admin is not synced. No credentials are logged.

## Rust tests

```powershell
cargo check --all-targets
cargo clippy --all-targets -- -D warnings
cargo test --no-fail-fast
```

`backend/tests/common/mod.rs` starts one real Hub per test with a temporary database and free ports. `TestHub::start_seeded()` uses the same seed path as `LARA_TEST_SEED=1`, then signs in through HTTP. Ordinary `start()` fixtures retain their minimal teacher-only setup for tests that assert exact row counts.

`backend/tests/scaffold.rs` parses operation methods and paths from `contracts/openapi.yaml`. A separate role matrix names the role-restricted contract operations and is checked against those paths. It probes each operation on the seeded real Hub, checks anonymous access, verifies the allowed role and refuses the other roles. Class membership, ownership, input validation and redaction have their own feature tests. This parser is intentionally limited to the repository's block-form OpenAPI path and method headings; keep that test aligned if the contract format changes.

`backend/tests/seed.rs` verifies account identities, roles, six-digit login, setup refusal when an admin exists, opt-in startup and preservation of PIN resets across a seeded restart.

The migration-parity test removes SQL comments from stored schema statements before comparing them. Quoted literals and identifiers remain intact, so changes to constraints and defaults still fail parity. This permits historical migration comments to differ from the contract without changing an applied migration's checksum.

## Browser checks

Build the Hub binary, then start `npm run dev -- --host 127.0.0.1` in `server/ui/`. Leave ports 8080/8081 free and run `node tests/admin-console.cjs` from that folder. The runner starts a seeded real Hub in a fresh directory, attempts setup, signs in as the existing seed admin, creates accounts, resets a PIN, checks role guards and exercises loading, empty and Hub-unreachable states. Only the Tauri address bridge is simulated.

Use `LARA_PLAYWRIGHT_PACKAGE` and `LARA_CHROMIUM_EXECUTABLE` for existing browser tools, and `LARA_HUB_BINARY` for a separately built binary. Screenshots go to `server/docs/evidence/345/`. Nonlocal HTTP requests are blocked by the test. This does not replace a physical router/WAN-disconnected check or native Tauri verification.

## Coordination and verification

This cleanup is developed on top of #339 so seeds and authentication both use six digits. It must land before or with the shared cleanup in #344. It changes only `server/`; shared contracts, scripts, root tests and CI remain Lead-owned. Older evidence under `server/docs/evidence/339/` is historical.

Verification output and remaining limitations are recorded in `server/docs/evidence/345/verification.md`. Acceptance includes building and running the Rust and browser checks from a disposable source snapshot containing no `scripts/mock_hub.py`, so the removed simulator cannot be used accidentally. The shared video fixture under `scripts/fixtures/` remains available to streaming tests. The source checkout's Lead-owned files remain intact.
