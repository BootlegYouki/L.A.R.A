# Dot-sourced by the other scripts: finds Python 3.10+ and defines Invoke-Py. PowerShell 5.1 compatible.
$env:PYTHONUNBUFFERED = '1'
$env:PYTHONUTF8 = '1'

function Find-Python {
    $candidates = @(@('py', '-3'), @('python'), @('python3'))
    foreach ($c in $candidates) {
        $exe = $c[0]
        if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { continue }
        $extra = @()
        if ($c.Count -gt 1) { $extra = @($c[1..($c.Count - 1)]) }
        $v = $null
        try { $v = & $exe @extra -c "import sys; print('%d.%d' % sys.version_info[:2])" 2>$null } catch { $v = $null }
        if ($LASTEXITCODE -eq 0 -and $v) {
            $p = ([string]$v).Trim().Split('.')
            if ([int]$p[0] -ge 3 -and [int]$p[1] -ge 10) { return ,(@($exe) + $extra) }
        }
    }
    return $null
}

$script:Py = Find-Python
if (-not $script:Py) {
    Write-Host ''
    Write-Host 'Python 3.10 or newer was not found.' -ForegroundColor Red
    Write-Host 'Install it from https://www.python.org/downloads/windows/ and tick "Add python.exe to PATH" in the installer,'
    Write-Host 'then close this window, open a new PowerShell and run the script again.'
    exit 1
}

function Invoke-Py {
    param([string[]]$PyArgs)
    $exe = $script:Py[0]
    $extra = @()
    if ($script:Py.Count -gt 1) { $extra = @($script:Py[1..($script:Py.Count - 1)]) }
    # Out-Host keeps Python's output on the screen instead of in the function's return value
    & $exe @extra @PyArgs | Out-Host
    return $LASTEXITCODE
}
Set-Location -LiteralPath $PSScriptRoot
