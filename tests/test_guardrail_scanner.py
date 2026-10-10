import json
import subprocess
import tempfile
import unittest
import sys
import os

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

try:
    from scripts.verify_invariants import check_forbidden_patterns, check_string_parity, check_json_parity
except ImportError:
    check_forbidden_patterns = None
    check_string_parity = None
    check_json_parity = None

SCRIPT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scripts", "verify_invariants.py"))


def _git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True,
                          env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}).stdout.strip()


def _scan_repo(files, extra_env=None):
    """Commit `files` on top of an empty commit in a throwaway repo and run the real script against it."""
    with tempfile.TemporaryDirectory() as d:
        _git(d, "init", "-q")
        _git(d, "commit", "-q", "--allow-empty", "-m", "base")
        base = _git(d, "rev-parse", "HEAD")
        for path, content in files.items():
            full = os.path.join(d, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w") as f:
                f.write(content)
        _git(d, "add", "-A")
        _git(d, "commit", "-q", "-m", "change")
        env = {**os.environ, **(extra_env or {})}
        env.pop("BASE_REF", None)
        env["BASE_SHA"] = base if extra_env is None or "BASE_SHA" not in extra_env else extra_env["BASE_SHA"]
        return subprocess.run([sys.executable, SCRIPT], cwd=d, capture_output=True, text=True, env=env)

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

    def test_scanner_flags_play_services_and_analytics(self):
        bad = """
        +    implementation("com.google.android.gms:play-services-location:21.0.1")
        +    id("com.google.gms.google-services")
        +@import url('https://fonts.googleapis.com/css2?family=Nunito');
        +implementation("com.google.firebase:firebase-crashlytics")
        """
        text = " ".join(check_forbidden_patterns(bad)).lower()
        for needle in ("play-services", "com.google.gms", "googleapis", "crashlytics"):
            self.assertIn(needle, text)

    def test_scanner_passes_normal_android_build_lines(self):
        ok = """
        +    google()
        +    mavenCentral()
        +    implementation("androidx.camera:camera-core:1.3.0")
        +    implementation("androidx.media3:media3-exoplayer:1.2.0")
        """
        self.assertEqual(check_forbidden_patterns(ok), [])

    def test_json_parity_flags_missing_filipino_keys_in_nested_dictionary(self):
        en = json.dumps({"quiz": {"timer": "Time", "submit": "Submit"}, "login": "Login"})
        fil = json.dumps({"quiz": {"timer": "Oras"}, "login": "Mag-login"})
        self.assertEqual(check_json_parity(en, fil), ["quiz.submit"])
        self.assertEqual(check_json_parity(en, en), [])

    def test_script_fails_when_it_cannot_diff_against_the_base(self):
        # The CI bug: a shallow checkout has no origin/staging, and the old script passed on an empty diff.
        r = _scan_repo({"a.kt": "val x = 1\n"}, {"BASE_SHA": "0123456789abcdef0123456789abcdef01234567x"})
        self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
        self.assertIn("COULD NOT RUN", r.stdout)

    def test_script_catches_dependency_in_gradle_kts_and_css_import(self):
        r = _scan_repo({"mobile/app/build.gradle.kts": 'implementation("com.google.firebase:firebase-bom:32.0")\n'})
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        r = _scan_repo({"desktop/src/index.css": "@import url('https://fonts.googleapis.com/css2?family=Nunito');\n"})
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)

    def test_script_ignores_files_that_legitimately_name_forbidden_things(self):
        r = _scan_repo({"tests/forbidden_fixture.json": '{"bad": "firebase"}\n', "docs/notes.xml": "<a>firebase</a>\n"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_gitattributes_cannot_hide_a_violation_from_the_scan(self):
        r = _scan_repo({".gitattributes": "*.kt -diff\n*.kts binary\n",
                        "Bad.kt": "import com.google.firebase.FirebaseApp\n",
                        "app/build.gradle.kts": 'implementation("com.google.android.gms:play-services-maps:18")\n'})
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)

    def test_script_passes_a_clean_change(self):
        r = _scan_repo({"server/backend/src/main.rs": "fn main() {}\n"})
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
