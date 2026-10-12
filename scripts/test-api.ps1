param([string]$Test, [switch]$Integration)
$ErrorActionPreference = 'Stop'
if ($Integration -and -not $Test) { throw 'Specify -Test <IntegrationClass> with -Integration. Compose acceptance requires running dependencies.' }
$root = Split-Path $PSScriptRoot -Parent
if (-not $env:COMPOSE_PROJECT_NAME) { $env:COMPOSE_PROJECT_NAME='researchdesk-delivery' }
if (-not $env:ACCEPTANCE_URL) { $env:ACCEPTANCE_URL='http://127.0.0.1:18085' }
if ($Integration -and $Test -eq 'ProjectOwnershipIT') {
    try {
        if ((Invoke-WebRequest "$env:ACCEPTANCE_URL/api/ready" -TimeoutSec 5).StatusCode -ne 200) { throw 'Dependencies unavailable' }
    } catch { throw 'ProjectOwnershipIT needs the real ResearchDesk Compose API and PostgreSQL at ACCEPTANCE_URL. Start Compose; acceptance is never skipped.' }
}
$env:MAVEN_USER_HOME = Join-Path $root '.cache/maven-home'
Push-Location (Join-Path $root 'apps/api')
try {
    $arguments = @("-Dmaven.repo.local=$root/.cache/m2", '-B', '-ntp')
    if ($Integration) { $arguments += @('verify', "-Dit.test=$Test") }
    else {
        $arguments += 'test'
        if ($Test) { $arguments += "-Dtest=$Test" }
    }
    & ./mvnw.cmd @arguments
    if ($LASTEXITCODE -ne 0) { throw "Maven exited $LASTEXITCODE" }
} finally { Pop-Location }
