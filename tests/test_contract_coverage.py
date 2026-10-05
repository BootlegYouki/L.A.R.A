"""Fails when contracts/ and scripts/mock_hub.py drift apart (a contract-change PR must update both)."""
import glob
import json
import os
import re
import sys
import time
import unittest

import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.dirname(__file__))

from scripts.mock_hub import MockHubServer  # noqa: E402
from hub_client import call, login  # noqa: E402

HTTP, WS = 8090, 8091
BASE = f"http://127.0.0.1:{HTTP}"
UUID = "00000000-0000-4000-8000-000000000000"
METHODS = ("get", "post", "patch", "put", "delete")


class TestContractCoverage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = MockHubServer(http_port=HTTP, ws_port=WS, udp_port=8898, broadcast_interval=5.0)
        cls.server.start()
        time.sleep(0.3)
        cls.admin = login(BASE, "ADMIN-0001")
        with open(os.path.join(ROOT, "contracts/openapi.yaml"), encoding="utf-8") as f:
            cls.spec = yaml.safe_load(f)

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    def test_every_openapi_operation_is_served_by_the_mock_hub(self):
        missing = []
        for path, item in self.spec["paths"].items():
            concrete = re.sub(r"\{[^}]+\}", UUID, path)
            for method in METHODS:
                if method not in item:
                    continue
                status, body, _ = call(BASE, method.upper(), concrete, token=self.admin)
                if isinstance(body, dict) and body.get("error", {}).get("code") == "ROUTE_NOT_FOUND":
                    missing.append(f"{method.upper()} {path}")
        self.assertEqual(missing, [], "Mock hub lacks routes declared in contracts/openapi.yaml")

    def test_mock_hub_does_not_serve_undocumented_api_routes(self):
        from scripts.mock_hub import ROUTES
        documented = {(m.upper(), re.compile("^" + re.sub(r"\{[^}]+\}", "[^/]+", p) + "$"))
                      for p, item in self.spec["paths"].items() for m in METHODS if m in item}
        extra = []
        for method, pattern, *_ in ROUTES:
            sample = pattern.pattern.strip("^$").replace("([^/]+)", UUID)
            if not sample.startswith("/api/"):
                continue
            if not any(m == method and rx.match(sample) for m, rx in documented):
                extra.append(f"{method} {sample}")
        self.assertEqual(extra, [], "Mock hub serves routes missing from contracts/openapi.yaml")

    def test_every_event_schema_is_implemented_and_documented(self):
        with open(os.path.join(ROOT, "scripts/mock_hub.py"), encoding="utf-8") as f:
            hub_source = f.read()
        with open(os.path.join(ROOT, "contracts/events/README.md"), encoding="utf-8") as f:
            readme = f.read()
        for path in sorted(glob.glob(os.path.join(ROOT, "contracts/events/*.json"))):
            with open(path, encoding="utf-8") as f:
                schema = json.load(f)
            event = schema["properties"]["event"]["const"]
            self.assertIn(f'"{event}"', hub_source, f"{os.path.basename(path)}: mock hub never emits/handles {event}")
            self.assertIn(f"`{event}`", readme, f"{os.path.basename(path)}: {event} missing from events/README.md")

    def test_student_schemas_never_declare_correct_answer(self):
        schemas = self.spec["components"]["schemas"]
        def property_names(node):
            names = set()
            if isinstance(node, dict):
                names.update(node.get("properties", {}).keys())
                for value in node.values():
                    names |= property_names(value)
            elif isinstance(node, list):
                for value in node:
                    names |= property_names(value)
            return names

        for name in ("StudentQuestion", "StudentQuizResponse"):
            self.assertNotIn("correct_answer", property_names(schemas[name]), f"{name} must stay redacted")

    def test_all_json_keys_in_openapi_are_snake_case(self):
        bad = set()

        def walk(node):
            if isinstance(node, dict):
                for key, value in node.items():
                    if key == "properties" and isinstance(value, dict):
                        bad.update(k for k in value if not re.fullmatch(r"[a-z][a-z0-9_]*", k))
                    walk(value)
            elif isinstance(node, list):
                for v in node:
                    walk(v)

        walk(self.spec)
        self.assertEqual(sorted(bad), [], "JSON keys must be snake_case (contracts/naming_rules.md)")


if __name__ == "__main__":
    unittest.main()
