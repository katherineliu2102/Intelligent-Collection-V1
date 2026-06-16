# 环境确认脚本（不启动常驻服务时可用于一次性冒烟）
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "=== 1. 加载 .env ==="
Get-Content ".env" | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $p = $_ -split '=', 2
    if ($p.Count -eq 2) { Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim() }
}
if ($env:NACOS_SERVER_ADDR -match '^https?://') {
    $env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://', '' -replace '/nacos/?$', '')
}
Write-Host "NACOS=$env:NACOS_SERVER_ADDR NS=$env:NACOS_NAMESPACE"

Write-Host "`n=== 2. Nacos 健康检查 ==="
Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/console/health/readiness" -TimeoutSec 10 | Out-Null
Write-Host "Nacos OK"

Write-Host "`n=== 3. 拉取 JDBC 配置 ==="
$params = @{
    dataId   = 'intelligent-collection-local.yml'
    group    = $env:NACOS_GROUP
    tenant   = $env:NACOS_NAMESPACE
    username = $env:NACOS_USERNAME
    password = $env:NACOS_PASSWORD
}
$yaml = Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/cs/configs" -Method Get -Body $params
if ($yaml -match 'url:\s*(jdbc:[^\s]+)') { $dbUrl = $Matches[1] }
if ($yaml -match '(?m)^\s*username:\s*(\S+)') { $dbUser = $Matches[1] }
if ($yaml -match '(?m)^\s*password:\s*(.+)$') { $dbPass = $Matches[1].Trim() }
Write-Host "JDBC URL OK (user=$dbUser)"

Write-Host "`n=== 3b. 渠道密钥（Nacos channel.*）==="
$hasSgKey = $yaml -match '(?m)^\s*api-key:\s*\S+' -and $yaml -match 'sendgrid:'
$hasNotifKey = $yaml -match '(?m)notification:\s*\n\s*app-key:\s*\S+'
if ($hasSgKey -and $hasNotifKey) {
    Write-Host "channel.sendgrid.api-key + channel.notification.app-key OK"
} else {
    Write-Warning "Nacos 缺少渠道密钥：请在控制台合并 nacos-publish.local.yml 的 channel 段，或运行 scripts/publish-channel-secrets-to-nacos.ps1"
}

Write-Host "`n=== 4. 启动应用（后台 90s 冒烟）==="
$jar = Join-Path $root "collection-admin\target\collection-admin.jar"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "java"
$psi.Arguments = "-jar `"$jar`" --spring.datasource.url=`"$dbUrl`" --spring.datasource.username=$dbUser --spring.datasource.password=`"$dbPass`""
$psi.WorkingDirectory = $root
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
Get-ChildItem Env: | ForEach-Object { $psi.EnvironmentVariables[$_.Name] = $_.Value }
$proc = [System.Diagnostics.Process]::Start($psi)

$deadline = (Get-Date).AddSeconds(60)
$up = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    try {
        $h = Invoke-RestMethod "http://localhost:8888/actuator/health" -TimeoutSec 2
        if ($h.status -eq 'UP') { $up = $true; break }
    } catch { }
}
if (-not $up) { Write-Error "App not ready within 60s" }

Write-Host "应用 UP"

Write-Host "`n=== 5. TC-PUSH-EMAIL（91001）==="
Invoke-RestMethod "http://localhost:8888/mock/ingest?caseId=91001&userId=91001&stage=S1" -Method POST | Out-Null
Start-Sleep -Seconds 90
$tl91001 = Invoke-RestMethod "http://localhost:8888/plans/timeline/91001?limit=10"
Write-Host "timeline_91001=$($tl91001.Count)"
if ($tl91001.Count -ge 2) {
    Write-Host "TC-PUSH-EMAIL PASS"
} else {
    Write-Warning "TC-PUSH-EMAIL FAIL timeline=$($tl91001.Count) expected>=2"
}

Write-Host "`n=== 6. TC-REG-01（legacy 三步步）==="
Invoke-RestMethod "http://localhost:8888/mock/ingest?caseId=91000&userId=91000&stage=S1" -Method POST | Out-Null
Start-Sleep -Seconds 90
$active = Invoke-RestMethod "http://localhost:8888/plans/active/by-case/91000"
$tl = Invoke-RestMethod "http://localhost:8888/plans/timeline/91000?limit=10"
Write-Host "active_plans=$($active.Count) timeline=$($tl.Count)"
if ($tl.Count -ge 3) {
    Write-Host "TC-REG-01 PASS (note: default plan is PUSH->EMAIL; use legacy-three-step=true for 3-step)"
} else {
    Write-Warning "TC-REG-01 timeline=$($tl.Count) (expected>=3 only with legacy-three-step=true)"
}

Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "=== DONE ==="
