#!/usr/bin/env bash
# Copies results.csv and runs.csv from the phone into ./pulled/ (USB debugging on, one device).
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=/sdcard/Android/data/org.lara.experiment/files
mkdir -p pulled
for f in results.csv runs.csv; do adb pull "$SRC/$f" "pulled/$f" || echo "no $f yet"; done
echo "now: python3 tools/summarize.py pulled/results.csv pulled/runs.csv"
