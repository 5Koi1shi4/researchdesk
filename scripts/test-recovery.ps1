param([string]$Project='researchdesk-delivery',[int]$Port=18085)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
Push-Location $script:DeliveryRoot
try {
 & (Get-AiPython) -m pytest apps/ai/tests/test_worker_recovery.py -m integration -q
 if($LASTEXITCODE -ne 0){throw 'Real worker/queue recovery acceptance failed'}
} finally {Pop-Location}
