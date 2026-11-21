#!/usr/bin/env bash
# Clones llama.cpp b11938 (same build as the desktop experiment) into third_party/. Not committed.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d third_party/llama.cpp ] && { echo "third_party/llama.cpp already there"; exit 0; }
mkdir -p third_party
git clone --depth 1 --branch b11938 https://github.com/ggml-org/llama.cpp third_party/llama.cpp
