# 本地启动 collection-admin（PowerShell）
# 用法：在项目根目录执行  .\scripts\dev\start-local.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) {
    Write-Error "缺少 .env，请复制 .env.example 并填写 Nacos 账号"
}

# ⚠ 必须显式 -Encoding UTF8：Windows PowerShell 5.1 的 Get-Content 默认按 ANSI/GBK 解码，
#   UTF-8 中文注释的字节会被错位识别，导致注释行与其后的配置行被合并成一行（实测 13 行读成 11 行），
#   合并后整行以 '#' 开头 → 被当成注释跳过 → 变量静默失效。
#   2026-09-02 实证：COLLECTION_SCAN_CASE_IDS 因此从未注入，ScanIsolationGuard 拒绝启动。
$loadedCount = 0
Get-Content -LiteralPath $envFile -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $p = $_ -split '=', 2
    if ($p.Count -ne 2) { return }
    $key = $p[0].Trim()
    $val = $p[1].Trim()
    # 剥离成对引号（.env 常见写法 KEY="value"），不做其它转义处理
    if (($val.StartsWith('"') -and $val.EndsWith('"')) -or ($val.StartsWith("'") -and $val.EndsWith("'"))) {
        if ($val.Length -ge 2) { $val = $val.Substring(1, $val.Length - 2) }
    }
    Set-Item -Path "Env:$key" -Value $val
    $loadedCount++
}
Write-Host "[start-local] 已加载 .env 变量 $loadedCount 个"

# 扫描隔离闸门兜底：local/test profile 下 ScanIsolationGuard 强制要求非空名单。
# 只「看后台」不跑扫描时用占位值（零触达）；真实联调请在 .env 覆盖为本轮批准的 case_id（逗号分隔）。
if (-not $env:COLLECTION_SCAN_CASE_IDS) {
    $env:COLLECTION_SCAN_CASE_IDS = "999999999"
    Write-Host "[start-local] 未配置 COLLECTION_SCAN_CASE_IDS -> 回退占位值 999999999（零触达）"
}
Write-Host "[start-local] scan whitelist = $($env:COLLECTION_SCAN_CASE_IDS)"

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

# ⚠ 必须强制端口。Nacos intelligent-collection-common.yml 下发了 server.port=56384，
#   且 Nacos ConfigData 的优先级高于 application-local.yml（后者写的 8888 会被覆盖）。
#   56384 与 WorkBuddy / Cursor 等 IDE 的服务代理端口冲突，本机必然 bind 失败
#   （2026-09-02 实测：Tomcat initialized with port(s): 56384 → PortInUseException）。
#   命令行参数优先级最高，故在此硬性覆盖；需要换端口时用 LOCAL_ADMIN_PORT。
$port = if ($env:LOCAL_ADMIN_PORT) { $env:LOCAL_ADMIN_PORT } else { "8888" }
Write-Host "[start-local] http://localhost:$port"
java -jar $jar @dbArgs "--server.port=$port"
