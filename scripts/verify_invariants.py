#!/usr/bin/env python3
"""
L.A.R.A Invariant & Guardrail Scanner
Checks git diffs and repository assets for:
1. Prohibited cloud/external dependencies (Firebase, Prisma, CDNs, Google Fonts).
2. Missing Tagalog string resource translations in Android.
"""

import sys
import re
import os
import subprocess
import xml.etree.ElementTree as ET

FORBIDDEN_PATTERNS = [
    r"firebase",
    r"@prisma/client",
    r"\bprisma\b",
    r"fonts\.googleapis\.com",
    r"cdnjs\.cloudflare\.com",
    r"cdn\.jsdelivr\.net",
    r"unpkg\.com"
]

def check_forbidden_patterns(diff_text: str):
    """
    Scans a git diff text for additions (+) introducing forbidden cloud dependencies.
    Ignores markdown doc files and test files.
    """
    violations = []
    lines = diff_text.splitlines()

    for line in lines:
        stripped = line.strip()
        if not stripped.startswith("+"):
            continue
        # Skip pure comment lines or diff headers
        if stripped.startswith("+++"):
            continue

        for pattern in FORBIDDEN_PATTERNS:
            if re.search(pattern, stripped, re.IGNORECASE):
                violations.append(f"Forbidden pattern '{pattern}' detected in line: {stripped}")


    return violations

def check_string_parity(en_xml: str, tl_xml: str):
    """
    Compares English string keys against Tagalog string keys.
    Returns list of keys present in EN but missing in TL.
    """
    def extract_keys(xml_content):
        keys = set()
        try:
            root = ET.fromstring(xml_content)
            for child in root.findall("string"):
                name = child.get("name")
                if name:
                    keys.add(name)
        except Exception:
            # Fallback regex if malformed XML fragment
            matches = re.findall(r'<string\s+name="([^"]+)"', xml_content)
            keys.update(matches)
        return keys

    en_keys = extract_keys(en_xml)
    tl_keys = extract_keys(tl_xml)
    return sorted(list(en_keys - tl_keys))

def run_git_diff_check():
    """Runs against live git diff for CI"""
    target_branch = os.environ.get("BASE_REF", "staging")
    cmd = ["git", "diff", f"origin/{target_branch}...HEAD", "--", "*.kt", "*.ts", "*.tsx", "*.rs", "*.html", "*.json"]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, check=False)
        diff_output = res.stdout
    except Exception as e:
        print(f"Warning: git diff command failed ({e}), checking unstaged diff instead")
        res = subprocess.run(["git", "diff", "--", "*.kt", "*.ts", "*.tsx", "*.rs", "*.html", "*.json"], capture_output=True, text=True, check=False)
        diff_output = res.stdout

    violations = check_forbidden_patterns(diff_output)
    if violations:
        print("\n❌ L.A.R.A INVARIANT VIOLATION: Prohibited cloud or external dependency detected!")
        for v in violations:
            print(f"  - {v}")
        return 1

    # Check Android strings if files exist
    en_path = "mobile/app/src/main/res/values/strings.xml"
    tl_path = "mobile/app/src/main/res/values-tl/strings.xml"
    if os.path.exists(en_path) and os.path.exists(tl_path):
        with open(en_path, "r", encoding="utf-8") as f:
            en_content = f.read()
        with open(tl_path, "r", encoding="utf-8") as f:
            tl_content = f.read()
        missing = check_string_parity(en_content, tl_content)
        if missing:
            print("\n❌ L.A.R.A LOCALIZATION VIOLATION: Missing Tagalog string translations:")
            for k in missing:
                print(f"  - {k}")
            return 1

    print("✅ All L.A.R.A offline invariants and localization rules PASSED.")
    return 0

if __name__ == "__main__":
    sys.exit(run_git_diff_check())
