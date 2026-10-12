param([ValidateSet('Unit','Foundation','Full')][string]$Scope='Full',[string]$Project='researchdesk-test-integration',[int]$Port=18087)
$ErrorActionPreference='Stop'
$env:GENERATION_MODE='demo'
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
if($Scope -eq 'Full'){Assert-FullJourney}
& "$PSScriptRoot/tests/runtime.tests.ps1"
& "$PSScriptRoot/tests/backup.tests.ps1"
Initialize-Runtime -Project $Project -Port $Port
& "$PSScriptRoot/test-contracts.ps1"
& "$PSScriptRoot/test-api.ps1"
Push-Location $script:DeliveryRoot
try {
 & (Get-AiPython) -m pytest apps/ai/tests evals/test_score.py -m 'not integration'
 if($LASTEXITCODE -ne 0){throw 'Python unit tests failed'}
 & npm --prefix apps/web test
 if($LASTEXITCODE -ne 0){throw 'Frontend unit tests failed'}
 & npm --prefix apps/web run build
 if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
 if($Scope -ne 'Unit'){
  & "$PSScriptRoot/tests/compose-config.tests.ps1"
  & "$PSScriptRoot/tests/env-config.tests.ps1"
  Initialize-Runtime -Project $Project -Port $Port
  Invoke-DeliveryCompose config --quiet
  Assert-DemoRuntime
  & "$PSScriptRoot/test-compose.ps1" -Project $Project -Port $Port
  & (Get-AiPython) tests/acceptance/log_redaction.py --project $Project --port $Port
  if($LASTEXITCODE -ne 0){throw 'Sensitive query log regression failed'}
  & "$PSScriptRoot/test-api.ps1" -Integration -Test '*IT,!UploadLimitsIT,!GenerationRaceIT'
  & "$PSScriptRoot/test-upload-limits.ps1" -Project $Project -Port $Port
  & (Get-AiPython) -m pytest apps/ai/tests -m integration
  if($LASTEXITCODE -ne 0){throw 'Python real integration/recovery tests failed'}
  & "$PSScriptRoot/test-generation-faults.ps1" -Project $Project -Port $Port
  & "$PSScriptRoot/smoke.ps1" -Scope $Scope -Project $Project -Port $Port
  if($Scope -eq 'Full'){
   & (Get-AiPython) tests/acceptance/backup_roundtrip.py
   if($LASTEXITCODE -ne 0){throw 'Integrated backup restoration failed'}
  }
 }
} finally {Pop-Location}
Write-Host "$Scope verification passed; real-model acceptance remains deferred. Unit scope excludes Compose/browser acceptance."
