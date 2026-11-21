#!/usr/bin/env bash
# Prepares the connected phone for ONE model: installs the APK (if missing), pushes lesson.json and only that model.
#   tools/phone_run.sh ~/lara-slm-models/gemma-3-270m-it-Q8_0.gguf
set -euo pipefail
cd "$(dirname "$0")/.."
MODEL="${1:?usage: tools/phone_run.sh <model.gguf>}"
DIR="${LARA_MODELS:-$HOME/lara-slm-models}"
APK="$DIR/app-debug.apk"
PKG=org.lara.experiment
FILES=/sdcard/Android/data/$PKG/files
[ -f "$MODEL" ] || { echo "no such file: $MODEL"; exit 1; }
[ -f "$DIR/lesson.json" ] || { echo "missing $DIR/lesson.json (run tools/fetch_models.sh)"; exit 1; }
n=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
[ "$n" = 1 ] || { echo "need exactly one phone with USB debugging authorised (found $n). Check 'adb devices'."; exit 1; }

if ! adb shell pm list packages "$PKG" | grep -q "$PKG"; then
  [ -f "$APK" ] || { echo "missing $APK"; exit 1; }
  adb install -r "$APK"
fi
adb shell mkdir -p "$FILES" 2>/dev/null || true
if ! adb shell ls "$FILES" >/dev/null 2>&1; then   # first run: let the app create its folder
  adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 4; adb shell am force-stop $PKG
fi

# keep earlier results out of this model's files
if adb shell ls "$FILES/results.csv" >/dev/null 2>&1; then
  P="pulled/_before-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$P"
  adb pull "$FILES/results.csv" "$P/" >/dev/null; adb pull "$FILES/runs.csv" "$P/" >/dev/null 2>&1 || true
  adb shell rm -f "$FILES/results.csv" "$FILES/runs.csv" "$FILES/run_marker.json"
  echo "old results on the phone saved to $P and cleared"
fi
adb shell "rm -f $FILES/*.gguf"   # one model at a time, so a 32 GB phone never fills up
adb push "$DIR/lesson.json" "$FILES/lesson.json" >/dev/null
echo "pushing $(basename "$MODEL") ($(( $(stat -c %s "$MODEL") / 1048576 )) MB)..."
adb push "$MODEL" "$FILES/$(basename "$MODEL")" | tail -1

echo
echo "=== phone ==="
echo "model:   $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "RAM:     $(adb shell grep MemTotal /proc/meminfo | tr -d '\r' | awk '{printf "%d MB (MemTotal)", $2/1024}')"
B=$(adb shell dumpsys battery | tr -d '\r')
echo "battery: $(echo "$B" | awk '/level:/{print $2}')%   temperature: $(echo "$B" | awk '/temperature:/{printf "%.1f C", $2/10}')"
echo "free storage: $(adb shell df /sdcard | tail -1 | awk '{printf "%d MB", $4/1024}')"
echo
cat <<TXT
=== now, on the phone ===
 1. Plug in the charger, keep the screen on, close other apps. Let the phone cool if it is warm.
 2. Open "LARA SLM Experiment". Tap Refresh, make sure $(basename "$MODEL") is selected, Threads 4, Context 2048, tap "Load model".
 3. Ask the 7 manual questions (README), tapping "Teacher view" to see chunks/prompt/timings.
 4. Tap "Run key" and keep the screen on until it says "Key finished".
 5. Back on the PC:  tools/pull_results.sh $(basename "$MODEL")
If the app closes by itself during the key run, just reopen it, then pull as in step 5 and tell us.
TXT
