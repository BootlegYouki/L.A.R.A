# Architecture Design: L.A.R.A Developer Ecosystem & Automated Workflow

* **Date:** 2026-09-21 (revised 2026-10-05 to match what is implemented)
* **Author:** Tech Lead & Lead Companion Architecture Gatekeeper
* **Status:** Implemented
* **Target Repository:** `BootlegYouki/L.A.R.A`

---

## 1. Executive Summary & Problem Statement

The **L.A.R.A** engineering group operates across three decoupled project streams:
1. **Mobile Project Team (`mobile/`):** Native Android (Kotlin, Jetpack Compose M3, Room DB).
2. **Desktop Project Team (`desktop/`):** Desktop Client (Tauri 2.x, React 19, Tailwind M3).
3. **Server Project Team (`server/`):** the Local Hub on a dedicated school PC (Tauri, Rust/Axum, SQLite).

The three teams are the only parallel streams. Inside a team both developers work one issue at a time, in step order (`rules/team-workflow-and-prs.md` section 1.1).

### The Three Operational Bottlenecks:
1. **The Dependency Lock:** Mobile and Desktop teams risk being blocked while waiting for the Server team to build endpoints and WebSockets; the contract is what they build to meanwhile.
2. **Review Fatigue (Missing CI/CD):** The Lead Developer manually pulls branches and tests basic compilation/linting instead of relying on automated quality gates.
3. **Contract Drift (Serialization Mismatches):** Without a formal contract, field names risk drifting between Rust (`snake_case`), Kotlin (`camelCase`), and TypeScript across the network boundary.

This specification defines the three-pillar developer ecosystem designed to unblock development, guarantee contract parity, and automate PR auditing.

---

## 2. Pillar 1: The Real Hub Is the Only Test Target

The Hub has no simulator (decided 2026-11-20). Mobile and Desktop build and test against the real Hub (`server/backend`), started with `LARA_TEST_SEED=1 cargo run --bin lara-hub` (see `rules/developer-tooling-and-testing.md` section 4). Until a route's server issue merges, `contracts/` is the truth and the Hub answers `501 NOT_IMPLEMENTED`.

`tests/test_contract_coverage.py` fails CI if any `openapi.yaml` path has no route literal in `server/backend/src` (a crude string match that ignores the HTTP method and runtime-built paths) or an event schema is missing from `contracts/events/README.md`.

---

## 3. Pillar 2: Shared API & Event Contracts (`contracts/`)

### 3.1 Directory Organization
```
contracts/
├── openapi.yaml           # REST: auth, admin, classrooms, sync, stream, materials, assignments, quizzes, export
├── events/                # WebSocket event schemas (JSON Schema) + README (handshake, direction table)
├── schema/                # server_master.sql and client_offline.sql
├── ai/                    # socratic_system_prompt.txt, the one tutor prompt every path loads
├── README.md              # how to change a contract
└── naming_rules.md        # serialization, privacy and error rules
```

### 3.2 Standards
1. **snake_case** for every JSON key over HTTP and WebSocket (Kotlin `@SerialName`, Rust `serde`, TypeScript interfaces).
2. **Answer-key redaction:** `StudentQuestion` and `StudentQuizResponse` have no `correct_answer`; `TeacherQuestion` and `TeacherQuiz` do.
3. **Privacy:** clients receive `PublicUser` only; never `pin_hash` or another person's LRN.
4. **Errors:** `{"error": {"code", "message"}}`.
5. **Sync cursor** is an integer sequence, never a timestamp.

---

## 4. Pillar 3: Automated "Lead Gatekeeper" CI/CD Pipeline

### 4.1 Invariant Guardrail Scanner (`.github/workflows/guardrails.yml`)
Runs `scripts/verify_invariants.py` on pull requests to `staging` and `main`: rejects added lines containing forbidden cloud patterns (Firebase, Prisma, Google Fonts, cdnjs, jsDelivr, unpkg) in `.kt .ts .tsx .rs .html .json` and checks Android `values` / `values-tl` string-key parity.

### 4.2 Path-Filtered CI (`.github/workflows/ci.yml`)
* **contracts-and-tools:** `python3 -m unittest discover tests` (contracts, schema, guardrails, route coverage).
* **mobile-ci** (when `mobile/` has a Gradle project): `./gradlew lintDebug testDebugUnitTest`.
* **desktop-ci** (when `desktop/package.json` exists): `npm ci`, `npm run lint`, `npm run build`.
* **server-ci** (when `server/backend/Cargo.toml` exists): `cargo check --all-targets` and `cargo test`.

### 4.3 Governance
* [`.github/CODEOWNERS`](../../.github/CODEOWNERS): Lead-owned shared paths (`contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/`).
* [`.github/PULL_REQUEST_TEMPLATE.md`](../../.github/PULL_REQUEST_TEMPLATE.md): checklist, including the contract-change section.
* Branch protection on `staging` should require the CI checks above and CODEOWNERS review (a repository setting the Lead enables).

---

## 5. Verification & Acceptance Criteria

1. **Real Hub:** the seeded Hub serves HTTP `:8080` and WebSocket `:8081`; a client can log in against it.
2. **Guardrail:** a PR adding a Firebase import or a Google Fonts link fails `guardrails.yml`.
3. **Contract tests:** `python3 -m unittest discover tests` passes, including OpenAPI validity, SQL schema checks and route coverage.
