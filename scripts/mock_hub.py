#!/usr/bin/env python3
"""
L.A.R.A Standalone Zero-Dependency Mock Hub Server

Emulates every Local Hub service defined in contracts/ so Mobile and Desktop can
build against a realistic Hub before the Rust server exists:
- UDP subnet discovery beacon (:8888)
- HTTP REST, uploads and 206 video streaming (:8080)  -> contracts/openapi.yaml
- WebSocket event broker (:8081)                       -> contracts/events/

State is in memory and resets on restart. Seeded accounts (PIN 1234 for all):
  ADMIN-0001      the Hub admin (role ADMIN); only this account may call /api/admin/*, backup and restore
  T-0001          teacher "Maria Santos" (owns Science 4)
  123456789012    pupil   "Juan dela Cruz" (enrolled, ACTIVE)
  123456789013    pupil   "Ana Reyes"      (not enrolled; join with class code K7M4QX)

Unknown routes answer 404 with error code ROUTE_NOT_FOUND so tests/test_contract_coverage.py
can tell a missing route from a missing entity (NOT_FOUND).
"""

import base64
import hashlib
import http.server
import json
import re
import socket
import socketserver
import struct
import sys
import threading
import time
import uuid

WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
GRACE_MS = 60_000
CLASS_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
ADMIN_ID = "ADMIN-0001"
MOCK_VIDEO_SIZE = 1024 * 1024
MOCK_MODEL_NAME = "mock-model-q4.gguf"
MOCK_MODEL_SIZE = 3 * 1024 * 1024          # a small stand-in; the real file is about 1.5 GB
MOCK_MODEL_BYTE = b"7"
MOCK_MODEL_SHA256 = hashlib.sha256(MOCK_MODEL_BYTE * MOCK_MODEL_SIZE).hexdigest()


def now_ms():
    return int(time.time() * 1000)


def new_id():
    return str(uuid.uuid4())


def hash_pin(pin):
    return hashlib.sha256(("lara-mock:" + pin).encode()).hexdigest()


class HubState:
    """All mutable hub data plus the WebSocket connection registry."""

    def __init__(self):
        self.lock = threading.RLock()
        self.users = {}
        self.sessions = {}
        self.classrooms = {}
        self.enrollments = {}
        self.announcements = {}
        self.comments = {}
        self.materials = {}
        self.chunks = {}
        self.assignments = {}
        self.submissions = {}
        self.submission_files = {}
        self.quizzes = {}
        self.attempts = {}
        self.topics = {}
        self.private_comments = {}
        self.co_teachers = {}                    # classroom_id -> set of co-teacher user ids
        self.ledger = []
        self.seq = 0
        self.hub_id = new_id()
        self.sync_epoch = 1
        self.ws_clients = []
        self._seed()

    def _seed(self):
        t = now_ms()
        self.add_user(ADMIN_ID, "Hub Admin", "ADMIN", "1234")
        teacher = self.add_user("T-0001", "Maria Santos", "TEACHER", "1234")
        pupil = self.add_user("123456789012", "Juan dela Cruz", "STUDENT", "1234")
        self.add_user("123456789013", "Ana Reyes", "STUDENT", "1234")
        classroom = {
            "id": "c1a2b3c4-0001-4000-8000-000000000001", "name": "Science 4",
            "section": "Aguinaldo", "class_code": "K7M4QX",
            "teacher_id": teacher["id"], "created_at": t, "updated_at": t,
        }
        self.classrooms[classroom["id"]] = classroom
        self.log("classrooms", classroom["id"], classroom["id"])
        enr = {"id": new_id(), "classroom_id": classroom["id"], "student_id": pupil["id"],
               "status": "ACTIVE", "joined_at": t, "updated_at": t}
        self.enrollments[enr["id"]] = enr
        self.log("enrollments", enr["id"], classroom["id"], pupil["id"])
        ann = {"id": new_id(), "classroom_id": classroom["id"],
               "title": "Maligayang Pagdating sa Agham 4!",
               "content": "Basahin ang Aralin 1 tungkol sa Ecosystem bago ang pagsusulit.",
               "allow_comments": True, "created_at": t - 3_600_000, "updated_at": t - 3_600_000}
        self.announcements[ann["id"]] = ann
        self.log("announcements", ann["id"], classroom["id"])
        mat = {"id": "m1000000-0000-4000-8000-000000000001", "classroom_id": classroom["id"],
               "title": "Aralin 1 - Ang Ecosystem.pdf", "file_type": "DOCUMENT",
               "file_size_bytes": 2048, "download_url": "/api/materials/m1000000-0000-4000-8000-000000000001/download",
               "created_at": t, "updated_at": t}
        self.materials[mat["id"]] = mat
        self.log("materials", mat["id"], classroom["id"])
        self.chunks[mat["id"]] = [
            {"id": new_id(), "material_id": mat["id"], "order_index": 1, "heading": "Ang Ecosystem",
             "text": "Ang ecosystem ay binubuo ng mga buhay at walang buhay na bagay na magkakaugnay."},
            {"id": new_id(), "material_id": mat["id"], "order_index": 2, "heading": "Photosynthesis",
             "text": "Gumagawa ng sariling pagkain ang mga halaman gamit ang sikat ng araw, tubig at hangin."},
        ]
        vid = {"id": "m2000000-0000-4000-8000-000000000002", "classroom_id": classroom["id"],
               "title": "Video: Food Chain", "file_type": "VIDEO", "file_size_bytes": MOCK_VIDEO_SIZE,
               "download_url": "/api/materials/m2000000-0000-4000-8000-000000000002/download",
               "created_at": t, "updated_at": t}
        self.materials[vid["id"]] = vid
        self.log("materials", vid["id"], classroom["id"])
        for chunk in self.chunks[mat["id"]]:
            self.log("material_chunks", chunk["id"], classroom["id"])
        asg = {"id": new_id(), "classroom_id": classroom["id"], "title": "Gumuhit ng Food Chain",
               "instructions": "Kunan ng litrato ang iyong guhit.", "allow_late": False,
               "max_points": 20, "due_date": t + 7 * 86_400_000, "created_at": t, "updated_at": t}
        self.assignments[asg["id"]] = asg
        self.log("assignments", asg["id"], classroom["id"])

    def log(self, table, entity_id, classroom_id=None, student_id=None, action="UPSERT"):
        """Mirror of the server's sync_revisions insert: strictly increasing seq, never a clock time."""
        self.seq += 1
        self.ledger.append({"seq": self.seq, "table": table, "id": entity_id, "classroom_id": classroom_id,
                            "student_id": student_id, "action": action})

    def add_user(self, lrn, name, role, pin):
        user = {"id": new_id(), "lrn_or_id": lrn, "full_name": name, "role": role,
                "pin_hash": hash_pin(pin), "created_at": now_ms(), "updated_at": now_ms()}
        self.users[user["id"]] = user
        self.log("users", user["id"])
        return user

    def public_user(self, user):
        return {k: user[k] for k in ("id", "lrn_or_id", "full_name", "role")}

    def open_session(self, user):
        token = uuid.uuid4().hex + uuid.uuid4().hex
        expires = now_ms() + 12 * 3_600_000
        self.sessions[token] = {"user_id": user["id"], "expires_at": expires}
        return {"token": token, "expires_at": expires, "user": self.public_user(user)}

    def user_for_token(self, token):
        s = self.sessions.get(token)
        if not s or s["expires_at"] < now_ms():
            return None
        return self.users.get(s["user_id"])

    def active_classroom_ids(self, student_id):
        return {e["classroom_id"] for e in self.enrollments.values()
                if e["student_id"] == student_id and e["status"] == "ACTIVE"}

    def visible_classroom_ids(self, user):
        if user["role"] == "TEACHER":
            return {c["id"] for c in self.classrooms.values() if user["id"] in self.teachers_of(c["id"])}
        return self.active_classroom_ids(user["id"])

    def has_active_attempt(self, student_id):
        return any(a["student_id"] == student_id and a["status"] == "IN_PROGRESS"
                   for a in self.attempts.values())

    # ---- realtime
    def broadcast(self, event, user_ids=None):
        frame = json.dumps(event)
        for client in list(self.ws_clients):
            if user_ids is None or client.user_id in user_ids:
                client.send_text(frame)

    def teachers_of(self, classroom_id):
        c = self.classrooms.get(classroom_id)
        return ({c["teacher_id"]} | self.co_teachers.get(classroom_id, set())) if c else set()

    def students_of(self, classroom_id):
        return {e["student_id"] for e in self.enrollments.values()
                if e["classroom_id"] == classroom_id and e["status"] == "ACTIVE"}


