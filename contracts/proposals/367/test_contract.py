"""Validate #367 proposal artifacts, not a running Hub or an implemented API."""

import copy
import json
from pathlib import Path
import sqlite3
import unittest

from jsonschema import Draft202012Validator, FormatChecker
import yaml


ROOT = Path(__file__).resolve().parent
DEFINITIONS = json.loads((ROOT / "schemas.json").read_text(encoding="utf-8"))["$defs"]


def validator(name):
    return Draft202012Validator(
        {"$ref": f"#/$defs/{name}", "$defs": DEFINITIONS},
        format_checker=FormatChecker(),
    )


def entry(seq=1):
    return {
        "seq": seq, "occurred_at": 1000, "event_code": "DOCUMENT_PROCESSED",
        "severity": "INFO", "result": "SUCCEEDED", "service": "DOCUMENTS",
        "details": {"item_count": 12, "duration_ms": 85},
    }


class ActivityContractProposal(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript((ROOT / "activity.sql").read_text(encoding="utf-8"))

    def test_json_schema_is_valid(self):
        Draft202012Validator.check_schema({"$defs": DEFINITIONS})
        for name in ("ActivityEntry", "ActivityLogPage"):
            Draft202012Validator.check_schema(validator(name).schema)

    def test_openapi_declares_private_read_only_proposed_route(self):
        spec = yaml.safe_load((ROOT / "openapi.yaml").read_text(encoding="utf-8"))
        self.assertEqual(list(spec["paths"]), ["/api/admin/activity-logs"])
        path = spec["paths"]["/api/admin/activity-logs"]
        self.assertEqual(list(path), ["get"])
        self.assertEqual(spec["security"], [{"bearerAuth": []}])
        operation = path["get"]
        self.assertEqual(set(operation["responses"]), {"200", "400", "401", "403", "409", "503"})
        parameters = {param["name"]: param["schema"] for param in operation["parameters"]}
        self.assertEqual(parameters["limit"], {"type": "integer", "minimum": 1, "maximum": 100, "default": 100})
        self.assertEqual(parameters["q"]["maxLength"], 64)
        self.assertIn("history_id", parameters)
        # The promoted contract must preserve this draft's safe DTO shapes.
        canonical = yaml.safe_load((ROOT.parents[1] / "openapi.yaml").read_text(encoding="utf-8"))
        self.assertIn("/api/admin/activity-logs", canonical["paths"])
        canonical_definitions = canonical["components"]["schemas"]
        for name, definition in DEFINITIONS.items():
            expected = json.loads(json.dumps(definition).replace('#/$defs/', '#/components/schemas/'))
            self.assertEqual(canonical_definitions[name], expected)

    def test_sql_is_executable_and_private_tables_have_no_foreign_keys(self):
        names = {row[0] for row in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertEqual(names, {"hub_activity_events", "hub_activity_state", "sqlite_sequence"})
        for table in ("hub_activity_events", "hub_activity_state"):
            self.assertEqual(self.db.execute(f"PRAGMA foreign_key_list({table})").fetchall(), [])
        sql = self.db.execute("SELECT sql FROM sqlite_master WHERE name='hub_activity_events'").fetchone()[0]
        self.assertIn("AUTOINCREMENT", sql)

    def test_sql_and_json_enum_allowlists_agree(self):
        columns = {
            "event_code": "ActivityEventCode", "severity": "ActivitySeverity",
            "result": "ActivityResult", "service": "ActivityService", "reason": "ActivityReason",
        }
        sql = self.db.execute("SELECT sql FROM sqlite_master WHERE name='hub_activity_events'").fetchone()[0]
        import re
        for column, schema in columns.items():
            check = re.search(rf"{column} IN \((.*?)\)", sql, re.S).group(1)
            allowed = re.findall(r"'([^']+)'", check)
            self.assertEqual(set(allowed), set(DEFINITIONS[schema]["enum"]), column)
            self.assertEqual(len(allowed), len(set(allowed)), column)

    def test_safe_entry_and_empty_page_are_valid(self):
        validator("ActivityEntry").validate(entry())
        page = {
            "history_id": "b9baa2eb-9a03-44bc-b721-28c54a849013", "entries": [],
            "high_water_seq": 0, "next_before_seq": None, "next_after_seq": None,
            "pruned_through_seq": 0, "dropped_total": 0,
            "recording_state": "ACTIVE", "server_time": 1000,
        }
        validator("ActivityLogPage").validate(page)
        page["entries"] = [entry() for _ in range(101)]
        self.assertTrue(list(validator("ActivityLogPage").iter_errors(page)))

    def test_private_and_unknown_fields_are_rejected_at_both_levels(self):
        for key in ("full_name", "lrn_or_id", "user_id", "pin", "pin_hash", "token", "file_path",
                    "document_text", "correct_answer", "message", "context", "request_url"):
            for nested in (False, True):
                candidate = entry()
                target = candidate["details"] if nested else candidate
                target[key] = "PRIVATE_SENTINEL"
                self.assertTrue(list(validator("ActivityEntry").iter_errors(candidate)), (key, nested))
        candidate = entry()
        candidate["details"]["reason"] = "PRIVATE_SENTINEL"
        self.assertTrue(list(validator("ActivityEntry").iter_errors(candidate)))

    def test_bounds_and_null_details_are_rejected(self):
        for field, invalid in (("seq", 0), ("seq", 9007199254740992), ("occurred_at", -1),
                               ("event_code", "ARBITRARY_TEXT"), ("details", None)):
            candidate = entry()
            candidate[field] = invalid
            self.assertTrue(list(validator("ActivityEntry").iter_errors(candidate)), (field, invalid))
        for field, invalid in (("item_count", -1), ("item_count", 1000001), ("duration_ms", 86400001),
                               ("reason", None)):
            candidate = entry()
            candidate["details"][field] = invalid
            self.assertTrue(list(validator("ActivityEntry").iter_errors(candidate)), (field, invalid))

    def test_sql_rejects_unsafe_reason_and_out_of_bounds_details(self):
        base = [1000, "DOCUMENT_PROCESSED", "INFO", "SUCCEEDED", "DOCUMENTS", None, 12, 85]
        insert = "INSERT INTO hub_activity_events (occurred_at,event_code,severity,result,service,reason,item_count,duration_ms) VALUES (?,?,?,?,?,?,?,?)"
        self.db.execute(insert, base)
        for position, invalid in ((1, "ARBITRARY_TEXT"), (5, "PRIVATE_SENTINEL"), (6, -1), (7, 86400001)):
            values = copy.copy(base)
            values[position] = invalid
            with self.assertRaises(sqlite3.IntegrityError):
                self.db.execute(insert, values)

    def test_sql_fixture_drains_burst_without_skipping_new_records(self):
        self.db.executemany(
            "INSERT INTO hub_activity_events (occurred_at,event_code,severity,result,service) VALUES (?, 'HUB_STARTED','INFO','SUCCEEDED','HUB')",
            [(1000,)] * 350,
        )
        watermark, snapshot = 100, 350
        seen = []
        while watermark < snapshot:
            # Normative example query: select oldest unseen first, serialize that page newest-first.
            page = [row[0] for row in self.db.execute(
                "SELECT seq FROM (SELECT seq FROM hub_activity_events WHERE seq>? AND seq<=? ORDER BY seq ASC LIMIT 100) ORDER BY seq DESC",
                (watermark, snapshot),
            )]
            self.assertTrue(page)
            seen.extend(page)
            watermark = max(page)
        self.assertEqual(len(seen), len(set(seen)))
        self.assertEqual(set(seen), set(range(101, 351)))
        self.assertEqual(seen[:3], [200, 199, 198])

    def test_pruning_does_not_reuse_sequence_numbers(self):
        for _ in range(2):
            self.db.execute("INSERT INTO hub_activity_events (occurred_at,event_code,severity,result,service) VALUES (1000,'HUB_STARTED','INFO','SUCCEEDED','HUB')")
        self.db.execute("DELETE FROM hub_activity_events")
        self.db.execute("INSERT INTO hub_activity_events (occurred_at,event_code,severity,result,service) VALUES (2000,'HUB_STARTED','INFO','SUCCEEDED','HUB')")
        self.assertEqual(self.db.execute("SELECT seq FROM hub_activity_events").fetchone()[0], 3)


if __name__ == "__main__":
    unittest.main()
