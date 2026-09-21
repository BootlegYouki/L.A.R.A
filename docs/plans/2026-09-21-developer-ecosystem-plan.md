# L.A.R.A Developer Ecosystem & Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish the shared API contracts, standalone zero-dependency Mock Hub, and GitHub Actions Invariant Guardrail CI to unblock parallel development across Mobile, Desktop, and Server teams.

**Architecture:** A canonical `contracts/` directory defines OpenAPI 3.1 REST specs and JSON Schema WebSocket events. A zero-dependency Python script (`scripts/mock_hub.py`) simulates UDP discovery, HTTP endpoints, and Socratic token streaming. GitHub Actions workflows automatically reject PRs with cloud dependencies or broken builds before Lead Developer review.

**Tech Stack:** Python 3 (standard libraries), OpenAPI 3.1, JSON Schema, GitHub Actions, Bash, Git.

**Spec:** `docs/architecture/developer-ecosystem-and-workflow.md`

## Global Constraints

- Zero external cloud dependencies (no Firebase, Google Play APIs, external CDNs, Google Fonts, or Prisma).
- All network JSON keys over HTTP and WebSockets must use `snake_case`.
- Active quiz payloads sent to students must strictly omit `correct_answer`.
- Mock Hub must run using only Python 3 standard library (`http.server`, `socket`, `threading`, `json`).
- Touch targets on any UI elements must meet >= 48dp.
- User strings must have parity between English and Filipino (`values-tl/strings.xml`).

## Review Focus

1. **UDP Broadcast Permission Failure:** Mock Hub handles binding or broadcast permission errors on multi-homed developer setups gracefully without crashing.
2. **WebSocket Handshake Compatibility:** Mock Hub's custom RFC 6455 WebSocket frame implementation handles text frames and client disconnects cleanly without lingering threads.
3. **HTTP 206 Partial Content Range Parsing:** Out-of-bounds or malformed `Range` headers return `416 Range Not Satisfiable` rather than throwing `IndexError`.
4. **Answer Key Leakage in Mock Endpoints:** Ensure `GET /api/quizzes/active` in the mock server strips `correct_answer` so client developers never write code depending on answer keys.
5. **Guardrail False Positives:** Guardrail regex correctly flags imported libraries without false-flagging markdown documentation mentioning "firebase" or "prisma".

---

### Task 1: Canonical API Contracts & WebSocket Schemas

**Files:**
- Create: `contracts/openapi.yaml`
- Create: `contracts/events/join_request.json`
- Create: `contracts/events/join_approval.json`
- Create: `contracts/events/quiz_start.json`
- Create: `contracts/events/quiz_submit.json`
- Create: `contracts/events/ai_stream.json`
- Create: `contracts/events/queue_status.json`
- Create: `contracts/naming_rules.md`
- Test: `tests/contracts/test_contracts.py`

**Interfaces:**
- Consumes: Nothing (foundational task)
- Produces: Canonical schema files consumed by Mobile (Room/Ktor), Desktop (Tauri/TS), Server (Axum/SQLx), and Mock Hub.

- [ ] **Step 1: Write contract validation test**

```python
# tests/contracts/test_contracts.py
import json
import os
import yaml

def test_openapi_yaml_exists_and_valid():
    spec_path = "contracts/openapi.yaml"
    assert os.path.exists(spec_path), "openapi.yaml missing"
    with open(spec_path, "r", encoding="utf-8") as f:
        spec = yaml.safe_load(f)
    assert spec["openapi"].startswith("3."), "Must be OpenAPI 3.x"
    assert "/api/classrooms" in spec["paths"]
    assert "/api/sync/pull" in spec["paths"]
    assert "/api/quizzes/active" in spec["paths"]

def test_websocket_event_schemas_valid_json():
    events_dir = "contracts/events"
    assert os.path.isdir(events_dir), "contracts/events directory missing"
    required_events = [
        "join_request.json", "join_approval.json", "quiz_start.json",
        "quiz_submit.json", "ai_stream.json", "queue_status.json"
    ]
    for filename in required_events:
        path = os.path.join(events_dir, filename)
        assert os.path.exists(path), f"Missing {filename}"
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        assert data.get("type") == "object"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest tests/contracts/test_contracts.py`
Expected: FAIL (files missing)

- [ ] **Step 3: Implement schemas and naming rules**

Create `contracts/openapi.yaml` with paths (`/download`, `/api/classrooms`, `/api/classrooms/join`, `/api/sync/pull`, `/api/sync/push`, `/api/quizzes/active`, `/api/quizzes/{id}/submit`, `/api/materials/{id}/stream`).
Create all 6 JSON schema files in `contracts/events/`.
Create `contracts/naming_rules.md` defining `snake_case` JSON rules and answer key redaction rules.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest tests/contracts/test_contracts.py`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add contracts/ tests/contracts/
git commit -m "feat(contracts): add OpenAPI 3.1 specification and WebSocket event schemas"
```

---

### Task 2: Zero-Dependency Standalone Mock Hub (`scripts/mock_hub.py`)

**Files:**
- Create: `scripts/mock_hub.py`
- Test: `tests/mock_hub/test_mock_hub.py`

**Interfaces:**
- Consumes: Contract endpoints and payload formats from `contracts/`
- Produces: Simulated UDP `:8888`, HTTP `:8080`, and WS `:8081` network services for Mobile and Desktop clients.

- [ ] **Step 1: Write integration test for Mock Hub**

