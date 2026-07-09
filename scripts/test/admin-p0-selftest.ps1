param(
  [string]$BaseUrl = "http://localhost:8888",
  [int]$CaseId = 92002
)

$ErrorActionPreference = "Stop"

function Add-Result {
  param(
    [string]$Id,
    [string]$Name,
    [string]$Expected,
    [string]$Actual,
    [bool]$Passed
  )
  $script:Results += [PSCustomObject]@{
    Id       = $Id
    Name     = $Name
    Expected = $Expected
    Actual   = $Actual
    Result   = if ($Passed) { "PASS" } else { "FAIL" }
  }
}

function To-JsonBody {
  param([object]$Obj)
  return ($Obj | ConvertTo-Json -Depth 10)
}

function Invoke-Api {
  param(
    [string]$Method,
    [string]$Path,
    [object]$Body = $null,
    [Microsoft.PowerShell.Commands.WebRequestSession]$Session = $null
  )
  $args = @{
    Uri         = "$BaseUrl$Path"
    Method      = $Method
    ContentType = "application/json"
  }
  if ($Session -ne $null) { $args.WebSession = $Session }
  if ($Body -ne $null) { $args.Body = (To-JsonBody $Body) }
  return Invoke-RestMethod @args
}

function Try-Invoke {
  param([scriptblock]$Action)
  try {
    $resp = & $Action
    return @{ ok = $true; resp = $resp; err = $null }
  } catch {
    return @{ ok = $false; resp = $null; err = $_ }
  }
}

$Results = @()
$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$SeedExceptionId = $null

Write-Host "[admin-p0-selftest] BaseUrl=$BaseUrl CaseId=$CaseId"

$health = Try-Invoke { Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method GET }
if (-not $health.ok) {
  throw "Service not reachable: $BaseUrl"
}

$r1 = Try-Invoke { Invoke-Api -Method "GET" -Path "/admin/me" }
$r1Passed = $false
$r1Actual = "unexpected success"
if (-not $r1.ok) {
  $status = $r1.err.Exception.Response.StatusCode.value__
  $r1Passed = ($status -eq 401)
  $r1Actual = "HTTP $status"
}
Add-Result -Id "ADM-P0-001" -Name "Unauth guard" -Expected "HTTP 401" -Actual $r1Actual -Passed $r1Passed

$r2 = Try-Invoke {
  Invoke-Api -Method "POST" -Path "/auth/login" -Session $Session -Body @{
    username = "admin"
    role     = "SYSTEM_ADMIN"
  }
}
$r2Passed = $r2.ok -and $r2.resp.success -eq $true
$r2Actual = "failed"
if ($r2Passed) { $r2Actual = "ok" }
Add-Result -Id "ADM-P0-002" -Name "Login" -Expected "success=true" -Actual $r2Actual -Passed $r2Passed

$r3 = Try-Invoke { Invoke-Api -Method "GET" -Path "/admin/me" -Session $Session }
$r3Passed = $r3.ok -and $r3.resp.success -eq $true
$r3Actual = "failed"
if ($r3Passed) { $r3Actual = "$($r3.resp.data.username)" }
Add-Result -Id "ADM-P0-003" -Name "Session me" -Expected "user returned" -Actual $r3Actual -Passed $r3Passed

$seedCase = Try-Invoke { Invoke-Api -Method "POST" -Path "/mock/admin/seed-case" -Body @{ caseId = $CaseId; userId = $CaseId } }
if (-not $seedCase.ok) {
  Write-Host "[admin-p0-selftest] warn: seed-case failed, case search may fail"
}

$r4 = Try-Invoke { Invoke-Api -Method "GET" -Path "/cases/search?page=1&pageSize=20" -Session $Session }
$r4Count = if ($r4.ok -and $r4.resp.success -eq $true) { ($r4.resp.data.items | Measure-Object).Count } else { 0 }
$r4Passed = $r4.ok -and $r4.resp.success -eq $true -and $r4Count -gt 0
Add-Result -Id "ADM-P0-004" -Name "Case search" -Expected "paged data" -Actual ("items=$r4Count") -Passed $r4Passed

