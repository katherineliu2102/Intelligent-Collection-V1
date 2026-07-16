$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$envFile = Join-Path $root ".env"
Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') { return }
    $p = $_ -split '=', 2
    if ($p.Count -eq 2) { Set-Item -Path "Env:$($p[0].Trim())" -Value $p[1].Trim() }
}
if ($env:NACOS_SERVER_ADDR -match '^https?://') {
    $env:NACOS_SERVER_ADDR = ($env:NACOS_SERVER_ADDR -replace '^https?://', '' -replace '/nacos/?$', '')
}

$log = Join-Path $root "admin-sms-prod.log"
$jar = Join-Path $root "collection-admin\target\collection-admin.jar"
$args = @("-jar", $jar, "--channel.notification.sms-test-mode=false")

Start-Process -FilePath "java" -ArgumentList $args -WorkingDirectory $root `
    -RedirectStandardOutput $log -RedirectStandardError "${log}.err" -WindowStyle Hidden
