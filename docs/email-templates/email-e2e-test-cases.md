# Email 端到端联调案例（方式 C）

> **收件箱**：全部使用 **`wzynju@126.com`**  
> **配置**：`application-local.yml` → `channel.sendgrid.templates.{scriptSlot}`（无 `default-template-id` 兜底）  
> **前置**：`channel.debug.single-step: EMAIL` · `.env` 填 `SENDGRID_API_KEY` / `SENDGRID_FROM_EMAIL`

## 测试案例表

| caseId | 别名 | scriptSlot | dpd | amount_due | due_date | 说明 |
|--------|------|------------|-----|------------|----------|------|
| 92001 | test_s0_user1 | `S0_DUE_TODAY_EMAIL` | 0 | 5000.00 | 今天 | S0 到期 |
| 92002 | test_s0_user2 | `S0_DUE_TODAY_EMAIL` | 0 | 5000.00 | 今天 | 重复进案推荐 |
| 93101 | test_s1_user1 | `S1_EMAIL_OVERDUE_NOTICE` | 2 | 2500.00 | 2026-06-06 | S1 逾期通知 |
| 93102 | test_s1_user2 | `S1_EMAIL_STAGE_WARNING` | 3 | 2500.00 | 2026-06-05 | S1 升级预警 |
| 93201 | test_s2_user1 | `S2_EMAIL_ENTRY` | 4 | 4000.00 | 2026-06-01 | S2 入催 |
| 93202 | test_s2_user2 | `S2_EMAIL_MID` | 7 | 4000.00 | 2026-06-01 | S2 中期 |
| 93203 | test_s2_user3 | `S2_EMAIL_PRE_S3` | 12 | 4000.00 | 2026-05-27 | S2 进 S3 前 |
| 93301 | test_s3_user1 | `S3_EMAIL_ENTRY` | 16 | 5000.00 | 2026-05-23 | S3 法务审核 |
| 93302 | test_s3_user2 | `S3_EMAIL_MID` | 23 | 5000.00 | 2026-05-16 | S3 征信警告 |
| 93303 | test_s3_user3 | `S3_EMAIL_PRE_S4` | 30 | 5000.00 | 2026-05-09 | S3 进 S4 前 |
| 93401 | test_s4_user1 | `S4_EMAIL_ENTRY` | 31 | 5000.00 | 2026-05-08 | S4 正式催收 |
| 93402 | test_s4_user2 | `S4_EMAIL_FINAL_REMINDER` | 45 | 5000.00 | 2026-04-24 | S4 中期催告 |
| 93403 | test_s4_user3 | `S4_EMAIL_MID` | 60 | 5000.00 | 2026-04-09 | S4 严重逾期 |
| 93404 | test_s4_user4 | `S4_EMAIL_PRE_CLOSE` | 75 | 5000.00 | 2026-03-25 | S4 最终审查（含 `assignment_date`） |

`assignment_date` = `due_date + 91`（英文格式，如 `June 24, 2026`）。

## 单封联调步骤

1. 确认 `application-local.yml` 中该 scriptSlot 的 `d-xxx` 已填  
2. 重启 `collection-admin`（8888）  
3. 进案（**caseId = userId**，stage 与上表一致）：

```bash
curl -X POST "http://localhost:8888/mock/ingest?caseId=93101&userId=93101&stage=S1"
```

4. 等 10–15s，查 timeline：

```bash
curl -s "http://localhost:8888/plans/timeline/93101?limit=5"
```

5. 验收：`wzynju@126.com` 收到对应模板；timeline `EMAIL` + `DELIVERED`；日志 `scriptSlot=S1_EMAIL_OVERDUE_NOTICE`

**重复测试**：每个 scriptSlot 用不同 caseId，或先换 caseId 再 ingest（避免 plan 冲突）。

## 批量脚本（PowerShell）

```powershell
$cases = @(
  @{ id=92002; stage="S0" },
  @{ id=93101; stage="S1" },
  @{ id=93102; stage="S1" },
  @{ id=93201; stage="S2" },
  @{ id=93202; stage="S2" },
  @{ id=93203; stage="S2" },
  @{ id=93301; stage="S3" },
  @{ id=93302; stage="S3" },
  @{ id=93303; stage="S3" },
  @{ id=93401; stage="S4" },
  @{ id=93402; stage="S4" },
  @{ id=93403; stage="S4" },
  @{ id=93404; stage="S4" }
)
foreach ($c in $cases) {
  Invoke-RestMethod -Method Post -Uri "http://localhost:8888/mock/ingest?caseId=$($c.id)&userId=$($c.id)&stage=$($c.stage)"
  Start-Sleep -Seconds 15
}
```

## 代码位置

| 组件 | 文件 |
|------|------|
| 案例注册 | `collection-service/.../MockEmailTestCases.java` |
| 案件变量 | `collection-service/.../MockCaseService.java` |
| 邮箱 / 姓名 | `collection-service/.../MockProfileService.java` |
| scriptSlot + dynamicTemplateData | `collection-channel/.../MockStepResolver.java` |
| template 映射 | `SendGridEmailAdapter` ← `channel.sendgrid.templates` |

## SendGrid ID SSOT

见 [`subjects.md`](./subjects.md) SendGrid ID 列。
