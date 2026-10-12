. "$PSScriptRoot/runtime.ps1"
$script:DeliveryRoot=Split-Path $PSScriptRoot -Parent
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$outputs = @('apps/web/src/generated/contracts.ts', 'apps/ai/generated/contracts.py')
$before = @{}
foreach ($file in $outputs) { $before[$file] = (Get-FileHash (Join-Path $root $file)).Hash }
& "$PSScriptRoot/generate-contracts.ps1"
foreach ($file in $outputs) {
    if ((Get-FileHash (Join-Path $root $file)).Hash -ne $before[$file]) { throw "Generated contract drift: $file. Review and commit regeneration." }
}
Push-Location $root
try {
    & (Get-AiPython) -m pytest apps/ai/tests/test_contracts.py
    if ($LASTEXITCODE -ne 0) { throw 'Python contracts failed' }
    Push-Location apps/web
    try {
        npm run test:contracts
        if ($LASTEXITCODE -ne 0) { throw 'TypeScript contracts failed' }
    } finally { Pop-Location }
    & "$PSScriptRoot/test-api.ps1" -Test ContractsTest
} finally { Pop-Location }
