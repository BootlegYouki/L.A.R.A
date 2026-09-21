# Developer Tooling, Contracts & Testing Rules

This rule document governs the usage of shared API contracts, the standalone Mock Hub simulator, pre-push guardrail validation, and the automated test suite.

All AI agents and human contributors must follow these rules.

---

## 1. The Contract-First Invariant (`contracts/`)

The `contracts/` directory is the single source of truth for all network communication between the Local Hub (Server) and Client applications (Mobile & Desktop).

* **Canonical REST Specification:** [`contracts/openapi.yaml`](../contracts/openapi.yaml)
* **WebSocket Event Schemas:** [`contracts/events/`](../contracts/events/)
  * `join_request.json`: Pupil sends 6-char Class Code to Local Hub.
  * `join_approval.json`: Teacher approves enrollment from phone or desktop.
  * `quiz_start.json`: Teacher triggers synchronized classroom quiz countdown.
  * `quiz_submit.json`: Pupil submits completed answers for auto-grading.
  * `ai_stream.json`: Local Hub streams Socratic hint tokens.
  * `queue_status.json`: Local Hub reports FIFO waiting position and estimated wait time.
* **Serialization Case Rule:** All network keys transmitted over HTTP and WebSockets must strictly use **`snake_case`**.
* **Strict Answer Key Redaction:** Endpoints serving active quizzes to students (`GET /api/quizzes/active`) must strictly omit the `correct_answer` field. Client data models must never contain unsubmitted answer keys.
* **Protocol Modification Rule:** Never introduce or modify an HTTP endpoint or WebSocket payload in client or server code without first updating and validating the corresponding schema in `contracts/`.

---

## 2. Local Hub Simulation (`scripts/mock_hub.py`)

Mobile and Desktop developers must never be blocked waiting for the Rust server implementation. The standalone Mock Hub provides a complete zero-dependency simulation of all Local Hub network services.

### Usage
Run from the repository root:
```bash
python3 scripts/mock_hub.py
```

### What It Simulates
1. **UDP Discovery Beacon (`255.255.255.255:8888`):** Broadcasts JSON discovery packets every 3 seconds so client auto-discovery scanners find the hub immediately.
2. **Captive Web Portal (`http://<ip>:8080/download`):** Serves dummy APK and desktop installer downloads.
3. **Classroom REST Endpoints (`:8080`):**
   * `GET /api/classrooms`: Returns sample classes (e.g. Science 4, Section Aguinaldo, Code `SCI4-AG`).
   * `POST /api/classrooms/join`: Simulates auto-approved enrollment.
   * `POST /api/sync/pull`: Returns mock announcements and lesson materials.
   * `GET /api/quizzes/active`: Serves a 2-item quiz with `correct_answer` safely stripped.
   * `POST /api/quizzes/:id/submit`: Returns an instant auto-graded score receipt.
   * `GET /api/materials/:id/stream`: Implements HTTP 206 Byte-Range streaming for video playback.
4. **Teacher Mobile Endpoints:**
   * `POST /api/announcements`: Simulates teacher broadcasting an announcement from a phone.
   * `POST /api/quizzes/:id/start`: Simulates teacher launching a quiz from a phone.

---

## 3. Pre-Push Invariant Scanner (`scripts/verify_invariants.py`)

Before pushing code or opening a Pull Request targeting `staging`, developers must verify that their changes do not violate the core offline or localization policies.

### Usage
Run from the repository root:
```bash
python3 scripts/verify_invariants.py
```

### What It Enforces
1. **Zero Cloud Dependencies:** Scans git diffs for additions introducing forbidden libraries or external links:
   * `firebase`, `@prisma/client`, `prisma`.
   * `fonts.googleapis.com` (web fonts must be bundled locally).
   * External CDNs (`cdnjs`, `unpkg`, `cdn.jsdelivr`).
2. **Android String Parity:** Verifies that any new string added to `mobile/app/src/main/res/values/strings.xml` has a matching translation key in `mobile/app/src/main/res/values-tl/strings.xml`.

If any violation is detected, the script exits with code 1 and prints the exact offending lines.

---

## 4. Automated Verification Test Suite (`tests/`)

All contract schemas, mock hub endpoints, and guardrail scanners are covered by automated unit tests in `tests/`.

### Running Tests Locally
```bash
python3 -m unittest discover tests
```

### Quality Gate Rule
* All tests in `tests/` must pass (**13/13 OK**) before pushing to `staging` or `main`.
* GitHub Actions automatically runs this suite on every push and pull request via `.github/workflows/ci.yml`.
