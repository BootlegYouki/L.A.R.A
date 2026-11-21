#!/bin/sh
# Linux twin of setup.ps1: ./setup.sh [--tools-only] [--install NAME] [--models-dir DIR] [--backend vulkan|cpu]
cd "$(dirname "$0")" || exit 1
exec python3 setup.py "$@"
