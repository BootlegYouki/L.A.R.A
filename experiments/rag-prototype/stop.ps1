# Stops the web app, the embedder and the LLM that run.ps1 started.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_python.ps1')
$rc = Invoke-Py @('launcher.py', 'stop')
exit $rc
