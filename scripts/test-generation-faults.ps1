param([string]$Project='researchdesk-test-integration',[int]$Port=18087)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
if($Project -notmatch '^researchdesk-test-'){throw 'Fault injection requires a test project'}
$binding=(Invoke-DeliveryCompose port proxy 8080).Trim()
if($binding -ne "127.0.0.1:$Port"){throw 'Fault target binding mismatch'}
Assert-DemoRuntime
$override=Join-Path $script:DeliveryRoot '.cache/generation-fault.override.yaml'
@"
services:
  api:
    environment:
      AI_BASE_URL: http://ai:8001
"@ | Set-Content -LiteralPath $override
$fixture=Join-Path $script:DeliveryRoot 'apps/api/src/test/fixtures/generation_fault_server.py'
try {
 Invoke-DeliveryCompose cp $fixture ai:/tmp/generation_fault_server.py
 Invoke-DeliveryCompose exec -d ai python /tmp/generation_fault_server.py
 $files=@('-f',"$script:DeliveryRoot/infra/compose.yaml")
 if(Test-Path -LiteralPath "$script:DeliveryRoot/.env"){$files+=@('--env-file',"$script:DeliveryRoot/.env")}
 if($Project -eq 'researchdesk-test-integration'){$files+=@('-f',"$script:DeliveryRoot/infra/compose.integration.yaml")}
 & docker compose -p $Project --project-directory "$script:DeliveryRoot/infra" @files -f $override up -d --no-deps --force-recreate api proxy
 if($LASTEXITCODE -ne 0){throw 'Fault API recreation failed'}
 $ready=$false
 for($i=0;$i -lt 60;$i++){
  try {if((Invoke-WebRequest "$env:ACCEPTANCE_URL/api/ready" -TimeoutSec 2).StatusCode -eq 200){$ready=$true;break}}catch{}
  Start-Sleep -Seconds 1
 }
 if(-not $ready){throw 'Fault API did not become ready'}
 & "$PSScriptRoot/test-api.ps1" -Integration -Test GenerationRaceIT
 & node "$script:DeliveryRoot/apps/web/tests/research-timeout.e2e.mjs"
 if($LASTEXITCODE -ne 0){throw 'Deadline browser acceptance failed'}
} finally {
 # Recreate only owned services; restarting AI also removes the internal-only fixture.
 Invoke-DeliveryCompose up -d --no-deps --force-recreate api ai proxy
 $ready=$false
 for($i=0;$i -lt 60;$i++){
  try {if((Invoke-WebRequest "$env:ACCEPTANCE_URL/api/ready" -TimeoutSec 2).StatusCode -eq 200){$ready=$true;break}}catch{}
  Start-Sleep -Seconds 1
 }
 if(-not $ready){throw 'Normal API restoration did not become ready'}
}
Write-Host 'Late completion cancellation/deletion races passed and normal services restored.'
