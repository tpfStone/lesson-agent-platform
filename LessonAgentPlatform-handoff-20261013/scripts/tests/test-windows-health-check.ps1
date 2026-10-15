$ErrorActionPreference = 'Stop'
$scriptPath = (Resolve-Path (Join-Path $PSScriptRoot '../health-check.ps1')).Path
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temporary = Join-Path $root ('.runtime/health-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary -Force | Out-Null
$harness = Join-Path $temporary 'harness.ps1'
@'
function Invoke-WebRequest {
    param($Uri, [switch]$UseBasicParsing, $TimeoutSec)
    $content = '{"status":"UP"}'
    if ($env:LESSON_HEALTH_CASE -eq 'spring-bytes' -and $Uri -like '*/actuator/health') {
        # Windows PowerShell returns byte[] for Spring's vendor JSON media type.
        $content = [System.Text.Encoding]::UTF8.GetBytes('{"groups":["liveness","readiness"],"status":"UP"}')
    }
    if ($env:LESSON_HEALTH_CASE -eq 'unready-bytes' -and $Uri -like '*:8003/*') {
        $content = [System.Text.Encoding]::UTF8.GetBytes('{"status":"up","model_configured":false}')
    }
    if ($env:LESSON_HEALTH_CASE -eq 'invalid-bytes' -and $Uri -like '*/actuator/health') {
        $content = [System.Text.Encoding]::UTF8.GetBytes('not-json')
    }
    [pscustomobject]@{ StatusCode = 200; Content = $content }
}
& $env:LESSON_HEALTH_SCRIPT
exit $LASTEXITCODE
'@ | Set-Content -LiteralPath $harness -Encoding UTF8
$previousCase = $env:LESSON_HEALTH_CASE
$previousScript = $env:LESSON_HEALTH_SCRIPT
try {
    $env:LESSON_HEALTH_SCRIPT = $scriptPath
    foreach ($case in @(
        @{ Name = 'text-json'; Expected = 0 },
        @{ Name = 'spring-bytes'; Expected = 0 },
        @{ Name = 'unready-bytes'; Expected = 1 },
        @{ Name = 'invalid-bytes'; Expected = 1 }
    )) {
        $env:LESSON_HEALTH_CASE = $case.Name
        $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $harness 2>&1
        if ($LASTEXITCODE -ne $case.Expected) {
            throw "$($case.Name): expected exit $($case.Expected), got $LASTEXITCODE`n$output"
        }
        Write-Output "PASS $($case.Name)"
    }
} finally {
    $env:LESSON_HEALTH_CASE = $previousCase
    $env:LESSON_HEALTH_SCRIPT = $previousScript
    $resolved = (Resolve-Path -LiteralPath $temporary).Path
    if (-not $resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the project.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
