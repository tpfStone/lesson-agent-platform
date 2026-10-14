[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$PidFile = Join-Path $Root '.runtime\pids.json'

if (-not (Test-Path -LiteralPath $PidFile)) {
    Write-Host 'No PID file found. Nothing was stopped.'
    exit 0
}

$saved = Get-Content -LiteralPath $PidFile -Raw | ConvertFrom-Json
$properties = @($saved.PSObject.Properties)
[Array]::Reverse($properties)
foreach ($property in $properties) {
    $name = $property.Name
    $processId = [int]$property.Value
    if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
        Write-Host "Stopping $name PID=$processId"
        & taskkill.exe /PID $processId /T /F | Out-Null
    } else {
        Write-Host "$name PID=$processId is already stopped"
    }
}

Remove-Item -LiteralPath $PidFile -Force
Write-Host 'All recorded services have been stopped.'

