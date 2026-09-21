import unittest
import sys
import os

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

try:
    from scripts.verify_invariants import check_forbidden_patterns, check_string_parity
except ImportError:
    check_forbidden_patterns = None
    check_string_parity = None

class TestGuardrailScanner(unittest.TestCase):
    def test_scanner_imported(self):
        self.assertIsNotNone(check_forbidden_patterns, "check_forbidden_patterns must exist")
        self.assertIsNotNone(check_string_parity, "check_string_parity must exist")

    def test_scanner_flags_forbidden_cloud_libs(self):
        bad_diff = """
        +import { initializeApp } from 'firebase/app';
        +import { PrismaClient } from '@prisma/client';
        +<script src="https://cdnjs.cloudflare.com/ajax/libs/react/18.2.0/umd/react.production.min.js"></script>
        +<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Roboto">
        """
        violations = check_forbidden_patterns(bad_diff)
        self.assertTrue(len(violations) >= 4)
        violation_text = " ".join(violations).lower()
        self.assertIn("firebase", violation_text)
        self.assertIn("prisma", violation_text)
        self.assertIn("cdnjs", violation_text)
        self.assertIn("fonts.googleapis", violation_text)

    def test_scanner_passes_clean_diff(self):
        clean_diff = """
        +import androidx.room.Database;
        +import androidx.compose.material3.Button;
        +use sqlx::sqlite::SqlitePool;
        """
        violations = check_forbidden_patterns(clean_diff)
        self.assertEqual(len(violations), 0)

    def test_string_parity_flags_missing_tagalog_keys(self):
        en_xml = """<resources>
            <string name="app_name">LARA</string>
            <string name="login_btn">Login</string>
            <string name="quiz_timer">Time Remaining</string>
        </resources>"""
        
        tl_xml = """<resources>
            <string name="app_name">LARA</string>
            <string name="login_btn">Mag-login</string>
        </resources>"""
        
        missing = check_string_parity(en_xml, tl_xml)
        self.assertIn("quiz_timer", missing)

    def test_string_parity_passes_when_equal(self):
        en_xml = """<resources>
            <string name="app_name">LARA</string>
            <string name="login_btn">Login</string>
        </resources>"""
        
        tl_xml = """<resources>
            <string name="app_name">LARA</string>
            <string name="login_btn">Mag-login</string>
        </resources>"""
        
        missing = check_string_parity(en_xml, tl_xml)
        self.assertEqual(len(missing), 0)

if __name__ == "__main__":
    unittest.main()
