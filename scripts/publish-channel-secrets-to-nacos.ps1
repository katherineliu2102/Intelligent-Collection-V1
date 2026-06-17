# 将渠道密钥发布到 Nacos intelligent-collection-local.yml（需 Nacos 写权限）
# 用法：
#   1. 在 deploy/nacos/nacos-publish.local.yml 填写 channel.sendgrid / channel.notification 密钥
#   2. .\scripts\publish-channel-secrets-to-nacos.ps1
# 或从环境变量注入（CI/运维）：
#   $env:SENDGRID_API_KEY=...; $env:SENDGRID_FROM_EMAIL=...; $env:NOTIFICATION_APP_KEY=...; .\scripts\publish-channel-secrets-to-nacos.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Get-Content ".env" | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $p = $_ -split '=', 2
    if ($p.Count -eq 2) { Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim() }
}
if ($env:NACOS_SERVER_ADDR -match '^https?://') {
    $env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://', '' -replace '/nacos/?$', '')
}

$sgKey = $env:SENDGRID_API_KEY
$sgFrom = if ($env:SENDGRID_FROM_EMAIL) { $env:SENDGRID_FROM_EMAIL } else { "collections@mocasa.com" }
$notifKey = $env:NOTIFICATION_APP_KEY

$patchFile = Join-Path $root "deploy/nacos/nacos-publish.local.yml"
if ((-not $sgKey -or -not $notifKey) -and (Test-Path $patchFile)) {
    $patch = Get-Content $patchFile -Raw
    if ($patch -match '(?m)^\s*api-key:\s*(\S+)') { if (-not $sgKey) { $sgKey = $Matches[1] } }
    if ($patch -match '(?m)^\s*from-email:\s*(\S+)') { if (-not $sgFrom) { $sgFrom = $Matches[1] } }
    if ($patch -match '(?m)notification:\s*\n\s*app-key:\s*(\S+)') { if (-not $notifKey) { $notifKey = $Matches[1] } }
}

if (-not $sgKey -or -not $notifKey) {
    Write-Error "缺少密钥：设置环境变量 SENDGRID_API_KEY / NOTIFICATION_APP_KEY，或填写 deploy/nacos/nacos-publish.local.yml"
}

$params = @{
    dataId   = 'intelligent-collection-local.yml'
    group    = $env:NACOS_GROUP
    tenant   = $env:NACOS_NAMESPACE
    username = $env:NACOS_USERNAME
    password = $env:NACOS_PASSWORD
}
$existing = Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/cs/configs" -Method Get -Body $params -TimeoutSec 15

# 去掉已有 channel 段（简单替换：若已存在则整段替换）
$base = $existing -replace '(?s)\nchannel:.*$', ''
$channelBlock = @"

channel:
  sendgrid:
    api-key: $sgKey
    from-email: $sgFrom
  notification:
    app-key: $notifKey
"@
$newContent = $base.TrimEnd() + "`n" + $channelBlock

$publishBody = @{
    dataId   = 'intelligent-collection-local.yml'
    group    = $env:NACOS_GROUP
    tenant   = $env:NACOS_NAMESPACE
    username = $env:NACOS_USERNAME
    password = $env:NACOS_PASSWORD
    type     = 'yaml'
    content  = $newContent
}
$result = Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/cs/configs" -Method Post -Body $publishBody -TimeoutSec 15
Write-Host "Nacos publish: $result"
Write-Host "已写入 channel.sendgrid.* / channel.notification.app-key → intelligent-collection-local.yml"
