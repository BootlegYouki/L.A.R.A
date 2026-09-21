import unittest
import os
import json
import yaml

class TestContracts(unittest.TestCase):
    def test_openapi_yaml_exists_and_valid(self):
        spec_path = "contracts/openapi.yaml"
        self.assertTrue(os.path.exists(spec_path), "openapi.yaml missing")
        with open(spec_path, "r", encoding="utf-8") as f:
            spec = yaml.safe_load(f)
        self.assertTrue(spec["openapi"].startswith("3."), "Must be OpenAPI 3.x")
        self.assertIn("/api/classrooms", spec["paths"])
        self.assertIn("/api/sync/pull", spec["paths"])
        self.assertIn("/api/quizzes/active", spec["paths"])

    def test_websocket_event_schemas_valid_json(self):
        events_dir = "contracts/events"
        self.assertTrue(os.path.isdir(events_dir), "contracts/events directory missing")
        required_events = [
            "join_request.json", "join_approval.json", "quiz_start.json",
            "quiz_submit.json", "ai_stream.json", "queue_status.json"
        ]
        for filename in required_events:
            path = os.path.join(events_dir, filename)
            self.assertTrue(os.path.exists(path), f"Missing {filename}")
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
            self.assertEqual(data.get("type"), "object", f"{filename} root must be object")

    def test_naming_rules_documented(self):
        rules_path = "contracts/naming_rules.md"
        self.assertTrue(os.path.exists(rules_path), "naming_rules.md missing")
        with open(rules_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn("snake_case", content)
        self.assertIn("correct_answer", content)

if __name__ == "__main__":
    unittest.main()
