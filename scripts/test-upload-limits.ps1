# Explicit local Compose acceptance; restores original process settings and API in finally.
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$compose=Join-Path $root 'infra/compose.yaml'
$names=@('MAX_FILE_BYTES','MAX_PDF_PAGES','MAX_DOCUMENTS_PER_PROJECT')
$original=@{}
foreach($name in $names){$original[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Wait-Api {
 for($attempt=0;$attempt -lt 30;$attempt++){
  $response=& curl.exe -s --max-time 2 http://localhost:18080/api/ready
  if($LASTEXITCODE -eq 0 -and $response -eq '{"status":"UP"}'){return}
  Start-Sleep -Milliseconds 500
 }
 throw 'ResearchDesk API did not become ready.'
}
try {
 $env:MAX_FILE_BYTES='1024';$env:MAX_PDF_PAGES='2';$env:MAX_DOCUMENTS_PER_PROJECT='2'
 docker compose -f $compose up -d --no-build api
 if($LASTEXITCODE -ne 0){throw 'Compose limit override failed'}
 docker compose -f $compose exec -T proxy nginx -s reload
 Wait-Api
 & "$PSScriptRoot/test-api.ps1" -Test UploadLimitsIT -Integration
}finally{
 foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
 docker compose -f $compose up -d --no-build api
 if($LASTEXITCODE -ne 0){throw 'Compose default restoration failed'}
 docker compose -f $compose exec -T proxy nginx -s reload
 Wait-Api
}
