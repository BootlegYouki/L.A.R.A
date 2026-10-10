#!/usr/bin/env python3
"""
L.A.R.A Invariant & Guardrail Scanner
Checks git diffs and repository assets for:
1. Prohibited cloud/external dependencies (Firebase, Google Play Services, analytics, CDNs, Google Fonts).
2. Missing Filipino string translations (Android strings.xml, desktop i18n JSON).

It fails closed: if the diff against the base branch cannot be computed, the check fails
instead of passing on an empty diff.
"""

import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

FORBIDDEN_PATTERNS = [
    r"firebase",
    r"@prisma/client",
    r"\bprisma\b",
    r"fonts\.googleapis\.com",
    r"cdnjs\.cloudflare\.com",
    r"cdn\.jsdelivr\.net",
    r"unpkg\.com",
    # AGENTS.md 3.1: no Google Play Services, no remote analytics
    r"play-services",
    r"com\.google\.android\.gms",
    r"com\.google\.gms",
    r"google-services",
    r"googleapis\.com",
    r"gstatic\.com",
    r"googletagmanager|google-analytics|\bgtag\(",
    r"crashlytics",
    r"\bsentry\b",
    r"mixpanel|amplitude|posthog",
]

# Scan every text file, not an allow-list of extensions: an allow-list is a gap (.js, .java, .properties, .yml, .sh...).
SCAN_GLOBS = ["."]
# These legitimately name the forbidden things (rules, tests, this script, CI config, markdown docs).
SCAN_EXCLUDES = [":(exclude)tests", ":(exclude)scripts", ":(exclude).github", ":(exclude)docs", ":(exclude)rules", ":(exclude)*.md"]
# Binary assets only produce noise under --text.
SCAN_EXCLUDES += [f":(exclude)*.{e}" for e in
                  "png jpg jpeg webp gif ico ttf otf woff woff2 jar apk aab so zip gz mp4 webm pdf gguf".split()]

ANDROID_EN = "mobile/app/src/main/res/values/strings.xml"
ANDROID_TL = "mobile/app/src/main/res/values-tl/strings.xml"
DESKTOP_EN = "desktop/src/i18n/en.json"
DESKTOP_FIL = "desktop/src/i18n/fil.json"


def check_forbidden_patterns(diff_text: str):
    """Scans a git diff text for added (+) lines introducing forbidden cloud dependencies."""
    violations = []
    for line in diff_text.splitlines():
        stripped = line.strip()
        if not stripped.startswith("+") or stripped.startswith("+++"):
            continue
        for pattern in FORBIDDEN_PATTERNS:
            if re.search(pattern, stripped, re.IGNORECASE):
                violations.append(f"Forbidden pattern '{pattern}' detected in line: {stripped}")
    return violations


def check_string_parity(en_xml: str, tl_xml: str):
    """Returns Android string keys present in English but missing in Filipino."""
    def extract_keys(xml_content):
        keys = set()
        try:
            root = ET.fromstring(xml_content)
            for child in root.findall("string"):
                name = child.get("name")
                if name:
                    keys.add(name)
        except Exception:
            keys.update(re.findall(r'<string\s+name="([^"]+)"', xml_content))
        return keys

    return sorted(extract_keys(en_xml) - extract_keys(tl_xml))


def _flatten_keys(obj, prefix=""):
    if isinstance(obj, dict):
        keys = set()
        for k, v in obj.items():
            keys |= _flatten_keys(v, f"{prefix}{k}.")
        return keys
    return {prefix.rstrip(".")}


def check_json_parity(en_json: str, fil_json: str):
    """Returns desktop dictionary keys (dotted paths) present in English but missing in Filipino."""
    return sorted(_flatten_keys(json.loads(en_json)) - _flatten_keys(json.loads(fil_json)))


def base_spec() -> str:
    """The commit or branch to diff against. A push has no base branch, so CI passes the previous SHA."""
    sha = os.environ.get("BASE_SHA", "")
    if sha and set(sha) != {"0"}:
        return sha
    return f"origin/{os.environ.get('BASE_REF', 'staging')}"


def run_git_diff_check():
    """Runs against the live git diff for CI. Returns 0 pass, 1 violation, 2 could not check."""
    spec = base_spec()
    # --text: a .gitattributes line such as "*.kt -diff" would otherwise turn the diff into "Binary files differ"
    # with no added lines, and the scan would pass. No textconv or external diff driver may rewrite what we read.
    cmd = ["git", "diff", "--text", "--no-textconv", "--no-ext-diff", f"{spec}...HEAD", "--", *SCAN_GLOBS, *SCAN_EXCLUDES]
    res = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if res.returncode != 0:
        print(f"\n❌ L.A.R.A GUARDRAIL COULD NOT RUN: git diff against '{spec}' failed.")
        print(f"   {res.stderr.strip()}")
        print("   Fetch the base branch (git fetch origin staging) and run again. A check that cannot look must not pass.")
        return 2

    violations = check_forbidden_patterns(res.stdout)
    if violations:
        print("\n❌ L.A.R.A INVARIANT VIOLATION: Prohibited cloud or external dependency detected!")
        for v in violations:
            print(f"  - {v}")
        return 1

    pairs = [
        (ANDROID_EN, ANDROID_TL, check_string_parity, "Android Filipino strings"),
        (DESKTOP_EN, DESKTOP_FIL, check_json_parity, "desktop Filipino strings"),
    ]
    for en_path, tl_path, check, label in pairs:
        if os.path.exists(en_path) != os.path.exists(tl_path):
            print(f"\n❌ L.A.R.A LOCALIZATION VIOLATION: {label}: only one of {en_path} / {tl_path} exists.")
            return 1
        if os.path.exists(en_path):
            with open(en_path, "r", encoding="utf-8") as f:
                en_content = f.read()
            with open(tl_path, "r", encoding="utf-8") as f:
                tl_content = f.read()
            missing = check(en_content, tl_content)
            if missing:
                print(f"\n❌ L.A.R.A LOCALIZATION VIOLATION: {label} missing:")
                for k in missing:
                    print(f"  - {k}")
                return 1

    print("✅ All L.A.R.A offline invariants and localization rules PASSED.")
    return 0


if __name__ == "__main__":
    sys.exit(run_git_diff_check())
