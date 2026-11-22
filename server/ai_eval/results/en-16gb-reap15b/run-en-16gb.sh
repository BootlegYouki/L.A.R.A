#!/usr/bin/env bash
# #73, Lead 2026-11-01: the English test set at 1, 2, 4 and 6 learners at once on a 16 GB machine,
# candidate Qwen3-30B-A3B-Instruct-2507 pruned to REAP-15B-A3B, Q4_K_M. Run it in Git Bash.
# Same settings as stage 2 (CPU only, 4 threads = the 4-core school PC), except the prompt cache is
# off (LLAMA_ARG_CACHE_RAM=0): with a 9.75 GB model on 15.2 GB of RAM, llama-server's default 8 GB
# cache could push the laptop into swap. TTFT is then a pessimistic number (stage 2 measured ~2x).
set -eo pipefail
REPO="/c/Users/Jv Bialen/Documents/GitHub/L.A.R.A"
MODEL="C:/models/reap-15b/Qwen3-30B-A3B-Instruct-2507-REAP-15B-A3B-Q4_K_M.gguf"
OUT="server/ai_eval/results/en-16gb-reap15b"
cd "$REPO"

branch=$(git branch --show-current)
if [ "$branch" != "test/73-server-1.5-ai-eval-stage-2" ]; then
  echo "Switching from $branch to the stage-2 branch (the runner and prompts live there)."
  git switch test/73-server-1.5-ai-eval-stage-2
fi
[ -f "$MODEL" ] || { echo "Model file missing: $MODEL"; exit 1; }
avail_mb=$(powershell -NoProfile -Command "[int](Get-Counter '\Memory\Available MBytes').CounterSamples[0].CookedValue")
echo "Available RAM now: $((avail_mb / 1024)).$(( (avail_mb % 1024) * 10 / 1024 )) GB. The model needs about 11.5 GB at 6 learners."
if [ "$avail_mb" -lt 10500 ]; then
  echo "That is not enough yet: stop Oracle and close Brave, Android Studio and Telegram (keep VS Code: Claude runs in it), then run this again."
  read -r -p "Or type yes to start anyway (the 6-learner run may swap and look slower than it is): " answer
  [ "$answer" = "yes" ] || exit 1
fi
echo "Starting. This takes a while; leave the laptop plugged in and awake."

LLAMA_ARG_CACHE_RAM=0 PYTHONUTF8=1 py server/ai_eval/run_eval.py \
  --prompts "$OUT/prompts_en.json" run \
  --llama-server "C:/llama/b11938/llama-server.exe" \
  --model "Qwen3-REAP-15B-A3B=$MODEL" \
  --slots 1 2 4 6 --threads 4 \
  --out "$OUT" 2>&1 | tee -a "$OUT/run-console.log"
echo "DONE. Tell Claude: run finished."
