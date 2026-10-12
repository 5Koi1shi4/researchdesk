$ErrorActionPreference='Stop'
. "$PSScriptRoot/../runtime.ps1"
function Assert-Rejected([scriptblock]$Action) {
 $rejected=$false
 try { & $Action } catch { $rejected=$true }
 if(-not $rejected){throw 'Unsafe runtime input was accepted'}
}
Assert-Rejected { Initialize-Runtime -Project researchdesk -Port 18080 }
Assert-Rejected { Initialize-Runtime -Project researchdesk-delivery -Port 18080 }
Assert-Rejected { Initialize-Runtime -Project researchdesk-sources -Port 18081 }
Assert-Rejected { Initialize-Runtime -Project researchdesk-delivery -Port 18086 }
Assert-Rejected { Initialize-Runtime -Project researchdesk-test-safety -Port 18085 }
Initialize-Runtime -Project researchdesk-delivery -Port 18085
if($env:COMPOSE_PROJECT_NAME -ne 'researchdesk-delivery' -or $env:ACCEPTANCE_URL -ne 'http://127.0.0.1:18085'){throw 'Runtime environment mismatch'}
$script:Seen=@()
function docker { $script:Seen=@($args); $global:LASTEXITCODE=0 }
Invoke-DeliveryCompose ps --quiet
if(($script:Seen -join ' ') -notmatch 'compose -p researchdesk-delivery --project-directory .+ -f .+ ps --quiet'){throw "Compose project not explicit: $script:Seen"}
$savedRoot=$script:DeliveryRoot
$script:DeliveryRoot=Join-Path $savedRoot '.cache/missing-journey-fixture'
try { Assert-Rejected { Assert-FullJourney } } finally { $script:DeliveryRoot=$savedRoot }
Write-Host 'Runtime safety checks passed; missing full journey fails closed.'
