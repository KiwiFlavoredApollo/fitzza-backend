$ErrorActionPreference = 'Stop'

$endpoints = [ordered]@{
    'Config' = 'http://localhost:8888/health'
    'Discovery' = 'http://localhost:8761/health'
    'Gateway' = 'http://localhost:8080/health'
    'Product' = 'http://localhost:8082/health'
    'Virtual Try-On' = 'http://localhost:8091/health'
    'Recommendation' = 'http://localhost:8092/health'
}

$failed = $false
foreach ($entry in $endpoints.GetEnumerator()) {
    try {
        $response = Invoke-WebRequest -Uri $entry.Value -UseBasicParsing -TimeoutSec 5
        $health = $response.Content | ConvertFrom-Json
        if ($health.status -ne 'UP' -or $health.liveness -ne 'UP' -or $health.readiness -ne 'UP') {
            throw "unexpected health response: $($response.Content)"
        }
        Write-Host "[OK] $($entry.Key) ($($response.StatusCode))" -ForegroundColor Green
    }
    catch {
        Write-Host "[FAIL] $($entry.Key): $($_.Exception.Message)" -ForegroundColor Red
        $failed = $true
    }
}

if ($failed) { exit 1 }
