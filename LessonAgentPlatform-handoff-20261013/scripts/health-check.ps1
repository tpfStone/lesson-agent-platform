[CmdletBinding()]
param(
    [int]$Retries = 1,
    [int]$DelaySeconds = 2,
    [switch]$SkipFrontend
)

$services = @(
    [pscustomobject]@{ Name = 'F4';       Url = 'http://127.0.0.1:8000/api/health' },
    [pscustomobject]@{ Name = 'F2Engine'; Url = 'http://127.0.0.1:8001/internal/v1/health' },
    [pscustomobject]@{ Name = 'F1';       Url = 'http://127.0.0.1:8002/internal/v1/health' },
    [pscustomobject]@{ Name = 'F3';       Url = 'http://127.0.0.1:8003/api/health' },
    [pscustomobject]@{ Name = 'Spring';   Url = 'http://127.0.0.1:8080/actuator/health' }
)
if (-not $SkipFrontend) {
    $services += [pscustomobject]@{ Name = 'Frontend'; Url = 'http://127.0.0.1:5177/' }
}

$last = @()
for ($attempt = 1; $attempt -le [Math]::Max(1, $Retries); $attempt++) {
    $last = foreach ($service in $services) {
        try {
            $response = Invoke-WebRequest -Uri $service.Url -UseBasicParsing -TimeoutSec 5
            $ready = $true
            if ($service.Name -ne 'Frontend') {
                $body = $response.Content | ConvertFrom-Json
                if ($body.status -notin @('up', 'UP', 'ok')) { $ready = $false }
                if ($body.PSObject.Properties.Name -contains 'model_configured' -and -not $body.model_configured) { $ready = $false }
                if ($body.PSObject.Properties.Name -contains 'ready' -and -not $body.ready) { $ready = $false }
                if ($body.PSObject.Properties.Name -contains 'llm_configured' -and $body.provider -ne 'mock' -and -not $body.llm_configured) { $ready = $false }
            }
            [pscustomobject]@{
                Service = $service.Name
                Status  = $(if ($ready) { 'READY' } else { 'NOT_READY' })
                HTTP    = [int]$response.StatusCode
                URL     = $service.Url
            }
        } catch {
            $code = $null
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            [pscustomobject]@{
                Service = $service.Name
                Status  = 'DOWN'
                HTTP    = $code
                URL     = $service.Url
            }
        }
    }
    if (-not ($last | Where-Object Status -ne 'READY')) { break }
    if ($attempt -lt $Retries) { Start-Sleep -Seconds $DelaySeconds }
}

$last | Format-Table -AutoSize
if ($last | Where-Object Status -ne 'READY') { exit 1 }
exit 0

