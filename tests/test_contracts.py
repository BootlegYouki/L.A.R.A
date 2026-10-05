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
        for required in ("/api/auth/login", "/api/auth/register", "/api/classrooms/{id}/approve",
                         "/api/assignments/{id}/submit", "/api/quizzes/{id}/start", "/api/export/class-record"):
            self.assertIn(required, spec["paths"], f"openapi.yaml missing {required}")

    def test_websocket_event_schemas_valid_json(self):
        events_dir = "contracts/events"
        self.assertTrue(os.path.isdir(events_dir), "contracts/events directory missing")
        required_events = [
            "join_request.json", "join_approval.json", "quiz_start.json",
            "quiz_submit.json", "ai_stream.json", "queue_status.json",
            "ws_hello.json", "grade_confirmed.json", "quiz_closed.json",
            "announcement_push.json", "presence.json", "error.json", "ai_chat_request.json"
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

    def test_sqlite_schemas_valid_and_executable(self):
        import sqlite3
        
        # Test server master schema
        conn_server = sqlite3.connect(":memory:")
        with open("contracts/schema/server_master.sql", "r", encoding="utf-8") as f:
            conn_server.executescript(f.read())
        cur_server = conn_server.cursor()
        cur_server.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
        server_tables = [r[0] for r in cur_server.fetchall()]
        self.assertEqual(len(server_tables), 16, f"Server master must have exactly 16 tables, got {server_tables}")
        self.assertIn("hub_meta", server_tables)
        self.assertIn("sessions", server_tables)
        self.assertIn("material_chunks", server_tables)
        self.assertIn("announcement_comments", server_tables)
        self.assertIn("ai_chat_messages", server_tables)
        self.assertIn("sync_revisions", server_tables)

        # Check server columns
        cur_server.execute("PRAGMA table_info(quizzes);")
        quiz_cols = [r[1] for r in cur_server.fetchall()]
        self.assertIn("started_at", quiz_cols)
        self.assertIn("deped_category", quiz_cols)

        # An unsubmitted attempt must be representable: the AI quiz lockout depends on it.
        cur_server.execute("PRAGMA table_info(quiz_attempts);")
        attempt_cols = {r[1]: r for r in cur_server.fetchall()}
        self.assertIn("status", attempt_cols)
        self.assertEqual(attempt_cols["submitted_at"][3], 0, "submitted_at must be nullable")
        self.assertEqual(attempt_cols["score"][3], 0, "score must be nullable")

        cur_server.execute("PRAGMA table_info(sync_revisions);")
        rev_cols = [r[1] for r in cur_server.fetchall()]
        self.assertEqual(rev_cols[0], "seq", "sync cursor must be an integer sequence, not a timestamp")
        self.assertIn("student_id", rev_cols)
        cur_server.execute("SELECT sql FROM sqlite_master WHERE name='sync_revisions'")
        self.assertIn("AUTOINCREMENT", cur_server.fetchone()[0])
        cur_server.execute("PRAGMA table_info(quiz_questions);")
        self.assertIn("synonyms_json", [r[1] for r in cur_server.fetchall()])
        for table in ("assignments", "quizzes"):
            cur_server.execute(f"PRAGMA table_info({table});")
            self.assertIn("quarter", [r[1] for r in cur_server.fetchall()], f"{table} needs quarter for the DepEd record")
        cur_server.execute("PRAGMA table_info(materials);")
        self.assertNotIn("extracted_text", [r[1] for r in cur_server.fetchall()], "lesson text lives in material_chunks only")
        conn_server.close()

        # Test client offline schema
        conn_client = sqlite3.connect(":memory:")
        with open("contracts/schema/client_offline.sql", "r", encoding="utf-8") as f:
            conn_client.executescript(f.read())
        cur_client = conn_client.cursor()
        cur_client.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
        client_tables = [r[0] for r in cur_client.fetchall()]
        self.assertEqual(len(client_tables), 14, f"Client offline must have exactly 14 tables, got {client_tables}")
        self.assertIn("sync_state", client_tables)
        self.assertNotIn("sessions", client_tables)
        self.assertNotIn("sync_revisions", client_tables)

        # Check client columns
        cur_client.execute("PRAGMA table_info(quiz_questions);")
        qq_cols = [r[1] for r in cur_client.fetchall()]
        self.assertNotIn("correct_answer", qq_cols, "CRITICAL: correct_answer must not exist on client schema!")

        cur_client.execute("PRAGMA table_info(users);")
        user_cols = {r[1]: r for r in cur_client.fetchall()}
        self.assertNotIn("pin_hash", user_cols, "CRITICAL: PIN hashes must never be stored on a client")
        self.assertEqual(user_cols["lrn_or_id"][3], 0, "client lrn_or_id must be nullable (classmates' LRN is not synced)")

        cur_client.execute("PRAGMA table_info(quiz_attempts);")
        client_attempt_cols = [r[1] for r in cur_client.fetchall()]
        self.assertIn("status", client_attempt_cols)
        self.assertIn("sync_status", client_attempt_cols)

        cur_client.execute("PRAGMA table_info(assignment_submissions);")
        sub_cols = [r[1] for r in cur_client.fetchall()]
        self.assertIn("sync_status", sub_cols)

        cur_client.execute("PRAGMA table_info(materials);")
        mat_cols = [r[1] for r in cur_client.fetchall()]
        self.assertIn("local_file_path", mat_cols)
        conn_client.close()


if __name__ == "__main__":

    unittest.main()
