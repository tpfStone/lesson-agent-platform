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
            [pscustomobject]@{
                Service = $service.Name
                Status  = 'UP'
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
    if (-not ($last | Where-Object Status -eq 'DOWN')) { break }
    if ($attempt -lt $Retries) { Start-Sleep -Seconds $DelaySeconds }
}

$last | Format-Table -AutoSize
if ($last | Where-Object Status -eq 'DOWN') { exit 1 }
exit 0

