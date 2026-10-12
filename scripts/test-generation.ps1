param(
    [ValidateSet('unit','module','http','race','browser')][string]$Phase='unit',
    [string]$Python
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if (-not $Python) { $Python=Join-Path $root 'apps/ai/.venv/Scripts/python.exe' }
function Assert-Exit([string]$Label) { if ($LASTEXITCODE -ne 0) { throw "$Label failed with exit $LASTEXITCODE" } }
Push-Location $root
$originalPythonPath=$env:PYTHONPATH
try {
    $env:PYTHONPATH=Join-Path $root 'apps/ai'
    switch ($Phase) {
        { $_ -in @('unit','module') } {
            if (-not (Test-Path -LiteralPath $Python)) { throw 'Python environment missing. Run setup or pass -Python <interpreter>.' }
            $pythonTests=if ($Phase -eq 'module') { @('apps/ai/tests/test_generation.py','apps/ai/tests/test_generation_transport.py','apps/ai/tests/test_contracts.py','scripts/test_generation_contracts.py') } else { @('apps/ai/tests','scripts/test_generation_contracts.py') }
            & $Python -m pytest @pythonTests -m 'not integration' -q
            Assert-Exit 'Python and generation contracts'
            npm --prefix apps/web test
            Assert-Exit 'Web tests'
            npm --prefix apps/web run test:contracts
            Assert-Exit 'TypeScript contract fixtures'
            npm --prefix apps/web run build
            Assert-Exit 'Web production build'
            if ($Phase -eq 'module') { & "$PSScriptRoot/test-api.ps1" -Test 'RunEventValidatorTest,RunStreamErrorTest,ContractsTest' } else { & "$PSScriptRoot/test-api.ps1" }
        }
        'http' {
            if ((Invoke-WebRequest "$env:ACCEPTANCE_URL/api/ready" -TimeoutSec 5).StatusCode -ne 200) { throw 'Explicit isolated generation API unavailable.' }
            & "$PSScriptRoot/test-api.ps1" -Test 'GenerationScopeIT,RunRecoveryIT' -Integration
        }
        'race' {
            # Requires the explicit internal fault fixture described in docs/generation-integration.md.
            & "$PSScriptRoot/test-api.ps1" -Test 'GenerationRaceIT' -Integration
        }
        'browser' {
            node apps/web/tests/research.e2e.mjs
            Assert-Exit 'Real generation browser journey'
        }
    }
} finally {
    $env:PYTHONPATH=$originalPythonPath
    Pop-Location
}
