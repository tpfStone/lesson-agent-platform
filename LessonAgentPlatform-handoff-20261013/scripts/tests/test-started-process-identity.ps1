param([int]$Iterations = 10)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../process-identity.ps1')
for ($index = 0; $index -lt $Iterations; $index++) {
    $child = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 20') `
        -WindowStyle Hidden -PassThru
    try {
        $record = Get-ServiceIdentity $child ('startup-test-' + $index)
        if ([string]::IsNullOrWhiteSpace($record.executable)) {
            throw 'A just-started process must never be recorded with an empty executable.'
        }
        $fresh = Get-Process -Id $child.Id -ErrorAction Stop
        if (-not (Test-ServiceIdentity $record $fresh)) {
            $actualTicks = $fresh.StartTime.ToUniversalTime().Ticks.ToString()
            $actualPath = Get-ServiceExecutable $fresh
            throw "Fresh lookup mismatch: saved=$($record | ConvertTo-Json -Compress); actualTicks=$actualTicks; actualPath=$actualPath"
        }
    } finally {
        # The original Process handle identifies only the child created above.
        if (-not $child.HasExited) { $child.Kill(); $child.WaitForExit() }
        $child.Dispose()
    }
}
Write-Output "$Iterations immediate-start identity checks passed; owned test processes stopped."
