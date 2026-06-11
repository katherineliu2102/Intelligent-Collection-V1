# MOCASA Phase 1 — collection-channel 功能测试指南

> **版本**: v1.1 · **日期**: 2026-06-05  
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
| `channel.lth.sms.*` | TC-SMS、TC-PUSH-02 fallback |
| `channel.fcm.*` | TC-PUSH |
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
| 90002 | phone + **fcmToken** | S1 | TC-PUSH-01 |
| 90003 | phone，**无 fcmToken** | S1 | TC-PUSH-02 |
| 90004 | phone + **email** | S1 | TC-EMAIL-01 |
| 90005 | phone，**无 email** | S1 | TC-EMAIL-02 |
| 90006 | phone | S1 | TC-VOICE-01/02、TTS |
| 90007 | phone + email | S2，`strategy_tone=FIRM` | TC-PLAN-TONE-02 |
| 90008 | phone + email | S2，`complaint_frozen=true` | TC-GUARD-03 |
| 90091 | phone + email | **D+91 / CEASED**，不建 plan | TC-CEASED-02 |
| 90100 | phone + email | **S0**，dpd=-3 | TC-PLAN-S0 |
| 92001 | phone + **wzynju@126.com** | **S0 D0**，dpd=0 | TC-EMAIL-D0-01 |
| 92002 | 同 92001 | **S0 D0**（重复进案推荐） | TC-EMAIL-D0-01 |
| **93101–93404** | **wzynju@126.com** | **13 里程碑 Email E2E** | TC-EMAIL-E2E（见下） |
| 91000 | 默认 mock | S1 | TC-REG-01 |

> **13 封 Email 全链路案例**（caseId、dpd、amount、scriptSlot）：[`email-templates/email-e2e-test-cases.md`](./email-templates/email-e2e-test-cases.md)  
> **Email 联调收件箱**：全部 **`wzynju@126.com`**（93xxx / 920xx）。Gmail 易 DMARC 拦截，Phase 1 不作为默认测试邮箱。

### 1.4 阶段门禁（哪个 TC 在何时可跑）

与 [开发执行指南 §7 Checklist](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md#7-推荐开发时间线checklist) 对齐：

| 最低阶段 | 可跑 TC |
|----------|---------|
| 随时（Mock 链路） | TC-REG-01 |
| 1b MockStepResolver 增强后 | TC-SMS-01（需 `sms_body`） |
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

### TC-SMS-01：LTH 短信发送

| 项 | 内容 |
|----|------|
| 门禁 | 执行指南阶段 1b+ |
| 前置 | `single-step=SMS`；user 90001；`channel.lth.sms.url` 有效 |
| 操作 | ingest 90001 → 等待扫描 |
| 预期 | ① LTH 收到短信或沙箱日志 ② step `COMPLETED`，`DELIVERED` ③ timeline 有 `provider_msg_id` ④ **无** `CHANNEL_CALLBACK` |
| 失败排查 | `sms_body` 是否在 metadata；Nacos URL |

### TC-PUSH-01：FCM 推送成功

| 项 | 内容 |
|----|------|
| 前置 | `single-step=PUSH`；user **90002**（有 fcmToken） |
| 预期 | FCM message id；step `COMPLETED` |

### TC-PUSH-02：无 token → SMS fallback

| 项 | 内容 |
|----|------|
| 前置 | `single-step=PUSH`；user **90003**（无 fcmToken） |
| 预期 | ① 引擎 **一次** dispatch（Gateway 内 fallback）② LTH 收到 SMS ③ step `COMPLETED` |

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

### TC-EMAIL-E2E：13 里程碑 Email 全链路

| 项 | 内容 |
|----|------|
| 前置 | `application-local.yml` 填齐 13 个 `templates`；`single-step=EMAIL` |
| 案例表 | [`email-templates/email-e2e-test-cases.md`](./email-templates/email-e2e-test-cases.md) |
| 操作 | 按表 `caseId` ingest，例如 `93101`（test_s1_user1 · S1 · dpd2 · ₱2500） |
| 预期 | 每案对应 scriptSlot 模板 + 变量与表一致 |

```bash
curl -X POST "http://localhost:8888/mock/ingest?caseId=93101&userId=93101&stage=S1"
curl -s "http://localhost:8888/plans/timeline/93101?limit=5"
```

PowerShell 本地密钥（可选，写入 `.env` 勿提交）：

```dotenv
SENDGRID_API_KEY=SG.xxxx
SENDGRID_FROM_EMAIL=notice.collections@your-domain.ph
SENDGRID_D0_TEMPLATE_ID=d-xxxxxxxx
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

启动后日志无 Nacos auth 失败；`ChannelProperties` 能读到 `channel.lth.sms.url`。

### TC-NACOS-02：热更新限额

修改 `daily-limit.SMS`（`@RefreshScope`）后新限额生效。

### TC-NACOS-03：本地覆盖

`application-local.yml` 改 `server.port` 生效，其余仍从 Nacos 拉取。

---

## 12. 测试记录模板

| TC ID | 日期 | 执行人 | 结果 | planId | 备注 |
|-------|------|--------|------|--------|------|
| TC-SMS-01 | | PASS/FAIL | | | |
| TC-CEASED-01 | | | | | |
| … | | | | | |

---

## 13. 通过标准（与开发执行指南 Checklist 映射）

### 必过（渠道模块 Phase 1）

- [ ] TC-REG-01 Mock 回归
- [ ] TC-SMS-01、TC-PUSH-01、TC-EMAIL-01 真实发送
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
