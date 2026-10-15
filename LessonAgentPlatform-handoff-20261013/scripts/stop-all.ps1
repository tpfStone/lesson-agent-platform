[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'process-identity.ps1')
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$PidFile = Join-Path $Root '.runtime\pids.json'

if (-not (Test-Path -LiteralPath $PidFile)) {
    Write-Host 'No PID file found. Nothing was stopped.'
    exit 0
}

$saved = Get-Content -LiteralPath $PidFile -Raw | ConvertFrom-Json
$properties = @($saved.PSObject.Properties)
[Array]::Reverse($properties)
$unverified = $false
$stopFailed = $false
foreach ($property in $properties) {
    $name = $property.Name
    $entry = $property.Value
    if (-not $entry.pid) { Write-Warning "Legacy PID entry for $name has no identity; no process was stopped."; $unverified = $true; continue }
    $processId = [int]$entry.pid
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($process -and -not (Test-ServiceIdentity $entry $process)) {
        Write-Warning "PID identity changed for $name; no process was stopped."
        $unverified = $true
        continue
    }
    if ($process) {
        Write-Host "Stopping $name PID=$processId"
        try {
            & taskkill.exe /PID $processId /T /F | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "taskkill exited with code $LASTEXITCODE" }
            $process.Refresh()
            if (-not $process.HasExited) { throw 'Process is still running.' }
        } catch {
            Write-Warning "Could not stop $name PID=$processId`: $($_.Exception.Message)"
            $stopFailed = $true
        }
    } else {
        Write-Host "$name PID=$processId is already stopped"
    }
}

if ($unverified) { Write-Warning 'Unverified entries retained for inspection.'; exit 1 }
if ($stopFailed) { Write-Warning 'Stop failed; PID identities retained for retry.'; exit 1 }
Remove-Item -LiteralPath $PidFile -Force
Write-Host 'All recorded services have been stopped.'

