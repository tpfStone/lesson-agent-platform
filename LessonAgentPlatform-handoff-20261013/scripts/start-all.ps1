[CmdletBinding()]
param(
    [switch]$SkipFrontend
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EnvFile = Join-Path $Root '.env'
$Runtime = Join-Path $Root '.runtime'
$Logs = Join-Path $Runtime 'logs'
$PidFile = Join-Path $Runtime 'pids.json'

function Import-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing $Path. Copy .env.example to .env and configure it first."
    }
    foreach ($raw in Get-Content -LiteralPath $Path) {
        $line = $raw.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $index = $line.IndexOf('=')
        if ($index -lt 1) { throw "Invalid .env line: $raw" }
        $name = $line.Substring(0, $index).Trim()
        $value = $line.Substring($index + 1).Trim()
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}

function Require-Env([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([string]::IsNullOrWhiteSpace($value) -or $value.StartsWith('replace-')) {
        throw "Configure $Name in $EnvFile before starting."
    }
}

function Assert-File([string]$Path, [string]$Hint) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing file: $Path. $Hint"
    }
}

function Test-PortBusy([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

Import-DotEnv $EnvFile
foreach ($name in @('DEEPSEEK_API_KEY', 'ENGINE_INTERNAL_TOKEN', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
    Require-Env $name
}
if ([string]::IsNullOrWhiteSpace($env:API_KEY) -or $env:API_KEY.StartsWith('replace-')) {
    $env:API_KEY = $env:DEEPSEEK_API_KEY
}
if ([string]::IsNullOrWhiteSpace($env:LLM_API_KEY) -or $env:LLM_API_KEY.StartsWith('replace-')) {
    $env:LLM_API_KEY = $env:DEEPSEEK_API_KEY
}

$F1Dir = Join-Path $Root 'System-v1.2'
$EngineDir = Join-Path $Root 'Lessongen-main\paper4_pipeline'
$F3Dir = Join-Path $Root 'SimClass-main'
$F4Dir = Join-Path $Root 'NoviceTeacher-AI-main\backend'
$WebDir = Join-Path $Root 'Lessongen-main\web'
$FrontendDir = Join-Path $WebDir 'frontend'

$F1Python = Join-Path $F1Dir '.venv\Scripts\python.exe'
$EnginePython = Join-Path $EngineDir '.venv\Scripts\python.exe'
$F3Python = Join-Path $F3Dir '.venv\Scripts\python.exe'
$F4Python = Join-Path $F4Dir '.venv\Scripts\python.exe'

Assert-File $F1Python 'Run the F1 installation steps.'
Assert-File $EnginePython 'Run the F2 Engine installation steps.'
Assert-File $F3Python 'Run the F3 installation steps.'
Assert-File $F4Python 'Run the F4 installation steps.'
Assert-File (Join-Path $F1Dir 'platform_api.py') 'Restore the complete F1 source.'
Assert-File (Join-Path $WebDir 'scripts\f3-runner\run_f3_demo.py') 'Restore web/scripts/f3-runner.'
Assert-File (Join-Path $FrontendDir 'package.json') 'Restore the Vue source.'
if (-not $SkipFrontend -and -not (Test-Path -LiteralPath (Join-Path $FrontendDir 'node_modules'))) {
    throw 'Frontend node_modules is missing. Run npm ci in web/frontend first.'
}

$env:F3_PYTHON = $F3Python
$env:PLATFORM_F3_RUNNER_SCRIPT = (Join-Path $WebDir 'scripts\f3-runner\run_f3_demo.py')
$engineSource = Join-Path $EngineDir 'src'
$env:PYTHONPATH = if ($env:PYTHONPATH) { "$engineSource;$env:PYTHONPATH" } else { $engineSource }

$ports = @(8000, 8001, 8002, 8003, 8080)
if (-not $SkipFrontend) { $ports += 5177 }
foreach ($port in $ports) {
    if (Test-PortBusy $port) {
        throw "Port $port is already in use. Run stop-all.ps1 or stop the existing process first."
    }
}

New-Item -ItemType Directory -Force $Logs | Out-Null
if (Test-Path -LiteralPath $PidFile) {
    throw "PID file already exists: $PidFile. Run stop-all.ps1 first."
}

$pids = [ordered]@{}
function Save-Pids {
    $pids | ConvertTo-Json | Set-Content -LiteralPath $PidFile -Encoding UTF8
}

function Start-LoggedProcess {
    param(
        [string]$Name,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory
    )
    $out = Join-Path $Logs "$Name.out.log"
    $err = Join-Path $Logs "$Name.err.log"
    $process = Start-Process -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $out `
        -RedirectStandardError $err `
        -PassThru
    $pids[$Name] = $process.Id
    Save-Pids
    Write-Host ("Started {0,-10} PID={1}" -f $Name, $process.Id)
}

Write-Host 'Applying F4 migrations...'
Push-Location $F4Dir
try {
    & $F4Python -m alembic -c alembic.ini upgrade head
    if ($LASTEXITCODE -ne 0) { throw 'F4 migration failed.' }
} finally {
    Pop-Location
}

Start-LoggedProcess 'f1' $F1Python @('-m', 'uvicorn', 'platform_api:app', '--host', '127.0.0.1', '--port', '8002', '--http', 'h11') $F1Dir
Start-LoggedProcess 'f2-engine' $EnginePython @('-m', 'paper4_pipeline.web_api') $EngineDir
Start-LoggedProcess 'f3' $F3Python @('-m', 'uvicorn', 'app.api.classroom_api:app', '--host', '127.0.0.1', '--port', '8003') $F3Dir
Start-LoggedProcess 'f4' $F4Python @('-m', 'uvicorn', 'app.api:app', '--host', '127.0.0.1', '--port', '8000') $F4Dir

$jar = Get-ChildItem -LiteralPath (Join-Path $WebDir 'target') -Filter '*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '\.original$' } |
    Select-Object -First 1
if ($jar) {
    Start-LoggedProcess 'spring' 'java.exe' @('-jar', $jar.FullName) $WebDir
} else {
    Assert-File (Join-Path $WebDir 'mvnw.cmd') 'Restore Maven Wrapper or build the Spring JAR first.'
    Start-LoggedProcess 'spring' 'cmd.exe' @('/d', '/c', 'mvnw.cmd spring-boot:run') $WebDir
}

if (-not $SkipFrontend) {
    Start-LoggedProcess 'frontend' 'npm.cmd' @('run', 'dev') $FrontendDir
}

Write-Host 'Waiting for services...'
$healthArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'health-check.ps1'), '-Retries', '45', '-DelaySeconds', '2')
if ($SkipFrontend) { $healthArgs += '-SkipFrontend' }
& powershell.exe @healthArgs
if ($LASTEXITCODE -ne 0) {
    Write-Warning "One or more services are not healthy. Inspect $Logs"
    exit 1
}

Write-Host 'All requested services are healthy.'
if (-not $SkipFrontend) { Write-Host 'Open http://127.0.0.1:5177' }