$r5 = Try-Invoke { Invoke-Api -Method "POST" -Path "/compliance/freeze" -Session $Session -Body @{ caseId = $CaseId; userId = $CaseId; reason = "selftest freeze" } }
$r5Passed = $r5.ok -and $r5.resp.success -eq $true -and $r5.resp.data.status -eq "FROZEN"
$r5Actual = "failed"
if ($r5.ok) { $r5Actual = "$($r5.resp.data.status)" }
Add-Result -Id "ADM-P0-005" -Name "Freeze" -Expected "FROZEN" -Actual $r5Actual -Passed $r5Passed

$r6 = Try-Invoke { Invoke-Api -Method "POST" -Path "/compliance/unfreeze" -Session $Session -Body @{ caseId = $CaseId; reason = "selftest unfreeze" } }
$r6Passed = $r6.ok -and $r6.resp.success -eq $true -and $r6.resp.data.status -eq "RELEASED"
$r6Actual = "failed"
if ($r6.ok) { $r6Actual = "$($r6.resp.data.status)" }
Add-Result -Id "ADM-P0-006" -Name "Unfreeze" -Expected "RELEASED" -Actual $r6Actual -Passed $r6Passed

$r7 = Try-Invoke { Invoke-Api -Method "POST" -Path "/compliance/escalate" -Session $Session -Body @{ caseId = $CaseId; reason = "selftest escalate" } }
$r7Passed = $r7.ok -and $r7.resp.success -eq $true -and $r7.resp.data.status -eq "ESCALATED"
$r7Actual = "failed"
if ($r7.ok) { $r7Actual = "$($r7.resp.data.status)" }
Add-Result -Id "ADM-P0-007" -Name "Escalate" -Expected "ESCALATED" -Actual $r7Actual -Passed $r7Passed

$seed = Try-Invoke { Invoke-Api -Method "POST" -Path "/mock/admin/seed-exception" -Body @{ caseId = $CaseId; type = "CALLBACK_TIMEOUT"; channel = "SMS"; errorCode = "TIMEOUT"; message = "seed by selftest" } }
if ($seed.ok -and $seed.resp.success -eq $true) {
  $SeedExceptionId = [int]$seed.resp.data.id
}

$r8 = Try-Invoke { Invoke-Api -Method "GET" -Path "/ops/exceptions?status=OPEN&page=1&pageSize=20" -Session $Session }
$r8Passed = $r8.ok -and $r8.resp.success -eq $true
$items = @()
if ($r8Passed) { $items = $r8.resp.data.items }
Add-Result -Id "ADM-P0-008" -Name "Ops list OPEN" -Expected "paged data" -Actual ("items=" + (($items | Measure-Object).Count)) -Passed $r8Passed

$targetId = $SeedExceptionId
if (-not $targetId -and $items.Count -gt 0) {
  $targetId = [int]$items[0].id
}

$r9Passed = $false
$r9Actual = "no target"
if ($targetId) {
  $r9 = Try-Invoke { Invoke-Api -Method "POST" -Path "/ops/exceptions/$targetId/ack" -Session $Session }
  $r9Passed = $r9.ok -and $r9.resp.success -eq $true
  $r9Actual = if ($r9Passed) { "acked id=$targetId" } else { "failed" }
}
Add-Result -Id "ADM-P0-009" -Name "Ops ACK" -Expected "ACK success" -Actual $r9Actual -Passed $r9Passed

$r10Passed = $false
$r10Actual = "no target"
if ($targetId) {
  $r10 = Try-Invoke { Invoke-Api -Method "POST" -Path "/ops/exceptions/$targetId/resolve" -Session $Session -Body @{ action = "MANUAL_FIXED"; note = "selftest resolve" } }
  $r10Passed = $r10.ok -and $r10.resp.success -eq $true
  $r10Actual = if ($r10Passed) { "resolved id=$targetId" } else { "failed" }
}
Add-Result -Id "ADM-P0-010" -Name "Ops resolve" -Expected "RESOLVED success" -Actual $r10Actual -Passed $r10Passed

$r11 = Try-Invoke { Invoke-Api -Method "GET" -Path "/admin/audit-logs?page=1&pageSize=20" -Session $Session }
$r11Passed = $r11.ok -and $r11.resp.success -eq $true
$r11Count = if ($r11Passed) { ($r11.resp.data.items | Measure-Object).Count } else { 0 }
Add-Result -Id "ADM-P0-011" -Name "Audit logs" -Expected "paged data" -Actual ("logs=$r11Count") -Passed $r11Passed

