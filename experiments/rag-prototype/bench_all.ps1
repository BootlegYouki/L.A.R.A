# Compares ALL candidate models, ONE AT A TIME: download -> benchmark -> delete -> next. Peak disk need = the largest single model.
# Results: results\MODEL_COMPARISON.md (updated after every model) and results\<computer>-<date>.md. Stop with Ctrl+C and continue later with -Resume.
# Examples:
#   .\bench_all.ps1 -ModelsDir D:\models -BigModelsDir E:\models -Yes
#   .\bench_all.ps1 -Only "Qwen3-8B,Gemma" -Resume
#   .\bench_all.ps1 -Install Qwen3-8B        (at the end, download and keep the winner)
param(
    [string]$ModelsDir = '',        # where the models are downloaded (default: models\llm next to the scripts)
    [string]$BigModelsDir = '',     # where the two optional 30B MoE files go (can be an HDD)
    [string]$Only = '',             # comma list of model names (see models.json)
    [switch]$Resume,                # skip models already finished in results\state.json
    [switch]$Keep,                  # do not delete the downloaded files
    [string]$Install = '',          # download and keep this model at the end
    [switch]$Yes,                   # do not ask before starting
    [string]$Pdf2 = '', [string]$Key2 = '',   # second PDF + its answer key (see AGENT_BRIEF.md)
    [switch]$All                    # accepted for gpu_bench.ps1 -All; this script always runs every model unless -Only is given
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_python.ps1')
if ($ModelsDir) { $env:MODELS_DIR = $ModelsDir }
if ($BigModelsDir) { $env:BIG_MODELS_DIR = $BigModelsDir }
$a = @('gpu_bench.py', '--all')
if ($Only) { $a += @('--only', $Only) }
if ($Resume) { $a += '--resume' }
if ($Keep) { $a += '--keep' }
if ($Install) { $a += @('--install', $Install) }
if ($Yes) { $a += '--yes' }
if ($Pdf2) { $a += @('--pdf2', $Pdf2) }
if ($Key2) { $a += @('--key2', $Key2) }
$rc = Invoke-Py $a
exit $rc
