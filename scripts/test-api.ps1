param([string]$Test, [string]$Integration)
$ErrorActionPreference = 'Stop'
if ($Test -and $Integration) { throw 'Choose -Test or -Integration, not both.' }
$root = Split-Path $PSScriptRoot -Parent
$env:MAVEN_USER_HOME = Join-Path $root '.cache/maven-home'
Push-Location (Join-Path $root 'apps/api')
try {
    $arguments = @("-Dmaven.repo.local=$root/.cache/m2", '-B', '-ntp')
    if ($Integration) { $arguments += @('verify', "-Dit.test=$Integration") }
    else {
        $arguments += 'test'
        if ($Test) { $arguments += "-Dtest=$Test" }
    }
    & ./mvnw.cmd @arguments
    if ($LASTEXITCODE -ne 0) { throw "Maven exited $LASTEXITCODE" }
} finally { Pop-Location }
