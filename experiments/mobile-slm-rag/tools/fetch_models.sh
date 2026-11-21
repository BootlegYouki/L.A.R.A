#!/usr/bin/env bash
# Downloads the test models, the Wells PDF and lesson.json into ~/lara-slm-models/ (outside the repo, never committed).
# Resumable (curl -C -), size-checked against tools/models.txt, checks free disk first.
#   tools/fetch_models.sh            # everything
#   tools/fetch_models.sh 1 2 3 4    # only these test-order numbers (plus the lesson)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${LARA_MODELS:-$HOME/lara-slm-models}"
mkdir -p "$OUT"
WANT=" ${*:-1 2 3 4 5 6} "
need=0
while IFS='|' read -r n f b u; do
  [[ "$n" =~ ^[0-9]+$ && "$WANT" == *" $n "* ]] || continue
  have=$(stat -c %s "$OUT/$f" 2>/dev/null || echo 0); (( have < b )) && need=$(( need + b - have ))
done < tools/models.txt
free=$(df --output=avail -B1 "$OUT" | tail -1)
need=$(( need + 400000000 )) # Wells PDF (13 MB) + slack
(( free > need * 11 / 10 )) || { echo "not enough disk: need ~$((need/1048576)) MB, free $((free/1048576)) MB"; exit 1; }

while IFS='|' read -r n f b u; do
  [[ "$n" =~ ^[0-9]+$ && "$WANT" == *" $n "* ]] || continue
  [ "$(stat -c %s "$OUT/$f" 2>/dev/null || echo 0)" = "$b" ] || curl -fsSL -C - --retry 5 -o "$OUT/$f" "$u"
  got=$(stat -c %s "$OUT/$f")
  [ "$got" = "$b" ] && echo "ok   $f ($got bytes)" || { echo "BAD SIZE $f: $got, expected $b (delete it and rerun)"; exit 1; }
done < tools/models.txt

# Wells biology PDF (public domain, same source as the desktop experiment) -> lesson.json via the desktop ingest from PR #337
[ -s "$OUT/wells.pdf" ] || curl -fsSL -C - --retry 5 -o "$OUT/wells.pdf" https://archive.org/download/textbookofbiolog02well/textbookofbiolog02well.pdf
if [ ! -s "$OUT/lesson.json" ]; then
  DESK="${DESKTOP_EXPERIMENT:-}"
  if [ -z "$DESK" ] && [ -f ../rag-prototype/rag.py ]; then DESK="$(cd ../rag-prototype && pwd)"; fi
  if [ -z "$DESK" ]; then
    git fetch -q origin pull/337/head:pr337 2>/dev/null || true
    T=$(mktemp -d); git -C "$(git rev-parse --show-toplevel)" archive pr337 experiments/rag-prototype | tar -x -C "$T"; DESK="$T/experiments/rag-prototype"
  fi
  (cd "$DESK" && python3 "$OLDPWD/tools/export_lesson.py" "$OUT/wells.pdf" "$OUT/lesson.json" --desktop "$DESK" --title "Text-book of Biology, Vol. II (Wells and Howes)")
fi
echo "lesson.json: $(stat -c %s "$OUT/lesson.json") bytes. Models are in $OUT"