$passCount = ($Results | Where-Object { $_.Result -eq "PASS" } | Measure-Object).Count
$totalCount = ($Results | Measure-Object).Count
$overall = if ($passCount -eq $totalCount) { "PASS" } else { "FAIL" }
Write-Host "[admin-p0-selftest] summary: $passCount/$totalCount PASS"
if ($overall -ne "PASS") {
  Write-Host "[admin-p0-selftest] failed cases:"
  $Results | Where-Object { $_.Result -eq "FAIL" } | ForEach-Object {
    Write-Host (" - {0}: {1} ({2})" -f $_.Id, $_.Name, $_.Actual)
  }
}

$reportPath = Join-Path $PSScriptRoot "..\..\docs\testing\admin-p0-test.md"
$reportPath = [System.IO.Path]::GetFullPath($reportPath)
$runAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

$lines = @()
$lines += "# MOCASA Collection Admin P0 Test Doc"
$lines += ""
$lines += "> Version: v1.0"
$lines += "> Mode: single document (cases + run guide + latest results)"
$lines += '> Script: `scripts/test/admin-p0-selftest.ps1`'
$lines += ""
$lines += "## 1. Scope"
$lines += ""
$lines += '- Auth: `/auth/login`, `/admin/me`'
$lines += '- Case query: `/cases/search`'
$lines += '- Compliance: `/compliance/freeze|unfreeze|escalate`'
$lines += '- Ops queue: `/ops/exceptions`, `/ops/exceptions/{id}/ack|resolve`'
$lines += '- Audit logs: `/admin/audit-logs`'
$lines += ""
$lines += "## 2. Test Cases"
$lines += ""
$lines += "| Case ID | Scenario | Expected |"
$lines += "|---|---|---|"
$lines += "| ADM-P0-001 | Unauth guard | HTTP 401 |"
$lines += "| ADM-P0-002 | Login | success=true |"
$lines += "| ADM-P0-003 | Session me | current user returned |"
$lines += "| ADM-P0-004 | Case search | paged data |"
$lines += "| ADM-P0-005 | Freeze | status=FROZEN |"
$lines += "| ADM-P0-006 | Unfreeze | status=RELEASED |"
$lines += "| ADM-P0-007 | Escalate | status=ESCALATED |"
$lines += "| ADM-P0-008 | Ops list | paged data |"
$lines += "| ADM-P0-009 | Ops ACK | status updated |"
$lines += "| ADM-P0-010 | Ops resolve | status updated |"
$lines += "| ADM-P0-011 | Audit logs | paged data |"
$lines += ""
$lines += "## 3. Run Guide"
$lines += ""
$lines += '```powershell'
$lines += "cd Intelligent-Collection-V1"
$lines += "powershell -ExecutionPolicy Bypass -File scripts/test/admin-p0-selftest.ps1"
$lines += '```'
$lines += ""
$lines += "Prerequisites:"
$lines += ""
$lines += '- `collection-admin` is running (default `http://localhost:8888`)'
$lines += '- Database has `db/schema.sql` and `db/schema-admin.sql`'
$lines += ""
$lines += "## 4. Latest Run Result"
$lines += ""
$lines += "> Run At: $runAt"
$lines += "> Base URL: $BaseUrl"
$lines += "> CaseId: $CaseId"
$lines += "> Overall: **$overall** ($passCount/$totalCount PASS)"
$lines += ""
$lines += "| Case ID | Scenario | Expected | Actual | Result |"
$lines += "|---|---|---|---|---|"
foreach ($r in $Results) {
  $lines += "| $($r.Id) | $($r.Name) | $($r.Expected) | $($r.Actual) | $($r.Result) |"
}
$lines += ""
$lines += "## 5. Notes"
$lines += ""
$lines += "- If ADM-P0-008~010 fails, check `schema-admin.sql` and `/mock/admin/seed-exception`."
$lines += "- If auth cases fail, check session interceptor and `/auth/login`."

$dir = Split-Path $reportPath -Parent
if (-not (Test-Path $dir)) {
  New-Item -ItemType Directory -Path $dir | Out-Null
}
$lines | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "[admin-p0-selftest] report generated: $reportPath"

