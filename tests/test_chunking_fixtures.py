"""Keeps contracts/ai/fixtures/*.json well formed. The fixtures are the golden cases both clients must pass
(rules/socratic-ai-guardrails.md section 3.4); this only checks the files themselves, not a client."""
import glob
import json
import math
import os
import re
import unittest
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


def tokens(text):
    return math.ceil(len(text.split()) * 1.4)


class TestChunkingFixtures(unittest.TestCase):
    def setUp(self):
        self.paths = sorted(glob.glob(os.path.join(ROOT, "contracts/ai/fixtures/*.json")))

    def test_there_are_fixtures_and_the_spec_marks_numbers_pending(self):
        self.assertGreaterEqual(len(self.paths), 3)
        with open(os.path.join(ROOT, "contracts/ai/chunking_spec.md"), encoding="utf-8") as f:
            self.assertIn("PENDING", f.read())

    def test_every_fixture_is_well_formed(self):
        for path in self.paths:
            name = os.path.basename(path)
            with open(path, encoding="utf-8") as f:
                fx = json.load(f)
            for key in ("spec_version", "normative", "description", "material_id", "title", "params", "input", "expected"):
                self.assertIn(key, fx, f"{name}: missing {key}")
            self.assertIn(fx["input"]["format"], ("pdf", "docx"), name)
            limit = fx["params"]["max_chunk_tokens"]
            chunks = fx["expected"]["chunks"]
            self.assertEqual([c["order_index"] for c in chunks], list(range(1, len(chunks) + 1)), f"{name}: order_index from 1, no gaps")
            blocks = fx["expected"]["blocks"]
            self.assertEqual(fx["expected"]["cleaned_text"], "\n\n".join(b["text"] for b in blocks), name)
            for c in chunks:
                self.assertEqual(c["id"], str(uuid.uuid5(uuid.UUID(fx["material_id"]), f"{fx['spec_version']}:{c['order_index']}")), f"{name}: id")
                self.assertTrue(c["text"].strip(), f"{name}: empty chunk")
                self.assertEqual(c["token_estimate"], tokens(c["text"]), f"{name}: token_estimate")
                self.assertLessEqual(c["token_estimate"], limit, f"{name}: chunk over max_chunk_tokens")
            # Chunks keep every word of the paragraphs, in order (a split only changes whitespace).
            squash = lambda s: re.sub(r"\s+", " ", s).strip()
            paragraphs = " ".join(b["text"] for b in blocks if b["kind"] == "paragraph")
            self.assertEqual(squash(" ".join(c["text"] for c in chunks)), squash(paragraphs), f"{name}: chunks lose or add text")


if __name__ == "__main__":
    unittest.main()
