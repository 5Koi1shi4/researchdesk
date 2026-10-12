param([ValidateSet('Backup','Restore')][string]$Action='Backup',[Parameter(Mandatory)][string]$Name,[string]$Project='researchdesk-delivery',[int]$Port=18085)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/runtime.ps1"
Initialize-Runtime -Project $Project -Port $Port
if($Name -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$'){throw 'Name must be a simple backup label'}
$folder=Join-Path $script:DeliveryRoot ".cache/backups/$Name"
$keys=@('postgres-data','minio-data','redis-data')
function Docker-Checked {
 & docker @args
 if($LASTEXITCODE -ne 0){throw "Docker backup operation failed ($LASTEXITCODE)"}
}
if($Action -eq 'Backup'){
 if(Test-Path $folder){throw 'Backup already exists; choose a new label'}
 $running=@(Invoke-DeliveryCompose ps --services --status running)
 if('postgres' -notin $running -or 'minio' -notin $running){throw 'Backup requires the running source PostgreSQL and MinIO services'}
 $config=(Invoke-DeliveryCompose config --format json | ConvertFrom-Json)
 $image=$config.services.postgres.image
 $imageIds=@{}
 foreach($service in @('postgres','minio','api','ai','web')){
  $reference=$config.services.$service.image
  if(-not $reference){throw "Missing explicit image for $service"}
  $configuredId=(Docker-Checked image inspect --format '{{.Id}}' $reference).Trim()
  $containers=@(Invoke-DeliveryCompose ps --all --quiet $service)
  if($containers.Count -ne 1){throw "Backup requires exactly one source container for $service"}
  $runtimeId=(Docker-Checked inspect --format '{{.Image}}' $containers[0]).Trim()
  if($runtimeId -notmatch '^sha256:[a-f0-9]{64}$' -or $runtimeId -ne $configuredId){throw "Runtime/tag image mismatch for $service; reconcile the deployment before backup"}
  $imageIds[$service]=$runtimeId
 }
 $sourceCommit=(& git -c "safe.directory=$script:DeliveryRoot" -C $script:DeliveryRoot rev-parse HEAD)
 if($LASTEXITCODE -ne 0){throw 'Cannot record source commit'}
 $volumes=@{}
 foreach($key in $keys){
  $volume="$Project`_$key"
  $info=(Docker-Checked volume inspect $volume | ConvertFrom-Json)[0]
  if($info.Labels.'com.docker.compose.project' -ne $Project){throw 'Volume ownership mismatch'}
  $volumes[$key]=$volume
 }
 New-Item -ItemType Directory -Path $folder -Force | Out-Null
 try {
  # Freeze both DB and object store before copying: no cross-store write skew.
  Invoke-DeliveryCompose stop --timeout 60
  $files=@{}
  foreach($key in $keys){
   Docker-Checked run --rm --network none --entrypoint tar --mount "type=volume,src=$($volumes[$key]),dst=/source,readonly" --mount "type=bind,src=$folder,dst=/backup" $image -cpf "/backup/$key.tar" -C /source .
   $files[$key]=(Get-FileHash "$folder/$key.tar" -Algorithm SHA256).Hash
  }
  @{format=2;sourceCommit=$sourceCommit;imageIds=$imageIds;createdUtc=[DateTime]::UtcNow.ToString('o');sourceProject=$Project;image=$image;files=$files;images=@{}+@{postgres=$config.services.postgres.image;minio=$config.services.minio.image}} | ConvertTo-Json -Depth 5 | Set-Content "$folder/manifest.json"
 } finally {
  if($running.Count -gt 0){Invoke-DeliveryCompose start @running}
 }
 Write-Host "Consistent cold backup saved: $folder. Contains sensitive user data; restrict access and encrypt off-host."
} else {
 if($Project -notmatch '^researchdesk-test-'){throw 'Restore requires a NEW researchdesk-test-* project; never overwrites delivery data'}
 $manifest=Get-Content "$folder/manifest.json" -Raw | ConvertFrom-Json
 if($manifest.format -ne 2){throw 'Unsupported backup format'}
 $config=(Invoke-DeliveryCompose config --format json | ConvertFrom-Json)
 if($config.services.postgres.image -ne $manifest.images.postgres -or $config.services.minio.image -ne $manifest.images.minio){throw 'Restore requires the same PostgreSQL and MinIO images; perform upgrades separately'}
 foreach($service in @('postgres','minio','api','ai','web')){
  $reference=$config.services.$service.image
  if(-not $reference){throw "Missing explicit image for $service"}
  $currentId=(Docker-Checked image inspect --format '{{.Id}}' $reference).Trim()
  if($currentId -ne $manifest.imageIds.$service){throw "Restore image ID mismatch for $service; restore the recorded build before proceeding"}
 }
 $existing=@(Docker-Checked ps -aq --filter "label=com.docker.compose.project=$Project")
 if($existing.Count -gt 0){throw 'Restore target already has containers'}
 $allVolumes=@(Docker-Checked volume ls -q)
 foreach($key in $keys){
  if("$Project`_$key" -in $allVolumes){throw 'Restore target volume already exists'}
  if((Get-FileHash "$folder/$key.tar" -Algorithm SHA256).Hash -ne $manifest.files.$key){throw 'Backup checksum mismatch'}
 }
 # Use reviewed local config image, never execute an image selected by the backup manifest.
 foreach($key in $keys){
  $volume="$Project`_$key"
  Docker-Checked volume create --label "com.docker.compose.project=$Project" --label "com.docker.compose.volume=$key" $volume | Out-Null
  Docker-Checked run --rm --network none --entrypoint tar --mount "type=volume,src=$volume,dst=/restore" --mount "type=bind,src=$folder,dst=/backup,readonly" $config.services.postgres.image -xpf "/backup/$key.tar" -C /restore
 }
 Write-Host "Restored volumes for $Project. Supply the original secrets privately, then start this isolated project and verify account, source contents, report versions, and exports. Restore validation is not yet complete."
}
