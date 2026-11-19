# Benchmark on this PC. Without -All: only the Qwen3-4B baseline (30 to 45 minutes). With -All: every candidate model, one at a time (calls bench_all.ps1).
param([switch]$All, [string]$ModelsDir = '', [string]$BigModelsDir = '', [string]$Only = '', [switch]$Resume, [switch]$Keep, [string]$Install = '', [switch]$Yes, [string]$Pdf2 = '', [string]$Key2 = '')
$ErrorActionPreference = 'Stop'
if ($All) {
    & (Join-Path $PSScriptRoot 'bench_all.ps1') -ModelsDir $ModelsDir -BigModelsDir $BigModelsDir -Only $Only -Resume:$Resume -Keep:$Keep -Install $Install -Yes:$Yes -Pdf2 $Pdf2 -Key2 $Key2
    exit $LASTEXITCODE
}
. (Join-Path $PSScriptRoot '_python.ps1')
if ($ModelsDir) { $env:MODELS_DIR = $ModelsDir }
$a = @('gpu_bench.py', '--yes')
if ($Pdf2) { $a += @('--pdf2', $Pdf2) }
if ($Key2) { $a += @('--key2', $Key2) }
$rc = Invoke-Py $a
exit $rc
