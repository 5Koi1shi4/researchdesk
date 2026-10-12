param()
$ErrorActionPreference = 'Stop'
$target = Join-Path $PSScriptRoot 'source.tar.gz'
$expected = '45521908307306e925c98d629e1c17d78c8b72b6ee242b1bfb1409f7d8ee5841'
if (-not (Test-Path -LiteralPath $target)) {
    Invoke-WebRequest 'https://codeload.github.com/minio/minio/tar.gz/9e49d5e7a648f00e26f2246f4dc28e6b07f8c84a' -OutFile $target
}
if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'MinIO source archive checksum mismatch; refusing build.'
}
Write-Host 'Official MinIO source archive checksum verified.'
