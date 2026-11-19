#!/bin/sh
# usage: ./gpu_bench.sh   (baseline only; for every model use ./bench_all.sh)
cd "$(dirname "$0")" || exit 1
exec python3 gpu_bench.py --yes "$@"
