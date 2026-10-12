param([ValidateSet('Foundation','Full')][string]$Scope='Full',[string]$Project='researchdesk-delivery',[int]$Port=18085)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
if($Scope -eq 'Full'){Assert-FullJourney}
& "$PSScriptRoot/test-health.ps1" -ApiUrl $env:ACCEPTANCE_URL
Push-Location "$script:DeliveryRoot/apps/web"
try {
 if($Scope -eq 'Full'){ & node tests/research-journey.e2e.mjs }
 else { & node tests/documents.e2e.mjs }
 if($LASTEXITCODE -ne 0){throw "$Scope browser smoke failed"}
} finally {Pop-Location}
Write-Host "$Scope smoke passed. Model quality/real-provider evaluation is separate and deferred by user."
