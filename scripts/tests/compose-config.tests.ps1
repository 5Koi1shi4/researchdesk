$ErrorActionPreference='Stop'
. "$PSScriptRoot/../runtime.ps1"
Initialize-Runtime
$config=Invoke-DeliveryCompose config --format json | ConvertFrom-Json
$expected=[IO.Path]::GetFullPath((Join-Path $script:DeliveryRoot 'infra/minio'))
if([IO.Path]::GetFullPath($config.services.minio.build.context) -ne $expected){throw 'MinIO build context resolves outside infra/minio'}
if([IO.Path]::GetFullPath($config.services.api.build.context) -ne [IO.Path]::GetFullPath($script:DeliveryRoot)){throw 'API build context resolves outside this clone'}
$mount=@($config.services.proxy.volumes | Where-Object target -eq '/etc/nginx/conf.d/default.conf')[0]
if([IO.Path]::GetFullPath($mount.source) -ne [IO.Path]::GetFullPath((Join-Path $script:DeliveryRoot 'infra/proxy.conf'))){throw 'Proxy config mount resolves outside infra'}
Write-Output 'PASS rendered Compose build contexts and mounts resolve inside this clone'
