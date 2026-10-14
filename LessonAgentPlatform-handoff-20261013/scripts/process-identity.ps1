function Get-ServiceIdentity([System.Diagnostics.Process]$Process, [string]$LaunchId) {
    [pscustomobject]@{ pid = $Process.Id; startTicks = $Process.StartTime.ToUniversalTime().Ticks.ToString();
        executable = $Process.Path; launchId = $LaunchId }
}
function Test-ServiceIdentity($Saved, [System.Diagnostics.Process]$Process) {
    if (-not $Saved -or -not $Saved.startTicks -or -not $Saved.executable -or -not $Saved.launchId -or -not $Process) { return $false }
    try {
        return ([int]$Saved.pid -eq $Process.Id -and
            $Saved.startTicks -eq $Process.StartTime.ToUniversalTime().Ticks.ToString() -and
            [string]::Equals($Saved.executable, $Process.Path, [StringComparison]::OrdinalIgnoreCase))
    } catch { return $false }
}
