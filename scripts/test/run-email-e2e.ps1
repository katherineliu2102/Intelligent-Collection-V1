# Phase 1 Email E2E (5 emails, original caseIds)
# Usage: .\scripts\test\run-email-e2e.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

function Load-DotEnv {
    $envFile = Join-Path $root ".env"
    if (-not (Test-Path $envFile)) { throw "missing .env" }
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
        $p = $_ -split '=', 2
        if ($p.Count -eq 2) { Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim() }
    }
    if ($env:NACOS_SERVER_ADDR -match '^https?://') {
        $env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://', '' -replace '/nacos/?$','')
    }
}

function Get-DbEnvFromNacos {
    $params = @{
        dataId   = 'intelligent-collection-local.yml'
        group    = $env:NACOS_GROUP
        tenant   = $env:NACOS_NAMESPACE
        username = $env:NACOS_USERNAME
        password = $env:NACOS_PASSWORD
    }
    $yaml = Invoke-RestMethod -Uri "http://$($env:NACOS_SERVER_ADDR)/nacos/v1/cs/configs" -Method Get -Body $params -TimeoutSec 15
    if ($yaml -match 'url:\s*jdbc:mysql://([^:/]+):(\d+)/([^?]+)') {
        $script:DbHost = $Matches[1]; $script:DbPort = $Matches[2]; $script:DbName = $Matches[3]
    }
    if ($yaml -match '(?m)^\s*username:\s*(\S+)') { $script:DbUser = $Matches[1] }
    if ($yaml -match '(?m)^\s*password:\s*(.+)$') { $script:DbPass = $Matches[1].Trim() }
    if ($yaml -match 'url:\s*(jdbc:[^\s]+)') { $script:DbUrl = $Matches[1] }
}

function Test-Database {
    $env:DB_HOST = $DbHost; $env:DB_PORT = $DbPort; $env:DB_NAME = $DbName
    $env:DB_USER = $DbUser; $env:DB_PASS = $DbPass
    python (Join-Path $root "scripts\dev\db-ping.py")
    return ($LASTEXITCODE -eq 0)
}

function Ensure-App {
    $conn = Get-NetTCPConnection -LocalPort 8888 -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return }
    $jar = Join-Path $root "collection-admin\target\collection-admin.jar"
    if (-not (Test-Path $jar)) {
        mvn -pl collection-admin -am package -DskipTests
    }
    $javaArgs = @(
        '-jar', $jar,
        "--spring.datasource.url=$DbUrl",
        "--spring.datasource.username=$DbUser",
        "--spring.datasource.password=$DbPass"
    )
    Start-Process -FilePath java -ArgumentList $javaArgs -WorkingDirectory $root -WindowStyle Hidden | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        try {
            $null = Invoke-WebRequest -Uri 'http://localhost:8888/mock/ingest?caseId=90001&userId=90001&stage=S1' -Method Post -TimeoutSec 3
            return
        } catch {
            if ($_.Exception.Response.StatusCode.value__ -eq 500) { return }
        }
    }
    throw "app 8888 startup timeout"
}

Load-DotEnv
Get-DbEnvFromNacos

Write-Host "[e2e] checking ai_collection_db on ${DbHost}:${DbPort}/${DbName} ..."
if (-not (Test-Database)) {
    Write-Host ""
    Write-Host "DB still blocked (MySQL errno 1129). Ask DBA to run on the server:"
    Write-Host "  FLUSH HOSTS;"
    Write-Host "Your public IP:"
    try { Write-Host "  $(Invoke-RestMethod -Uri 'https://api.ipify.org' -TimeoutSec 5)" } catch { }
    exit 1
}

Write-Host "[e2e] DB OK, ensure app on 8888 ..."
Ensure-App

$cases = @(
    @{ id = 92002; stage = 'S0'; slot = 'S0_DUE_TODAY_EMAIL' },
    @{ id = 93101; stage = 'S1'; slot = 'S1_EMAIL_OVERDUE_NOTICE' },
    @{ id = 93201; stage = 'S2'; slot = 'S2_EMAIL_ENTRY' },
    @{ id = 93401; stage = 'S4'; slot = 'S4_EMAIL_ENTRY' },
    @{ id = 93404; stage = 'S4'; slot = 'S4_EMAIL_PRE_CLOSE' }
)

$results = @()
foreach ($c in $cases) {
    Write-Host "[e2e] ingest caseId=$($c.id) ..."
    $ingestUri = "http://localhost:8888/mock/ingest?caseId=$($c.id)&userId=$($c.id)&stage=$($c.stage)"
    Invoke-RestMethod -Method Post -Uri $ingestUri -TimeoutSec 30 | Out-Null
    Start-Sleep -Seconds 15
    $tlUri = "http://localhost:8888/plans/timeline/$($c.id)?limit=3"
    $tl = Invoke-RestMethod -Uri $tlUri -TimeoutSec 15
    $latest = $tl | Select-Object -First 1
    $ok = $latest.result -eq 'DELIVERED'
    $failMsg = "FAIL($($latest.result))"
    $results += [PSCustomObject]@{
        caseId = $c.id
        slot   = $c.slot
        result = if ($ok) { 'PASS' } else { $failMsg }
        msgId  = $latest.providerMsgId
    }
}

Write-Host ""
$results | Format-Table -AutoSize
$failed = @($results | Where-Object { $_.result -ne 'PASS' })
if ($failed.Count -gt 0) { exit 1 }
