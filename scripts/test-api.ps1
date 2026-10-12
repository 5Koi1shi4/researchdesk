param([string]$Test, [switch]$Integration)
$ErrorActionPreference = 'Stop'
if ($Integration -and -not $Test) { throw 'Specify -Test <IntegrationClass> with -Integration. Compose acceptance requires running dependencies.' }
$root = Split-Path $PSScriptRoot -Parent
if ($Integration -and $Test -eq 'ProjectOwnershipIT') {
    try {
        if ((Invoke-WebRequest "http://localhost:18080/api/ready" -TimeoutSec 5).StatusCode -ne 200) { throw 'Dependencies unavailable' }
    } catch { throw 'ProjectOwnershipIT needs the real ResearchDesk Compose API and PostgreSQL on localhost:18080. Start Compose; acceptance is never skipped.' }
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
