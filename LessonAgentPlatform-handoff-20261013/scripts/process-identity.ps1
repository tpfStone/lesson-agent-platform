function Get-ServiceExecutable([System.Diagnostics.Process]$Process) {
    # MainModule can be empty or still point to ntdll.dll during process startup.
    # All platform launchers (Python, Java, cmd) are Windows .exe programs.
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $Process.Refresh()
        if ($Process.HasExited) { return $null }
        $executable = $Process.Path
        if (-not [string]::IsNullOrWhiteSpace($executable) -and
            [IO.Path]::GetExtension($executable) -ieq '.exe') { return $executable }
        Start-Sleep -Milliseconds 50
    }
    return $null
}

function Get-ServiceIdentity([System.Diagnostics.Process]$Process, [string]$LaunchId) {
    $executable = Get-ServiceExecutable $Process
    if ([string]::IsNullOrWhiteSpace($executable)) {
        throw "Cannot record executable identity for PID=$($Process.Id)."
    }
    [pscustomobject]@{ pid = $Process.Id; startTicks = $Process.StartTime.ToUniversalTime().Ticks.ToString();
        executable = $executable; launchId = $LaunchId }
}
function Test-ServiceIdentity($Saved, [System.Diagnostics.Process]$Process) {
    if (-not $Saved -or -not $Saved.startTicks -or -not $Saved.executable -or -not $Saved.launchId -or -not $Process) { return $false }
    try {
        return ([int]$Saved.pid -eq $Process.Id -and
            $Saved.startTicks -eq $Process.StartTime.ToUniversalTime().Ticks.ToString() -and
            [string]::Equals($Saved.executable, (Get-ServiceExecutable $Process), [StringComparison]::OrdinalIgnoreCase))
    } catch { return $false }
}
