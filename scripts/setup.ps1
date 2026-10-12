$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:npm_config_cache = Join-Path $root '.cache/npm'
Push-Location $root
try {
    python -m venv apps/ai/.venv
    if ($LASTEXITCODE -ne 0) { throw 'Python venv creation failed' }
    & apps/ai/.venv/Scripts/python.exe -m pip install --no-cache-dir uv==0.13.0
    if ($LASTEXITCODE -ne 0) { throw 'uv installation failed' }
    & apps/ai/.venv/Scripts/uv.exe sync --project apps/ai --locked --cache-dir .cache/uv --link-mode copy
    if ($LASTEXITCODE -ne 0) { throw 'AI dependency installation failed' }
    Push-Location apps/web
    try {
        npm ci
        if ($LASTEXITCODE -ne 0) { throw 'Web dependency installation failed' }
    } finally { Pop-Location }
} finally { Pop-Location }
