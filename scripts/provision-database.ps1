param([string]$Project='researchdesk-delivery',[int]$Port=18085)
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    & Invoke-DeliveryCompose exec -T postgres sh /docker-entrypoint-initdb.d/10-researchdesk-roles.sh
    if ($LASTEXITCODE -ne 0) { throw 'Database role provisioning failed. Start this project PostgreSQL first.' }
} finally { Pop-Location }
