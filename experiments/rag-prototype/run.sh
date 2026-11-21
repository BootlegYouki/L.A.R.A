#!/bin/sh
# Linux twin of run.ps1
cd "$(dirname "$0")" || exit 1
exec python3 launcher.py start "$@"
