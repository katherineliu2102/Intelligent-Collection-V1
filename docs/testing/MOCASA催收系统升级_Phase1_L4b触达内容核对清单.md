# MOCASA 催收系统升级 Phase 1 — L4b 触达内容核对清单

> **用途**：供测试同事核对 L4b 端到端联调后，手机 / App / 邮箱是否收到预期触达。  
> **关联**：[`L4b测试报告_20260707`](./MOCASA催收系统升级_Phase1_L4b测试报告_20260707.md) · [`L4b环境交接清单`](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) · [`db/l4b-assert.sql`](../db/l4b-assert.sql)

- **测试执行日期**：2026-07-07（UTC+8）
- **触达地址（全案统一）**
  - 手机：`+639451374358`
  - 邮箱：`wzynju@126.com`
  - Push：jpushToken `1a0018970bf0c19de04`（MOCASA App 需已绑定该 token 的设备）
- **主流程发送时间窗口**：约 **19:32–19:36**；mock 补充用例约 **19:45–19:47**

---

## 1. 主流程汇总（L4b-1 PubSub 入案）

| 渠道 | 应收条数 | 说明 |
|------|----------|------|
| **SMS** | **6 条** | 99000000×1 + 99000001×1 + 99000002×2 + 99000004×1 + 99000005×1 |
| **Push** | **3 条** | 99000001×1 + 99000002×1 + 99000004×1 |
| **Email** | **0 封** | 全部 SKIPPED（见 §4） |

> **99000003**：PubSub 未投递成功（共享订阅分流），主流程**不应收到**任何触达。

---

## 2. 按 loan_id 逐条核对（主流程）

文案来源：`application-local.yml` → `channel.scripts`（Phase 1 占位话术，非正式版）。  
金额格式：`%,.2f`（如 `5,100.00`）；姓名、dpd、欠款来自旧库 + case_push payload。

### 2.1 99000000 · S0 · dpd=0 · ₱5,100.00 · `Test Case S0`

| # | 渠道 | 状态 | scriptSlot | 应收正文 |
|---|------|------|------------|----------|
| 1 | SMS | ✅ 应收 | `S0_DUE_TODAY` | `MOCASA: Test Case S0, your PHP 5,100.00 payment is due today. Please pay now to stay current.` |

S0 计划仅 1 步 SMS，无 Push / Email。

---

### 2.2 99000001 · S1 · dpd=2 · ₱5,250.00 · `Test Case S1`

| # | 渠道 | 状态 | scriptSlot | 应收正文 |
|---|------|------|------------|----------|
| 1 | SMS | ✅ 应收 | `S1_SMS_STANDARD` | `MOCASA Collections: Test Case S1, your account is 2 day(s) overdue. Please settle PHP 5,250.00 promptly.` |
| 2 | Push | ✅ 应收 | `S1_PUSH_STANDARD` | **标题**：`Overdue: PHP 5,250.00`<br>**正文**：`Test Case S1, your payment is past due. Tap to settle in the app.` |
| 3 | Email | ❌ 不发 | — | dpd=2，Email 里程碑仅在 **dpd=1** 触发 → SKIPPED |

---

### 2.3 99000002 · S2 · dpd=7 · ₱8,560.00 · `Test Case S2`

| # | 渠道 | 状态 | scriptSlot | 应收正文 |
|---|------|------|------------|----------|
| 1 | SMS | ✅ 应收 | `S2_SMS_STANDARD` | `MOCASA Collections: Test Case S2, 7 days overdue. See your personalized payment options for PHP 8,560.00.` |
| 2 | Push | ✅ 应收 | `S2_PUSH_STANDARD` | **标题**：`7 days overdue`<br>**正文**：`See your personalized payment options for PHP 8,560.00. Tap to view.` |
| 3 | SMS | ✅ 应收 | `S2_SMS_STANDARD` | 与第 1 条相同（S2 计划第 3 步再发一条） |
| 4 | Email | ❌ 不发 | — | dpd=7，Email 里程碑仅在 **dpd=4** 触发 → SKIPPED |

---

### 2.4 99000003 · S2 · dpd=13 · ₱10,950.00 · `Test Case S3`

