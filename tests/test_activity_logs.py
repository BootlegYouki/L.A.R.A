"""Activity-history contract coverage. Durable Rust capture is implemented separately."""
import json
import os
from pathlib import Path
import sqlite3
import sys
import unittest
from urllib.parse import urlencode

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from scripts.mock_hub import MockHubServer, now_ms
from hub_client import EXTERNAL_HUB, call, login

ROOT = Path(__file__).resolve().parents[1]


class TestActivitySchema(unittest.TestCase):
    def test_activity_tables_are_private_and_not_client_synced(self):
        with sqlite3.connect(":memory:") as db:
            db.executescript((ROOT / "contracts/schema/server_master.sql").read_text(encoding="utf-8"))
            events = {row[1] for row in db.execute("PRAGMA table_info(hub_activity_events)")}
            self.assertEqual(events, {"seq", "occurred_at", "event_code", "severity", "result", "service", "reason", "item_count", "duration_ms"})
            self.assertEqual(db.execute("PRAGMA foreign_key_list(hub_activity_events)").fetchall(), [])
            for invalid in ("PRIVATE_SENTINEL", "../secret/path", "Bearer secret"):
                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute("INSERT INTO hub_activity_events (occurred_at,event_code,severity,result,service,reason) VALUES (1,'PIN_RESET','INFO','SUCCEEDED','ACCOUNTS',?)", (invalid,))
        client_sql = (ROOT / "contracts/schema/client_offline.sql").read_text(encoding="utf-8")
        self.assertNotIn("hub_activity_", client_sql)

    def test_canonical_payloads_have_only_safe_fields(self):
        spec = yaml.safe_load((ROOT / "contracts/openapi.yaml").read_text(encoding="utf-8"))
        schemas = spec["components"]["schemas"]
        self.assertFalse(schemas["ActivityEntry"]["additionalProperties"])
        self.assertFalse(schemas["ActivityDetails"]["additionalProperties"])
        self.assertEqual(set(schemas["ActivityDetails"]["properties"]), {"reason", "item_count", "duration_ms"})
        self.assertEqual(schemas["ActivityLogPage"]["properties"]["entries"]["maxItems"], 100)
        operation = spec["paths"]["/api/admin/activity-logs"]["get"]
        self.assertEqual(set(operation["responses"]), {"200", "400", "401", "403", "409", "503"})


