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
3. **Server Project Team (`server/`):** Local Hub & Teacher Host (Tauri, Rust/Axum, SQLite, `llama-server`).

### The Three Operational Bottlenecks:
1. **The Dependency Lock (Mocking Gap):** Mobile and Desktop teams risk being blocked while waiting for the Server team to build endpoints and WebSockets.
2. **Review Fatigue (Missing CI/CD):** The Lead Developer manually pulls branches and tests basic compilation/linting instead of relying on automated quality gates.
3. **Contract Drift (Serialization Mismatches):** Without a formal contract, field names risk drifting between Rust (`snake_case`), Kotlin (`camelCase`), and TypeScript across the network boundary.

This specification defines the three-pillar developer ecosystem designed to unblock development, guarantee contract parity, and automate PR auditing.

---

## 2. Pillar 1: Zero-Dependency Standalone Mock Hub (`scripts/mock_hub.py`)

### 2.1 What it is
A single-file Python 3 program (standard library only) that implements **every** route and event in `contracts/`, so Mobile and Desktop never wait for the Rust server. It is also the behavioral acceptance reference for the Server team.

```
+------------------------------------------------------------------------------+
|                      MOCK HUB (scripts/mock_hub.py)                           |
|  [UDP Beacon :8888]     [HTTP :8080]                [WebSocket :8081]         |
|   JSON every 3 s         - bearer auth, all routes   - EVENT_HELLO handshake  |
|                          - uploads (multipart)       - join, quiz, grade,     |
|                          - 206 Range streaming         presence, announcement |
|                          - cursor delta-sync           pushes                 |
|                          - in-memory state           - AI queue + token stream|
+------------------------------------------------------------------------------+
        |                      |                              |
 [Android Phone]        [Student Laptop]              [Teacher Desktop/Phone]
```

### 2.2 Behavior it enforces (so clients meet the rules early)
* Bearer auth on every route except `/download`, register and login; media routes also accept `?token=`.
* Learner quiz payloads never contain `correct_answer`; the teacher view does.
* AI requests fail with `QUIZ_IN_PROGRESS` while the learner has an `IN_PROGRESS` attempt; otherwise the mock streams a canned Socratic reply with `EVENT_QUEUE_STATUS` and `grounded_chunk_id`.
* Quiz submissions later than the limit plus 60 s fail with `TIME_LIMIT_EXCEEDED`.
* Delta-sync uses an integer cursor from a change ledger, with tombstones, `hub_id`, `sync_epoch` and `reset`; pulls never contain PIN data or other people's LRN, and learners never see classmates' homework.
* Unknown routes return `404 ROUTE_NOT_FOUND`, which lets the coverage test tell a missing route from a missing entity.

### 2.3 Usage and seed data
```bash
python3 scripts/mock_hub.py
```
State is in memory and reseeds on every start. Accounts (PIN `1234`): `ADMIN-0001`, teacher `T-0001` (Science 4, code `K7M4QX`), learner `123456789012` (enrolled), learner `123456789013` (join with the code).

### 2.4 Parity guarantee
`tests/test_contract_coverage.py` fails CI if any `openapi.yaml` operation has no mock route, if the mock serves an undocumented `/api/` route, or if an event schema is not emitted or handled by the mock. A contract change is therefore incomplete until the mock hub follows.

---

## 3. Pillar 2: Shared API & Event Contracts (`contracts/`)

### 3.1 Directory Organization
```
contracts/
├── openapi.yaml           # REST: auth, admin, classrooms, sync, stream, materials, assignments, quizzes, export
├── events/                # WebSocket event schemas (JSON Schema) + README (handshake, direction table)
├── schema/                # server_master.sql and client_offline.sql
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
* **contracts-and-tools:** `python3 -m unittest discover tests` (contracts, schema, mock hub behavior, coverage).
* **mobile-ci** (when `mobile/` has a Gradle project): `./gradlew lintDebug testDebugUnitTest`.
* **desktop-ci** (when `desktop/package.json` exists): `npm ci`, `npm run lint`, `npm run build`.
* **server-ci** (when `server/backend/Cargo.toml` exists): `cargo check --all-targets` and `cargo test`.

### 4.3 Governance
* [`.github/CODEOWNERS`](../../.github/CODEOWNERS): Lead-owned shared paths (`contracts/`, `rules/`, `design-system/`, `scripts/`, `tests/`, `.github/`).
* [`.github/PULL_REQUEST_TEMPLATE.md`](../../.github/PULL_REQUEST_TEMPLATE.md): checklist, including the contract-change section.
* Branch protection on `staging` should require the CI checks above and CODEOWNERS review (a repository setting the Lead enables).

---

## 5. Verification & Acceptance Criteria

1. **Mock hub:** `python3 scripts/mock_hub.py` serves UDP `:8888`, HTTP `:8080` and WebSocket `:8081`; a client can discover it, log in, join with `K7M4QX`, get approved, take a quiz and receive streamed tutor tokens.
2. **Guardrail:** a PR adding a Firebase import or a Google Fonts link fails `guardrails.yml`.
3. **Contract tests:** `python3 -m unittest discover tests` passes, including OpenAPI validity, SQL schema checks and mock hub coverage.
