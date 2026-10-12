. "$PSScriptRoot/runtime.ps1"
$script:DeliveryRoot=Split-Path $PSScriptRoot -Parent
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$env:npm_config_cache = Join-Path $root '.cache/npm'
Push-Location (Join-Path $root 'apps/web')
try {
    npm run generate:contracts
    if ($LASTEXITCODE -ne 0) { throw 'TypeScript contract generation failed' }
} finally { Pop-Location }
Push-Location $root
try {
    New-Item -ItemType Directory -Force apps/ai/generated | Out-Null
    & (Get-AiPython) scripts/prepare-python-contracts.py contracts/openapi.json .cache/python-contracts/openapi.json
    if ($LASTEXITCODE -ne 0) { throw 'Python contract preparation failed' }
    & (Get-AiPython) -m datamodel_code_generator --input .cache/python-contracts/openapi.json --input-file-type openapi --output apps/ai/generated/contracts.py --output-model-type pydantic_v2.BaseModel --target-python-version 3.11 --disable-timestamp --use-standard-collections --use-union-operator --enum-field-as-literal all --use-annotated --strict-nullable --extra-fields forbid --formatters black isort
    if ($LASTEXITCODE -ne 0) { throw 'Python contract generation failed' }
    # Keep regeneration stable across Git checkout line endings and host platforms.
    $output = Join-Path $root 'apps/ai/generated/contracts.py'
    $source = [System.IO.File]::ReadAllText($output).Replace("`r`n", "`n")
    [System.IO.File]::WriteAllText($output, $source, [System.Text.UTF8Encoding]::new($false))
} finally { Pop-Location }
