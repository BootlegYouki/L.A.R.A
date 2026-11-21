# Starts the embedder, the LLM and the web app. Example:  .\run.ps1      .\run.ps1 -Model Qwen3-1.7B-Q4_K_M.gguf
param([string]$Model = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_python.ps1')
$a = @('launcher.py', 'start')
if ($Model) { $a += @('--model', $Model) }
$rc = Invoke-Py $a
exit $rc
