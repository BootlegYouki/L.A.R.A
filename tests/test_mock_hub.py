import unittest
import urllib.request
import urllib.error
import json
import socket
import time
import threading
import sys
import os

# Add repo root to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

try:
    from scripts.mock_hub import MockHubServer
except ImportError:
    MockHubServer = None

class TestMockHub(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if MockHubServer is None:
            return
        cls.server = MockHubServer(http_port=8089, ws_port=8099, udp_port=8899, broadcast_interval=1.0)
        cls.server.start()
        time.sleep(0.3)

    @classmethod
    def tearDownClass(cls):
        if hasattr(cls, "server") and cls.server:
            cls.server.stop()

    def test_mock_hub_imported(self):
        self.assertIsNotNone(MockHubServer, "scripts.mock_hub.MockHubServer must exist")

    def test_classrooms_endpoint(self):
        url = "http://127.0.0.1:8089/api/classrooms"
        req = urllib.request.urlopen(url, timeout=3.0)
        self.assertEqual(req.status, 200)
        data = json.loads(req.read().decode("utf-8"))
        self.assertIsInstance(data, list)
        self.assertTrue(len(data) > 0)
        self.assertEqual(data[0]["class_code"], "SCI4-AG")

    def test_active_quiz_strips_correct_answer(self):
        url = "http://127.0.0.1:8089/api/quizzes/active"
        req = urllib.request.urlopen(url, timeout=3.0)
        self.assertEqual(req.status, 200)
        quiz = json.loads(req.read().decode("utf-8"))
        self.assertIn("questions", quiz)
        for q in quiz["questions"]:
            self.assertNotIn("correct_answer", q, "CRITICAL: correct_answer leaked to client!")

    def test_video_byte_range_streaming(self):
        url = "http://127.0.0.1:8089/api/materials/dummy-id/stream"
        req = urllib.request.Request(url, headers={"Range": "bytes=0-99"})
        try:
            resp = urllib.request.urlopen(req, timeout=3.0)
            self.assertEqual(resp.status, 206)
            self.assertIn("Content-Range", resp.headers)
            body = resp.read()
            self.assertEqual(len(body), 100)
        except urllib.error.HTTPError as e:
            self.assertEqual(e.code, 206)

    def test_teacher_mobile_announcement_post(self):
        url = "http://127.0.0.1:8089/api/announcements"
        payload = json.dumps({
            "classroom_id": "c1",
            "title": "Field Trip Tomorrow",
            "content": "Magdala ng payong at tubig."
        }).encode("utf-8")
        req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"})
        resp = urllib.request.urlopen(req, timeout=3.0)
        self.assertEqual(resp.status, 200)
        data = json.loads(resp.read().decode("utf-8"))
        self.assertTrue(data.get("success"))

if __name__ == "__main__":
    unittest.main()
