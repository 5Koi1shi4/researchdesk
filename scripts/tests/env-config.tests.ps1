$ErrorActionPreference='Stop'
. "$PSScriptRoot/../runtime.ps1"
Initialize-Runtime -Project researchdesk-test-env -Port 18089
$fixture=Join-Path $script:DeliveryRoot ('.cache/env-contract-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$fixture/infra" -Force | Out-Null
'RESEARCHDESK_CONFIG_SENTINEL=from-root-env' | Set-Content "$fixture/.env"
' services:
  probe:
    image: busybox
    environment:
      SENTINEL: ${RESEARCHDESK_CONFIG_SENTINEL:-missing}
' | Set-Content "$fixture/infra/compose.yaml"
$script:DeliveryRoot=$fixture
Push-Location "$fixture/infra"
try {
 $config=Invoke-DeliveryCompose config --format json | ConvertFrom-Json
 if($config.services.probe.environment.SENTINEL -ne 'from-root-env'){throw 'Root .env was not loaded from nested cwd'}
}finally{Pop-Location}
Write-Host 'PASS explicit root .env loaded from a nested working directory (nonsecret fixture).'
