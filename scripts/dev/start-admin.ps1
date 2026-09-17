# Start MOCASA Admin (backend :8888 + frontend :5173)
# Usage from repo root:
#   powershell -ExecutionPolicy Bypass -File scripts\dev\start-admin.ps1

param(
    [switch]$OpenBrowser = $true
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$npm = "C:\Program Files\nodejs\npm.cmd"
if (-not (Test-Path $npm)) {
    $npmCmd = Get-Command npm -ErrorAction SilentlyContinue
    if ($npmCmd) { $npm = $npmCmd.Source }
}
if (-not $npm) {
    Write-Error "npm not found. Install Node.js from https://nodejs.org/"
}

function Test-PortUp([int]$Port) {
    try {
        $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        return $null -ne $conn
    } catch {
        return $false
    }
}

function Wait-HttpOk([string]$Url, [int]$Seconds = 60) {
    for ($i = 0; $i -lt $Seconds; $i += 2) {
        try {
            $r = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            # 本机无 Redis 时 /actuator/health 返回 503(DOWN)，但应用已可用，视为就绪
            if ($r.StatusCode -eq 200 -or $r.StatusCode -eq 503) { return $true }
        } catch [System.Net.WebException] {
            # 503/5xx 会抛 WebException，但 Response.StatusCode 仍可读
            if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 503) { return $true }
        } catch {}
        Start-Sleep -Seconds 2
    }
    return $false
}

if (Test-PortUp 8888) {
    Write-Host "[start-admin] backend already on :8888"
} else {
    Write-Host "[start-admin] starting backend..."
    Start-Process powershell -ArgumentList @(
        "-NoExit", "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $root "scripts\dev\start-local.ps1")
    ) -WorkingDirectory $root | Out-Null
    if (-not (Wait-HttpOk "http://localhost:8888/actuator/health")) {
        Write-Error "backend startup timeout - check the new PowerShell window"
    }
    Write-Host "[start-admin] backend ready :8888"
}

$uiDir = Join-Path $root "collection-admin\ui"
if (Test-PortUp 5173) {
    Write-Host "[start-admin] frontend already on :5173"
} else {
    if (-not (Test-Path (Join-Path $uiDir "node_modules"))) {
        Write-Host "[start-admin] first run - npm install..."
        Push-Location $uiDir
        & $npm install
        Pop-Location
    }
    Write-Host "[start-admin] starting frontend..."
    Start-Process powershell -ArgumentList @(
        "-NoExit", "-ExecutionPolicy", "Bypass",
        "-Command", "Set-Location '$uiDir'; & '$npm' run dev -- --host 127.0.0.1"
    ) -WorkingDirectory $uiDir | Out-Null
    if (-not (Wait-HttpOk "http://127.0.0.1:5173")) {
        Write-Error "frontend startup timeout - check the new PowerShell window"
    }
    Write-Host "[start-admin] frontend ready :5173"
}

$url = "http://127.0.0.1:5173/"
Write-Host ""
Write-Host "Admin UI: $url"
Write-Host "Login: admin / local-dev"
Write-Host "Do NOT open :8888 in browser (API only)"

if ($OpenBrowser) {
    Start-Process $url
}
