# N learners chatting at once. The app must be running (run.ps1). Example:  .\concurrency_test.ps1 -Users 1,2,3,5,8
param([string]$Users = '1,2,3,5,8', [int]$Requests = 6, [string]$Key = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '_python.ps1')
$a = @('concurrency_test.py', '--users', $Users, '--requests', "$Requests")
if ($Key) { $a += @('--key', $Key) }
$rc = Invoke-Py $a
exit $rc
