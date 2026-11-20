import json
import unittest
import os
import yaml

class TestContracts(unittest.TestCase):
    def test_openapi_yaml_exists_and_valid(self):
        spec_path = "contracts/openapi.yaml"
        self.assertTrue(os.path.exists(spec_path), "openapi.yaml missing")
        with open(spec_path, "r", encoding="utf-8") as f:
            spec = yaml.safe_load(f)
        self.assertTrue(spec["openapi"].startswith("3."), "Must be OpenAPI 3.x")

    def test_one_shared_socratic_system_prompt(self):
        # The Kotlin and TypeScript apps bundle this one file and build the prompt on-device (the Hub never
        # loads it), so the tutor behaves the same on every device. Guided and grounded since 2026-11-20:
        # accuracy first, no outside knowledge, the exact decline and English-only sentences.
        with open("contracts/ai/socratic_system_prompt.txt", "r", encoding="utf-8") as f:
            prompt = f.read()
        self.assertEqual(prompt.count("{lesson_chunks}"), 1, "prompt needs exactly one {lesson_chunks}")
        self.assertNotIn("{reply_language}", prompt, "English only: no reply-language placeholder")
        decline = "This lesson does not cover that question. Please return to your lesson or ask your teacher."
        english_only = "I can only help in English. Please ask your question again in English."
        self.assertIn(decline, prompt)
        self.assertIn(english_only, prompt)
        self.assertRegex(prompt, r"(?i)outside knowledge", "prompt must forbid outside knowledge")
        self.assertRegex(prompt, r"(?i)directly supported by a passage", "prompt must require grounding in a passage")
        self.assertRegex(prompt, r"\(Source: <heading>, page <page>\)", "prompt must ask for the citation")
        for removed in ("I can't give you the answer itself", "never give the final answer", "ONE small clue"):
            self.assertNotIn(removed, prompt, "the old Socratic rule was removed on 2026-11-20")
        with open("rules/socratic-ai-guardrails.md", "r", encoding="utf-8") as f:
            rules = f.read()
        self.assertIn(decline, rules)
        self.assertIn(english_only, rules)

    def test_required_event_schemas_exist(self):
        # Shape and snake_case of every contracts/events/*.json is checked in test_contract_coverage.
        for filename in ("join_request", "join_approval", "quiz_start", "quiz_submit",
                         "ws_hello", "grade_confirmed", "quiz_closed", "announcement_push", "presence", "error"):
            self.assertTrue(os.path.exists(f"contracts/events/{filename}.json"), f"Missing {filename}.json")

    def test_hub_has_no_ai_surface(self):
        # Decided 2026-11-20: the tutor runs on the device. The Hub has no AI events, errors or routes.
        for filename in ("ai_chat_request", "ai_stream", "queue_status"):
            self.assertFalse(os.path.exists(f"contracts/events/{filename}.json"), f"{filename}.json must stay removed")
        with open("contracts/events/error.json", "r", encoding="utf-8") as f:
            codes = json.load(f)["properties"]["code"]["enum"]
        self.assertEqual([c for c in codes if c.startswith("AI_") or c == "QUIZ_IN_PROGRESS"], [])
        with open("contracts/events/README.md", "r", encoding="utf-8") as f:
            self.assertNotRegex(f.read(), r"EVENT_AI_|EVENT_QUEUE_STATUS")
        with open("contracts/openapi.yaml", "r", encoding="utf-8") as f:
            self.assertNotRegex(f.read(), r"AI_NOT_AVAILABLE|AI_QUEUE_FULL|QUIZ_IN_PROGRESS|/api/ai")
        # TODO(post-defense, Hub AI removal issue): also scan server/ for llama-server and Hub-side prompt building
        # once that code is gone. It is not a failing check yet because the server team removes it after the defense.

    def test_planned_document_pipeline_is_marked_planned_and_not_in_openapi(self):
        with open("contracts/planned/on-device-documents.md", "r", encoding="utf-8") as f:
            text = f.read()
        self.assertIn("PLANNED, not implemented", text[:600])
        with open("contracts/openapi.yaml", "r", encoding="utf-8") as f:
            self.assertNotIn("/api/models", f.read(), "planned routes must not be added before the contract-change PR")

    def test_sqlite_schemas_valid_and_executable(self):
        import sqlite3
        
        # Test server master schema
        conn_server = sqlite3.connect(":memory:")
        with open("contracts/schema/server_master.sql", "r", encoding="utf-8") as f:
            conn_server.executescript(f.read())
        cur_server = conn_server.cursor()
        cur_server.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
        server_tables = [r[0] for r in cur_server.fetchall()]
        self.assertEqual(len(server_tables), 19, f"Server master must have exactly 19 tables, got {server_tables}")
        for added in ("classroom_teachers", "topics", "private_comments"):
            self.assertIn(added, server_tables)
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
        self.assertNotIn("deped_category", quiz_cols, "grading categories are the teacher's own, not DepEd's")

        # An unsubmitted attempt must be representable: the client's tutor lockout depends on it.
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
        cur_server.execute("PRAGMA table_info(materials);")
        material_cols = [r[1] for r in cur_server.fetchall()]
        self.assertNotIn("extracted_text", material_cols, "lesson text lives in material_chunks only")
        self.assertIn("uploaded_by", material_cols)
        material_fks = cur_server.execute("PRAGMA foreign_key_list(materials);").fetchall()
        self.assertTrue(any(row[2] == "users" and row[3] == "uploaded_by" and row[4] == "id"
                            and row[6] == "RESTRICT" for row in material_fks),
                        "materials.uploaded_by must reference users(id) with ON DELETE RESTRICT")

        # School data uses the same audit timestamp pair. Append-only and internal tables are explicit exceptions.
        cur_server.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
        for table in server_tables:
            columns = {row[1] for row in cur_server.execute(f"PRAGMA table_info({table})")}
            if table in {"hub_meta"}:
                self.assertNotIn("created_at", columns, table)
                self.assertNotIn("updated_at", columns, table)
            elif table in {"sessions", "ai_chat_messages", "sync_revisions"}:
                self.assertIn("created_at", columns, table)
                self.assertNotIn("updated_at", columns, table)
            else:
                self.assertIn("created_at", columns, table)
                self.assertIn("updated_at", columns, table)
        conn_server.close()

        # Test client offline schema
        conn_client = sqlite3.connect(":memory:")
        with open("contracts/schema/client_offline.sql", "r", encoding="utf-8") as f:
            conn_client.executescript(f.read())
        cur_client = conn_client.cursor()
        cur_client.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
        client_tables = [r[0] for r in cur_client.fetchall()]
        self.assertEqual(len(client_tables), 16, f"Client offline must have exactly 16 tables, got {client_tables}")
        self.assertNotIn("classroom_teachers", client_tables)
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

        for table in ("assignment_submissions", "quiz_attempts", "announcement_comments", "private_comments"):
            cur_client.execute(f"PRAGMA table_info({table});")
            self.assertIn("sync_reject_reason", [r[1] for r in cur_client.fetchall()], f"{table} must keep the Hub's reject reason")
            sql = cur_client.execute("SELECT sql FROM sqlite_master WHERE name = ?", (table,)).fetchone()[0]
            self.assertIn("'REJECTED'", sql, f"{table}.sync_status must allow REJECTED")

        cur_client.execute("PRAGMA table_info(materials);")
        mat_cols = [r[1] for r in cur_client.fetchall()]
        self.assertIn("local_file_path", mat_cols)
        self.assertNotIn("uploaded_by", mat_cols, "uploader identity stays server-side")
        for table in client_tables:
            columns = {row[1] for row in cur_client.execute(f"PRAGMA table_info({table})")}
            if table == "sync_state":
                self.assertNotIn("created_at", columns, table)
                self.assertIn("updated_at", columns, table)
            elif table == "ai_chat_messages":
                self.assertIn("created_at", columns, table)
                self.assertNotIn("updated_at", columns, table)
            else:
                self.assertIn("created_at", columns, table)
                self.assertIn("updated_at", columns, table)
        conn_client.close()


if __name__ == "__main__":

    unittest.main()
