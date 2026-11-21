#!/usr/bin/env bash
# Saves the phone's results per model: pulled/<model>/results.csv + runs.csv, so runs never overwrite each other.
#   tools/pull_results.sh gemma-3-270m-it-Q8_0.gguf
set -euo pipefail
cd "$(dirname "$0")/.."
M="${1:?usage: tools/pull_results.sh <model file name>}"
D="pulled/${M%.gguf}"; SRC=/sdcard/Android/data/org.lara.experiment/files
mkdir -p "$D"
for f in results.csv runs.csv; do adb pull "$SRC/$f" "$D/$f" || echo "no $f on the phone yet"; done
B=$(adb shell dumpsys battery | tr -d '\r'); echo "battery now: $(echo "$B" | awk '/level:/{print $2}')%, $(echo "$B" | awk '/temperature:/{printf "%.1f C", $2/10}')" | tee "$D/battery-after.txt"
echo "saved in $D. Table of everything pulled so far:  python3 tools/summarize.py pulled"