| 渠道 | 主流程 |
|------|--------|
| 全部 | ❌ PubSub 未到达 consumer，**主流程不应收到** |

补充触达见 §3（L4b-4 mock 停催用例）。

---

### 2.5 99000004 · S3 · dpd=22 · ₱7,060.00 · `Test Case S4`

| # | 渠道 | 状态 | scriptSlot | 应收正文 |
|---|------|------|------------|----------|
| 1 | SMS | ✅ 应收 | `S3_SMS_STANDARD` | `MOCASA Collections: Test Case S4, 22 days overdue. Delay may limit your account and future loan eligibility. Please settle PHP 7,060.00 or view your payment options.` |
| 2 | Push | ✅ 应收 | `S3_PUSH_STANDARD` | **标题**：`22 days overdue`<br>**正文**：`Delay may limit your account. View your options for PHP 7,060.00. Tap now.` |
| 3 | Email | ❌ 不发 | — | Phase 1 无 S3 Email；dpd=22 也不命中里程碑 → SKIPPED |

---

### 2.6 99000005 · S4 · dpd=45 · ₱14,160.00 · `Test Case Ceased`

| # | 渠道 | 状态 | scriptSlot | 应收正文 |
|---|------|------|------------|----------|
| 1 | SMS | ✅ 应收 | `S4_SMS_STANDARD` | `MOCASA Collections: Test Case Ceased, final notice. 45 days overdue and at risk of a delinquency record. Please resolve PHP 14,160.00 using your payment options.` |
| 2 | Email | ❌ 不发 | — | dpd=45，Email 里程碑仅在 **dpd=31** 或 **dpd=75** → SKIPPED |

S4 计划无 Push 步（SMS + Email 两步）。

---

## 3. 补充触达（L4b-2/3/4 mock，可能额外收到）

主流程完成后，为验证还款 / 升档 / 停催又建了计划，手机或 App **可能**再收到以下消息（时间约 19:45–19:47）。

| loan_id | 场景 | 计划 | 可能渠道 | 若已发出，应收内容 |
|---------|------|------|----------|-------------------|
| 99000001 | L4b-2 还款取消 | 139 | SMS（仅第 1 步） | 同 §2.2 第 1 条 SMS；还款后 Push/Email **不再发** |
| 99000002 | L4b-3 升档（错档 S1） | 140 | SMS | `MOCASA Collections: Test Case S2, your account is 7 day(s) overdue. Please settle PHP 8,560.00 promptly.`（计划 stage=S1，dpd 仍读旧库=7） |
| 99000002 | L4b-3 升档后新 S2 计划 | 141 | SMS×2 + Push×1 | 同 §2.3（可能部分执行） |
| 99000003 | L4b-4 停催 mock | 142 | SMS（至少第 1 步） | `MOCASA Collections: Test Case S3, 13 days overdue. See your personalized payment options for PHP 10,950.00.`；CASE_CEASED 后后续步 **不再发** |

**合计（含补充）**：SMS 约 **6–10 条**，Push 约 **3–4 条**。

---

## 4. 邮件（126 邮箱）— L4b 应收 **0 封**

Phase 1 Email **仅在精确 DPD 里程碑日**发信；Resolver 对非里程碑返回 null → 步骤 **SKIPPED**，不调用 SendGrid。

| loan_id | stage | 测试 dpd | Email 里程碑 | L4b 结果 |
|---------|-------|----------|--------------|----------|
| 99000000 | S0 | 0 | 计划无 Email 步 | 无 |
| 99000001 | S1 | 2 | 需 dpd=**1** → `S1_EMAIL_OVERDUE_NOTICE` | SKIPPED |
| 99000002 | S2 | 7 | 需 dpd=**4** → `S2_EMAIL_ENTRY` | SKIPPED |
| 99000003 | S2 | 13 | 需 dpd=**4** | SKIPPED |
| 99000004 | S3 | 22 | Phase 1 无 S3 Email | SKIPPED |
| 99000005 | S4 | 45 | 需 dpd=**31** 或 **75** | SKIPPED |

**126 邮箱查不到 L4b 邮件属正常。** 若需单独验 Email，跑 L4a Email E2E（如 caseId `92002` / `93101`），见 [`collection-channel功能测试指南`](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) §TC-EMAIL-D0-01 / TC-EMAIL-E2E。

