$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temporary = Join-Path $root ('.runtime/startup-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary -Force | Out-Null
. (Join-Path $PSScriptRoot '../process-identity.ps1')
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot '../start-all.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Startup script does not parse.' }
$launch = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Start-LoggedProcess'
}, $true)
. ([scriptblock]::Create($launch.Extent.Text))
$Logs = $temporary
$LaunchId = 'failure-cleanup-test'
$originalIdentity = (Get-Item Function:Get-ServiceIdentity).ScriptBlock
try {
    foreach ($failure in @('identity', 'pid-file', 'cleanup')) {
        $pids = [ordered]@{}
        $script:ownedChild = $null
        $script:saveCalled = $false
        function Get-ServiceIdentity($Process, $LaunchId) {
            $script:ownedChild = $Process
            if ($failure -in @('identity', 'cleanup')) { throw 'Injected identity capture failure' }
            & $originalIdentity $Process $LaunchId
        }
        function Save-Pids { $script:saveCalled = $true; throw 'Injected PID file write failure' }
        if ($failure -eq 'cleanup') {
            function taskkill.exe { throw 'Injected process cleanup failure' }
        }
        $threw = $false
        $message = ''
        try {
            Start-LoggedProcess $failure 'powershell.exe' `
                @('-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Seconds 20') $root
        } catch { $threw = $true; $message = $_.Exception.Message }
        if (-not $threw -or -not $script:ownedChild) { throw 'Expected launch failure was not reached.' }
        $expectedMessage = if ($failure -eq 'pid-file') { 'Injected PID file write failure' } else { 'Injected identity capture failure' }
        if ($message -ne $expectedMessage) { throw "Original startup error was lost: $message" }
        if ($script:saveCalled -ne ($failure -eq 'pid-file')) { throw 'Unexpected PID persistence path.' }
        if ($failure -eq 'cleanup') {
            Remove-Item Function:taskkill.exe
            $script:ownedChild.Kill()
            $script:ownedChild.WaitForExit()
            Write-Output 'PASS cleanup failure preserves original startup error'
        } elseif (-not $script:ownedChild.WaitForExit(3000)) {
            throw "A process survived $failure failure without a durable PID record."
        } else {
            Write-Output "PASS $failure failure stops owned process"
        }
    }
} finally {
    if ($script:ownedChild -and -not $script:ownedChild.HasExited) {
        $script:ownedChild.Kill()
        $script:ownedChild.WaitForExit()
    }
    Set-Item Function:Get-ServiceIdentity $originalIdentity
    $resolved = (Resolve-Path -LiteralPath $temporary).Path
    if (-not $resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the project.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
