[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'help')]
    [string]$Command = 'start'
)

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$uiDirectory = Join-Path $repoRoot 'server/ui'
$tauriDirectory = Join-Path $repoRoot 'server/src-tauri'

if ($Command -eq 'help') {
    Write-Output 'Run .\lara.ps1 start from PowerShell in the L.A.R.A repository to build and start the Hub GUI.'
    exit 0
}

foreach ($tool in @('npm.cmd', 'cargo')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Could not find $tool. Install Node.js and Rust, then reopen PowerShell."
    }
}

$busyListeners = @(Get-NetTCPConnection -State Listen -LocalPort 8080, 8081 -ErrorAction SilentlyContinue)
if ($busyListeners.Count -gt 0) {
    $ports = ($busyListeners | Select-Object -ExpandProperty LocalPort -Unique | Sort-Object) -join ', '
    throw "A process is already listening on Hub port(s) $ports. Close the running Hub, then retry."
}

Write-Output 'Building the Hub window...'
Push-Location $uiDirectory
try {
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw "UI build failed (exit code $LASTEXITCODE)." }
} finally {
    Pop-Location
}

$oldDataDirectory = $env:LARA_DATA_DIR
$oldTargetDirectory = $env:CARGO_TARGET_DIR
$oldDebugSymbols = $env:CARGO_PROFILE_DEV_DEBUG
$oldIncremental = $env:CARGO_INCREMENTAL
try {
    if ([string]::IsNullOrWhiteSpace($env:LARA_DATA_DIR)) {
        $env:LARA_DATA_DIR = Join-Path $env:APPDATA 'org.lara.hub'
    }
    $env:CARGO_TARGET_DIR = Join-Path $tauriDirectory 'target'
    $env:CARGO_PROFILE_DEV_DEBUG = '0'
    $env:CARGO_INCREMENTAL = '0'

    Write-Output "Starting the Hub with data in $env:LARA_DATA_DIR. Press Ctrl+C to stop it."
    Push-Location $tauriDirectory
    try {
        & cargo run -j 1 --features tauri/custom-protocol
        if ($LASTEXITCODE -ne 0) { throw "Hub exited with code $LASTEXITCODE." }
    } finally {
        Pop-Location
    }
} finally {
    $env:LARA_DATA_DIR = $oldDataDirectory
    $env:CARGO_TARGET_DIR = $oldTargetDirectory
    $env:CARGO_PROFILE_DEV_DEBUG = $oldDebugSymbols
    $env:CARGO_INCREMENTAL = $oldIncremental
}
