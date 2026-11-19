# Installs the tools (llama.cpp Vulkan build, poppler), the embedder and the Qwen3-4B baseline (about 2.6 GB of models).
# The other candidate models are downloaded one at a time by bench_all.ps1. Safe to run again (resumes, skips finished files).
# Examples:  .\setup.ps1      .\setup.ps1 -ModelsDir D:\models      .\setup.ps1 -ToolsOnly      .\setup.ps1 -Install Qwen3-8B
param(
    [string]$Backend = 'vulkan',    # vulkan | cpu
    [string]$ModelsDir = '',        # put the model files on another drive, e.g. D:\models
    [string]$Install = '',          # also download and keep these models (comma list, see models.json)
    [switch]$ToolsOnly,             # only llama.cpp and poppler, no model files
    [switch]$SkipLlama,
    [switch]$SkipPoppler
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_python.ps1')
$a = @('setup.py', '--backend', $Backend)
if ($ToolsOnly) { $a += '--tools-only' }
if ($Install) { $a += @('--install', $Install) }
if ($ModelsDir) { $a += @('--models-dir', $ModelsDir) }
if ($SkipLlama) { $a += '--skip-llama' }
if ($SkipPoppler) { $a += '--skip-poppler' }
$rc = Invoke-Py $a
exit $rc
