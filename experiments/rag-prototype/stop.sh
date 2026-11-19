#!/bin/sh
# Linux twin of stop.ps1
cd "$(dirname "$0")" || exit 1
exec python3 launcher.py stop
