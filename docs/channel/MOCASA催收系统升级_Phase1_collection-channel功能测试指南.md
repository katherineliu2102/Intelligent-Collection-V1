# MOCASA Phase 1 — collection-channel 功能测试指南

> **版本**: v1.2 · **日期**: 2026-06-12  
> **前置**: 应用已启动（见 [操作说明.md](../操作说明.md)）；Nacos 已配置渠道密钥（见 [开发执行指南 §6](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md#6-nacos-配置清单)）。  
> **原则**: 先 Mock 回归 → 单渠道冒烟 → 合规/引擎 → **计划结构验证**（不依赖真实时刻触发多槽位）。

---

## 1. 测试环境

### 1.1 服务地址

| 项 | 默认 |
|----|------|
| 应用端口 | `8080`（`application-local.yml` 可能为 `8888`，以实际为准） |
| Base URL | `http://localhost:<port>` |

### 1.2 Nacos 公共账号用途

| 配置类 | 测试中的用途 |
|--------|--------------|
| `spring.datasource.*` | 计划/步骤/时间线落库验证 |
| `spring.redis.*` | 合规 Guard、渠道幂等 |
| `channel.notification.*` | TC-SMS、TC-PUSH（含 fallback SMS） |
| `channel.sendgrid.*` | TC-EMAIL |
| `channel.lth.voice.*` | TC-VOICE |
| `channel.callback.base-url` | Voice 回调 URL |
| `channel.debug.single-step` | 单渠道冒烟（`SMS`/`PUSH`/`EMAIL`/`AI_CALL`/`TTS`） |
| `channel.compliance.*` | Guard 限额、静默时段、触达窗 |
| `channel.plan-templates` | DefaultPlanFactory（可选 YAML） |
| `collection.scan.interval-ms` | 联调缩短等待（建议 `3000`） |

### 1.3 测试数据约定（`MockProfileService` + `MockCaseService` 已固化）

| caseId / userId | 画像 | 案件特征 | 主要 TC |
|-----------------|------|----------|---------|
| 90001 | 有 phone | S1 常规模拟 | TC-SMS-01 |
| 90002 | phone + **jpushToken** | S1 | TC-PUSH-01 |
| 90003 | phone，**无 jpushToken** | S1 | TC-PUSH-02 |
| 90004 | phone + **email** | S1 | TC-EMAIL-01 |
| 90005 | phone，**无 email** | S1 | TC-EMAIL-02 |
| 90006 | phone | S1 | TC-VOICE-01/02、TTS |
| 90007 | phone + email | S2，`strategy_tone=FIRM` | TC-PLAN-TONE-02 |
| 90008 | phone + email | S2，`complaint_frozen=true` | TC-GUARD-03 |
| 90091 | phone + email | **D+91 / CEASED**，不建 plan | TC-CEASED-02 |
| 90100 | phone + email | **S0**，dpd=-3 | TC-PLAN-S0 |
| 92002 | phone + **wzynju@126.com** | **S0 D0**，dpd=0 · `S0_DUE_TODAY_EMAIL` | TC-EMAIL-D0-01 |
| **93101 / 93201 / 93401 / 93404** | **wzynju@126.com** | **Phase 1 其余 4 封 Email E2E** | TC-EMAIL-E2E（见下） |
| **94100** | mobile **123456** | S1 · testSend Virtual | TC-SMS-TEST-01 |
| **94101** | mobile **9451374358** | S1 · 真号 A | TC-SMS-PROD-01 / TC-SMS-PROD-05 |
| **94102** | mobile **9451373897** | S1 · 真号 B（验路由） | TC-SMS-PROD-02 |
| **94200** | **假 jpushToken** `MOCK_JPUSH_RID_PLACEHOLDER_0001` | S1 · Push 占位联调（走 push 不 fallback） | TC-PUSH-01 |
| **94201** | 无 jpushToken（phone 9451373897） | S1 · Push → SMS fallback | TC-PUSH-02 |
| 91000 | 默认 mock | S1 | TC-REG-01 |

> **Phase 1 仅 5 封 Email 联调案例**（caseId、dpd、amount、scriptSlot）：[`email-templates/email-e2e-test-cases.md`](./email-templates/email-e2e-test-cases.md)  
> **发件人**：`collections@mocasa.com` · **收件箱**：`wzynju@126.com`

### 1.4 阶段门禁（哪个 TC 在何时可跑）

与 [开发执行指南 §7 Checklist](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md#7-推荐开发时间线checklist) 对齐：

| 最低阶段 | 可跑 TC |
|----------|---------|
| 随时（Mock 链路） | TC-REG-01 |
| DefaultStepResolver + NotificationSmsAdapter | TC-SMS-01、TC-SMS-TEST-*、TC-SMS-PROD-* |
| 2 四 Adapter 完成后 | TC-PUSH/EMAIL/VOICE 单渠道 |
| 3 ComplianceExecutionGuard | TC-EMAIL-02、TC-GUARD-* |
| 3 DefaultPlanFactory | TC-PLAN-STRUCT-*、TC-PLAN-TONE-* |
| 0+ `CASE_CEASED` 契约 + 引擎 Consumer | TC-CEASED-01/02 |
| 4 Webhook 完成后 | TC-EMAIL-03、TC-VOICE-03 |

### 1.5 加速联调（Nacos）

```yaml
channel:
  debug:
    single-step: SMS    # 或 PUSH / EMAIL / AI_CALL / TTS
collection:
  scan:
    interval-ms: 3000
```

---

## 2. 测试分层

| 层级 | 范围 | 工具 |
|------|------|------|
| L0 | Adapter 请求/响应映射 | JUnit + WireMock（可选） |
| L1 | Gateway 路由、幂等 | 单元测试 + Redis |
| L2 | 七步管线 + DB | 本文 curl + SQL |
| L3 | 真实供应商 | Nacos 测试密钥 + 沙箱号/邮箱 |

本文覆盖 **L2/L3**。TC-IDEM-02 建议 **L1 单测**覆盖，功能测试标为可选。

---

## 3. 通用操作

### 3.1 注入案件

```bash
curl -X POST "http://localhost:8080/mock/ingest?caseId=90001&userId=90001&stage=S1"
```

S0 / S2 等：`stage=S0` 或省略 stage（由 MockCaseService 按 caseId 决定）。

### 3.2 查询计划状态

```bash
curl -s "http://localhost:8080/plans/active/by-case/90001"
curl -s "http://localhost:8080/plans/<planId>"
```

### 3.3 查询触达时间线（按 userId）

```bash
curl -s "http://localhost:8080/plans/timeline/90001"
```

### 3.4 SQL 辅助验证

```sql
SELECT id, status, stage, cancel_reason FROM t_contact_plan WHERE case_id = 90001 ORDER BY id DESC LIMIT 1;

SELECT step_order, channel_type, template_id, status, contact_result, delay_minutes, trigger_time
  FROM t_contact_plan_step WHERE plan_id = ? ORDER BY step_order;

SELECT channel_type, contact_result, provider_msg_id, created_at
  FROM t_contact_timeline WHERE case_id = 90001 ORDER BY id DESC;
```

**计划结构验证**（§8）主要用第二条 SQL 检查 `channel_type` / `template_id`（scriptSlot）序列。

### 3.5 模拟还款 / 停催

```bash
curl -X POST "http://localhost:8080/mock/repayment?userId=90001&caseId=90001"
curl -X POST "http://localhost:8080/mock/case-ceased?caseId=90001&maxDpd=91"
```

### 3.6 异步渠道模拟回调

**骨架接口**（阶段 2 即可）：

```bash
curl -X POST "http://localhost:8080/webhook/channel-callback?planId=<planId>&stepId=<stepId>&caseId=90006&result=ANSWERED"
```

**LTH 真实路径**（阶段 4 完成后，TC-VOICE-03）：

```bash
curl -X POST "http://localhost:8080/webhook/lth/voice" -H "Content-Type: application/json" -d '{ ... }'
```

---

## 4. 回归基线

### TC-REG-01：Mock 三步步计划闭环

| 项 | 内容 |
|----|------|
| 门禁 | 随时 |
| 前置 | `channel.debug.single-step` 为空；`MockPlanFactory` 三步步 |
| 操作 | `POST /mock/ingest?caseId=91000&userId=91000&stage=S1` |
| 预期 | 约 2 分钟内 plan `PLAN_COMPLETED`；timeline 3 条（SMS/PUSH/SMS） |

---

## 5. 单渠道冒烟（Adapter 阶段）

### TC-SMS-01：通知中心短信发送（全链路 · 扫描器）

| 项 | 内容 |
|----|------|
| 门禁 | `NotificationSmsAdapter` + `DefaultStepResolver` |
| 前置 | `single-step=SMS`；`channel.notification.base-url` + `app-code=mocasa`；**测试**配 `sms-test-mode=true`（免 app-key）；**生产**配 `sms-test-mode=false` + `app-key` |
| 操作 | `POST /mock/ingest?caseId=90001&userId=90001&stage=S1` → 等待扫描（`collection.scan.interval-ms`） |
| 预期 | ① 通知中心 `code=0` 且 `data.requestSuccess=true` ② step `COMPLETED`，`DELIVERED` ③ timeline `provider_msg_id` = `requestId` ④ **无** `CHANNEL_CALLBACK` |
| 失败排查 | `sms_body` 是否来自 `channel.scripts`；`contentType=collection`；测试模式是否走 `/testSend` |

### TC-SMS-TEST-01：测试接口 testSend（mobile=123456，免签名）

| 项 | 内容 |
|----|------|
| 门禁 | 通知中心测试环境可达 |
| 前置 | `channel.notification.sms-test-mode=true`；`app-code=mocasa`；**无需** `app-key` |
| 方式 A（推荐 · 不经 DB） | `POST /mock/send-sms?caseId=94100` |
| 方式 B（curl 直调通知中心） | 见 [Notification 对接说明 §5.1](./MOCASA催收系统升级_Phase1_Notification对接说明.md#51-sms-测试案例可直接-curl--postman) |
| 预期 | `code=0`、`data.requestSuccess=true`、`requestId` 非空；`data.channel` 多为 **Virtual**（测试路由，非真机送达） |
| 记录 | 2026-06-12 联调：`requestId=63b5afe30bfa482d8618819daa48461f`，channel=Virtual |

```powershell
Invoke-RestMethod -Uri "http://localhost:8888/mock/send-sms?caseId=94100" -Method POST
```

### TC-SMS-TEST-02 ~ 04：测试接口边界（curl）

| TC | 操作 | 预期 |
|----|------|------|
| TC-SMS-TEST-02 | testSend + `accountName` 指定通道 | `data.channel` 命中指定测试账号 |
| TC-SMS-TEST-03 | content/mobile 空 | 参数校验失败 |
| TC-SMS-TEST-04 | `contentType=otp` vs `collection` | 路由账号不同 |

详见 [Notification 对接说明 §5.1](./MOCASA催收系统升级_Phase1_Notification对接说明.md#51-sms-测试案例可直接-curl--postman)。

### TC-SMS-PROD-01：生产接口真号 A（9451374358）

| 项 | 内容 |
|----|------|
| 门禁 | 运维已签发 `appKey`；**禁止** `sms-test-mode=true`（testSend 走 Virtual，不会真下发） |
| 前置 | `sms-test-mode=false`；`.env` 填 `NOTIFICATION_APP_KEY`；`application-local.yml` 或 Nacos 引用该变量 |
| 方式 A（推荐 · 适配器） | `POST /mock/send-sms?caseId=94101` |
| 方式 B（curl + 签名） | 见下方 PowerShell 签名示例 |
| 预期 | `code=0`、`requestSuccess=true`、真机收到；`data.channel` 为 QH/Hiway/BORI 等（非 Virtual） |

```powershell
# .env 增加 NOTIFICATION_APP_KEY=<运维下发> 后重启应用
# application-local.yml: channel.notification.sms-test-mode: false

Invoke-RestMethod -Uri "http://localhost:8888/mock/send-sms?caseId=94101" -Method POST
```

**curl 直调生产 `/v1/sms/send`（需签名）**：

```powershell
$appCode = "mocasa"
$appKey  = $env:NOTIFICATION_APP_KEY
$dateTime = [string]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
$md5 = [System.Security.Cryptography.MD5]::Create()
$signBytes = $md5.ComputeHash([Text.Encoding]::UTF8.GetBytes("$appCode$appKey$dateTime"))
$sign = -join ($signBytes | ForEach-Object { '{0:x2}' -f $_ })
$body = @{
  appCode = $appCode; dateTime = $dateTime; sign = $sign
  mobile = "9451374358"
  content = "MOCASA Collections: test prod SMS. Pay in the SKYPAYLOANS app only."
  contentType = "collection"
} | ConvertTo-Json
Invoke-RestMethod -Uri "https://service-test.mocasa.com/notification/v1/sms/send" -Method POST -Body $body -ContentType "application/json"
```

### TC-SMS-PROD-02：生产真号 B（9451373897 · 验运营商路由）

| 项 | 内容 |
|----|------|
| 操作 | `POST /mock/send-sms?caseId=94102` 或 curl 改 `mobile=9451373897` |
| 预期 | 同 PROD-01；`data.channel` 可能与号 A 不同（号段路由） |

### TC-SMS-PROD-05：全链路 ingest + timeline

| 项 | 内容 |
|----|------|
| 前置 | `single-step=SMS`；真号 case **94101**；`sms-test-mode=false` + `app-key` |
| 操作 | ingest 94101 → 等待扫描 → 查 timeline / `orchestration.html` |
| 预期 | step `DELIVERED`；timeline 含 `provider_msg_id`；观测页可见 SMS 步骤 |

### TC-PUSH-01：通知中心 App Push（假 jpushToken 占位联调）

> Phase 1 暂无真实极光 Registration ID，用 **假 token 占位**（`MOCK_JPUSH_RID_PLACEHOLDER_0001`，见 `MockPushTestCases`）先跑通链路。
> Push **无免签名测试端点**（不同于 SMS 的 `/testSend`）：`/sync/send`、`/send`、`/queue/send` 全部 `@Auth`，**真实入队必须 `appKey`**（与 SMS 真号同一 blocker）。
> 联调由 `channel.notification.push-sync-mode` 选端点：`true`→`/v1/app_notification/sync/send`（可见 `requestSuccess`/`requestId`）；`false`→异步 `/v1/app_notification/send`（入队 `code=0`）。

| 项 | 内容 |
|----|------|
| 前置 | user **94200**（假 jpushToken 占位）；`app-code=mocasa`；`push-sync-mode=true` 便于观测 |
| 方式（推荐 · 不经 DB） | `POST /mock/send-push?caseId=94200` |
| 验链路（无 appKey） | 返回体含 `targetAddress=MOCK_JPUSH_RID_...`（**未** fallback）、`title=Overdue: PHP {amount}`、`body`、`pushData`（含 `deep_link`）；`result=NOTIFICATION_NOT_CONFIGURED`（适配器正确按 appKey 门禁短路） |
| 验 round-trip（dummy appKey） | 启动加 `--channel.notification.app-key=dummy`，再发 → 适配器 POST 至 `/sync/send`，通知中心返回 `code=1000 invalid sign` → `result=NOTIFICATION_INVALID_SIGN`（证明完整往返，仅缺有效签名） |
| 真实入队（待 appKey） | 运维签发 `appKey` 后，配 `.env` `NOTIFICATION_APP_KEY` + 真 token → `requestSuccess=true` + `requestId`；step `DELIVERED` |

```powershell
# push-sync-mode=true（application-local.yml 已配）
Invoke-RestMethod -Uri "http://localhost:8888/mock/send-push?caseId=94200" -Method POST | ConvertTo-Json
```

### TC-PUSH-02：无 token → SMS fallback

| 项 | 内容 |
|----|------|
| 前置 | user **94201**（无 jpushToken，phone 9451373897）；`sms-test-mode=true` |
| 方式 | `POST /mock/send-push?caseId=94201` |
| 预期 | ① 引擎 **一次** dispatch（PushAdapter 内 fallback SMS）② SMS（test 模式走 testSend Virtual）`DELIVERED` + `providerMsgId` ③ 返回体 `targetAddress` 为手机号、`jpushToken=null` |

### TC-EMAIL-01：SendGrid 里程碑邮件

| 项 | 内容 |
|----|------|
| 前置 | `single-step=EMAIL`；user **90004** |
| 预期 | ① dispatch 成功即 step `COMPLETED` ② timeline `DELIVERED` |
| 注意 | SendGrid `delivered/open` Event **不改变** step 状态 |

### TC-EMAIL-D0-01：D0 到期提醒 → 真实 126 邮箱

| 项 | 内容 |
|----|------|
| 门禁 | 阶段 2 `SendGridEmailAdapter` 已上线 |
| 前置 | `channel.sendgrid.templates.S0_DUE_TODAY_EMAIL` 已填；`single-step=EMAIL` |
| 测试数据 | **92002** → `wzynju@126.com`，S0，`dpd=0`，`amount_due=5000` |
| 操作 | `POST /mock/ingest?caseId=92002&userId=92002&stage=S0` → 等 10~15s |
| 预期 | ① timeline 1 条 EMAIL `DELIVERED` ② 126 邮箱收到 ③ 日志含 `S0_DUE_TODAY_EMAIL` |

### TC-EMAIL-E2E：Phase 1 五封 Email 全链路

| 项 | 内容 |
|----|------|
| 前置 | `application-local.yml` 填齐 **5 个** `templates`；`from-email=collections@mocasa.com`；`single-step=EMAIL` |
| 案例表 | [`email-templates/email-e2e-test-cases.md`](./email-templates/email-e2e-test-cases.md) |
| 操作 | 按表 `caseId` ingest，例如 `93101`（test_s1_user1 · S1 · dpd1 · ₱2500） |
| 预期 | 每案对应 scriptSlot 模板 + 变量与表一致 |

```bash
curl -X POST "http://localhost:8888/mock/ingest?caseId=93101&userId=93101&stage=S1"
curl -s "http://localhost:8888/plans/timeline/93101?limit=5"
```

PowerShell 本地密钥（可选，写入 `.env` 勿提交）：

```dotenv
SENDGRID_API_KEY=SG.xxxx
SENDGRID_FROM_EMAIL=collections@mocasa.com
```

### TC-EMAIL-02：无邮箱 Guard 拦截

| 项 | 内容 |
|----|------|
| 门禁 | `ComplianceExecutionGuard` 已 `@Primary` |
| 前置 | `single-step=EMAIL`；user **90005** |
| 预期 | 无 SendGrid 调用；step `SKIPPED`，`COMPLIANCE_BLOCKED` |

### TC-VOICE-01：AI_CALL 异步接通（骨架回调）

| 项 | 内容 |
|----|------|
| 前置 | `single-step=AI_CALL`；user 90006 |
| 操作 | ingest → plan `STEP_EXECUTING` → §3.6 骨架 callback `ANSWERED` |
| 预期 | 回调后 step `COMPLETED` |

### TC-VOICE-02：未接通

| 项 | 内容 |
|----|------|
| 操作 | 回调 `result=NO_ANSWER` |
| 预期 | timeline 记录；按 `DefaultAdvancementPolicy` 推进 |

### TC-VOICE-TTS-01：TTS 与 AI_CALL 同路由

| 项 | 内容 |
|----|------|
| 前置 | `single-step=TTS`（需 PlanFactory/Mock 支持 TTS 单步或手工构造 step） |
| 预期 | `LthVoiceAdapter` 受理；异步回调闭环同 TC-VOICE-01 |

### TC-VOICE-03：LTH 真实 Webhook 路径

| 项 | 内容 |
|----|------|
| 门禁 | 阶段 4 `/webhook/lth/voice` 已实现 |
| 预期 | 解析话单 → `CHANNEL_CALLBACK` 含 `disposition` / `providerMsgId` |

---

## 6. 引擎行为用例

### TC-RETRY-01：可重试失败

| 项 | 内容 |
|----|------|
| 前置 | LTH URL 不可达或 Adapter 返回 `retryable=true` |
| 预期 | `retry_count` 增加；`trigger_time` 推迟 |

### TC-IDEM-01：步骤幂等

| 项 | 内容 |
|----|------|
| 操作 | 同一 `PLAN_STEP_DUE` 重复触发 |
| 预期 | 供应商仅 1 次发送 |

### TC-IDEM-02：渠道幂等（可选，建议 L1）

| 项 | 内容 |
|----|------|
| 说明 | 相同 `idempotencyKey` 连续 dispatch；curl 难触发，建议 Gateway 单测 |

### TC-CANCEL-01：还款取消

```bash
curl -X POST "http://localhost:8080/mock/repayment?userId=90001&caseId=90001"
```

| 预期 | plan `PLAN_CANCELLED`，`cancel_reason=REPAID` |

### TC-WAIT-01：观察期（可选）

| 项 | 内容 |
|----|------|
| 前置 | 须构造 `observation_minutes > 0` 的测试 plan（当前 Mock 默认为 0，可跳过 Phase 1） |

---

## 7. 合规用例

### TC-GUARD-01：日限额

| 项 | 内容 |
|----|------|
| 门禁 | ComplianceExecutionGuard |
| 前置 | `channel.compliance.daily-limit.SMS: 1` |
| 预期 | 同日第 2 条 SMS → `SKIPPED`，`COMPLIANCE_BLOCKED` |

### TC-GUARD-02：静默时段

| 项 | 内容 |
|----|------|
| 前置 | 当前 PHT 在 `quiet-hours`（21:00–08:00）内 |
| 预期 | Guard BLOCK |

### TC-GUARD-03：投诉冻结

| 项 | 内容 |
|----|------|
| 门禁 | ComplianceExecutionGuard |
| 前置 | ingest **90008**（snapshot `complaint_frozen=true`）；`single-step=SMS` |
| 预期 | 全渠道 BLOCK；无供应商 API 调用 |

### TC-GUARD-04：触达窗 08:00–21:00

| 项 | 内容 |
|----|------|
| 前置 | PHT 在 08:00 前或 21:00 后（`touch-window` 外） |
| 预期 | Guard BLOCK（与 quiet-hours 规则一致） |

---

## 8. 计划结构验证（DefaultPlanFactory 完成后）

> **范围说明**：Phase 1 验收 **仅验证 SQL 中 step 序列是否符合编排规格**，不要求按真实 `trigger_time` 跑完一日所有槽位。  
> 操作：关闭 `channel.debug.single-step` → ingest → **立即查 step 表**（不必等扫描执行）。

### TC-PLAN-STRUCT-S1：S1 步骤清单

| 项 | 内容 |
|----|------|
| 操作 | `POST /mock/ingest?caseId=90001&stage=S1` |
| 预期（结构） | 含 08 SMS、12 PUSH、09:15+ AI、14:00 里程碑 Email 等槽位；**无** `*_EMAIL_CONDITIONAL`；**无** `HUMAN_CALL` |
| 禁止 | scriptSlot 含 `S1_EMAIL_CONDITIONAL` 的 step |

### TC-PLAN-S0：S0 到期前

| 项 | 内容 |
|----|------|
| 操作 | ingest **90100**，`stage=S0` |
| 预期 | 含 D-3~D0 日块：Push/SMS 槽 + D0 `S0_DUE_TODAY_EMAIL`；**无** AI_CALL |

### TC-PLAN-TONE-01：S1 无 FIRM

| 项 | 内容 |
|----|------|
| 操作 | ingest 90001（STANDARD） |
| 预期 | template/scriptSlot **不含** `_FIRM` 后缀 |

### TC-PLAN-TONE-02：S2+ 难催 FIRM

| 项 | 内容 |
|----|------|
| 操作 | ingest **90007**（`strategy_tone=FIRM`） |
| 预期 | SMS 等槽位使用 `*_FIRM` 或 FIRM 模板键 |

### TC-PLAN-STRUCT-S4：S4 晚期分段

| 项 | 内容 |
|----|------|
| 操作 | ingest case，`stage=S4`，snapshot `dpd=65`（可在 Mock 扩展或手工改 snapshot 后重建 plan） |
| 预期 | 当日仅 **1** 个 AI_CALL step；**无** Wave-2 / `*_VOICE_RETRY` step |

### TC-PLAN-STRUCT-COMMON：全局禁止项

| 检查 | 预期 |
|------|------|
| 全 Stage | 无 `HUMAN_CALL` channel_type |
| 全 Stage | 无 `*_EMAIL_CONDITIONAL` scriptSlot |
| S4 D+61~90 日块 | AI 槽位 ≤1/日 |

---

## 9. 生命周期 — CASE_CEASED

### TC-CEASED-01：停催取消活跃计划

| 项 | 内容 |
|----|------|
| 操作 | ① `POST /mock/ingest?caseId=90001&stage=S1` ② 确认有活跃 plan ③ `POST /mock/case-ceased?caseId=90001` |
| 预期 | ① 活跃 plan `PLAN_CANCELLED`，`cancel_reason=CEASED` ② 无新的 `PLAN_STEP_DUE` 执行 |

### TC-CEASED-02：已停催案件不建 plan

| 项 | 内容 |
|----|------|
| 操作 | `POST /mock/ingest?caseId=90091&stage=S4` |
| 预期 | **无**活跃 plan（`PlanFactory.create` 返回 null 或引擎跳过 CEASED） |

---

## 10. Webhook 补充

### TC-EMAIL-03：SendGrid Event → timeline  enrichment

| 项 | 内容 |
|----|------|
| 门禁 | `/webhook/sendgrid` 已实现 |
| 操作 | 模拟 `delivered` / `open` 事件 |
| 预期 | 同 `provider_msg_id` **幂等**升级 timeline；step 状态 **不变** |

---

## 11. Nacos 配置测试

### TC-NACOS-01：连通性

启动后日志无 Nacos auth 失败；`ChannelProperties` 能读到 `channel.notification.app-code`。

### TC-NACOS-02：热更新限额

修改 `daily-limit.SMS`（`@RefreshScope`）后新限额生效。

### TC-NACOS-03：本地覆盖

`application-local.yml` 改 `server.port` 生效，其余仍从 Nacos 拉取。

---

## 12. 测试记录模板

| TC ID | 日期 | 执行人 | 结果 | planId | 备注 |
|-------|------|--------|------|--------|------|
| TC-SMS-TEST-01 | 2026-06-12 | Agent | PASS | | curl+适配器；Virtual；requestId=3e7396c300af443b91d84e546ef46462 |
| TC-SMS-01 | 2026-06-12 | Agent | PASS | planId=47 | timeline DELIVERED requestId=0a5a3ec502e04320aee9f6710cfa3f7c |
| TC-SMS-PROD-01 | 2026-06-12 | Agent | PASS | | 真号 A 9451374358；`sms-test-mode=false`+真 appKey；DELIVERED providerMsgId=f71224362b3943abb320bb8896c256cb（请收件人确认手机收到） |
| TC-SMS-PROD-02 | 2026-06-12 | Agent | PASS | | 真号 B 9451373897；DELIVERED providerMsgId=2a76ef8bc52e4a309f9b7b6ddfc44b46（验运营商路由；请确认手机收到） |
| TC-SMS-PROD-05 | 2026-06-12 | Agent | PASS | planId=48 | ingest 94101→扫描→plan `PLAN_COMPLETED`；step SMS `DELIVERED`；timeline providerMsgId=9171a9daa2ef475795336ee55d0fd2ad |
| TC-PUSH-01 | 2026-06-12 | Agent | PASS（链路·待真 token） | | 真 appKey 签名通过；通知中心→极光受理；假 token 被极光判无效 → `requestSuccess=false` → NOTIFICATION_PUSH_REJECTED（预期）；换真 jpushToken 即 DELIVERED |
| TC-PUSH-02 | 2026-06-12 | Agent | PASS | | 94201 无 token → SMS fallback → DELIVERED |
| TC-CEASED-01 | | | | | |
| … | | | | | |

---

## 13. 通过标准（与开发执行指南 Checklist 映射）

### 必过（渠道模块 Phase 1）

- [ ] TC-REG-01 Mock 回归
- [ ] TC-SMS-TEST-01 testSend（123456 / Virtual）
- [ ] TC-SMS-PROD-01/02 真号生产 `/send`（需 `NOTIFICATION_APP_KEY`）
- [ ] TC-SMS-01 全链路扫描、TC-PUSH-01、TC-EMAIL-01 真实发送
- [ ] TC-PUSH-02 fallback
- [ ] TC-EMAIL-02 无邮箱 SKIPPED（Guard 上线后）
- [ ] TC-VOICE-01 异步回调闭环
- [ ] TC-CEASED-01、TC-CEASED-02
- [ ] TC-PLAN-STRUCT-S1 + TC-PLAN-S0 + TC-PLAN-STRUCT-COMMON
- [ ] TC-GUARD-03 投诉冻结（Guard 上线后）

### 阶段可选 / 联调完成后勾选

- [ ] TC-PLAN-TONE-02（FIRM 模板）
- [ ] TC-PLAN-STRUCT-S4（S4 分段）
- [ ] TC-VOICE-03、TC-EMAIL-03（Webhook 阶段 4）
- [ ] TC-GUARD-01/02/04（需 Redis + 时段条件）
- [ ] TC-RETRY-01、TC-IDEM-01

---

## 14. 关联文档

| 文档 | 用途 |
|------|------|
| [开发执行指南 v1.1](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md) | 阶段顺序、Checklist |
| [渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围) | 结构验证依据 |
| [collection-channel 总规格 §6](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#6-同步渠道-vs-异步渠道) | 同步/异步完成时机 |
| [操作说明.md](../操作说明.md) | 本地启动 |
