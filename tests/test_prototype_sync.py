"""Fails when design-system/prototype.html no longer matches the remake screen in design-system/showcase.html."""
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


class TestPrototypeSync(unittest.TestCase):
    def test_prototype_is_up_to_date(self):
        r = subprocess.run([sys.executable, str(ROOT / "scripts" / "build_prototype.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr or r.stdout)


if __name__ == "__main__":
    unittest.main()
