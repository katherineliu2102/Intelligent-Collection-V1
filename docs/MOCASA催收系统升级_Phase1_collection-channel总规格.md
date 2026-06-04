# MOCASA 催收系统升级 — Phase 1 collection-channel 总规格

> **版本**: v1.0  
> **日期**: 2026-06-03  
> **定位**: 渠道**执行子层**（哑管道）：`ChannelGateway`、供应商 Adapter、Webhook 接入；与 **策略子层** SPI（`PlanFactory` / `ExecutionGuard` / `StepResolver` 等）同属 `collection-channel` 模块。  
> **范围**: Phase 1 机器轨 — SMS（LTH）、Email（SendGrid）、Push（FCM）、AI 外呼 / TTS（LTH）。  
> **关联文档**: [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §3.5、[核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)、[领域模型](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md)、[HANDOFF 模块 A](../HANDOFF.md)、[选型报告](../../AI%20collection/philippines_fintech_channel_vendor_selection_report.md)

---

## 目录

- [1. 文档分层与模块边界](#1-文档分层与模块边界)
- [2. 与引擎的端到端链路](#2-与引擎的端到端链路)
- [3. 契约：StepCommand / StepResult / CHANNEL_CALLBACK](#3-契约stepcommand--stepresult--channel_callback)
- [4. collection-common 演进（Phase 1 必选）](#4-collection-common-演进phase-1-必选)
- [5. ChannelGateway 与 Adapter](#5-channelgateway-与-adapter)
- [6. 同步渠道 vs 异步渠道](#6-同步渠道-vs-异步渠道)
- [7. Webhook 与 collection-admin](#7-webhook-与-collection-admin)
- [8. 幂等、重试与配置](#8-幂等重试与配置)
- [9. 子渠道文档索引](#9-子渠道文档索引)
- [10. E2E 样例（字段级 trace）](#10-e2e-样例字段级-trace)
- [附录 A：scriptSlot → 供应商 template_id 映射表](#附录-ascriptslot--供应商-template_id-映射表)

---

## 1. 文档分层与模块边界

| 层级 | 文档 / 代码 | 职责 |
|------|-------------|------|
| L1 编排策略 | [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) | Stage 槽位、Guard 规则、Tone、事件语义 |
| L2 引擎 | `collection-engine` + [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md) | 状态机、七步管线、事件消费 |
| L3 渠道执行 | **本文 + 四份子渠道说明** | API 调用、Webhook、Adapter |

**哑管道原则**（架构设计）：Adapter **不查业务库**；案件/画像信息仅来自 `StepCommand` 与 `ExecutionContext.contextSnapshot`（由 `StepResolver` 读取）。

**替换初版 Mock**（[HANDOFF.md §四 模块 A](../HANDOFF.md)）：

| Mock 类 | 目标类 |
|---------|--------|
| `MockPlanFactory` | `DefaultPlanFactory` |
| `MockExecutionGuard` | `ComplianceExecutionGuard` |
| `MockStepResolver` | `DefaultStepResolver` |
| `MockAdvancementPolicy` | `DefaultAdvancementPolicy` |
| `MockExhaustionPolicy` | `DefaultExhaustionPolicy` |
| `MockChannelGateway` | `ChannelGatewayImpl` |

---

## 2. 与引擎的端到端链路

```
PLAN_STEP_DUE
  → PlanLifecycleManager.prepareStepDue (事务内状态前置)
  → StepExecutionOrchestrator.executeStep (非事务)
       ① IdempotencyService.acquire
       ② PreFlightChecker (还款/冻结)
       ③ ExecutionGuard.evaluate
       ④ StepResolver.resolve → StepCommand
       ⑤ ChannelGateway.dispatch → StepResult
       ⑥ 故障降级 / ⑦ 渠道分流
  → 消息类: STEP_COMPLETED
  → 异步类: 保持 STEP_EXECUTING → CHANNEL_CALLBACK → STEP_COMPLETED
```

---

## 3. 契约：StepCommand / StepResult / CHANNEL_CALLBACK

### 3.1 StepCommand（Java：`com.collection.common.dto.StepCommand`）

| 字段 | 类型 | 说明 |
|------|------|------|
| channelType | ChannelType | SMS / PUSH / EMAIL / AI_CALL / TTS |
| targetAddress | String | 手机 / 邮箱 / FCM token（由 Resolver 按渠道填入） |
| templateId | String | scriptSlot 或 SendGrid `d-xxx` / 内部模板键 |
| idempotencyKey | String | 建议 `{planId}:{stepOrder}:{retryCount}` |
| metadata | Map | 见下表 |

**metadata 已知 key（Phase 1）**

| key | 类型 | 必填场景 | 说明 |
|-----|------|----------|------|
| stage | String | 建议 | `Stage.name()` |
| language | String | 建议 | `tl` / `en` |
| callbackUrl | String | AI_CALL / TTS | 供应商回调基址（Adapter 拼 LTH 回调） |
| timeoutMinutes | Integer | AI_CALL / TTS | 默认 60，覆盖引擎 `callback_timeout_minutes` |
| scriptSlot | String | 建议 | 编排话术槽名，如 `S1_SMS_STANDARD` |
| sms_body | String | SMS | **StepResolver 渲染后的正文**（LTH 只发送） |
| dynamicTemplateData | Map | EMAIL | SendGrid Handlebars 变量 |
| fallback_sms | Boolean | PUSH 输出 | PushAdapter fallback 后=true |
| case_id | Long | 建议 | 透传 SendGrid custom_args / 日志 |

### 3.2 StepResult

| 字段 | 规则 |
|------|------|
| success | `contactResult` 非 FAILED 类 → true |
| contactResult | 见领域模型 §6.2；消息类 dispatch 成功常用 **DELIVERED**（已提交供应商） |
| retryable | 网络超时/5xx=true；号码/token 无效=false |
| providerMsgId | LTH/SendGrid/FCM 返回 ID，回调与对账关联 |

### 3.3 CHANNEL_CALLBACK 事件 payload

初版 [WebhookController](../collection-admin/src/main/java/com/collection/admin/web/WebhookController.java) 扩展后统一信封：

| payload key | 类型 | 必填 | 说明 |
|-------------|------|------|------|
| planId | Long | 是 | |
| stepId | Long | 是 | |
| caseId | Long | 建议 | |
| result | String | 是 | `ContactResult` 名：ANSWERED / NO_ANSWER / BUSY / … |
| disposition | String | Voice 建议 | 业务分支：见 [LTH Voice](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) |
| providerMsgId | String | 建议 | 对账 |

**引擎行为**（`PlanLifecycleManager.onChannelCallback`）：仅当 plan 为 `STEP_EXECUTING` 时更新 step → `STEP_COMPLETED`。

**SendGrid Event Webhook**：**不**发布 `CHANNEL_CALLBACK` 完成步骤；由 admin 服务 **更新** `t_contact_timeline`（同 `providerMsgId` 幂等升级 READ/CLICKED 等）。

---

## 4. collection-common 演进（Phase 1 必选）

与 [HANDOFF「契约可演进」](../HANDOFF.md) 一致，下列扩展须在实现 Adapter 前合入 `collection-common` / `collection-service`：

| 项 | 变更 |
|----|------|
| UserProfile | `BasicInfo.email`；`DeviceInfo.fcmToken` |
| CaseContext | `repaymentUrl`（ingestion 从 App/信贷写入，供模板变量） |
| CollectionEvent | 常量 `DISPOSITION`、`PROVIDER_MSG_ID` |
| EventType | `CASE_CEASED`（ingestion 发、引擎 Consumer 处理，**非本模块实现**） |
| StepCommand | metadata 键：`META_SCRIPT_SLOT`、`META_SMS_BODY`、`META_DYNAMIC_TEMPLATE_DATA` 等 |

---

## 5. ChannelGateway 与 Adapter

### 5.1 包结构（建议）

```
collection-channel/
  gateway/ChannelGatewayImpl.java
  adapter/LthSmsAdapter.java
  adapter/SendGridEmailAdapter.java
  adapter/FcmPushAdapter.java
  adapter/LthVoiceAdapter.java
  strategy/DefaultPlanFactory.java
  strategy/ComplianceExecutionGuard.java
  strategy/DefaultStepResolver.java
  strategy/DefaultAdvancementPolicy.java
  strategy/DefaultExhaustionPolicy.java
```

### 5.2 路由

| channelType | Adapter | 子文档 |
|-------------|---------|--------|
| SMS | LthSmsAdapter | [LTH SMS](./MOCASA催收系统升级_Phase1_LTH_SMS对接说明.md) |
| EMAIL | SendGridEmailAdapter | [SendGrid Email](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) |
| PUSH | FcmPushAdapter | [FCM Push](./MOCASA催收系统升级_Phase1_FCM_Push对接说明.md) |
| AI_CALL / TTS | LthVoiceAdapter | [LTH Voice](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) |
| HUMAN_CALL | — | **禁止** dispatch（StepResolver 不得输出） |

`ChannelGatewayImpl.dispatch`：

1. 渠道幂等（Redis `idempotency:channel:{idempotencyKey}`，TTL 24h，见基础设施规范 §3）
2. 路由 Adapter
3. 熔断 / fallback 在 Adapter 或 Gateway 内完成，**只返回最终** `StepResult`

---

## 6. 同步渠道 vs 异步渠道

依据初版 `ChannelType.isMessageChannel()` / `StepExecutionOrchestrator` ⑦：

| 分类 | channelType | dispatch 后引擎 | 供应商二次回调 |
|------|-------------|-----------------|----------------|
| 消息类（同步） | SMS, PUSH, EMAIL | `STEP_COMPLETED`（无 observation 时） | 仅 timeline enrichment，**不**完成 step |
| 电话类（异步） | AI_CALL, TTS | `STEP_EXECUTING` + `CALLBACK_TIMEOUT` Job | `CHANNEL_CALLBACK` **必须** |

---

## 7. Webhook 与 collection-admin

| 路径 | 供应商 | 行为 |
|------|--------|------|
| `POST /webhook/channel-callback` | 联调 / 骨架 | 已有；扩展支持 `disposition` |
| `POST /webhook/lth/voice` | LTH AI/TTS | 解析话单 → `CHANNEL_CALLBACK` |
| `POST /webhook/sendgrid` | SendGrid | 验签（TODO）→ 更新 timeline / suppression |

鉴权：Phase 1 可跳过；生产须 SendGrid 签名校验 + LTH IP/签名（TODO 列入运维）。

---

## 8. 幂等、重试与配置

| 机制 | 说明 |
|------|------|
| 步骤幂等 | 引擎 `IdempotencyService`，key=`planId:stepOrder:retryCount` |
| 渠道幂等 | Redis + SendGrid `custom_args.idempotency_key` |
| 引擎重试 | `retryable=true` 时注册延迟 `PLAN_STEP_DUE`（Orchestrator ⑥） |
| 配置表 | `t_channel_config`（HANDOFF 列，DDL 待补 schema）；`credentials_ref` 指向密钥 |

---

## 9. 子渠道文档索引

| 文档 | 供应商 API |
|------|------------|
| [LTH SMS 对接说明](./MOCASA催收系统升级_Phase1_LTH_SMS对接说明.md) | `LthFunction.sendSms` |
| [SendGrid Email 对接说明](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) | `POST /v3/mail/send`；API 附录见 [SendGrid催收邮件接入指南](./SendGrid催收邮件接入指南.md) |
| [LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) | `voiceNotification` + 回调 |
| [FCM Push 对接说明](./MOCASA催收系统升级_Phase1_FCM_Push对接说明.md) | FCM HTTP v1 |

---

## 10. E2E 样例（字段级 trace）

### 10.1 同步：S1 08:00 SMS

```
PLAN_STEP_DUE(planId=1, stepId=10)
  → Guard: allowed
  → StepCommand{
       channelType=SMS,
       targetAddress="639171234567",
       templateId="S1_SMS_STANDARD",
       idempotencyKey="1:1:0",
       metadata={ scriptSlot, sms_body, stage=S1, language=tl, case_id=1001 }
     }
  → LthSmsAdapter → LTH sendSms
  → StepResult{ success=true, contactResult=DELIVERED, providerMsgId="lth-xxx" }
  → timeline WRITE
  → STEP_COMPLETED → AdvancementPolicy
```

### 10.2 异步：S1 09:15 AI_CALL

```
PLAN_STEP_DUE → StepCommand{ channelType=AI_CALL, metadata={ callbackUrl, timeoutMinutes=60, disposition... } }
  → LthVoiceAdapter → voiceNotification 受理
  → StepResult{ success=true, DELIVERED }  // 仅表示已提交
  → plan STEP_EXECUTING, timeout_time=now+60m
  → (later) POST /webhook/lth/voice
  → CHANNEL_CALLBACK{ planId, stepId, result=ANSWERED, disposition=CONNECTED_NO_PAY }
  → step COMPLETED → STEP_COMPLETED
```

---

## 附录 A：scriptSlot → 供应商 template_id 映射表

> Phase 1 先填 **S0–S2** 与 S1 Voice；`template_id` 由运营在 SendGrid 控制台创建后填入。SMS 使用 `sms_body` 渲染，**无** LTH template_id 列。

| scriptSlot | channel | provider | template_id / 渲染 | language | Phase 1 状态 |
|------------|---------|----------|----------------------|----------|--------------|
| S0_REMINDER | SMS | LTH | Resolver→sms_body | tl | 待填文案 |
| S0_DUE_TODAY_EMAIL | EMAIL | SendGrid | d-____________ | tl/en | 待运营创建 |
| S1_SMS_STANDARD | SMS | LTH | Resolver→sms_body | tl | 待填文案 |
| S1_PUSH_STANDARD | PUSH | FCM | data payload 模板 | tl | 待 App 深链 |
| S1_EMAIL_OVERDUE_NOTICE | EMAIL | SendGrid | d-____________ | tl/en | D+1 里程碑 |
| S1_VOICE_PRIMARY | AI_CALL | LTH | voice 脚本 ID / 参数 | tl | 待 LTH 确认 |
| S2_SMS_STANDARD | SMS | LTH | Resolver→sms_body | tl | 待填文案 |
| S2_EMAIL_ENTRY | EMAIL | SendGrid | d-____________ | tl/en | D+4 里程碑 |
| … | … | … | … | … | S3/S4 上线前补全 |

**Phase 2 保留、Phase 1 不生成 step**：`S1_EMAIL_CONDITIONAL`、`S2_EMAIL_CONDITIONAL` 等。

---

> MOCASA Collection — Phase 1 collection-channel Master Spec v1.0 — 2026-06-03
