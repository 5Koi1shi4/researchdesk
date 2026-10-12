param([string]$ApiUrl = 'http://localhost:18080', [string]$AiUrl = '', [switch]$Unavailable)
$ErrorActionPreference = 'Stop'
$health = Invoke-RestMethod "$ApiUrl/api/health" -TimeoutSec 10
if ($health.status -ne 'UP') { throw 'API liveness must be UP' }
$ready = Invoke-WebRequest "$ApiUrl/api/ready" -SkipHttpErrorCheck -TimeoutSec 15
$expected = if ($Unavailable) { 503 } else { 200 }
if ($ready.StatusCode -ne $expected) { throw "Readiness expected $expected, got $($ready.StatusCode)" }
if ($AiUrl) {
    $ai = Invoke-RestMethod "$AiUrl/health" -TimeoutSec 10
    if ($ai.status -ne 'UP') { throw 'AI liveness must be UP' }
    $aiReady = Invoke-WebRequest "$AiUrl/ready" -SkipHttpErrorCheck -TimeoutSec 15
    if ($aiReady.StatusCode -ne $expected) { throw "AI readiness expected $expected, got $($aiReady.StatusCode)" }
}
Write-Host "Health checks passed (readiness $expected)."
