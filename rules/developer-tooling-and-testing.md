# Developer Tooling, Contracts & Testing Rules

This rule document governs the usage of shared API contracts, the real-Hub test setup, pre-push guardrail validation, and the automated test suite.

All AI agents and human contributors must follow these rules.

---

## 1. The Contract-First Invariant (`contracts/`)

The `contracts/` directory is the single source of truth for all network communication between the Local Hub (Server) and Client applications (Mobile & Desktop).

* **Canonical REST Specification:** [`contracts/openapi.yaml`](../contracts/openapi.yaml)
* **WebSocket Event Schemas:** [`contracts/events/`](../contracts/events/), one file per event; the handshake and the direction of each event are in its `README.md`.
* **Database Schemas:** [`contracts/schema/`](../contracts/schema/).
* **The tutor's system prompt:** [`contracts/ai/socratic_system_prompt.txt`](../contracts/ai/socratic_system_prompt.txt).
* **Serialization Case Rule:** All network keys transmitted over HTTP and WebSockets must strictly use **`snake_case`**.
* **Strict Answer Key Redaction:** Every response a learner can receive (for example `GET /api/quizzes/active`) must strictly omit the `correct_answer` field. Client data models must never contain unsubmitted answer keys.
* **Protocol Modification Rule:** Never introduce or modify an HTTP endpoint or WebSocket payload in client or server code without first updating and validating the corresponding schema in `contracts/`.

---

## 2. Test Against the Real Hub

Clients have no Hub simulator (decided 2026-11-20). Mobile and Desktop build and test against the real Hub (`server/backend`). A route the Hub has not built yet answers `501 NOT_IMPLEMENTED`; the contract in `contracts/` is the truth until it lands.

### Contract changes
A change to `contracts/` is a `contract-change` PR that updates `contracts/` and `tests/` together; the server team then implements it. `tests/test_contract_coverage.py` fails CI when an `openapi.yaml` path has no route literal in `server/backend/src` (a crude string match: it ignores the HTTP method and paths built at runtime), or an event schema is missing from `contracts/events/README.md`.

---

## 3. Pre-Push Invariant Scanner (`scripts/verify_invariants.py`)

Before pushing code or opening a Pull Request targeting `staging`, developers must verify that their changes do not violate the core offline or string-resource policies.

### Usage
Run from the repository root:
```bash
python3 scripts/verify_invariants.py
```

### What It Enforces
1. **Zero Cloud Dependencies:** Scans the git diff of every text file (not only code; build files, CSS and config count) for additions introducing forbidden libraries or external links. Markdown, `docs/`, `rules/`, `tests/`, `scripts/` and binary assets are skipped.
   * `firebase`, `@prisma/client`, `prisma`, `crashlytics`, `sentry`, `mixpanel`, the Amplitude SDK (`com.amplitude`, `@amplitude/`, `amplitude-js`; the plain word is fine), `posthog`, Google Analytics and Tag Manager.
   * Google Play Services (`play-services`, `com.google.android.gms`, `google-services`) and `googleapis.com` / `gstatic.com` (web fonts must be bundled locally).
   * External CDNs (`cdnjs`, `unpkg`, `cdn.jsdelivr`).
2. **Fails closed:** if the diff against the base branch cannot be computed (for example a shallow checkout with no base), the script exits 2 instead of passing. Run `git fetch origin staging` first.

If any violation is detected, the script exits with code 1 and prints the exact offending lines.

---

## 4. Automated Verification Test Suite (`tests/`)

All contract schemas and guardrail scanners are covered by automated unit tests in `tests/`. So are the design tokens (`test_design_tokens.py` compares `design-system/showcase.html` with every token mirror) and the prototype (`test_prototype_sync.py` fails when `design-system/prototype.html` is stale; regenerate it with `python3 scripts/build_prototype.py`).

### Running Tests Locally
```bash
python3 -m unittest discover tests
```

### Running a client against the real Hub
Before you open a mobile or desktop PR, run the PR's main flow against the real Hub on `staging`.

```bash
cd server/backend && LARA_TEST_SEED=1 LARA_DATA_DIR=/tmp/lara-test cargo run --bin lara-hub
```
`LARA_TEST_SEED=1` creates the test accounts (`ADMIN-0001`, `T-0001`, `123456789012`, `123456789013`) but no class or posts: create those through the app or the API. The contract PIN is 6 digits; until #339 the server still seeds PIN `1234`, so read `server/backend/tests/seed.rs` for the current value. Delete the data folder to start fresh. Point the app at `127.0.0.1:8080` (desktop), `10.0.2.2:8080` (emulator) or the PC's LAN IP (phone).

* A route the Hub has not built yet answers `501 NOT_IMPLEMENTED`. Say so under Not Verified (for example "class settings: real Hub answers 501, not tested").
* Put one screenshot or log of the flow against the real Hub in the PR's Evidence.
* If the Hub answers differently from the contract, the contract decides. Report the difference on the PR and tag the server team; do not code around it in the client.

### Quality Gate Rule
* All tests in `tests/` must pass before pushing to `staging` or `main`.
* GitHub Actions automatically runs this suite on every push and pull request via `.github/workflows/ci.yml`.
