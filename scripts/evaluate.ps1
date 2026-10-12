param(
    [Parameter(Mandatory=$true)][ValidateSet('dev','holdout')][string]$Split,
    [Parameter(Mandatory=$true)][ValidateSet('stub','real')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$Config,
    [string]$Output = 'evals/results/latest.json',
    [string]$Python = 'python',
    [string]$Release = ''
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Push-Location $repoRoot
try {
    $arguments = @('-m', 'evals.run', '--split', $Split, '--mode', $Mode, '--config', $Config, '--output', $Output)
    if ($Release) { $arguments += @('--release', $Release) }
    & $Python @arguments
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $code
