# Developer Tooling, Contracts & Testing Rules

This rule document governs the usage of shared API contracts, the standalone Mock Hub simulator, pre-push guardrail validation, and the automated test suite.

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

## 2. Local Hub Simulation (`scripts/mock_hub.py`)

Mobile and Desktop developers must never be blocked waiting for the Rust server implementation. The standalone Mock Hub is a zero-dependency Python simulation of every Hub network service in `contracts/`.

### Usage
Run from the repository root:
```bash
python3 scripts/mock_hub.py
```
State is in memory and resets on restart. Seeded accounts (PIN `1234`): teacher `T-0001` (owns Science 4, class code `K7M4QX`), learner `123456789012` (enrolled), learner `123456789013` (not enrolled, use for the join and approval flow), and the Hub admin `ADMIN-0001` (role `ADMIN`, not a teacher; the only account accepted by `/api/admin/*`, backup and restore).

### What It Simulates
1. **UDP Discovery Beacon (`255.255.255.255:8888`):** every 3 seconds.
2. **Captive Web Portal (`/download`).**
3. **REST (`:8080`):** every operation in `contracts/openapi.yaml`: auth, admin setup and accounts, classrooms (approval, rename, archive, remove a learner, co-teachers), delta-sync, topics, announcements and comments, materials (upload, edit, delete, download, chunks, `206` streaming), assignments (create, edit, delete, homework upload, private comments), teacher grading, quizzes (create, edit while draft, start, begin, submit, close, results), the AI model file, export and backup. Bearer auth is enforced. Media routes also accept `?token=`.
4. **WebSocket (`:8081`):** `EVENT_HELLO` handshake, then pushes `EVENT_JOIN_REQUEST`, `EVENT_JOIN_APPROVAL`, `EVENT_ANNOUNCEMENT_PUSH`, `EVENT_QUIZ_START`, `EVENT_QUIZ_CLOSED`, `EVENT_GRADE_CONFIRMED`, `EVENT_PRESENCE`, and streams `EVENT_QUEUE_STATUS` / `EVENT_AI_TOKEN_STREAM` for `EVENT_AI_CHAT_REQUEST`.
5. **Rules it enforces so clients meet them early:** learner quiz payloads never contain `correct_answer`; AI requests fail with `QUIZ_IN_PROGRESS` while the learner has an `IN_PROGRESS` attempt; quiz submissions later than the limit plus 60 seconds fail with `TIME_LIMIT_EXCEEDED`; held scores are `null` for the learner until the quiz is closed; private comments and homework reach only their owner and the class teachers; a co-teacher can teach but not rename or archive; unknown routes return `ROUTE_NOT_FOUND`.

### Parity Rule
Any change to `contracts/` must be mirrored in the mock hub in the same PR. `tests/test_contract_coverage.py` fails CI otherwise.

---

## 3. Pre-Push Invariant Scanner (`scripts/verify_invariants.py`)

Before pushing code or opening a Pull Request targeting `staging`, developers must verify that their changes do not violate the core offline or localization policies.

### Usage
Run from the repository root:
```bash
python3 scripts/verify_invariants.py
```

### What It Enforces
1. **Zero Cloud Dependencies:** Scans the git diff of every text file (not only code; build files, CSS and config count) for additions introducing forbidden libraries or external links. Markdown, `docs/`, `rules/`, `tests/`, `scripts/` and binary assets are skipped.
   * `firebase`, `@prisma/client`, `prisma`, `crashlytics`, `sentry`, `mixpanel`, `amplitude`, `posthog`, Google Analytics and Tag Manager.
   * Google Play Services (`play-services`, `com.google.android.gms`, `google-services`) and `googleapis.com` / `gstatic.com` (web fonts must be bundled locally).
   * External CDNs (`cdnjs`, `unpkg`, `cdn.jsdelivr`).
2. **String Parity:** Every key in `mobile/app/src/main/res/values/strings.xml` needs a key in `values-tl/strings.xml`, and every key in `desktop/src/i18n/en.json` needs one in `fil.json`. A language file without its pair fails.
3. **Fails closed:** if the diff against the base branch cannot be computed (for example a shallow checkout with no base), the script exits 2 instead of passing. Run `git fetch origin staging` first.

If any violation is detected, the script exits with code 1 and prints the exact offending lines.

---

## 4. Automated Verification Test Suite (`tests/`)

All contract schemas, mock hub endpoints, and guardrail scanners are covered by automated unit tests in `tests/`.

### Running Tests Locally
```bash
python3 -m unittest discover tests
```

### Quality Gate Rule
* All tests in `tests/` must pass before pushing to `staging` or `main`.
* GitHub Actions automatically runs this suite on every push and pull request via `.github/workflows/ci.yml`.
