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

    def test_teacher_mobile_announcement_post_and_pull(self):
        status, ann, _ = call(BASE, "POST", "/api/announcements", {
            "classroom_id": CLASSROOM, "title": "Field Trip", "content": "Magdala ng tubig."}, self.teacher)
        self.assertEqual(status, 200)
        _, pull, _ = call(BASE, "POST", "/api/sync/pull", {"last_synced_at": 0}, self.pupil)
        self.assertIn(ann["id"], [a["id"] for a in pull["announcements"]])
        call(BASE, "DELETE", f"/api/announcements/{ann['id']}", token=self.teacher)
        _, pull, _ = call(BASE, "POST", "/api/sync/pull", {"last_synced_at": 0}, self.pupil)
        self.assertIn(ann["id"], [d["entity_id"] for d in pull["deleted"]])

    def test_admin_routes_are_admin_only(self):
        status, _, _ = call(BASE, "GET", "/api/admin/users", token=self.teacher)
        self.assertEqual(status, 403)
        admin = login(BASE, "ADMIN-0001")
        status, users, _ = call(BASE, "GET", "/api/admin/users", token=admin)
        self.assertEqual(status, 200)
        self.assertTrue(all("pin_hash" not in u for u in users))


if __name__ == "__main__":
    unittest.main()
