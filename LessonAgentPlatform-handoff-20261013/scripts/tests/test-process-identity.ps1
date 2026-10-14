$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\process-identity.ps1')
$process = Get-Process -Id $PID
$record = Get-ServiceIdentity $process 'test-launch'
if (-not (Test-ServiceIdentity $record $process)) { throw 'Matching process must be recognized' }
$record.startTicks = '0'
if (Test-ServiceIdentity $record $process) { throw 'Reused PID must be rejected' }
if (Test-ServiceIdentity $PID $process) { throw 'Legacy PID without identity must be rejected' }
Write-Output '3 process identity checks passed; no process stopped.'
