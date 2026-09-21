# Architecture Design: L.A.R.A Developer Ecosystem & Automated Workflow

* **Date:** 2026-09-21
* **Author:** Tech Lead & Lead Companion Architecture Gatekeeper
* **Status:** Validated Specification (Ready for Implementation Planning)
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

### 2.1 Architecture & Objectives
A lightweight, single-file Python 3 script using only standard library modules (`http.server`, `socket`, `threading`, `json`) that emulates the complete Local Hub environment on any developer or CI machine.

```
+-------------------------------------------------------------------------+
|                  STANDALONE MOCK HUB (scripts/mock_hub.py)              |
|                                                                         |
|  [UDP Beacon :8888]      [HTTP Server :8080]      [WebSocket :8081]     |
|   Broadcasts JSON         - /download              - Synced Timers      |
|   every 3 seconds         - /api/classrooms        - Socratic AI Token  |
|   to 255.255.255.255      - /api/sync/pull           Streaming Stream   |
|                           - /api/quizzes           - Join Approval Push |
|                           - Byte-Range Video                            |
+-------------------------------------------------------------------------+
           |                         |                         |
           v                         v                         v
   [Android Phone]           [Student Laptop]          [Teacher Laptop]
```

### 2.2 Simulated Interfaces & Protocols
* **UDP Subnet Beacon (`255.255.255.255:8888`):**
  * Broadcasts every 3 seconds:
    ```json
    {
      "app": "lara",
      "version": "1.0.0-mock",
      "name": "Grade 4 - Science (Mock Hub)",
      "ip": "<host_lan_ip>",
      "http_port": 8080,
      "ws_port": 8081
    }
    ```
* **HTTP REST API (`:8080`):**
  * `GET /download`: Serves dummy `.apk` and desktop installer binaries with simulated 3-step sideloading HTML.
  * `GET /api/classrooms`: Returns sample classes (e.g., Grade 4 Science, Section Aguinaldo, Code `SCI4-AG`).
  * `POST /api/classrooms/join`: Accepts student enrollment; automatically simulates teacher approval 2 seconds later.
  * `POST /api/sync/pull`: Returns mock announcements, lesson modules, and assignments.
  * `GET /api/quizzes/active`: Serves a 5-item mock quiz with `correct_answer` stripped out.
  * `POST /api/quizzes/:id/submit`: Auto-grades submission in 20ms and returns a signed receipt.
  * `GET /api/materials/:id/stream`: Implements HTTP `206 Partial Content` with byte-range slicing for mock MP4 video files.
* **WebSocket Realtime Broker (`:8081`):**
  * Accepts WebSocket connections on `/ws`.
  * Emits synchronized `EVENT_QUIZ_START` countdowns.
  * Handles simulated Socratic AI streaming (`EVENT_AI_PROMPT`) yielding realistic Filipino pedagogical tokens with 30ms inter-token delay:
    *"Magandang araw! Balikan natin ang binasa mo sa talata 2..."*
* **Teacher Mobile Support:**
  * Handles `POST /api/announcements` from mobile teacher clients.
  * Handles `POST /api/quizzes/:id/start` to trigger classroom-wide synchronized countdowns.
* **Interactive CLI Dashboard:**
  * Logs all client requests with IP addresses, request latency, and active WebSocket connection count in real time.

---

## 3. Pillar 2: Shared API & Event Contracts (`contracts/`)

### 3.1 Directory Organization
```
contracts/
├── openapi.yaml           # Canonical OpenAPI 3.1 REST specification
├── events/                # WebSocket event payloads (JSON Schema)
│   ├── join_request.json  # Pupil sends 6-char code -> Hub
│   ├── join_approval.json # Teacher approves on phone/desktop -> Hub pushes to Pupil
│   ├── quiz_start.json    # Teacher launches quiz -> Synced epoch + duration to all
│   ├── quiz_submit.json   # Pupil answers payload -> Hub auto-grader
│   ├── ai_stream.json     # Token-by-token streaming chunk format
│   └── queue_status.json  # FIFO position in line update
└── naming_rules.md        # Explicit serialization guidelines
```

### 3.2 Canonical Contract Standards
1. **Network Serialization Rule:** All JSON keys transmitted across HTTP and WebSockets must strictly use **`snake_case`**.
   * Kotlin: Annotated with `@SerialName("field_name")`.
   * Rust: Annotated with `#[serde(rename_all = "snake_case")]`.
   * TypeScript: Defined with `snake_case` interfaces.
2. **Answer Key Redaction:**
   * In `contracts/openapi.yaml`, the `Quiz` schema has two distinct representations:
     * `TeacherQuizResponse`: Includes `correct_answer` and grading rubric.
     * `StudentQuizResponse`: Strictly omits `correct_answer`.

---

## 4. Pillar 3: Automated "Lead Gatekeeper" CI/CD Pipeline

### 4.1 Invariant Guardrail Scanner (`.github/workflows/guardrails.yml`)
Runs on all pull requests targeting `staging` or `main`. Executes in < 10 seconds:

```bash
# High-level logic executed by guardrails.yml:
# 1. Reject forbidden cloud dependencies
FORBIDDEN="firebase|googleapis|fonts.googleapis|cdnjs|cdn.jsdelivr|unpkg|prisma"
if git diff origin/staging...HEAD | grep -E -i "$FORBIDDEN"; then
  echo "FAIL: Cloud dependency or prohibited library detected in diff!"
  exit 1
fi

# 2. Verify bilingual Android string parity
# Ensures all new keys in strings.xml exist in values-tl/strings.xml
python3 scripts/verify_strings_parity.py
```

### 4.2 Path-Filtered Matrix CI (`.github/workflows/ci.yml`)
Executes isolated build checks only for subsystems with modified files:
* **`mobile-ci`** (path: `mobile/**`):
  * Environment: Java 17 + Android SDK.
  * Command: `./gradlew lintDebug assembleDebug`.
* **`desktop-ci`** (path: `desktop/**`):
  * Environment: Node 20 + Rust toolchain.
  * Command: `npm run lint && npm run typecheck && cargo check --manifest-path desktop/src-tauri/Cargo.toml`.
* **`server-ci`** (path: `server/**`):
  * Environment: Rust toolchain.
  * Command: `cargo clippy -- -D warnings && cargo test`.

### 4.3 Clean PR Template Integration
Rather than posting noisy automated comments on every PR, GitHub natively loads the pre-configured checklist from `.github/PULL_REQUEST_TEMPLATE.md` directly into the PR description upon creation.


---

## 5. Verification & Acceptance Criteria

1. **Mock Hub Test:** Running `python3 scripts/mock_hub.py` starts all three services (UDP `:8888`, HTTP `:8080`, WS `:8081`) and allows a mobile client to discover, join, and receive simulated Socratic AI tokens.
2. **CI Guardrail Test:** Opening a test PR containing `import firebase from 'firebase'` is instantly rejected by GitHub Actions with an exit code 1.
3. **Contract Test:** All REST routes in `contracts/openapi.yaml` validate cleanly against OpenAPI 3.1 schema validators.
