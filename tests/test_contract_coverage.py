"""Keeps contracts/ self-consistent and checks the real Hub (server/backend/src) still has a route for every openapi operation."""
import glob
import json
import os
import re
import unittest

import yaml

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
METHODS = ("get", "post", "patch", "put", "delete")
SERVER_SRC = os.path.join(ROOT, "server/backend/src")


def norm(path):
    return re.sub(r"\{[^}]+\}", "{}", path)


class TestContractCoverage(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(ROOT, "contracts/openapi.yaml"), encoding="utf-8") as f:
            cls.spec = yaml.safe_load(f)

    def test_every_openapi_path_has_a_route_in_the_hub_source(self):
        # shortcut: matches the path as a string literal anywhere in server/backend/src, so it cannot tell
        # methods apart or see paths built at runtime; upgrade to a Hub-started contract test if that bites.
        source = ""
        for path in glob.glob(os.path.join(SERVER_SRC, "**/*.rs"), recursive=True):
            with open(path, encoding="utf-8") as f:
                source += f.read()
        literals = {norm(m) for m in re.findall(r'"(/[^"\s]*)"', source)}
        missing = [f"{m.upper()} {p}" for p, item in self.spec["paths"].items()
                   for m in METHODS if m in item and norm(p) not in literals]
        self.assertEqual(missing, [], "Hub source has no route literal for these operations in contracts/openapi.yaml")

    def test_every_event_schema_is_documented(self):
        with open(os.path.join(ROOT, "contracts/events/README.md"), encoding="utf-8") as f:
            readme = f.read()
        for path in sorted(glob.glob(os.path.join(ROOT, "contracts/events/*.json"))):
            with open(path, encoding="utf-8") as f:
                schema = json.load(f)
            event = schema["properties"]["event"]["const"]
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
