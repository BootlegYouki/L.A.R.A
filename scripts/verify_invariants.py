#!/usr/bin/env python3
"""
L.A.R.A Invariant & Guardrail Scanner
Checks git diffs for: Prohibited cloud/external dependencies (Firebase, Google Play Services, analytics, CDNs, Google Fonts).

It fails closed: if the diff against the base branch cannot be computed, the check fails
instead of passing on an empty diff.
"""

import os
import re
import subprocess
import sys

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
    r"mixpanel|com\.amplitude|@amplitude/|amplitude-js|posthog",
]

# Scan every text file, not an allow-list of extensions: an allow-list is a gap (.js, .java, .properties, .yml, .sh...).
SCAN_GLOBS = ["."]
# These legitimately name the forbidden things (rules, tests, this script, CI config, markdown docs).
SCAN_EXCLUDES = [":(exclude)tests", ":(exclude)scripts", ":(exclude).github", ":(exclude)docs", ":(exclude)rules", ":(exclude)*.md"]
# Binary assets only produce noise under --text.
SCAN_EXCLUDES += [f":(exclude)*.{e}" for e in
                  "png jpg jpeg webp gif ico ttf otf woff woff2 jar apk aab so zip gz mp4 webm pdf gguf".split()]

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

    print("✅ All L.A.R.A offline invariants PASSED.")
    return 0


if __name__ == "__main__":
    sys.exit(run_git_diff_check())
