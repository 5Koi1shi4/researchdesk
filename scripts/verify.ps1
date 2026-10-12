param([ValidateSet('Unit','Foundation','Full')][string]$Scope='Full',[string]$Project='researchdesk-delivery',[int]$Port=18085)
$ErrorActionPreference='Stop'
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
 & (Get-AiPython) -m pytest apps/ai/tests -m 'not integration'
 if($LASTEXITCODE -ne 0){throw 'Python unit tests failed'}
 & npm --prefix apps/web test
 if($LASTEXITCODE -ne 0){throw 'Frontend unit tests failed'}
 & npm --prefix apps/web run build
 if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
 if($Scope -ne 'Unit'){
  & "$PSScriptRoot/tests/compose-config.tests.ps1"
  Initialize-Runtime -Project $Project -Port $Port
  Invoke-DeliveryCompose config --quiet
  & "$PSScriptRoot/test-compose.ps1" -Project $Project -Port $Port
  & (Get-AiPython) tests/acceptance/log_redaction.py --project $Project --port $Port
  if($LASTEXITCODE -ne 0){throw 'Sensitive query log regression failed'}
  & "$PSScriptRoot/test-api.ps1" -Integration -Test '*IT,!UploadLimitsIT'
  & "$PSScriptRoot/test-upload-limits.ps1" -Project $Project -Port $Port
  & (Get-AiPython) -m pytest apps/ai/tests -m integration
  if($LASTEXITCODE -ne 0){throw 'Python real integration/recovery tests failed'}
  & "$PSScriptRoot/smoke.ps1" -Scope $Scope -Project $Project -Port $Port
 }
} finally {Pop-Location}
Write-Host "$Scope verification passed; real-model acceptance remains deferred. Unit scope excludes Compose/browser acceptance."
