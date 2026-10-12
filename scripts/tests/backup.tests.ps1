$ErrorActionPreference='Stop'
$global:DeliveryBackupTestCalls=[System.Collections.Generic.List[string]]::new()
function docker {
 $line=$args -join ' '
 $global:DeliveryBackupTestCalls.Add($line)
 $global:LASTEXITCODE=0
 if($line -match 'ps --services --status running'){return @('postgres','minio','api')}
 if($line -match 'config --format json'){return '{"services":{"postgres":{"image":"test-image"},"minio":{"image":"test-minio"},"api":{"image":"test-api"},"ai":{"image":"test-ai"},"web":{"image":"test-web"}}}'}
 if($line -match 'ps --all --quiet'){return 'test-container'}
 if($line -match '^inspect --format'){return ('sha256:' + ('a' * 64))}
 if($line -match '^image inspect'){return ('sha256:' + ('a' * 64))}
 if($line -match '^volume inspect'){return '[{"Labels":{"com.docker.compose.project":"researchdesk-delivery"}}]'}
 if($line -match '^run '){$global:LASTEXITCODE=42; return}
}
$name='failure-test-'+[Guid]::NewGuid().ToString('N')
$failed=$false
try { & "$PSScriptRoot/../backup.ps1" -Name $name } catch { $failed=$true; Write-Host $_ }
if(-not $failed){throw 'Archive failure was silently accepted'}
if(-not ($global:DeliveryBackupTestCalls | Where-Object {$_ -match 'stop --timeout 60$'})){throw 'Backup did not stop services'}
if(-not ($global:DeliveryBackupTestCalls | Where-Object {$_ -match 'start postgres minio api$'})){throw 'Backup did not restore original service state after failure'}
$manifest=Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) ".cache/backups/$name/manifest.json"
if(Test-Path $manifest){throw 'Incomplete backup was marked complete'}
$global:DeliveryBackupTestCalls.Clear()
$failed=$false
try { & "$PSScriptRoot/../backup.ps1" -Action Restore -Name $name } catch { $failed=$true; Write-Host $_ }
if(-not $failed -or $global:DeliveryBackupTestCalls.Count -gt 0){throw 'Restore touched existing delivery project'}
Write-Host 'Backup failure restart and non-overwriting restore checks passed (mock Docker; not a restore drill).'

# A tag may resolve to a different image after rebuild. Refuse before creating volumes.
$manifestFolder=Split-Path $manifest -Parent
@{format=2;images=@{postgres='test-image';minio='test-minio'};imageIds=@{postgres=('sha256:'+('b'*64))}} | ConvertTo-Json -Depth 4 | Set-Content $manifest
$global:DeliveryBackupTestCalls.Clear()
$failed=$false
try { & "$PSScriptRoot/../backup.ps1" -Action Restore -Name $name -Project researchdesk-test-imageguard -Port 18086 } catch { $failed=$true; if($_ -notmatch 'image ID mismatch'){throw} }
if(-not $failed -or ($global:DeliveryBackupTestCalls | Where-Object {$_ -match 'volume create|^run '})){throw 'Changed image was restored or target mutated'}
Write-Host 'Immutable image mismatch rejected before restore mutation.'
