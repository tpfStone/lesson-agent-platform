param(
    [ValidateSet('Check', 'Start', 'Status', 'Stop')]
    [string]$Action = 'Check',
    [string]$NginxHome,
    [string]$FrontendDist,
    [string]$RuntimeDir,
    [ValidateRange(1024, 65535)]
    [int]$ListenPort = 18080,
    [ValidateRange(1, 65535)]
    [int]$BackendPort = 8080,
    [ValidateRange(1, 3600)]
    [int]$ProxyReadTimeoutSeconds = 360
)

$ErrorActionPreference = 'Stop'
$RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepositoryRoot 'scripts\process-identity.ps1')
if (-not $RuntimeDir) { $RuntimeDir = Join-Path $RepositoryRoot '.runtime\windows-entry' }
$RuntimeDir = [IO.Path]::GetFullPath($RuntimeDir)
$StatePath = Join-Path $RuntimeDir 'entry.json'
$NginxPidPath = Join-Path $RuntimeDir 'logs\lesson-agent.pid'

function Test-LocalPortAvailable([int]$Port) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $listener.Start(); return $true }
    catch { return $false }
    finally { $listener.Stop() }
}

function ConvertTo-NginxPath([string]$Path) {
    if ($Path -match '["\r\n]') { throw 'Configuration paths cannot contain quotes or line breaks.' }
    return $Path.Replace('\', '/')
}

function Read-EntryState {
    if (-not (Test-Path -LiteralPath $StatePath)) { return $null }
    $saved = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
    if ($saved.schema -ne 'lesson-agent-windows-entry-v1' -or
        -not [string]::Equals($saved.runtimeDir, $RuntimeDir, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Unrecognized entry identity; retained state without stopping any process.'
    }
    return $saved
}

function Test-NginxPid($Saved) {
    if (-not (Test-Path -LiteralPath $NginxPidPath)) { return $false }
    $recorded = (Get-Content -LiteralPath $NginxPidPath -Raw).Trim()
    return ($recorded -match '^\d+$' -and [int]$recorded -eq [int]$Saved.identity.pid)
}

function Remove-StoppedEntryState($Saved) {
    if (Test-Path -LiteralPath $NginxPidPath) {
        if (-not (Test-NginxPid $Saved)) { throw 'PID file changed; retained files for inspection.' }
        Remove-Item -LiteralPath $NginxPidPath
    }
    Remove-Item -LiteralPath $StatePath
}

$Saved = Read-EntryState
if ($Action -eq 'Status' -or $Action -eq 'Stop') {
    if (-not $Saved) {
        if (Test-Path -LiteralPath $NginxPidPath) {
            throw 'A PID file exists without verified entry metadata; no process was stopped.'
        }
        [pscustomobject]@{ status = 'STOPPED'; runtimeDir = $RuntimeDir; recordedProcess = $false } | ConvertTo-Json
        exit 0
    }
    $process = Get-Process -Id ([int]$Saved.identity.pid) -ErrorAction SilentlyContinue
    if ($process -and -not (Test-ServiceIdentity $Saved.identity $process)) {
        throw 'Process identity mismatch; retained metadata and refused to stop the process.'
    }
    if ($Action -eq 'Status') {
        if ($process -and -not (Test-NginxPid $Saved)) { throw 'Live process PID file does not match recorded identity.' }
        [pscustomobject]@{ status = $(if ($process) { 'RUNNING' } else { 'STOPPED_WITH_RECORD' });
            pid = $Saved.identity.pid; entryUrl = $Saved.entryUrl; runtimeDir = $RuntimeDir } | ConvertTo-Json
        exit 0
    }
    if ($process) {
        if (-not (Test-NginxPid $Saved)) { throw 'Refused to signal Nginx because its PID file does not match.' }
        & $Saved.identity.executable '-p' ((ConvertTo-NginxPath $RuntimeDir) + '/') '-c' 'conf/lesson-agent.conf' '-s' 'quit'
        if ($LASTEXITCODE -ne 0) { throw 'Nginx quit failed; retained entry identity.' }
        if (-not $process.WaitForExit(20000)) { throw 'Nginx did not stop within 20 seconds; retained entry identity.' }
    }
    if (-not (Test-LocalPortAvailable ([int]$Saved.listenPort))) {
        throw 'The entry port is still occupied; retained metadata without killing another process.'
    }
    Remove-StoppedEntryState $Saved
    [pscustomobject]@{ status = 'STOPPED'; entryUrl = $Saved.entryUrl; runtimeDir = $RuntimeDir } | ConvertTo-Json
    exit 0
}

if (-not $NginxHome) { throw '-NginxHome is required for Check and Start.' }
$NginxHome = (Resolve-Path -LiteralPath $NginxHome).Path
$NginxExe = Join-Path $NginxHome 'nginx.exe'
if (-not (Test-Path -LiteralPath $NginxExe -PathType Leaf) -or
    -not (Test-Path -LiteralPath (Join-Path $NginxHome 'conf\mime.types') -PathType Leaf)) {
    throw 'NginxHome must be an extracted Windows Nginx distribution.'
}
if (-not $FrontendDist) { $FrontendDist = Join-Path $RepositoryRoot 'Lessongen-main\web\frontend\dist' }
$FrontendDist = (Resolve-Path -LiteralPath $FrontendDist).Path
if (-not (Test-Path -LiteralPath (Join-Path $FrontendDist 'index.html') -PathType Leaf)) {
    throw 'FrontendDist needs a built index.html; run npm run build in the frontend first.'
}
if ($ListenPort -eq $BackendPort) { throw 'The entry port must differ from the backend port.' }
$TemplatePath = Join-Path $PSScriptRoot 'lesson-agent.windows.conf.example'
$Config = [IO.File]::ReadAllText($TemplatePath)
$Config = $Config.Replace('__NGINX_HOME__', (ConvertTo-NginxPath $NginxHome))
$Config = $Config.Replace('__FRONTEND_DIST__', (ConvertTo-NginxPath $FrontendDist))
$Config = $Config.Replace('__LISTEN_PORT__', $ListenPort.ToString())
$Config = $Config.Replace('__BACKEND_PORT__', $BackendPort.ToString())
$Config = $Config.Replace('__PROXY_READ_TIMEOUT_SECONDS__', $ProxyReadTimeoutSeconds.ToString())
$Hasher = [Security.Cryptography.SHA256]::Create()
try { $ConfigHash = [BitConverter]::ToString($Hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($Config))).Replace('-', '').ToLowerInvariant() }
finally { $Hasher.Dispose() }

if ($Saved) {
    $existing = Get-Process -Id ([int]$Saved.identity.pid) -ErrorAction SilentlyContinue
    if ($existing -and (Test-ServiceIdentity $Saved.identity $existing) -and
        (Test-NginxPid $Saved) -and $Saved.configSha256 -eq $ConfigHash) {
        [pscustomobject]@{ status = 'ALREADY_RUNNING'; pid = $existing.Id; entryUrl = $Saved.entryUrl } | ConvertTo-Json
        exit 0
    }
    throw 'Existing entry record differs or is stale. Run Status/Stop and inspect it before starting again.'
}
if (Test-Path -LiteralPath $NginxPidPath) { throw 'An unverified Nginx PID file already exists; start refused.' }

foreach ($directory in @($RuntimeDir, (Join-Path $RuntimeDir 'logs'), (Join-Path $RuntimeDir 'conf'), (Join-Path $RuntimeDir 'temp'))) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
$ConfigPath = Join-Path $RuntimeDir 'conf\lesson-agent.conf'
[IO.File]::WriteAllText($ConfigPath, $Config, (New-Object Text.UTF8Encoding($false)))
$Prefix = (ConvertTo-NginxPath $RuntimeDir) + '/'
& $NginxExe '-p' $Prefix '-c' 'conf/lesson-agent.conf' '-t'
$CheckExitCode = $LASTEXITCODE
# Windows nginx -t opens the configured PID file but does not write a PID.
# This invocation proved that the path was absent before the check, so remove
# only the empty file it created. Never discard a pre-existing or populated PID.
if (Test-Path -LiteralPath $NginxPidPath) {
    if ((Get-Item -LiteralPath $NginxPidPath).Length -ne 0) {
        throw 'Configuration check unexpectedly produced a populated PID file; retained it for inspection.'
    }
    Remove-Item -LiteralPath $NginxPidPath
}
if ($CheckExitCode -ne 0) { throw 'Nginx configuration check failed; no listener started.' }
$EntryUrl = "http://127.0.0.1:$ListenPort"
if ($Action -eq 'Check') {
    [pscustomobject]@{ status = 'CONFIG_OK'; config = $ConfigPath; entryUrl = $EntryUrl;
        backendUrl = "http://127.0.0.1:$BackendPort"; requiredFrontendOrigin = $EntryUrl;
        proxyReadTimeoutSeconds = $ProxyReadTimeoutSeconds } | ConvertTo-Json
    exit 0
}
if (-not (Test-LocalPortAvailable $ListenPort)) { throw "Port $ListenPort is occupied; no listener started." }
$LaunchId = [Guid]::NewGuid().ToString('N')
$Arguments = '-p "' + $Prefix + '" -c conf/lesson-agent.conf'
$Process = Start-Process -FilePath $NginxExe -ArgumentList $Arguments -WorkingDirectory $RuntimeDir `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $RuntimeDir 'logs\stdout.log') `
    -RedirectStandardError (Join-Path $RuntimeDir 'logs\stderr.log')
$Identity = Get-ServiceIdentity $Process $LaunchId
$NewState = [pscustomobject]@{ schema = 'lesson-agent-windows-entry-v1'; runtimeDir = $RuntimeDir;
    identity = $Identity; commandArguments = $Arguments; configSha256 = $ConfigHash;
    listenPort = $ListenPort; backendPort = $BackendPort; entryUrl = $EntryUrl }
[IO.File]::WriteAllText($StatePath, ($NewState | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
$Ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $Process.Refresh()
    if ($Process.HasExited) { break }
    if (Test-NginxPid $NewState) {
        try {
            $response = Invoke-WebRequest -Uri ($EntryUrl + '/') -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { $Ready = $true; break }
        } catch { }
    }
    Start-Sleep -Milliseconds 100
}
if (-not $Ready) {
    throw 'Nginx did not become ready; entry identity was retained. Inspect logs and use Stop for identity-checked cleanup.'
}
[pscustomobject]@{ status = 'READY'; pid = $Process.Id; entryUrl = $EntryUrl;
    requiredFrontendOrigin = $EntryUrl; runtimeDir = $RuntimeDir } | ConvertTo-Json
