$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & docker compose -f infra/compose.yaml exec -T postgres sh /docker-entrypoint-initdb.d/10-researchdesk-roles.sh
    if ($LASTEXITCODE -ne 0) { throw 'Database role provisioning failed. Start this project PostgreSQL first.' }
} finally { Pop-Location }
