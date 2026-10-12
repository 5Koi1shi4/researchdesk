param([string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$composeFile = Join-Path $PSScriptRoot 'compose.yaml'
$composeArgs = @('compose', '-p', 'researchdesk-retrieval', '-f', $composeFile)
$previousPath = $env:PYTHONPATH
$previousDsn = $env:RETRIEVAL_TEST_ADMIN_URL
$started = $false
$exitCode = 1
Push-Location $repoRoot
try {
    $started = $true
    & docker @composeArgs up -d --wait --wait-timeout 90 test-db
    if ($LASTEXITCODE -ne 0) { throw 'Unable to start the dedicated retrieval test database' }
    $container = & docker @composeArgs ps -q test-db
    if ($LASTEXITCODE -ne 0 -or -not $container) { throw 'Retrieval test container was not found' }
    $project = & docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' $container
    if ($LASTEXITCODE -ne 0 -or $project.Trim() -ne 'researchdesk-retrieval') { throw 'Refusing an unexpected Compose project' }
    $service = & docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' $container
    if ($LASTEXITCODE -ne 0 -or $service.Trim() -ne 'test-db') { throw 'Refusing an unexpected Compose service' }
    $env:PYTHONPATH = (Join-Path $repoRoot 'apps/ai') + [IO.Path]::PathSeparator + $repoRoot
    $env:RETRIEVAL_TEST_ADMIN_URL = 'postgresql://researchdesk:researchdesk-dev-only@127.0.0.1:18082/researchdesk'
    & $Python (Join-Path $PSScriptRoot 'migrate.py')
    if ($LASTEXITCODE -ne 0) { throw 'Retrieval test migration failed' }
    & $Python -m pytest (Join-Path $repoRoot 'apps/ai/tests/test_retrieval_postgres.py') -q
    $exitCode = $LASTEXITCODE
} catch {
    Write-Error -ErrorAction Continue $_
    $exitCode = 1
} finally {
    $env:PYTHONPATH = $previousPath
    $env:RETRIEVAL_TEST_ADMIN_URL = $previousDsn
    if ($started) {
        & docker @composeArgs stop test-db
        if ($LASTEXITCODE -ne 0) { $exitCode = 1 }
    }
    Pop-Location
}
exit $exitCode