STATE = HubState()


# ----------------------------------------------------------------- grading
def grade(quiz, answers):
    given = {a["question_id"]: str(a.get("selected_option", "")) for a in answers}
    score = total = 0
    for q in quiz["questions"]:
        total += q["points"]
        got = given.get(q["id"], "")
        if q["question_type"] == "IDENTIFICATION":
            accepted = {q["correct_answer"].strip().lower()} | {s.strip().lower() for s in q.get("synonyms", [])}
            ok = got.strip().lower() in accepted
        else:
            ok = got == q["correct_answer"]
        if ok:
            score += q["points"]
    return score, total


def strip_private(obj):
    """Never leak mock-internal fields (pin_hash, leading underscore keys) in any HTTP reply."""
    if isinstance(obj, dict):
        return {k: strip_private(v) for k, v in obj.items() if k != "pin_hash" and not k.startswith("_")}
    if isinstance(obj, list):
        return [strip_private(v) for v in obj]
    return obj


def student_quiz(quiz):
    out = {k: quiz.get(k) for k in ("id", "classroom_id", "title", "time_limit_minutes", "started_at")}
    out["questions"] = [{k: v for k, v in q.items() if k not in ("correct_answer", "synonyms")}
                        for q in quiz["questions"]]
    return out


def parse_multipart(content_type, body):
    m = re.search(r'boundary="?([^";]+)"?', content_type or "")
    if not m:
        return {}, {}
    boundary = ("--" + m.group(1)).encode()
    fields, files = {}, {}
    for part in body.split(boundary):
        if b"\r\n\r\n" not in part:
            continue
        head, _, data = part.partition(b"\r\n\r\n")
        data = data.rsplit(b"\r\n", 1)[0]
        name = re.search(rb'name="([^"]+)"', head)
        if not name:
            continue
        if re.search(rb'filename="', head):
            files[name.group(1).decode()] = data
        else:
            fields[name.group(1).decode()] = data.decode("utf-8", "replace")
    return fields, files


# ----------------------------------------------------------------- HTTP
class ApiError(Exception):
    def __init__(self, status, code, message=""):
        self.status, self.code, self.message = status, code, message or code


ROUTES = []


def route(method, pattern, auth=True, role=None):
    def deco(fn):
        ROUTES.append((method, re.compile("^" + pattern + "$"), fn, auth, role))
        return fn
    return deco


class MockHttpHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        sys.stdout.write(f"[HTTP] {self.address_string()} - {format % args}\n")
        sys.stdout.flush()

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS, PUT, DELETE")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Range, Authorization")

    def send_json(self, status, obj):
        data = json.dumps(strip_private(obj)).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self._cors()
        self.end_headers()
        self.wfile.write(data)

    def send_bytes(self, status, data, ctype, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self._cors()
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self._dispatch("GET")

    def do_POST(self):
        self._dispatch("POST")

    def do_PATCH(self):
        self._dispatch("PATCH")

    def do_DELETE(self):
        self._dispatch("DELETE")

    def _token(self):
        header = self.headers.get("Authorization", "")
        if header.startswith("Bearer "):
            return header[7:]
        m = re.search(r"[?&]token=([^&]+)", self.path)
        return m.group(1) if m else None

    def _dispatch(self, method):
        path = self.path.split("?")[0]
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            for m, pattern, fn, auth, role in ROUTES:
                match = pattern.match(path) if m == method else None
                if not match:
                    continue
                user = None
                if auth:
                    with STATE.lock:
                        user = STATE.user_for_token(self._token())
                    if not user:
                        raise ApiError(401, "UNAUTHORIZED", "Missing or expired token")
                    if role == "TEACHER" and user["role"] != "TEACHER":
                        raise ApiError(403, "FORBIDDEN", "Teacher only")
                    if role == "ADMIN" and user["role"] != "ADMIN":
                        raise ApiError(403, "FORBIDDEN", "Hub admin only")
                    if role == "STUDENT" and user["role"] != "STUDENT":
                        raise ApiError(403, "FORBIDDEN", "Pupil only")
                req = Request(self, user, raw, match.groups())
                with STATE.lock:
                    result = fn(req)
                if result is not None:
                    self._reply(result)
                return
            raise ApiError(404, "ROUTE_NOT_FOUND", f"{method} {path}")
        except ApiError as e:
            self.send_json(e.status, {"error": {"code": e.code, "message": e.message}})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # keep the mock alive for client developers
            self.send_json(500, {"error": {"code": "MOCK_INTERNAL", "message": str(e)}})

    def _reply(self, result):
        if isinstance(result, Raw):
            self.send_bytes(result.status, result.data, result.ctype, result.headers)
        else:
            self.send_json(200, result)


class Raw:
    def __init__(self, data, ctype, status=200, headers=None):
        self.data, self.ctype, self.status, self.headers = data, ctype, status, headers or {}


class Request:
    def __init__(self, handler, user, raw, groups):
        self.handler, self.user, self.raw, self.groups = handler, user, raw, groups

    def json(self):
        try:
            return json.loads(self.raw.decode("utf-8")) if self.raw else {}
        except ValueError:
            raise ApiError(400, "BAD_REQUEST", "Invalid JSON")

    def multipart(self):
        return parse_multipart(self.handler.headers.get("Content-Type"), self.raw)


def need(body, *keys):
    missing = [k for k in keys if k not in body]
    if missing:
        raise ApiError(400, "BAD_REQUEST", "Missing: " + ", ".join(missing))


def owned_classroom(req, classroom_id, owner_only=False):
    """The caller must teach the class: the owner, or a co-teacher unless owner_only."""
    c = STATE.classrooms.get(classroom_id)
    if not c:
        raise ApiError(404, "NOT_FOUND", "Classroom not found")
    allowed = {c["teacher_id"]} if owner_only else STATE.teachers_of(classroom_id)
    if req.user["id"] not in allowed:
        raise ApiError(403, "FORBIDDEN", "Not your classroom")
    return c


def member_of(user, classroom_id):
    """Every class-scoped route checks this: the class's teachers and its ACTIVE learners only."""
    if classroom_id not in STATE.visible_classroom_ids(user):
        raise ApiError(403, "FORBIDDEN", "Not your class")


# ---- portal
@route("GET", r"/", auth=False)
@route("GET", r"/download", auth=False)
def portal(req):
    html = ("<!DOCTYPE html><html><head><meta charset='utf-8'><title>L.A.R.A Hub</title></head>"
            "<body style='font-family:sans-serif;text-align:center;padding:40px'>"
            "<h1>L.A.R.A Local Classroom Hub (mock)</h1>"
            "<a href='/download/lara-student.apk'>Download Android App (.apk)</a></body></html>")
    return Raw(html.encode(), "text/html; charset=utf-8")


@route("GET", r"/download/[^/]+", auth=False)
def portal_file(req):
    return Raw(b"MOCK-INSTALLER", "application/octet-stream")


# ---- auth
@route("POST", r"/api/auth/register", auth=False)
def register(req):
    b = req.json()
    need(b, "lrn_or_id", "full_name", "pin")
    if not re.fullmatch(r"\d{4}", str(b["pin"])):
        raise ApiError(400, "BAD_REQUEST", "PIN must be 4 digits")
    if any(u["lrn_or_id"] == b["lrn_or_id"] for u in STATE.users.values()):
        raise ApiError(409, "ALREADY_EXISTS", "Account already exists")
    user = STATE.add_user(b["lrn_or_id"], b["full_name"], "STUDENT", b["pin"])
    return STATE.open_session(user)


@route("POST", r"/api/auth/login", auth=False)
def login(req):
    b = req.json()
    need(b, "lrn_or_id", "pin")
    for u in STATE.users.values():
        if u["lrn_or_id"] == b["lrn_or_id"] and u["pin_hash"] == hash_pin(str(b["pin"])):
            return STATE.open_session(u)
    raise ApiError(401, "UNAUTHORIZED", "Wrong LRN or PIN")


@route("POST", r"/api/auth/logout")
def logout(req):
    STATE.sessions.pop(req.handler._token(), None)
    return {"success": True}


# ---- admin
@route("POST", r"/api/admin/setup", auth=False)
def admin_setup(req):
    # The real Hub accepts this only from the Hub PC itself (loopback). The mock is seeded with an admin,
    # so this always answers ADMIN_EXISTS unless a test clears the seed.
    b = req.json()
    need(b, "full_name", "pin")
    if any(u["role"] == "ADMIN" for u in STATE.users.values()):
        raise ApiError(409, "ADMIN_EXISTS", "The Hub admin is already set up")
    if not re.fullmatch(r"\d{4}", str(b["pin"])):
        raise ApiError(400, "BAD_REQUEST", "PIN must be 4 digits")
    return STATE.open_session(STATE.add_user(ADMIN_ID, b["full_name"], "ADMIN", b["pin"]))


@route("GET", r"/api/admin/users", role="ADMIN")
def admin_list(req):
    return [STATE.public_user(u) for u in STATE.users.values()]


@route("POST", r"/api/admin/users", role="ADMIN")
def admin_create(req):
    b = req.json()
    need(b, "lrn_or_id", "full_name", "role", "pin")
    if b["role"] not in ("TEACHER", "STUDENT"):
        raise ApiError(400, "BAD_REQUEST", "Invalid role")
    if any(u["lrn_or_id"] == b["lrn_or_id"] for u in STATE.users.values()):
        raise ApiError(409, "ALREADY_EXISTS", "Account already exists")
    return STATE.public_user(STATE.add_user(b["lrn_or_id"], b["full_name"], b["role"], b["pin"]))


@route("POST", r"/api/admin/users/([^/]+)/reset-pin", role="ADMIN")
def admin_reset(req):
    user = STATE.users.get(req.groups[0])
    if not user:
        raise ApiError(404, "NOT_FOUND", "User not found")
    user["pin_hash"] = hash_pin(str(req.json().get("pin", "")))
    user["updated_at"] = now_ms()
    return {"success": True}


# ---- classrooms
def public_classroom(c, user):
    out = {k: c[k] for k in ("id", "name", "section", "class_code", "teacher_id")}
    out["archived_at"] = c.get("archived_at")
    out["co_teacher_ids"] = sorted(STATE.co_teachers.get(c["id"], set()))
    if user["role"] == "STUDENT":
        for e in STATE.enrollments.values():
            if e["classroom_id"] == c["id"] and e["student_id"] == user["id"]:
                out["enrollment_status"] = e["status"]
    return out


@route("GET", r"/api/classrooms")
def classrooms_list(req):
    if req.user["role"] == "TEACHER":
        return [public_classroom(c, req.user) for c in STATE.classrooms.values()
                if req.user["id"] in STATE.teachers_of(c["id"])]
    mine = {e["classroom_id"] for e in STATE.enrollments.values()
            if e["student_id"] == req.user["id"] and e["status"] in ("ACTIVE", "PENDING")}
    return [public_classroom(STATE.classrooms[i], req.user) for i in mine]


@route("POST", r"/api/classrooms", role="TEACHER")
def classroom_create(req):
    b = req.json()
    need(b, "name", "section")
    import random
    code = "".join(random.choice(CLASS_CODE_ALPHABET) for _ in range(6))
    t = now_ms()
    c = {"id": new_id(), "name": b["name"], "section": b["section"], "class_code": code,
         "teacher_id": req.user["id"], "created_at": t, "updated_at": t}
    STATE.classrooms[c["id"]] = c
    STATE.log("classrooms", c["id"], c["id"])
    return public_classroom(c, req.user)


@route("POST", r"/api/classrooms/join", role="STUDENT")
def classroom_join(req):
    code = str(req.json().get("class_code", "")).replace("-", "").upper()
    c = next((c for c in STATE.classrooms.values() if c["class_code"] == code), None)
    if not c:
        raise ApiError(404, "CLASS_CODE_INVALID", "Hindi nahanap ang Class Code")
    t = now_ms()
    enr = next((e for e in STATE.enrollments.values()
                if e["classroom_id"] == c["id"] and e["student_id"] == req.user["id"]), None)
    if not enr:
        enr = {"id": new_id(), "classroom_id": c["id"], "student_id": req.user["id"],
               "status": "PENDING", "joined_at": t, "updated_at": t}
        STATE.enrollments[enr["id"]] = enr
        STATE.log("enrollments", enr["id"], c["id"], req.user["id"])
        STATE.broadcast({"event": "EVENT_JOIN_REQUEST", "class_code": c["class_code"],
                         "student_id": req.user["id"], "full_name": req.user["full_name"],
                         "lrn": req.user["lrn_or_id"], "timestamp": t}, STATE.teachers_of(c["id"]))
    return {"status": enr["status"], "classroom_id": c["id"]}


@route("GET", r"/api/classrooms/([^/]+)/roster", role="TEACHER")
def roster(req):
    owned_classroom(req, req.groups[0])
    return [{"student_id": e["student_id"], "full_name": STATE.users[e["student_id"]]["full_name"],
             "lrn": STATE.users[e["student_id"]]["lrn_or_id"], "status": e["status"], "joined_at": e["joined_at"]}
            for e in STATE.enrollments.values() if e["classroom_id"] == req.groups[0]]


def decide(req, status):
    owned_classroom(req, req.groups[0])
    sid = req.json().get("student_id")
    enr = next((e for e in STATE.enrollments.values()
                if e["classroom_id"] == req.groups[0] and e["student_id"] == sid), None)
    if not enr:
        raise ApiError(404, "NOT_FOUND", "Enrollment not found")
    enr["status"], enr["updated_at"] = status, now_ms()
    STATE.log("enrollments", enr["id"], req.groups[0], sid)
    STATE.broadcast({"event": "EVENT_JOIN_APPROVAL", "classroom_id": req.groups[0], "student_id": sid,
                     "status": status, "timestamp": now_ms()}, {sid})
    return {"success": True}


@route("POST", r"/api/classrooms/([^/]+)/approve", role="TEACHER")
def approve(req):
    return decide(req, "ACTIVE")


@route("POST", r"/api/classrooms/([^/]+)/reject", role="TEACHER")
def reject(req):
    return decide(req, "REJECTED")


@route("POST", r"/api/classrooms/([^/]+)/remove", role="TEACHER")
def remove_learner(req):
    return decide(req, "REMOVED")


@route("PATCH", r"/api/classrooms/([^/]+)", role="TEACHER")
def classroom_patch(req):
    c = owned_classroom(req, req.groups[0], owner_only=True)
    b = req.json()
    for k in ("name", "section"):
        if k in b:
            c[k] = b[k]
    if "archived" in b:
        c["archived_at"] = now_ms() if b["archived"] else None
    c["updated_at"] = now_ms()
    STATE.log("classrooms", c["id"], c["id"])
    return public_classroom(c, req.user)


@route("POST", r"/api/classrooms/([^/]+)/teachers", role="TEACHER")
def co_teacher_add(req):
    c = owned_classroom(req, req.groups[0], owner_only=True)
    need(req.json(), "lrn_or_id")
    t = next((u for u in STATE.users.values()
              if u["lrn_or_id"] == req.json()["lrn_or_id"] and u["role"] == "TEACHER"), None)
    if not t:
        raise ApiError(404, "NOT_FOUND", "No teacher with that ID")
    if t["id"] != c["teacher_id"]:
        STATE.co_teachers.setdefault(c["id"], set()).add(t["id"])
    c["updated_at"] = now_ms()
    STATE.log("classrooms", c["id"], c["id"])
    return public_classroom(c, req.user)


@route("DELETE", r"/api/classrooms/([^/]+)/teachers/([^/]+)", role="TEACHER")
def co_teacher_remove(req):
    c = owned_classroom(req, req.groups[0], owner_only=True)
    STATE.co_teachers.get(c["id"], set()).discard(req.groups[1])
    c["updated_at"] = now_ms()
    STATE.log("classrooms", c["id"], c["id"])
    return {"success": True}


# ---- sync
@route("POST", r"/api/sync/pull")
def sync_pull(req):
    b = req.json()
    cursor = int(b.get("cursor", 0))
    claimed_hub, claimed_epoch = b.get("hub_id"), b.get("sync_epoch")
    reset = bool(claimed_hub) and (claimed_hub != STATE.hub_id or claimed_epoch != STATE.sync_epoch)
    if reset:
        cursor = 0
    ids = STATE.visible_classroom_ids(req.user)
    teacher = req.user["role"] == "TEACHER"

    latest = {}
    for e in STATE.ledger:
        if e["seq"] <= cursor or e["table"] == "users" or e["classroom_id"] not in ids:
            continue
        if e["student_id"] and e["student_id"] != req.user["id"] and not teacher:
            continue
        latest[(e["table"], e["id"])] = e

    out = {k: [] for k in ("classrooms", "enrollments", "topics", "announcements", "comments", "materials",
                           "material_chunks", "assignments", "submissions", "private_comments", "quizzes",
                           "quiz_attempts")}
    deleted = []
    for (table, eid), e in latest.items():
        if e["action"] == "DELETE":
            deleted.append({"entity_table": table, "entity_id": eid})
        elif table == "classrooms" and eid in STATE.classrooms:
            out["classrooms"].append(public_classroom(STATE.classrooms[eid], req.user))
        elif table == "enrollments" and eid in STATE.enrollments:
            out["enrollments"].append(STATE.enrollments[eid])
        elif table == "topics" and eid in STATE.topics:
            out["topics"].append(STATE.topics[eid])
        elif table == "private_comments" and eid in STATE.private_comments:
            out["private_comments"].append(STATE.private_comments[eid])
        elif table == "announcements" and eid in STATE.announcements:
            out["announcements"].append(STATE.announcements[eid])
        elif table == "announcement_comments" and eid in STATE.comments:
            out["comments"].append(STATE.comments[eid])
        elif table == "materials" and eid in STATE.materials:
            out["materials"].append(STATE.materials[eid])
        elif table == "material_chunks":
            out["material_chunks"] += [c for cs in STATE.chunks.values() for c in cs if c["id"] == eid]
        elif table == "assignments" and eid in STATE.assignments:
            out["assignments"].append(STATE.assignments[eid])
        elif table == "assignment_submissions" and eid in STATE.submissions:
            out["submissions"].append(STATE.submissions[eid])
        elif table == "quizzes" and eid in STATE.quizzes:
            q = STATE.quizzes[eid]
            if q["status"] != "DRAFT" or teacher:
                out["quizzes"].append({k: q.get(k) for k in ("id", "classroom_id", "title", "status", "time_limit_minutes",
                                                              "started_at", "topic_id")})
        elif table == "quiz_attempts" and eid in STATE.attempts:
            a = STATE.attempts[eid]
            if a["status"] == "SUBMITTED":
                out["quiz_attempts"].append(grade_receipt(STATE.quizzes[a["quiz_id"]], a, for_learner=not teacher))

    people = {req.user["id"]}
    for cid in ids:
        people |= STATE.teachers_of(cid) | STATE.students_of(cid)
    users = []
    for uid in people:
        u = STATE.users[uid]
        entry = {"id": u["id"], "full_name": u["full_name"], "role": u["role"]}
        if uid == req.user["id"] or teacher:
            entry["lrn_or_id"] = u["lrn_or_id"]
        users.append(entry)

    return {"server_time": now_ms(), "hub_id": STATE.hub_id, "sync_epoch": STATE.sync_epoch,
            "next_cursor": STATE.seq, "has_more": False, "reset": reset, "users": users,
            "deleted": deleted, **out}


@route("POST", r"/api/sync/push", role="STUDENT")
def sync_push(req):
    b = req.json()
    receipts = []
    for att in b.get("quiz_attempts", []):
        try:
            submit_attempt(req.user, att["quiz_id"], att["started_at"], att["submitted_at"], att["answers"])
            receipts.append({"id": att["id"], "status": "SYNCED"})
        except ApiError as e:
            receipts.append({"id": att["id"], "status": "REJECTED", "reason": e.code})
    for c in b.get("comments", []):
        ann = STATE.announcements.get(c["announcement_id"])
        existing = STATE.comments.get(c["id"])
        if not ann or ann["classroom_id"] not in STATE.visible_classroom_ids(req.user) or (
                existing and existing["author_id"] != req.user["id"]):
            receipts.append({"id": c["id"], "status": "REJECTED", "reason": "FORBIDDEN"})
        elif ann["allow_comments"]:
            STATE.comments[c["id"]] = {"id": c["id"], "announcement_id": c["announcement_id"],
                                       "author_id": req.user["id"], "content": c["content"],
                                       "created_at": c["created_at"], "updated_at": now_ms()}
            STATE.log("announcement_comments", c["id"], ann["classroom_id"])
            receipts.append({"id": c["id"], "status": "SYNCED"})
        else:
            receipts.append({"id": c["id"], "status": "REJECTED", "reason": "COMMENTS_DISABLED"})
    for c in b.get("private_comments", []):
        asg = STATE.assignments.get(c["assignment_id"])
        existing = STATE.private_comments.get(c["id"])
        # Same rule as the REST route: only a learner enrolled in the class, and a retry may only
        # re-send the caller's own comment, never overwrite someone else's row by guessing its id.
        if (asg and not asg.get("archived_at")
                and asg["classroom_id"] in STATE.active_classroom_ids(req.user["id"])
                and (existing is None or existing["author_id"] == req.user["id"])):
            add_private_comment(asg, req.user["id"], req.user["id"], c["content"], c["id"], c["created_at"])
            receipts.append({"id": c["id"], "status": "SYNCED"})
        else:
            receipts.append({"id": c["id"], "status": "REJECTED", "reason": "FORBIDDEN"})
    return {"acknowledged": True, "receipts": receipts}


# ---- stream
@route("POST", r"/api/announcements", role="TEACHER")
def announcement_create(req):
    b = req.json()
    need(b, "classroom_id", "title", "content")
    owned_classroom(req, b["classroom_id"])
    t = now_ms()
    a = {"id": new_id(), "classroom_id": b["classroom_id"], "title": b["title"], "content": b["content"],
         "allow_comments": b.get("allow_comments", True), "created_at": t, "updated_at": t}
    STATE.announcements[a["id"]] = a
    STATE.log("announcements", a["id"], a["classroom_id"])
    STATE.broadcast({"event": "EVENT_ANNOUNCEMENT_PUSH", "classroom_id": a["classroom_id"],
                     "announcement_id": a["id"], "title": a["title"], "timestamp": t},
                    STATE.students_of(a["classroom_id"]))
    return a


@route("PATCH", r"/api/announcements/([^/]+)", role="TEACHER")
def announcement_patch(req):
    a = STATE.announcements.get(req.groups[0])
    if not a:
        raise ApiError(404, "NOT_FOUND", "Announcement not found")
    owned_classroom(req, a["classroom_id"])
    for k in ("title", "content", "allow_comments"):
        if k in req.json():
            a[k] = req.json()[k]
    a["updated_at"] = now_ms()
    STATE.log("announcements", a["id"], a["classroom_id"])
    return a


@route("DELETE", r"/api/announcements/([^/]+)", role="TEACHER")
def announcement_delete(req):
    a = STATE.announcements.get(req.groups[0])
    if not a:
        raise ApiError(404, "NOT_FOUND", "Announcement not found")
    owned_classroom(req, a["classroom_id"])
    del STATE.announcements[a["id"]]
    STATE.log("announcements", a["id"], a["classroom_id"], action="DELETE")
    return {"success": True}


def announcement_in_my_class(req):
    """Comments are class-scoped: only the class's teachers and ACTIVE learners may read or write them."""
    a = STATE.announcements.get(req.groups[0])
    if not a:
        raise ApiError(404, "NOT_FOUND", "Announcement not found")
    member_of(req.user, a["classroom_id"])
    return a


@route("GET", r"/api/announcements/([^/]+)/comments")
def comments_list(req):
    announcement_in_my_class(req)
    return sorted((c for c in STATE.comments.values() if c["announcement_id"] == req.groups[0]),
                  key=lambda c: c["created_at"])


@route("POST", r"/api/announcements/([^/]+)/comments")
def comment_create(req):
    a = announcement_in_my_class(req)
    if not a["allow_comments"]:
        raise ApiError(403, "COMMENTS_DISABLED", "Naka-off ang komento")
    need(req.json(), "content")
    t = now_ms()
    c = {"id": new_id(), "announcement_id": a["id"], "author_id": req.user["id"],
         "content": req.json()["content"], "created_at": t, "updated_at": t}
    STATE.comments[c["id"]] = c
    STATE.log("announcement_comments", c["id"], a["classroom_id"])
    return c


# ---- topics
@route("POST", r"/api/topics", role="TEACHER")
def topic_create(req):
    b = req.json()
    need(b, "classroom_id", "name")
    owned_classroom(req, b["classroom_id"])
    t = now_ms()
    order = 1 + max([x["order_index"] for x in STATE.topics.values() if x["classroom_id"] == b["classroom_id"]] or [0])
    topic = {"id": new_id(), "classroom_id": b["classroom_id"], "name": b["name"], "order_index": order,
             "created_at": t, "updated_at": t}
    STATE.topics[topic["id"]] = topic
    STATE.log("topics", topic["id"], topic["classroom_id"])
    return topic


def owned_entity(req, store, label):
    e = store.get(req.groups[0])
    if not e or e.get("archived_at"):
        raise ApiError(404, "NOT_FOUND", f"{label} not found")
    owned_classroom(req, e["classroom_id"])
    return e


def patch_entity(req, store, label, table, keys):
    e = owned_entity(req, store, label)
    for k in keys:
        if k in req.json():
            e[k] = req.json()[k]
    e["updated_at"] = now_ms()
    STATE.log(table, e["id"], e["classroom_id"])
    return e


@route("PATCH", r"/api/topics/([^/]+)", role="TEACHER")
def topic_patch(req):
    return patch_entity(req, STATE.topics, "Topic", "topics", ("name", "order_index"))


@route("DELETE", r"/api/topics/([^/]+)", role="TEACHER")
def topic_delete(req):
    topic = owned_entity(req, STATE.topics, "Topic")
    del STATE.topics[topic["id"]]
    STATE.log("topics", topic["id"], topic["classroom_id"], action="DELETE")
    for table, store in (("materials", STATE.materials), ("assignments", STATE.assignments), ("quizzes", STATE.quizzes)):
        for e in store.values():
            if e.get("topic_id") == topic["id"]:
                e["topic_id"], e["updated_at"] = None, now_ms()
                STATE.log(table, e["id"], e["classroom_id"])
    return {"success": True}


# ---- materials
@route("PATCH", r"/api/materials/([^/]+)", role="TEACHER")
def material_patch(req):
    return patch_entity(req, STATE.materials, "Material", "materials", ("title", "topic_id"))


@route("DELETE", r"/api/materials/([^/]+)", role="TEACHER")
def material_delete(req):
    m = owned_entity(req, STATE.materials, "Material")
    del STATE.materials[m["id"]]
    STATE.chunks.pop(m["id"], None)
    STATE.log("materials", m["id"], m["classroom_id"], action="DELETE")
    return {"success": True}


@route("POST", r"/api/materials", role="TEACHER")
def material_create(req):
    f, files = req.multipart()
    need(f, "classroom_id", "title", "file_type")
    owned_classroom(req, f["classroom_id"])
    data = files.get("file", b"")
    if len(data) > 250 * 1024 * 1024:
        raise ApiError(413, "FILE_TOO_LARGE", "Max 250MB")
    t, mid = now_ms(), new_id()
    m = {"id": mid, "classroom_id": f["classroom_id"], "title": f["title"], "file_type": f["file_type"],
         "file_size_bytes": len(data), "download_url": f"/api/materials/{mid}/download",
         "topic_id": f.get("topic_id"), "assignment_id": f.get("assignment_id"),
         "announcement_id": f.get("announcement_id"), "created_at": t, "updated_at": t}
    STATE.materials[mid] = m
    STATE.log("materials", mid, f["classroom_id"])
    if f["file_type"] != "VIDEO":
        chunk = {"id": new_id(), "material_id": mid, "order_index": 1,
                 "heading": f["title"], "text": data.decode("utf-8", "replace")[:500]}
        STATE.chunks[mid] = [chunk]
        STATE.log("material_chunks", chunk["id"], f["classroom_id"])
    return m


@route("GET", r"/api/materials/([^/]+)/download")
def material_download(req):
    if req.groups[0] not in STATE.materials:
        raise ApiError(404, "NOT_FOUND", "Material not found")
    member_of(req.user, STATE.materials[req.groups[0]]["classroom_id"])
    return Raw(b"%PDF-1.4 mock lesson handout\n", "application/octet-stream")


@route("GET", r"/api/materials/([^/]+)/chunks")
def material_chunks(req):
    if req.groups[0] not in STATE.materials:
        raise ApiError(404, "NOT_FOUND", "Material not found")
    member_of(req.user, STATE.materials[req.groups[0]]["classroom_id"])
    return STATE.chunks.get(req.groups[0], [])


@route("GET", r"/api/materials/([^/]+)/stream")
def material_stream(req):
    header = req.handler.headers.get("Range")
    start, end = 0, min(65535, MOCK_VIDEO_SIZE - 1)
    if header and header.startswith("bytes="):
        lo, _, hi = header[6:].partition("-")
        start = int(lo) if lo else 0
        end = int(hi) if hi else min(start + 65535, MOCK_VIDEO_SIZE - 1)
    if start >= MOCK_VIDEO_SIZE:
        return Raw(b"", "video/mp4", 416, {"Content-Range": f"bytes */{MOCK_VIDEO_SIZE}"})
    end = min(end, MOCK_VIDEO_SIZE - 1)
    return Raw(b"0" * (end - start + 1), "video/mp4", 206,
               {"Accept-Ranges": "bytes", "Content-Range": f"bytes {start}-{end}/{MOCK_VIDEO_SIZE}"})


# ---- AI model file (resumable download; the real Hub serves the file chosen by the AI evaluation)
@route("GET", r"/api/model")
def model_offer(req):
    return {"available": True, "file_name": MOCK_MODEL_NAME, "size_bytes": MOCK_MODEL_SIZE,
            "sha256": MOCK_MODEL_SHA256}


@route("GET", r"/api/model/file")
def model_file(req):
    header = req.handler.headers.get("Range")
    if not header:
        return Raw(MOCK_MODEL_BYTE * MOCK_MODEL_SIZE, "application/octet-stream", 200, {"Accept-Ranges": "bytes"})
    lo, _, hi = header.removeprefix("bytes=").partition("-")
    try:
        start = int(lo) if lo else 0
        end = int(hi) if hi else MOCK_MODEL_SIZE - 1
    except ValueError:
        return Raw(b"", "application/octet-stream", 416, {"Content-Range": f"bytes */{MOCK_MODEL_SIZE}"})
    end = min(end, MOCK_MODEL_SIZE - 1)
    if start > end:
        return Raw(b"", "application/octet-stream", 416, {"Content-Range": f"bytes */{MOCK_MODEL_SIZE}"})
    return Raw(MOCK_MODEL_BYTE * (end - start + 1), "application/octet-stream", 206,
               {"Accept-Ranges": "bytes", "Content-Range": f"bytes {start}-{end}/{MOCK_MODEL_SIZE}"})


# ---- assignments
@route("POST", r"/api/assignments", role="TEACHER")
def assignment_create(req):
    b = req.json()
    need(b, "classroom_id", "title", "max_points", "due_date")
    owned_classroom(req, b["classroom_id"])
    t = now_ms()
    a = {"id": new_id(), "classroom_id": b["classroom_id"], "title": b["title"],
         "instructions": b.get("instructions", ""), "allow_late": b.get("allow_late", False),
         "max_points": b["max_points"], "due_date": b["due_date"], "topic_id": b.get("topic_id"),
         "created_at": t, "updated_at": t}
    STATE.assignments[a["id"]] = a
    STATE.log("assignments", a["id"], a["classroom_id"])
    return a


@route("PATCH", r"/api/assignments/([^/]+)", role="TEACHER")
def assignment_patch(req):
    return patch_entity(req, STATE.assignments, "Assignment", "assignments",
                        ("title", "instructions", "max_points", "due_date", "allow_late", "topic_id"))


@route("DELETE", r"/api/assignments/([^/]+)", role="TEACHER")
def assignment_delete(req):
    # Archived, never destroyed: submissions and grades stay on the Hub (rules/database-and-sync.md section 4).
    a = owned_entity(req, STATE.assignments, "Assignment")
    a["archived_at"] = a["updated_at"] = now_ms()
    STATE.log("assignments", a["id"], a["classroom_id"], action="DELETE")
    return {"success": True}


def add_private_comment(asg, student_id, author_id, content, comment_id=None, created_at=None):
    t = now_ms()
    c = {"id": comment_id or new_id(), "assignment_id": asg["id"], "student_id": student_id,
         "author_id": author_id, "content": content, "created_at": created_at or t, "updated_at": t}
    STATE.private_comments[c["id"]] = c
    STATE.log("private_comments", c["id"], asg["classroom_id"], student_id)
    return c


def private_thread(req, student_id):
    """Resolve whose thread the caller may touch: a learner only their own, a teacher any learner of the class."""
    asg = STATE.assignments.get(req.groups[0])
    if not asg or asg.get("archived_at"):
        raise ApiError(404, "NOT_FOUND", "Assignment not found")
    if req.user["role"] == "STUDENT":
        if asg["classroom_id"] not in STATE.active_classroom_ids(req.user["id"]):
            raise ApiError(403, "FORBIDDEN", "Not your class")
        return asg, req.user["id"]
    owned_classroom(req, asg["classroom_id"])
    if not student_id:
        raise ApiError(400, "BAD_REQUEST", "Missing: student_id")
    return asg, student_id


@route("GET", r"/api/assignments/([^/]+)/private-comments")
def private_comments_list(req):
    m = re.search(r"[?&]student_id=([^&]+)", req.handler.path)
    asg, sid = private_thread(req, m.group(1) if m else None)
    return sorted((c for c in STATE.private_comments.values()
                   if c["assignment_id"] == asg["id"] and c["student_id"] == sid), key=lambda c: c["created_at"])


@route("POST", r"/api/assignments/([^/]+)/private-comments")
def private_comment_create(req):
    b = req.json()
    need(b, "content")
    asg, sid = private_thread(req, b.get("student_id"))
    return add_private_comment(asg, sid, req.user["id"], b["content"])


@route("POST", r"/api/assignments/([^/]+)/submit", role="STUDENT")
def submission_create(req):
    asg = STATE.assignments.get(req.groups[0])
    if not asg or asg.get("archived_at"):
        raise ApiError(404, "NOT_FOUND", "Assignment not found")
    member_of(req.user, asg["classroom_id"])
    fields, files = req.multipart()
    need(fields, "submission_id")
    if "file" not in files:
        raise ApiError(400, "BAD_REQUEST", "Missing file")
    t = now_ms()
    existing = next((s for s in STATE.submissions.values()
                     if s["assignment_id"] == asg["id"] and s["student_id"] == req.user["id"]), None)
    sub = existing or {"id": fields["submission_id"], "assignment_id": asg["id"], "student_id": req.user["id"],
                       "file_type": "IMAGE", "score": None, "teacher_feedback": None}
    sub["submitted_at"], sub["updated_at"] = t, t
    STATE.submissions[sub["id"]] = sub
    STATE.submission_files[sub["id"]] = files["file"]
    STATE.log("assignment_submissions", sub["id"], asg["classroom_id"], req.user["id"])
    return sub


@route("GET", r"/api/assignments/([^/]+)/submissions", role="TEACHER")
def submissions_list(req):
    asg = STATE.assignments.get(req.groups[0])
    if not asg:
        raise ApiError(404, "NOT_FOUND", "Assignment not found")
    owned_classroom(req, asg["classroom_id"])
    return [s for s in STATE.submissions.values() if s["assignment_id"] == asg["id"]]


@route("PATCH", r"/api/submissions/([^/]+)", role="TEACHER")
def submission_grade(req):
    s = STATE.submissions.get(req.groups[0])
    if not s:
        raise ApiError(404, "NOT_FOUND", "Submission not found")
    owned_classroom(req, STATE.assignments[s["assignment_id"]]["classroom_id"])
    b = req.json()
    for k in ("score", "teacher_feedback"):
        if k in b:
            s[k] = b[k]
    s["updated_at"] = now_ms()
    STATE.log("assignment_submissions", s["id"], STATE.assignments[s["assignment_id"]]["classroom_id"], s["student_id"])
    return s


@route("GET", r"/api/submissions/([^/]+)/file")
def submission_file(req):
    s = STATE.submissions.get(req.groups[0])
    if not s:
        raise ApiError(404, "NOT_FOUND", "Submission not found")
    if req.user["role"] == "STUDENT" and s["student_id"] != req.user["id"]:
        raise ApiError(403, "FORBIDDEN", "Not your submission")
    member_of(req.user, STATE.assignments[s["assignment_id"]]["classroom_id"])
    return Raw(STATE.submission_files.get(s["id"], b""), "image/jpeg")


# ---- quizzes
@route("POST", r"/api/quizzes", role="TEACHER")
def quiz_create(req):
    b = req.json()
    owned_classroom(req, b.get("classroom_id"))
    t = now_ms()
    quiz = {"id": new_id(), "classroom_id": b["classroom_id"], **quiz_fields(b),
            "status": "DRAFT", "started_at": None, "created_at": t, "updated_at": t}
    STATE.quizzes[quiz["id"]] = quiz
    STATE.log("quizzes", quiz["id"], quiz["classroom_id"])
    return quiz


def quiz_fields(b):
    need(b, "classroom_id", "title", "time_limit_minutes", "questions")
    qs = []
    for i, q in enumerate(b["questions"]):
        need(q, "question_text", "question_type", "correct_answer")
        qs.append({"id": q.get("id") or new_id(), "order_index": q.get("order_index", i + 1),
                   "question_text": q["question_text"], "question_type": q["question_type"],
                   "options": q.get("options", []), "points": q.get("points", 1),
                   "correct_answer": q["correct_answer"], "synonyms": q.get("synonyms", [])})
    return {"title": b["title"], "instructions": b.get("instructions"),
            "time_limit_minutes": b["time_limit_minutes"], "shuffle_questions": b.get("shuffle_questions", False),
            "release_scores_immediately": b.get("release_scores_immediately", True),
            "topic_id": b.get("topic_id"), "questions": qs}


@route("PATCH", r"/api/quizzes/([^/]+)", role="TEACHER")
def quiz_patch(req):
    quiz = owned_entity(req, STATE.quizzes, "Quiz")
    if quiz["status"] != "DRAFT":
        raise ApiError(409, "QUIZ_NOT_DRAFT", "A quiz can be edited only before it starts")
    quiz.update(quiz_fields({**req.json(), "classroom_id": quiz["classroom_id"]}), updated_at=now_ms())
    STATE.log("quizzes", quiz["id"], quiz["classroom_id"])
    return quiz


def pupil_quiz_or_404(quiz, user):
    if not quiz or quiz["status"] == "DRAFT":
        raise ApiError(404, "NOT_FOUND", "Quiz not found")
    member_of(user, quiz["classroom_id"])


@route("GET", r"/api/quizzes/active")
def quiz_active(req):
    ids = STATE.visible_classroom_ids(req.user)
    quiz = next((q for q in STATE.quizzes.values() if q["classroom_id"] in ids and q["status"] == "ACTIVE"), None)
    if not quiz:
        raise ApiError(404, "NOT_FOUND", "Walang aktibong pagsusulit")
    return student_quiz(quiz)


@route("GET", r"/api/quizzes/([^/]+)")
def quiz_get(req):
    quiz = STATE.quizzes.get(req.groups[0])
    if req.user["role"] == "TEACHER":
        if not quiz:
            raise ApiError(404, "NOT_FOUND", "Quiz not found")
        owned_classroom(req, quiz["classroom_id"])
        return quiz
    pupil_quiz_or_404(quiz, req.user)
    return student_quiz(quiz)


@route("POST", r"/api/quizzes/([^/]+)/start", role="TEACHER")
def quiz_start(req):
    quiz = STATE.quizzes.get(req.groups[0])
    if not quiz:
        raise ApiError(404, "NOT_FOUND", "Quiz not found")
    owned_classroom(req, quiz["classroom_id"])
    t = now_ms()
    quiz.update(status="ACTIVE", started_at=t, updated_at=t)
    STATE.log("quizzes", quiz["id"], quiz["classroom_id"])
    duration = quiz["time_limit_minutes"] * 60
    STATE.broadcast({"event": "EVENT_QUIZ_START", "quiz_id": quiz["id"], "title": quiz["title"],
                     "start_epoch_ms": t, "duration_seconds": duration}, STATE.students_of(quiz["classroom_id"]))
    return {"started_at": t, "duration_seconds": duration}


@route("POST", r"/api/quizzes/([^/]+)/begin", role="STUDENT")
def quiz_begin(req):
    quiz = STATE.quizzes.get(req.groups[0])
    pupil_quiz_or_404(quiz, req.user)
    if quiz["status"] != "ACTIVE":
        raise ApiError(409, "QUIZ_CLOSED", "Sarado na ang pagsusulit")
    att = next((a for a in STATE.attempts.values()
                if a["quiz_id"] == quiz["id"] and a["student_id"] == req.user["id"]), None)
    if not att:
        t = now_ms()
        att = {"id": new_id(), "quiz_id": quiz["id"], "student_id": req.user["id"], "status": "IN_PROGRESS",
               "started_at": t, "submitted_at": None, "score": None, "total_points": None,
               "answers": [], "updated_at": t}
        STATE.attempts[att["id"]] = att
        STATE.log("quiz_attempts", att["id"], quiz["classroom_id"], req.user["id"])
        STATE.broadcast({"event": "EVENT_PRESENCE", "classroom_id": quiz["classroom_id"],
                         "student_id": req.user["id"], "state": "ANSWERING_QUIZ", "timestamp": t},
                        STATE.teachers_of(quiz["classroom_id"]))
    return {"attempt_id": att["id"], "started_at": att["started_at"]}


def submit_attempt(user, quiz_id, started_at, submitted_at, answers):
    quiz = STATE.quizzes.get(quiz_id)
    pupil_quiz_or_404(quiz, user)
    if submitted_at - started_at > quiz["time_limit_minutes"] * 60_000 + GRACE_MS:
        raise ApiError(422, "TIME_LIMIT_EXCEEDED", "Lampas na sa oras")
    att = next((a for a in STATE.attempts.values()
                if a["quiz_id"] == quiz_id and a["student_id"] == user["id"]), None)
    if att and att["status"] == "SUBMITTED":
        return att
    score, total = grade(quiz, answers)
    t = now_ms()
    if not att:
        att = {"id": new_id(), "quiz_id": quiz_id, "student_id": user["id"], "started_at": started_at}
        STATE.attempts[att["id"]] = att
    att.update(status="SUBMITTED", submitted_at=submitted_at, score=score, total_points=total,
               answers=answers, updated_at=t)
    STATE.log("quiz_attempts", att["id"], quiz["classroom_id"], user["id"])
    if quiz["release_scores_immediately"]:
        STATE.broadcast({"event": "EVENT_GRADE_CONFIRMED", "quiz_id": quiz_id, "attempt_id": att["id"],
                         "score": score, "total_points": total, "timestamp": t}, {user["id"]})
    STATE.broadcast({"event": "EVENT_PRESENCE", "classroom_id": quiz["classroom_id"], "student_id": user["id"],
                     "state": "SUBMITTED", "timestamp": t}, STATE.teachers_of(quiz["classroom_id"]))
    return att


@route("POST", r"/api/quizzes/([^/]+)/submit", role="STUDENT")
def quiz_submit(req):
    b = req.json()
    need(b, "started_at", "submitted_at", "answers")
    att = submit_attempt(req.user, req.groups[0], b["started_at"], b["submitted_at"], b["answers"])
    return grade_receipt(STATE.quizzes[req.groups[0]], att, for_learner=True)


def grade_receipt(quiz, att, for_learner):
    # Held scores stay hidden from the learner until the teacher closes the quiz (PRD FR-4.6, FR-4.7).
    held = for_learner and not quiz["release_scores_immediately"] and quiz["status"] != "CLOSED"
    return {"attempt_id": att["id"], "score": None if held else att["score"],
            "total_points": att["total_points"], "submitted_at": att["submitted_at"]}


@route("POST", r"/api/quizzes/([^/]+)/close", role="TEACHER")
def quiz_close(req):
    quiz = STATE.quizzes.get(req.groups[0])
    if not quiz:
        raise ApiError(404, "NOT_FOUND", "Quiz not found")
    owned_classroom(req, quiz["classroom_id"])
    quiz.update(status="CLOSED", updated_at=now_ms())
    STATE.log("quizzes", quiz["id"], quiz["classroom_id"])
    for att in list(STATE.attempts.values()):
        if att["quiz_id"] == quiz["id"] and att["status"] == "IN_PROGRESS":
            score, total = grade(quiz, att["answers"])
            att.update(status="SUBMITTED", submitted_at=now_ms(), score=score, total_points=total, updated_at=now_ms())
            STATE.log("quiz_attempts", att["id"], quiz["classroom_id"], att["student_id"])
        elif att["quiz_id"] == quiz["id"] and not quiz["release_scores_immediately"]:
            STATE.log("quiz_attempts", att["id"], quiz["classroom_id"], att["student_id"])
            STATE.broadcast({"event": "EVENT_GRADE_CONFIRMED", "quiz_id": quiz["id"], "attempt_id": att["id"],
                             "score": att["score"], "total_points": att["total_points"], "timestamp": now_ms()},
                            {att["student_id"]})
    STATE.broadcast({"event": "EVENT_QUIZ_CLOSED", "quiz_id": quiz["id"], "timestamp": now_ms()},
                    STATE.students_of(quiz["classroom_id"]))
    return {"success": True}


@route("GET", r"/api/quizzes/([^/]+)/results", role="TEACHER")
def quiz_results(req):
    quiz = STATE.quizzes.get(req.groups[0])
    if not quiz:
        raise ApiError(404, "NOT_FOUND", "Quiz not found")
    owned_classroom(req, quiz["classroom_id"])
    rows = []
    for sid in STATE.students_of(quiz["classroom_id"]):
        att = next((a for a in STATE.attempts.values() if a["quiz_id"] == quiz["id"] and a["student_id"] == sid), None)
        row = {"student_id": sid, "full_name": STATE.users[sid]["full_name"],
               "status": att["status"] if att else "NOT_STARTED"}
        if att and att["status"] == "SUBMITTED":
            row.update(score=att["score"], total_points=att["total_points"])
        rows.append(row)
    return rows


# ---- export & backup
@route("POST", r"/api/export/class-record", role="TEACHER")
def export_class_record(req):
    b = req.json()
    need(b, "classroom_id", "format")
    owned_classroom(req, b["classroom_id"])
    name = "Gradebook." + ("xlsx" if b["format"] == "XLSX" else "csv")
    out = {"file_name": name}
    if b.get("target_path"):
        out["saved_to"] = b["target_path"].rstrip("/\\") + "/" + name
    return out


@route("POST", r"/api/backup", role="ADMIN")
def backup(req):
    need(req.json(), "target_path")
    return {"file_name": "classroom.lara-backup", "checksum_sha256": hashlib.sha256(b"mock").hexdigest()}


@route("POST", r"/api/restore", role="ADMIN")
def restore(req):
    need(req.json(), "source_path")
    return {"success": True}


# ----------------------------------------------------------------- WebSocket
class WsClient:
    def __init__(self, sock):
        self.sock = sock
        self.user_id = None
        self.send_lock = threading.Lock()
        self.alive = True

    def _send_frame(self, opcode, payload):
        header = bytes([0x80 | opcode])
        n = len(payload)
        if n < 126:
            header += bytes([n])
        elif n < 65536:
            header += bytes([126]) + struct.pack(">H", n)
        else:
            header += bytes([127]) + struct.pack(">Q", n)
        try:
            with self.send_lock:
                self.sock.sendall(header + payload)
        except OSError:
            self.alive = False

    def send_text(self, text):
        self._send_frame(0x1, text.encode("utf-8"))

    def close(self, code=1000):
        self._send_frame(0x8, struct.pack(">H", code))
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass

    def _read_exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError
            buf += chunk
        return buf

    def read_frame(self):
        b1, b2 = self._read_exact(2)
        opcode, masked, n = b1 & 0x0F, b2 & 0x80, b2 & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._read_exact(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read_exact(8))[0]
        mask = self._read_exact(4) if masked else b""
        data = self._read_exact(n)
        if masked:
            data = bytes(c ^ mask[i % 4] for i, c in enumerate(data))
        return opcode, data


def ws_error(client, code, message, request_id=None):
    ev = {"event": "EVENT_ERROR", "code": code, "message": message}
    if request_id:
        ev["request_id"] = request_id
    client.send_text(json.dumps(ev))


def handle_ai_request(client, msg):
    rid = msg.get("request_id", "")
    with STATE.lock:
        if STATE.has_active_attempt(client.user_id):
            return ws_error(client, "QUIZ_IN_PROGRESS", "Naka-lock ang L.A.R.A AI habang may pagsusulit.", rid)
        chunks = STATE.chunks.get(msg.get("material_id"), [])
    chunk_id = chunks[0]["id"] if chunks else None
    client.send_text(json.dumps({"event": "EVENT_QUEUE_STATUS", "position": 2, "estimated_wait_seconds": 1,
                                 "message": "Pangalawa ka sa pila - est. 1s"}))
    time.sleep(0.3)
    reply = ("Hindi ko maibibigay ang mismong sagot, pero tutulungan kitang tuklasin ito! "
             "Balikan natin ang binasa mo. Ano ang unang hakbang?").split(" ")
    for i, word in enumerate(reply):
        ev = {"event": "EVENT_AI_TOKEN_STREAM", "request_id": rid, "token": word + " ",
              "is_finished": i == len(reply) - 1}
        if chunk_id:
            ev["grounded_chunk_id"] = chunk_id
        client.send_text(json.dumps(ev))
        time.sleep(0.03)


def ws_session(sock):
    client = WsClient(sock)
    try:
        while client.alive:
            opcode, data = client.read_frame()
            if opcode == 0x8:
                break
            if opcode == 0x9:
                client._send_frame(0xA, data)
                continue
            if opcode != 0x1:
                continue
            try:
                msg = json.loads(data.decode("utf-8"))
            except ValueError:
                ws_error(client, "BAD_REQUEST", "Invalid JSON")
                continue
            event = msg.get("event")
            if client.user_id is None:
                if event != "EVENT_HELLO":
                    ws_error(client, "UNAUTHORIZED", "Send EVENT_HELLO first")
                    continue
                with STATE.lock:
                    user = STATE.user_for_token(msg.get("token"))
                    if not user:
                        client.close(4401)
                        return
                    client.user_id = user["id"]
                    STATE.ws_clients.append(client)
                client.send_text(json.dumps({"event": "EVENT_HELLO_ACK", "server_time": now_ms(),
                                                 "hub_id": STATE.hub_id, "sync_epoch": STATE.sync_epoch}))
            elif event == "EVENT_AI_CHAT_REQUEST":
                threading.Thread(target=handle_ai_request, args=(client, msg), daemon=True).start()
            elif event == "EVENT_QUIZ_SUBMIT":
                with STATE.lock:
                    user = STATE.users[client.user_id]
                    try:
                        submit_attempt(user, msg["quiz_id"], msg.get("started_at", msg["submitted_at"]),
                                       msg["submitted_at"], msg["answers"])
                    except ApiError as e:
                        ws_error(client, "BAD_REQUEST", e.code)
    except (ConnectionError, OSError):
        pass
    finally:
        client.alive = False
        with STATE.lock:
            if client in STATE.ws_clients:
                STATE.ws_clients.remove(client)
        try:
            sock.close()
        except OSError:
            pass


class WsHandshakeHandler(socketserver.BaseRequestHandler):
    def handle(self):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.request.recv(4096)
            if not chunk:
                return
            data += chunk
        m = re.search(rb"Sec-WebSocket-Key:\s*(\S+)", data, re.I)
        if not m:
            self.request.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return
        accept = base64.b64encode(hashlib.sha1(m.group(1) + WS_GUID.encode()).digest())
        self.request.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                             b"Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + b"\r\n\r\n")
        ws_session(self.request)


# ----------------------------------------------------------------- server
class MockHubServer:
    def __init__(self, http_port=8080, ws_port=8081, udp_port=8888, broadcast_interval=3.0):
        self.http_port = http_port
        self.ws_port = ws_port
        self.udp_port = udp_port
        self.broadcast_interval = broadcast_interval
        self.running = False
        self.httpd = None
        self.wsd = None
        self.udp_thread = None
        self.state = STATE

    def _get_lan_ip(self):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("10.255.255.255", 1))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return "127.0.0.1"

    def _udp_broadcast_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        lan_ip = self._get_lan_ip()
        while self.running:
            payload = json.dumps({
                "app": "lara", "version": "1.0.0-mock", "name": "Grade 4 - Science (Mock Hub)",
                "ip": lan_ip, "http_port": self.http_port, "ws_port": self.ws_port,
            }).encode("utf-8")
            try:
                sock.sendto(payload, ("255.255.255.255", self.udp_port))
            except OSError:
                pass
            time.sleep(self.broadcast_interval)
        sock.close()

    def start(self):
        self.running = True
        STATE.__init__()  # fresh seed data on every start so tests and dev sessions are repeatable
        socketserver.ThreadingTCPServer.allow_reuse_address = True
        self.httpd = socketserver.ThreadingTCPServer(("0.0.0.0", self.http_port), MockHttpHandler)
        self.httpd.daemon_threads = True
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.wsd = socketserver.ThreadingTCPServer(("0.0.0.0", self.ws_port), WsHandshakeHandler)
        self.wsd.daemon_threads = True
        threading.Thread(target=self.wsd.serve_forever, daemon=True).start()
        self.udp_thread = threading.Thread(target=self._udp_broadcast_loop, daemon=True)
        self.udp_thread.start()

    def stop(self):
        self.running = False
        for server in (self.httpd, self.wsd):
            if server:
                server.shutdown()
                server.server_close()
        for client in list(STATE.ws_clients):
            client.close()


if __name__ == "__main__":
    hub = MockHubServer(http_port=8080, ws_port=8081, udp_port=8888)
    hub.start()
    lan_ip = hub._get_lan_ip()
    print("=" * 65)
    print("  L.A.R.A Local Hub Mock Server is RUNNING")
    print(f"  - HTTP REST:    http://{lan_ip}:8080")
    print(f"  - Web Portal:   http://{lan_ip}:8080/download")
    print(f"  - WebSocket:    ws://{lan_ip}:8081")
    print("  - UDP Beacon:   Broadcasting to 255.255.255.255:8888")
    print("  - Teacher:      T-0001 / PIN 1234   (class code K7M4QX)")
    print("  - Pupil:        123456789012 / PIN 1234")
    print("  - Admin:        ADMIN-0001 / PIN 1234")
    print("=" * 65)
    print("Press Ctrl+C to stop.\n")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nStopping Mock Hub...")
        hub.stop()
        print("Mock Hub stopped.")
