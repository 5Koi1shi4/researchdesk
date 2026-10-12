param([string]$Project='researchdesk-delivery',[int]$Port=18085)
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
$ApiUrl=$env:ACCEPTANCE_URL
$ErrorActionPreference = 'Stop'
$compose = Join-Path (Split-Path $PSScriptRoot -Parent) 'infra/compose.yaml'
function Invoke-Compose([string[]]$ComposeArgs) {
    Invoke-DeliveryCompose @ComposeArgs
    if ($LASTEXITCODE -ne 0) { throw "Docker failed: $ComposeArgs" }
}
function Test-Ai([int]$Expected) {
    $probe = @'
import json, urllib.request, urllib.error, sys
assert json.load(urllib.request.urlopen('http://localhost:8000/health')) == {'status':'UP'}
try:
    response = urllib.request.urlopen('http://localhost:8000/ready')
    code = response.status
except urllib.error.HTTPError as error:
    code = error.code
assert code == int(sys.argv[1]), (code, sys.argv[1])
print('Internal AI health passed; readiness', code)
'@
    Invoke-Compose -ComposeArgs @('exec', '-T', 'ai', 'python', '-c', $probe, "$Expected")
}
function Wait-ApiRecovery {
    # Container health precedes the application's client reconnection.
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    $lastStatus = 'unreachable'
    do {
        try {
            $lastStatus = (Invoke-WebRequest "$ApiUrl/api/ready" -SkipHttpErrorCheck -TimeoutSec 3).StatusCode
            if ($lastStatus -eq 200) { return }
        } catch { $lastStatus = 'unreachable' }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "API did not recover within 30 seconds; readiness was $lastStatus."
}
& "$PSScriptRoot/test-health.ps1" -ApiUrl $ApiUrl
Test-Ai 200
if ((Invoke-WebRequest $ApiUrl -TimeoutSec 10).StatusCode -ne 200) { throw 'Frontend did not respond' }
foreach ($dependency in @('redis', 'postgres')) {
    try {
        Invoke-Compose -ComposeArgs @('stop', $dependency)
        & "$PSScriptRoot/test-health.ps1" -ApiUrl $ApiUrl -Unavailable
        Test-Ai 503
    } finally {
        Invoke-Compose -ComposeArgs @('start', '--wait', $dependency)
    }
    Wait-ApiRecovery
    & "$PSScriptRoot/test-health.ps1" -ApiUrl $ApiUrl
    Test-Ai 200
}
Write-Host 'Compose acceptance passed: live health, same-origin web, and each dependency outage/recovery.'
