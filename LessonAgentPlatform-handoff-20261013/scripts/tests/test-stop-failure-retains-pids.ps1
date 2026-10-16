$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temporary = Join-Path $root ('.runtime/stop-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $temporary 'scripts'), (Join-Path $temporary '.runtime') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../stop-all.ps1') -Destination (Join-Path $temporary 'scripts/stop-all.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../process-identity.ps1') -Destination (Join-Path $temporary 'scripts/process-identity.ps1')
. (Join-Path $PSScriptRoot '../process-identity.ps1')
$ownedChild = Start-Process powershell.exe -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 60') -WindowStyle Hidden -PassThru
try {
    $identity = Get-ServiceIdentity $ownedChild 'stop-failure-test'
    foreach ($failure in @('nonzero', 'still-alive')) {
        $pidFile = Join-Path $temporary '.runtime/pids.json'
        @{ test = $identity } | ConvertTo-Json | Set-Content -LiteralPath $pidFile -Encoding UTF8
        $code = if ($failure -eq 'nonzero') { 5 } else { 0 }
        $runner = Join-Path $temporary 'run.ps1'
        @"
function taskkill.exe {
    Set-Content -LiteralPath (Join-Path `$PSScriptRoot 'taskkill-called.txt') -Value 'called'
    `$global:LASTEXITCODE = $code
}
& '`$PSScriptRoot/scripts/stop-all.ps1'
exit `$LASTEXITCODE
"@.Replace('''$PSScriptRoot/scripts/stop-all.ps1''', "(Join-Path `$PSScriptRoot 'scripts/stop-all.ps1')") | Set-Content -LiteralPath $runner -Encoding UTF8
        $called = Join-Path $temporary 'taskkill-called.txt'
        if (Test-Path -LiteralPath $called) { Remove-Item -LiteralPath $called }
        $output = @(& powershell.exe -NoProfile -File $runner)
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 1) { throw "Expected stop failure exit 1 after $failure; got $exitCode." }
        if (-not (Test-Path -LiteralPath $called)) { throw 'Expected taskkill branch was not reached.' }
        $expectedReason = if ($failure -eq 'nonzero') { 'taskkill exited with code 5' } else { 'Process is still running.' }
        if (($output -join "`n") -notmatch [regex]::Escape($expectedReason)) { throw "Wrong failure reason: $($output -join ' ')" }
        if (-not (Test-Path -LiteralPath $pidFile)) { throw "PID record was removed after $failure failure." }
        $ownedChild.Refresh()
        if ($ownedChild.HasExited) { throw 'Mocked stop unexpectedly killed the test process.' }
        Write-Output "PASS $failure stop retains PID identity and reports failure"
    }
} finally {
    if (-not $ownedChild.HasExited) { $ownedChild.Kill(); $ownedChild.WaitForExit() }
    $resolved = (Resolve-Path -LiteralPath $temporary).Path
    if (-not $resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside project.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
