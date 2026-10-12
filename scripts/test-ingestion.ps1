$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & "$PSScriptRoot/test-api.ps1" -Test TaskLeaseIT -Integration
    & apps/ai/.venv/Scripts/uv.exe run --project apps/ai pytest apps/ai/tests/test_ingestion.py apps/ai/tests/test_worker_recovery.py -q
    if($LASTEXITCODE -ne 0){throw 'Real ingestion acceptance failed'}
} finally {Pop-Location}
