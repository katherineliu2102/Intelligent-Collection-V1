# 本地启动 collection-admin（PowerShell）
# 用法：在项目根目录执行  .\scripts\start-local.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) {
    Write-Error "缺少 .env，请复制 .env.example 并填写 Nacos 账号"
}

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $p = $_ -split '=', 2
    if ($p.Count -eq 2) {
        Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim()
    }
}

# Nacos server-addr 必须是 host:port，不能带 http:// 或 /nacos
if ($env:NACOS_SERVER_ADDR -match '^https?://') {
    $env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://', '' -replace '/nacos/?$', '')
    Write-Host "[start-local] 已修正 NACOS_SERVER_ADDR -> $($env:NACOS_SERVER_ADDR)"
}

$jar = Join-Path $root "collection-admin\target\collection-admin.jar"
if (-not (Test-Path $jar)) {
    Write-Host "[start-local] 未找到 jar，正在编译..."
    mvn -pl collection-admin -am clean package -DskipTests
}

# 从 Nacos 拉取 JDBC（intelligent-collection-local.yml）；ConfigData 未加载时作为 CLI 回退
$dbArgs = @()
try {
    $params = @{
        dataId   = 'intelligent-collection-local.yml'
        group    = $env:NACOS_GROUP
        tenant   = $env:NACOS_NAMESPACE
        username = $env:NACOS_USERNAME
        password = $env:NACOS_PASSWORD
    }
    $yaml = Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/cs/configs" -Method Get -Body $params -TimeoutSec 15
    if ($yaml -match 'url:\s*(jdbc:[^\s]+)') { $dbUrl = $Matches[1] }
    if ($yaml -match '(?m)^\s*username:\s*(\S+)') { $dbUser = $Matches[1] }
    if ($yaml -match '(?m)^\s*password:\s*(.+)$') { $dbPass = $Matches[1].Trim() }
    if ($dbUrl -and $dbUser -and $dbPass) {
        $dbArgs = @(
            "--spring.datasource.url=$dbUrl",
            "--spring.datasource.username=$dbUser",
            "--spring.datasource.password=$dbPass"
        )
        Write-Host "[start-local] JDBC from Nacos"
    }
} catch {
    Write-Warning "[start-local] Nacos JDBC fetch failed"
}

Write-Host "[start-local] http://localhost:8888"
java -jar $jar @dbArgs
