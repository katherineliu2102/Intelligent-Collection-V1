# Email 端到端联调案例

> **收件箱**：`wzynju@126.com`  
> **发件人**：`collections@mocasa.com`  
> **配置**：`application-local.yml` → `channel.sendgrid.templates`（Phase 1 仅 5 个 scriptSlot）  
> **DB 注册表**：`db/seed/email-e2e-test-cases.sql` → `t_email_e2e_registry`（14 case，5 个 `phase1_active=1`）  
> **前置**：`channel.debug.single-step: EMAIL`；`ai_collection_db` 可连

## 全量注册表（MockEmailTestCases · 14 case）

| caseId | 别名 | scriptSlot | dpd | amount_due | due_date |
|--------|------|------------|-----|------------|----------|
| 92001 | test_s0_user1 | `S0_DUE_TODAY_EMAIL` | 0 | 5000.00 | 今天 |
| 92002 | test_s0_user2 | `S0_DUE_TODAY_EMAIL` | 0 | 5000.00 | 今天 |
| 93101 | test_s1_user1 | `S1_EMAIL_OVERDUE_NOTICE` | 2 | 2500.00 | 2026-06-06 |
| 93102 | test_s1_user2 | `S1_EMAIL_STAGE_WARNING` | 3 | 2500.00 | 2026-06-05 |
| 93201 | test_s2_user1 | `S2_EMAIL_ENTRY` | 4 | 4000.00 | 2026-06-01 |
| 93202 | test_s2_user2 | `S2_EMAIL_MID` | 7 | 4000.00 | 2026-06-01 |
| 93203 | test_s2_user3 | `S2_EMAIL_PRE_S3` | 12 | 4000.00 | 2026-05-27 |
| 93301 | test_s3_user1 | `S3_EMAIL_ENTRY` | 16 | 5000.00 | 2026-05-23 |
| 93302 | test_s3_user2 | `S3_EMAIL_MID` | 23 | 5000.00 | 2026-05-16 |
| 93303 | test_s3_user3 | `S3_EMAIL_PRE_S4` | 30 | 5000.00 | 2026-05-09 |
| 93401 | test_s4_user1 | `S4_EMAIL_ENTRY` | 31 | 5000.00 | 2026-05-08 |
| 93402 | test_s4_user2 | `S4_EMAIL_FINAL_REMINDER` | 45 | 5000.00 | 2026-04-24 |
| 93403 | test_s4_user3 | `S4_EMAIL_MID` | 60 | 5000.00 | 2026-04-09 |
| 93404 | test_s4_user4 | `S4_EMAIL_PRE_CLOSE` | 75 | 5000.00 | 2026-03-25 |

> **92002** 推荐重复进案（避免 92001 历史 plan 冲突）。  
> **93102–93403** 等备用 scriptSlot 在 Phase 1 无 SendGrid 映射，ingest 会 `SENDGRID_NO_TEMPLATE`。

## Phase 1 发信联调（沿用原 caseId · 5 封）

| caseId | scriptSlot |
|--------|------------|
| **92002** | `S0_DUE_TODAY_EMAIL` |
| **93101** | `S1_EMAIL_OVERDUE_NOTICE` |
| **93201** | `S2_EMAIL_ENTRY` |
| **93401** | `S4_EMAIL_ENTRY` |
| **93404** | `S4_EMAIL_PRE_CLOSE` |

`assignment_date`（93404）= `due_date + 91`。

## 单封

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8888/mock/ingest?caseId=92002&userId=92002&stage=S0"
Start-Sleep -Seconds 15
Invoke-RestMethod -Uri "http://localhost:8888/plans/timeline/92002?limit=3"
```

## 批量（Phase 1 · 5 封）

```powershell
$cases = @(
  @{ id=92002; stage='S0' },
  @{ id=93101; stage='S1' },
  @{ id=93201; stage='S2' },
  @{ id=93401; stage='S4' },
  @{ id=93404; stage='S4' }
)
foreach ($c in $cases) {
  Invoke-RestMethod -Method Post -Uri "http://localhost:8888/mock/ingest?caseId=$($c.id)&userId=$($c.id)&stage=$($c.stage)"
  Start-Sleep -Seconds 15
}
```

SendGrid ID 见 [`subjects.md`](./subjects.md)。
