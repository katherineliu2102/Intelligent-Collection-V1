$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$log = Join-Path $root "collection-admin-run.log"

Get-Content (Join-Path $root ".env") | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $p = $_ -split '=', 2
    if ($p.Count -eq 2) { Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim() }
}
if ($env:NACOS_SERVER_ADDR -match '^https?://') {
    $env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://', '' -replace '/nacos/?$', '')
}

$params = @{
    dataId   = 'intelligent-collection-local.yml'
    group    = $env:NACOS_GROUP
    tenant   = $env:NACOS_NAMESPACE
    username = $env:NACOS_USERNAME
    password = $env:NACOS_PASSWORD
}
$yaml = Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/cs/configs" -Method Get -Body $params -TimeoutSec 15
$dbUrl = $null; $dbUser = $null; $dbPass = $null
if ($yaml -match 'url:\s*(jdbc:[^\s]+)') { $dbUrl = $Matches[1] }
if ($yaml -match '(?m)^\s*username:\s*(\S+)') { $dbUser = $Matches[1] }
if ($yaml -match '(?m)^\s*password:\s*(.+)$') { $dbPass = $Matches[1].Trim() }

$args = @(
    "-jar", (Join-Path $root "collection-admin\target\collection-admin.jar"),
    "--spring.datasource.url=$dbUrl",
    "--spring.datasource.username=$dbUser",
    "--spring.datasource.password=$dbPass"
)
Start-Process -FilePath java -ArgumentList $args -WorkingDirectory $root -RedirectStandardOutput $log -RedirectStandardError (Join-Path $root "collection-admin-run.err.log") -WindowStyle Hidden