@unittest.skipIf(bool(EXTERNAL_HUB), "Reference contract only; the production route belongs to #368")
class TestActivityHistoryReference(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = MockHubServer(http_port=0, ws_port=0, udp_port=8896, broadcast_interval=5)
        cls.server.start()
        cls.base = f"http://127.0.0.1:{cls.server.httpd.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    def setUp(self):
        self.server.state.__init__()
        self.admin = login(self.base, "ADMIN-0001")
        self.history = self.server.state.activity_history_id

    def populate(self, count=350):
        self.time = now_ms()
        self.server.state.activity_entries = [{
            "seq": n, "occurred_at": self.time, "event_code": "DOCUMENT_PROCESSED",
            "severity": "INFO", "result": "SUCCEEDED", "service": "DOCUMENTS",
            "details": {"item_count": 3},
        } for n in range(1, count + 1)]
        self.server.state.activity_seq = count

    def get(self, **query):
        path = "/api/admin/activity-logs" + ("?" + urlencode(query) if query else "")
        return call(self.base, "GET", path, token=self.admin)

    def test_authentication_roles_and_locality(self):
        path = "/api/admin/activity-logs"
        self.assertEqual(call(self.base, "GET", path)[0], 401)
        for account in ("T-0001", "123456789012"):
            token = login(self.base, account)
            self.assertEqual(call(self.base, "GET", path, token=token)[0], 403)
        status, body, _ = call(self.base, "GET", path, token=self.admin, headers={"Cf-Connecting-IP": "203.0.113.10"})
        self.assertEqual((status, body["error"]["code"]), (403, "ADMIN_LOCAL_ONLY"))
        self.assertEqual(call(self.base, "GET", path + "?token=" + self.admin)[0], 401)

    def test_empty_history_and_no_store(self):
        status, page, headers = self.get()
        self.assertEqual(status, 200)
        self.assertEqual(page["entries"], [])
        self.assertEqual(page["high_water_seq"], 0)
        self.assertEqual(page["history_id"], self.history)
        self.assertEqual(headers["Cache-Control"], "no-store")

    def test_initial_and_older_pages_are_newest_first(self):
        self.populate()
        _, first, _ = self.get()
        self.assertEqual([entry["seq"] for entry in first["entries"]], list(range(350, 250, -1)))
        self.assertEqual(first["next_before_seq"], 251)
        _, older, _ = self.get(before_seq=251, snapshot_seq=350, history_id=self.history)
        self.assertEqual([entry["seq"] for entry in older["entries"]], list(range(250, 150, -1)))
        self.assertIsNone(older["next_after_seq"])

    def test_catch_up_does_not_skip_a_large_burst_or_include_new_arrivals(self):
        self.populate()
        cursor, snapshot, seen = 100, 350, []
        while True:
            status, page, _ = self.get(after_seq=cursor, snapshot_seq=snapshot, history_id=self.history)
            self.assertEqual(status, 200)
            seen.extend(entry["seq"] for entry in page["entries"])
            if page["next_after_seq"] is None:
                break
            cursor = page["next_after_seq"]
            if cursor == 200:
                arrival = dict(self.server.state.activity_entries[-1], seq=351)
                self.server.state.activity_entries.append(arrival)
                self.server.state.activity_seq = 351
        self.assertEqual(len(seen), 250)
        self.assertEqual(set(seen), set(range(101, 351)))
        self.assertEqual(seen[:3], [200, 199, 198])
        _, next_poll, _ = self.get(after_seq=350, history_id=self.history)
        self.assertEqual([entry["seq"] for entry in next_poll["entries"]], [351])

    def test_filters_combine_and_empty_filter_advances_watermark(self):
        self.populate(4)
        self.server.state.activity_entries[0]["severity"] = "WARNING"
        status, page, _ = self.get(q="document_processed", severity="INFO", from_time=self.time, to_time=self.time + 1)
        self.assertEqual(status, 200)
        self.assertEqual([entry["seq"] for entry in page["entries"]], [4, 3, 2])
        _, empty, _ = self.get(q="no matching catalog event", after_seq=0, history_id=self.history)
        self.assertEqual(empty["entries"], [])
        self.assertEqual(empty["high_water_seq"], 4)
        self.assertIsNone(empty["next_after_seq"])

    def test_bad_filters_and_restored_history(self):
        self.populate()
        invalid = ["unknown=1", "limit=0", "limit=101", "limit=1e2", "limit=1&limit=2", "after_seq=1", "severity=DEBUG",
                   "from_time=2&to_time=1", "history_id=bad", "q=" + "a" * 65,
                   "before_seq=2&after_seq=1&history_id=" + self.history,
                   "snapshot_seq=351&history_id=" + self.history, "q=%FF"]
        for query in invalid:
            status, body, _ = call(self.base, "GET", "/api/admin/activity-logs?" + query, token=self.admin)
            self.assertEqual((status, body["error"]["code"]), (400, "BAD_REQUEST"), query)
        status, body, _ = self.get(after_seq=0, history_id="00000000-0000-4000-8000-000000000000")
        self.assertEqual((status, body["error"]["code"]), (409, "ACTIVITY_LOG_RESET"))

    def test_retention_and_hard_cap_report_removed_history(self):
        self.populate(20002)
        self.server.state.activity_entries[0]["occurred_at"] = self.time - 8 * 86400000
        _, page, _ = self.get()
        self.assertEqual(len(self.server.state.activity_entries), 20000)
        self.assertEqual(page["pruned_through_seq"], 2)
        self.assertEqual(page["high_water_seq"], 20002)

    def test_recorder_failure_is_separate_from_read_failure(self):
        self.populate(2)
        self.server.state.activity_recording_state = "UNAVAILABLE"
        self.server.state.activity_dropped_total = 7
        status, page, _ = self.get()
        self.assertEqual(status, 200)
        self.assertEqual(page["recording_state"], "UNAVAILABLE")
        self.assertEqual(page["dropped_total"], 7)
        self.assertEqual(len(page["entries"]), 2)
        self.server.state.activity_readable = False
        status, body, _ = self.get()
        self.assertEqual((status, body["error"]["code"]), (503, "ACTIVITY_LOG_UNAVAILABLE"))


if __name__ == "__main__":
    unittest.main()
