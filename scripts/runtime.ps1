# Shared isolated runtime. Dot-source; no Docker side effects until invoked.
function Initialize-Runtime {
 param([string]$Project='researchdesk-delivery',[int]$Port=18085)
 if($Project -notmatch '^researchdesk-(delivery|test-[a-z0-9-]+)$'){throw 'Only delivery or researchdesk-test-* projects are allowed; the shared project is forbidden.'}
 if($Project -eq 'researchdesk-delivery' -and $Port -ne 18085){throw 'Delivery must use port 18085'}
 if($Project -ne 'researchdesk-delivery' -and $Port -eq 18085){throw 'Port 18085 is reserved for delivery'}
 if($Port -lt 1024 -or $Port -gt 65535 -or $Port -in 18080..18084){throw 'Use an isolated unprivileged port, never shared ports 18080-18084.'}
 $script:DeliveryRoot=Split-Path $PSScriptRoot -Parent
 $script:DeliveryProject=$Project
 $env:COMPOSE_PROJECT_NAME=$Project
 $env:RESEARCHDESK_PORT="$Port"
 $env:ACCEPTANCE_URL="http://127.0.0.1:$Port"
 $env:E2E_BASE_URL=$env:ACCEPTANCE_URL
 $env:PYTHONUTF8='1'
 $env:PYTHONPATH=Join-Path $script:DeliveryRoot 'apps/ai'
 if($Project -eq 'researchdesk-test-integration' -and $Port -ne 18087){throw 'Integration must use port 18087'}
 if($Project -eq 'researchdesk-test-integration'){
  $env:RESEARCHDESK_TEST_DB_PORT='15487'
  $env:RETRIEVAL_TEST_ADMIN_URL='postgresql://researchdesk:researchdesk-dev-only@127.0.0.1:15487/researchdesk'
 }
}
function Invoke-DeliveryCompose {
 $files=@('-f',"$script:DeliveryRoot/infra/compose.yaml")
 if($script:DeliveryProject -eq 'researchdesk-test-integration'){$files+=@('-f',"$script:DeliveryRoot/infra/compose.integration.yaml")}
 & docker compose -p $script:DeliveryProject --project-directory "$script:DeliveryRoot/infra" @files @args
 if($LASTEXITCODE -ne 0){throw "Compose operation failed (exit $LASTEXITCODE)"}
}
function Assert-FullJourney {
 $journey=Join-Path $script:DeliveryRoot 'apps/web/tests/research-journey.e2e.mjs'
 if(-not (Test-Path -LiteralPath $journey)){throw 'FULL ACCEPTANCE PENDING: integrated registration → upload → answer/citation → report version → Markdown/PDF export browser journey is missing (apps/web/tests/research-journey.e2e.mjs). Foundation is not full acceptance.'}
}
function Get-AiPython {
 if($env:RESEARCHDESK_PYTHON){return $env:RESEARCHDESK_PYTHON}
 $windows=Join-Path $script:DeliveryRoot 'apps/ai/.venv/Scripts/python.exe'
 if(Test-Path $windows){return $windows}
 return (Join-Path $script:DeliveryRoot 'apps/ai/.venv/bin/python')
}