### 4.1 里程碑 Email 参考（L4b 未实际发送）

发件人：`collections@mocasa.com`。Subject 在 SendGrid 控制台配置，SSOT 见 [`email-templates/subjects.md`](../email-templates/subjects.md)。

| scriptSlot | 触发 dpd | Subject |
|------------|----------|---------|
| `S0_DUE_TODAY_EMAIL` | 0 | ⚠️ Action Required: Your Payment Is Due Today |
| `S1_EMAIL_OVERDUE_NOTICE` | 1 | Overdue: Payment Not Received—Late Fees Apply |
| `S2_EMAIL_ENTRY` | 4 | Notice: Account Escalated—Late Fees Accruing |
| `S4_EMAIL_ENTRY` | 31 | Formal Collection Notice—Pay Full Balance Immediately |
| `S4_EMAIL_PRE_CLOSE` | 75 | ⚠️ Final Notice: Account Scheduled for Final Review |

---

## 5. 系统侧核对（不含完整正文）

`t_contact_timeline.content_summary` Phase 1 **通常为空**（脱敏设计），库内无 SMS/邮件全文。可用以下方式确认「是否发出、发到哪」：

### 5.1 REST

```bash
curl -s "http://localhost:8888/plans/timeline/99000002?limit=20"
curl -s "http://localhost:8888/plans/observation/by-case/99000002"
```

### 5.2 SQL

```sql
SET @caseId = 99000002;

SELECT channel, direction, result, provider_msg_id, source, created_at
  FROM t_contact_timeline
 WHERE case_id = @caseId
 ORDER BY created_at;

SELECT step_order, channel_type, status, result
  FROM t_contact_plan_step
 WHERE plan_id = (SELECT id FROM t_contact_plan WHERE case_id = @caseId ORDER BY id DESC LIMIT 1)
 ORDER BY step_order;
```

| result | 含义 |
|--------|------|
| `DELIVERED` | 供应商已受理（SMS/Push 真实下发） |
| `SKIPPED` | 未发出（L4b 中 Email 均为此类） |

完整断言脚本：[`db/l4b-assert.sql`](../db/l4b-assert.sql)。

### 5.3 预览渲染正文（不走 PubSub）

```bash
curl -s -X POST "http://localhost:8888/mock/send-sms?caseId=99000002"
curl -s -X POST "http://localhost:8888/mock/send-push?caseId=99000002"
```

响应含 `scriptSlot`、`smsBody` 或 `title`/`body`，可与真机收到内容比对。

### 5.4 供应商对账

| 渠道 | 凭证 | 查什么 |
|------|------|--------|
| SMS | timeline.`provider_msg_id`（= 通知中心 `requestId`） | 手机号 `639451374358` + requestId |
| Email | SendGrid Activity | 收件人 `wzynju@126.com` |
| Push | 极光控制台 | token `1a0018970bf0c19de04` |

详见 [`Notification对接说明 §7`](../channel/MOCASA催收系统升级_Phase1_Notification对接说明.md)。

---

## 6. 测试同事核对 Checklist

- [ ] 手机 `+639451374358`：收到约 **6–10 条** SMS，关键字 `MOCASA` / `MOCASA Collections`
- [ ] 各 loan 文案与 §2 表格一致（姓名、dpd、PHP 金额）
- [ ] App Push：约 **3–4 条**，标题含 `Overdue` / `days overdue` 等
- [ ] 126 邮箱：**0 封** L4b 邮件（§4 说明原因）
- [ ] SQL / REST：`DELIVERED` 与 SKIPPED 与 §2–§4 一致
- [ ] 99000003 主流程无触达；若有 SMS 仅来自 §3 mock 停催

---

## 7. 备注

1. **话术为 Phase 1 占位**，正式版由编排同事提供（见 `application-local.yml` 注释）。
2. **99000003 PubSub 分流**为环境侧问题，不影响 SMS/Push 文案逻辑；S2 路径已由 99000002 覆盖。
3. 日志/SQL 输出手机号、邮箱需脱敏；勿将 `provider_callback` 含 PII 内容外传。
