param([string]$Project='researchdesk-delivery',[int]$Port=18085)
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
# Build host artifacts before packaging. Linux wheel target matches the pinned amd64 Alpine image.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:npm_config_cache = Join-Path $root '.cache/npm'
Push-Location $root
try {
    & "$PSScriptRoot/test-contracts.ps1"
    & "$PSScriptRoot/test-api.ps1" -Test HealthIT -Integration
    Push-Location apps/web
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        npm run build
        if ($LASTEXITCODE -ne 0) { throw 'Web build failed' }
    } finally { Pop-Location }
    # Container-independent checks precede packaging. Run test-ingestion.ps1 after Compose starts.
    & (Get-AiPython) -m pytest apps/ai/tests -m 'not integration'
    if ($LASTEXITCODE -ne 0) { throw 'AI tests failed' }
    & (Get-AiPython) -m uv export --project apps/ai --locked --no-dev --no-emit-project --format requirements-txt --output-file apps/ai/requirements.lock --cache-dir .cache/uv --quiet
    if ($LASTEXITCODE -ne 0) { throw 'AI lock export failed' }
    & (Get-AiPython) -m pip download --no-cache-dir --index-url https://pypi.org/simple -r apps/ai/requirements.lock --dest apps/ai/wheels --only-binary=:all: --platform musllinux_1_2_x86_64 --platform musllinux_1_1_x86_64 --python-version 312 --implementation cp --abi cp312
    if ($LASTEXITCODE -ne 0) { throw 'AI wheel download failed' }
    & "$root/infra/minio/prepare.ps1"
    & Invoke-DeliveryCompose build
    if ($LASTEXITCODE -ne 0) { throw 'Compose build failed' }
} finally { Pop-Location }
