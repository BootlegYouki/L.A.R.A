import json
import os
import sys
import time
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
sys.path.insert(0, os.path.dirname(__file__))

from scripts.mock_hub import MockHubServer  # noqa: E402
from hub_client import WsClient, call, login  # noqa: E402

HTTP, WS = 8089, 8099
BASE = f"http://127.0.0.1:{HTTP}"
CLASSROOM = "c1a2b3c4-0001-4000-8000-000000000001"

QUIZ = {
    "classroom_id": CLASSROOM, "title": "Ecosystem", "time_limit_minutes": 15, "deped_category": "WRITTEN_WORK",
    "questions": [
        {"question_text": "Gumagawa ng pagkain ang halaman.", "question_type": "MULTIPLE_CHOICE",
         "options": ["Photosynthesis", "Evaporation"], "points": 1, "correct_answer": "Photosynthesis"},
        {"question_text": "Ano ang kinakain ng herbivore?", "question_type": "IDENTIFICATION",
         "options": [], "points": 1, "correct_answer": "Halaman", "synonyms": ["plants"]},
    ],
}


class TestMockHub(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = MockHubServer(http_port=HTTP, ws_port=WS, udp_port=8899, broadcast_interval=1.0)
        cls.server.start()
        time.sleep(0.3)

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    def setUp(self):
        self.server.state.__init__()
        self.teacher = login(BASE, "T-0001")
        self.pupil = login(BASE, "123456789012")

    def make_active_quiz(self):
        _, quiz, _ = call(BASE, "POST", "/api/quizzes", QUIZ, self.teacher)
        call(BASE, "POST", f"/api/quizzes/{quiz['id']}/start", token=self.teacher)
        return quiz

    def test_requires_bearer_token(self):
        status, body, _ = call(BASE, "GET", "/api/classrooms")
        self.assertEqual(status, 401)
        self.assertEqual(body["error"]["code"], "UNAUTHORIZED")

    def test_classrooms_for_teacher_and_pupil(self):
        _, rooms, _ = call(BASE, "GET", "/api/classrooms", token=self.teacher)
        self.assertEqual(rooms[0]["class_code"], "K7M4QX")
        _, rooms, _ = call(BASE, "GET", "/api/classrooms", token=self.pupil)
        self.assertEqual(rooms[0]["enrollment_status"], "ACTIVE")

    def test_unknown_route_is_route_not_found(self):
        status, body, _ = call(BASE, "GET", "/api/nope", token=self.teacher)
        self.assertEqual((status, body["error"]["code"]), (404, "ROUTE_NOT_FOUND"))

    def test_student_quiz_never_contains_correct_answer(self):
        quiz = self.make_active_quiz()
        _, active, _ = call(BASE, "GET", "/api/quizzes/active", token=self.pupil)
        _, by_id, _ = call(BASE, "GET", f"/api/quizzes/{quiz['id']}", token=self.pupil)
        for payload in (active, by_id):
            self.assertNotIn("correct_answer", json.dumps(payload), "CRITICAL: answer key leaked to pupil")
        _, teacher_view, _ = call(BASE, "GET", f"/api/quizzes/{quiz['id']}", token=self.teacher)
        self.assertIn("correct_answer", json.dumps(teacher_view))

    def test_video_byte_range_streaming(self):
        status, body, hdrs = call(BASE, "GET", "/api/materials/m2000000-0000-4000-8000-000000000002/stream",
                                  token=self.pupil, headers={"Range": "bytes=0-99"})
        self.assertEqual(status, 206)
        self.assertEqual(len(body), 100)
        self.assertEqual(hdrs["Content-Range"], "bytes 0-99/1048576")

    def test_stream_accepts_token_query_for_html5_video(self):
        status, _, _ = call(BASE, "GET", f"/api/materials/m2000000-0000-4000-8000-000000000002/stream?token={self.pupil}",
                            headers={"Range": "bytes=0-9"})
        self.assertEqual(status, 206)

    def test_model_file_is_offered_and_resumable_and_matches_its_checksum(self):
        import hashlib
        status, offer, _ = call(BASE, "GET", "/api/model", token=self.pupil)
        self.assertEqual(status, 200)
        self.assertTrue(offer["available"])
        # download in two Range requests, the second resuming where the first stopped
        half = offer["size_bytes"] // 2
        s1, first, h1 = call(BASE, "GET", "/api/model/file", token=self.pupil, headers={"Range": f"bytes=0-{half - 1}"})
        s2, rest, h2 = call(BASE, "GET", "/api/model/file", token=self.pupil, headers={"Range": f"bytes={half}-"})
        self.assertEqual((s1, s2), (206, 206))
        self.assertEqual(h2["Content-Range"], f"bytes {half}-{offer['size_bytes'] - 1}/{offer['size_bytes']}")
        data = first if isinstance(first, bytes) else first.encode()
        data += rest if isinstance(rest, bytes) else rest.encode()
        self.assertEqual(len(data), offer["size_bytes"])
        self.assertEqual(hashlib.sha256(data).hexdigest(), offer["sha256"])

    def test_model_file_needs_a_token_accepts_token_query_and_rejects_bad_range(self):
        self.assertEqual(call(BASE, "GET", "/api/model")[0], 401)
        self.assertEqual(call(BASE, "GET", "/api/model/file", headers={"Range": "bytes=0-9"})[0], 401)
        status, _, _ = call(BASE, "GET", f"/api/model/file?token={self.pupil}", headers={"Range": "bytes=0-9"})
        self.assertEqual(status, 206)
        status, _, _ = call(BASE, "GET", "/api/model/file", token=self.pupil, headers={"Range": "bytes=999999999-"})
        self.assertEqual(status, 416)

    def test_join_approve_flow_pushes_websocket_events(self):
        ana = login(BASE, "123456789013")
        teacher_ws = WsClient("127.0.0.1", WS, self.teacher)
        ana_ws = WsClient("127.0.0.1", WS, ana)
        status, receipt, _ = call(BASE, "POST", "/api/classrooms/join", {"class_code": "K7M-4QX"}, ana)
        self.assertEqual((status, receipt["status"]), (200, "PENDING"))
        self.assertEqual(teacher_ws.recv_until("EVENT_JOIN_REQUEST")["lrn"], "123456789013")
        _, roster, _ = call(BASE, "GET", f"/api/classrooms/{CLASSROOM}/roster", token=self.teacher)
        sid = next(r["student_id"] for r in roster if r["lrn"] == "123456789013")
        call(BASE, "POST", f"/api/classrooms/{CLASSROOM}/approve", {"student_id": sid}, self.teacher)
        self.assertEqual(ana_ws.recv_until("EVENT_JOIN_APPROVAL")["status"], "ACTIVE")
        teacher_ws.close()
        ana_ws.close()

    def test_bad_class_code(self):
        status, body, _ = call(BASE, "POST", "/api/classrooms/join", {"class_code": "ZZZZZZ"}, self.pupil)
        self.assertEqual((status, body["error"]["code"]), (404, "CLASS_CODE_INVALID"))

    def test_quiz_grading_and_grade_event(self):
        quiz = self.make_active_quiz()
        _, full, _ = call(BASE, "GET", f"/api/quizzes/{quiz['id']}", token=self.teacher)
        ids = [q["id"] for q in full["questions"]]
        pupil_ws = WsClient("127.0.0.1", WS, self.pupil)
        _, begin, _ = call(BASE, "POST", f"/api/quizzes/{quiz['id']}/begin", token=self.pupil)
        now = int(time.time() * 1000)
        _, receipt, _ = call(BASE, "POST", f"/api/quizzes/{quiz['id']}/submit", {
            "started_at": begin["started_at"], "submitted_at": now,
            "answers": [{"question_id": ids[0], "selected_option": "Photosynthesis"},
                        {"question_id": ids[1], "selected_option": "  PLANTS "}]}, self.pupil)
        self.assertEqual((receipt["score"], receipt["total_points"]), (2, 2))
        self.assertEqual(pupil_ws.recv_until("EVENT_GRADE_CONFIRMED")["score"], 2)
        pupil_ws.close()

    def test_submit_after_time_limit_is_rejected(self):
        quiz = self.make_active_quiz()
        call(BASE, "POST", f"/api/quizzes/{quiz['id']}/begin", token=self.pupil)
        started = int(time.time() * 1000) - 20 * 60_000
        status, body, _ = call(BASE, "POST", f"/api/quizzes/{quiz['id']}/submit", {
            "started_at": started, "submitted_at": started + 17 * 60_000, "answers": []}, self.pupil)
        self.assertEqual((status, body["error"]["code"]), (422, "TIME_LIMIT_EXCEEDED"))

    def test_ai_tutor_locked_while_quiz_in_progress(self):
        quiz = self.make_active_quiz()
        call(BASE, "POST", f"/api/quizzes/{quiz['id']}/begin", token=self.pupil)
        ws = WsClient("127.0.0.1", WS, self.pupil)
        ws.send({"event": "EVENT_AI_CHAT_REQUEST", "request_id": "r1", "classroom_id": CLASSROOM,
                 "material_id": "m1000000-0000-4000-8000-000000000001", "message": "Ano ang sagot sa #3?", "language": "FIL"})
        err = ws.recv_until("EVENT_ERROR")
        self.assertEqual(err["code"], "QUIZ_IN_PROGRESS")
        ws.close()

    def test_ai_tutor_streams_when_no_quiz_active(self):
        ws = WsClient("127.0.0.1", WS, login(BASE, "123456789012"))
        ws.send({"event": "EVENT_AI_CHAT_REQUEST", "request_id": "r2", "classroom_id": CLASSROOM,
                 "material_id": "m1000000-0000-4000-8000-000000000001", "message": "Tulong po", "language": "FIL"})
        self.assertEqual(ws.recv_until("EVENT_QUEUE_STATUS")["position"], 2)
        done = False
        for _ in range(40):
            msg = ws.recv_until("EVENT_AI_TOKEN_STREAM")
            if msg["is_finished"]:
                done = True
                self.assertIn("grounded_chunk_id", msg)
                break
        self.assertTrue(done)
        ws.close()

    def test_bad_websocket_token_closes_with_4401(self):
        ws = WsClient("127.0.0.1", WS)
        ws.send({"event": "EVENT_HELLO", "token": "nope"})
        self.assertEqual(ws.recv(), {"event": "CLOSED", "code": 4401})

    def pull(self, token, **body):
        status, data, _ = call(BASE, "POST", "/api/sync/pull", {"cursor": 0, **body}, token)
        self.assertEqual(status, 200, data)
        return data

    def test_teacher_mobile_announcement_post_and_pull(self):
        first = self.pull(self.pupil)
        status, ann, _ = call(BASE, "POST", "/api/announcements", {
            "classroom_id": CLASSROOM, "title": "Field Trip", "content": "Magdala ng tubig."}, self.teacher)
        self.assertEqual(status, 200)
        delta = self.pull(self.pupil, cursor=first["next_cursor"])
        self.assertEqual([a["id"] for a in delta["announcements"]], [ann["id"]])
        call(BASE, "DELETE", f"/api/announcements/{ann['id']}", token=self.teacher)
        delta = self.pull(self.pupil, cursor=delta["next_cursor"])
        self.assertEqual([d["entity_id"] for d in delta["deleted"]], [ann["id"]])

    def test_cursor_never_misses_changes_made_in_the_same_millisecond(self):
        first = self.pull(self.pupil)
        ids = []
        for n in range(5):
            _, ann, _ = call(BASE, "POST", "/api/announcements", {
                "classroom_id": CLASSROOM, "title": f"A{n}", "content": "x"}, self.teacher)
            ids.append(ann["id"])
        delta = self.pull(self.pupil, cursor=first["next_cursor"])
        self.assertEqual(sorted(a["id"] for a in delta["announcements"]), sorted(ids))
        again = self.pull(self.pupil, cursor=delta["next_cursor"])
        self.assertEqual(again["announcements"], [], "a pull after the last cursor must be empty")

    def test_first_pull_returns_everything_and_identifies_the_hub(self):
        data = self.pull(self.pupil)
        self.assertTrue(data["hub_id"] and data["sync_epoch"] >= 1)
        self.assertFalse(data["reset"])
        self.assertTrue(data["announcements"] and data["materials"] and data["material_chunks"] and data["assignments"])

    def test_pull_from_a_different_hub_or_epoch_asks_for_a_reset(self):
        known = self.pull(self.pupil)
        data = self.pull(self.pupil, cursor=known["next_cursor"], hub_id="some-other-hub", sync_epoch=1)
        self.assertTrue(data["reset"])
        self.assertTrue(data["announcements"], "after a reset the response starts again from cursor 0")
        ok = self.pull(self.pupil, cursor=known["next_cursor"], hub_id=known["hub_id"], sync_epoch=known["sync_epoch"])
        self.assertFalse(ok["reset"])

    def test_pull_never_leaks_pin_data_or_other_pupils_lrn(self):
        data = self.pull(self.pupil)
        self.assertNotIn("pin_hash", json.dumps(data))
        mine = next(u for u in data["users"] if u["full_name"] == "Juan dela Cruz")
        self.assertIn("lrn_or_id", mine)
        others = [u for u in data["users"] if u["id"] != mine["id"]]
        self.assertTrue(others)
        self.assertTrue(all("lrn_or_id" not in u for u in others), "a pupil must not receive classmates' LRN")
        teacher_view = self.pull(self.teacher)
        self.assertTrue(all("lrn_or_id" in u for u in teacher_view["users"]))

    def test_submissions_are_scoped_to_their_owner(self):
        ana = login(BASE, "123456789013")
        call(BASE, "POST", "/api/classrooms/join", {"class_code": "K7M4QX"}, ana)
        _, roster, _ = call(BASE, "GET", f"/api/classrooms/{CLASSROOM}/roster", token=self.teacher)
        sid = next(r["student_id"] for r in roster if r["lrn"] == "123456789013")
        call(BASE, "POST", f"/api/classrooms/{CLASSROOM}/approve", {"student_id": sid}, self.teacher)
        asg = self.pull(self.pupil)["assignments"][0]["id"]
        boundary = "XX"
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="submission_id"\r\n\r\nsub-1\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.jpg"\r\n\r\nJPEGDATA\r\n--{boundary}--\r\n').encode()
        status, _, _ = call(BASE, "POST", f"/api/assignments/{asg}/submit", token=self.pupil, raw=body,
                            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
        self.assertEqual(status, 200)
        self.assertEqual(len(self.pull(self.pupil)["submissions"]), 1)
        self.assertEqual(self.pull(self.teacher)["submissions"][0]["student_id"], self.pull(self.pupil)["submissions"][0]["student_id"])
        self.assertEqual(self.pull(ana)["submissions"], [], "classmates must never see each other's homework")

    def test_admin_routes_are_admin_only(self):
        status, _, _ = call(BASE, "GET", "/api/admin/users", token=self.teacher)
        self.assertEqual(status, 403)
        admin = login(BASE, "ADMIN-0001")
        status, users, _ = call(BASE, "GET", "/api/admin/users", token=admin)
        self.assertEqual(status, 200)
        self.assertTrue(all("pin_hash" not in u for u in users))


if __name__ == "__main__":
    unittest.main()
