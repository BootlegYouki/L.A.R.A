#!/bin/sh
# usage: ./bench_all.sh [--only NAMES] [--resume] [--keep] [--install NAME] [--yes] [--pdf2 FILE --key2 FILE]
# set MODELS_DIR / BIG_MODELS_DIR in the environment to choose the drives
cd "$(dirname "$0")" || exit 1
exec python3 gpu_bench.py --all "$@"