```python
# tests/mock_hub/test_mock_hub.py
import urllib.request
import json
import socket
import time

def test_mock_hub_http_endpoints():
    base_url = "http://127.0.0.1:8080"
    
    # Test classroom listing
    req = urllib.request.urlopen(f"{base_url}/api/classrooms")
    assert req.status == 200
    data = json.loads(req.read().decode("utf-8"))
    assert isinstance(data, list)
    assert data[0]["class_code"] == "SCI4-AG"
    
    # Test active quiz without correct_answer leak
    req_quiz = urllib.request.urlopen(f"{base_url}/api/quizzes/active")
    assert req_quiz.status == 200
    quiz = json.loads(req_quiz.read().decode("utf-8"))
    for q in quiz["questions"]:
        assert "correct_answer" not in q, "SECURITY VIOLATION: correct_answer leaked to client!"

def test_mock_hub_udp_beacon():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("", 8888))
    sock.settimeout(5.0)
    data, addr = sock.recvfrom(1024)
    payload = json.loads(data.decode("utf-8"))
    assert payload["app"] == "lara"
    assert payload["http_port"] == 8080
    sock.close()
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest tests/mock_hub/test_mock_hub.py`
Expected: FAIL (connection refused)

- [ ] **Step 3: Implement `scripts/mock_hub.py`**

Implement single-file server in Python 3 using `socketserver.ThreadingTCPServer`, `http.server.BaseHTTPRequestHandler`, and a background thread for the UDP broadcast loop (`255.255.255.255:8888`).
Implement simulated WebSocket framing on port `8081` for quiz countdowns and Socratic token streaming.
Include handling for Teacher mobile endpoints (`POST /api/announcements`, `POST /api/quizzes/:id/start`).

- [ ] **Step 4: Run test to verify it passes**

Start mock hub in background: `python3 scripts/mock_hub.py --test-mode &`
Run: `python3 -m pytest tests/mock_hub/test_mock_hub.py`
Expected: PASS
Kill background mock hub process.

- [ ] **Step 5: Commit**

```bash
git add scripts/mock_hub.py tests/mock_hub/
git commit -m "feat(mock): add standalone zero-dependency Local Hub simulator"
```

---

### Task 3: Invariant Guardrail Scanner (`scripts/verify_invariants.py` & `.github/workflows/guardrails.yml`)

**Files:**
- Create: `scripts/verify_invariants.py`
- Create: `.github/workflows/guardrails.yml`
- Test: `tests/guardrails/test_guardrail_scanner.py`

**Interfaces:**
- Consumes: Git diffs of modified code and Android string resource files.
- Produces: Exit code 0 (Pass) or Exit code 1 (Fail) with descriptive invariant violation report.

- [ ] **Step 1: Write unit tests for guardrail scanner**

```python
# tests/guardrails/test_guardrail_scanner.py
from scripts.verify_invariants import check_forbidden_patterns, check_string_parity

def test_scanner_flags_forbidden_cloud_libs():
    bad_diff = """
    +import { initializeApp } from 'firebase/app';
    +import { PrismaClient } from '@prisma/client';
    """
    violations = check_forbidden_patterns(bad_diff)
    assert len(violations) >= 2
    assert "firebase" in violations[0].lower()

def test_scanner_passes_clean_diff():
    clean_diff = """
    +import androidx.room.Database;
    +import androidx.compose.material3.Button;
    """
    violations = check_forbidden_patterns(clean_diff)
    assert len(violations) == 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest tests/guardrails/test_guardrail_scanner.py`
Expected: FAIL (module not found)

- [ ] **Step 3: Implement `scripts/verify_invariants.py` and `.github/workflows/guardrails.yml`**

Write `scripts/verify_invariants.py`:
- Checks regex patterns: `r"(?:import|from|require|implementation|compile)[^;\n]*(?:firebase|googleapis|prisma|fonts\.googleapis|cdnjs|unpkg|cdn\.jsdelivr)"`.
- Checks XML string key parity between `mobile/app/src/main/res/values/strings.xml` and `values-tl/strings.xml` if they exist.
Write `.github/workflows/guardrails.yml` triggering on PRs to `staging` and `main`, checking out code with depth 50, and executing `python3 scripts/verify_invariants.py`.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 -m pytest tests/guardrails/test_guardrail_scanner.py`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add scripts/verify_invariants.py .github/workflows/guardrails.yml tests/guardrails/
git commit -m "ci(guardrails): add automated offline invariant and string parity scanner"
```

---

### Task 4: Path-Filtered Matrix CI Workflow (`.github/workflows/ci.yml`)

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: Commits targeting `staging` or `main`.
- Produces: Matrix build status badges for `mobile`, `desktop`, and `server`.

- [ ] **Step 1: Create `.github/workflows/ci.yml`**

Configure GitHub Actions workflow using `paths` triggers:
- `job-mobile`: Runs when `mobile/**` changed. Sets up JDK 17, validates Gradle wrapper and Kotlin syntax.
- `job-desktop`: Runs when `desktop/**` changed. Sets up Node 20 + Rust, runs `npm run lint` and `cargo check`.
- `job-server`: Runs when `server/**` changed. Sets up Rust, runs `cargo check` and `cargo test`.
- `job-contracts`: Runs on `contracts/**` or `scripts/**`. Runs pytest on `tests/`.

- [ ] **Step 2: Validate workflow syntax**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
Expected: Clean load without YAML syntax errors.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: configure path-filtered matrix build pipeline for mobile, desktop, and server"
```

---

### Task 5: Streamlined PR Experience (Clean PR Template)

Instead of a noisy automated comment bot, the PR experience uses the clean, native GitHub PR template (`.github/PULL_REQUEST_TEMPLATE.md`) to avoid discussion thread clutter.

