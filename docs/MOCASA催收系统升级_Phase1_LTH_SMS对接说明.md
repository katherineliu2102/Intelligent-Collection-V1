# MOCASA Phase 1 — LTH SMS 对接说明

> **版本**: v1.0 · **日期**: 2026-06-03  
> **供应商**: LTH（现网 `LthFunction.sendSms`）  
> **上级文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §3.5  
> **代码目标**: `com.collection.channel.adapter.LthSmsAdapter`（替换 `MockChannelGateway` 中 SMS 分支）

---

## 1. 范围与依赖

| 项 | 说明 |
|----|------|
| Phase 1 | 唯一 SMS 主通道；不签 Semaphore/PhilSMS 备通道 |
| snapshot | `contextSnapshot.userProfile.basic.primaryPhone`（ingestion 映射 `t_user_basis.phone`，建议含国家码 `63`） |
| 编排 | 各 Stage 08:00 等 SMS 槽位；Push 失败 fallback 亦调用本 Adapter |

**不在本 Adapter 内**：`filterNumber`（还款/停催由引擎 `PreFlightChecker` 与中断事件处理）。

---

## 2. 调用模型

**同步渠道**：`dispatch` 返回后引擎即 `STEP_COMPLETED`（见总规格 §6）。

```
StepCommand(SMS) → LthSmsAdapter → HTTP sendSms → StepResult
```

Phase 1 **不依赖** LTH SMS delivery Webhook；无送达回执时 `contactResult=DELIVERED` 表示「已提交 LTH」。

---

## 3. StepCommand → LTH API

| StepCommand | LTH 请求 |
|-------------|----------|
| targetAddress | 被叫手机号 |
| metadata.sms_body | 短信正文（**DefaultStepResolver 渲染**，见总规格 §4） |
| metadata.scriptSlot | 日志 / 分类 |
| idempotencyKey | 业务去重键（LTH 若支持则透传） |

**渲染责任**：`DefaultStepResolver` 按 `scriptSlot` + snapshot（应还金额、DPD、深链等）生成 `sms_body`；Adapter **不**再查库。

**还款深链**：使用 `caseContext.repaymentUrl`（ingestion 写入）；变量规则与 Push/Email 一致。

---

## 4. LTH 响应 → StepResult

| LTH 结果 | success | contactResult | retryable |
|----------|---------|---------------|-----------|
| HTTP/业务成功 | true | DELIVERED | false |
| 超时 / 5xx | false | FAILED | true |
| 号码格式非法 | false | FAILED | false |

`providerMsgId`：LTH 返回的消息/任务 ID（若有）。

---

## 5. Webhook / 回调

Phase 1：**无** `CHANNEL_CALLBACK` 用于 SMS 步骤完成。

若 LTH 后续提供 delivery 回调，仅 **更新** `t_contact_timeline.result`（同 `providerMsgId`），**不** 发布 `STEP_COMPLETED`。

---

## 6. 错误码与 retryable

| errorCode | retryable | 说明 |
|-----------|-----------|------|
| LTH_TIMEOUT | true | 可退避重试（引擎 ⑥） |
| LTH_5XX | true | |
| INVALID_MSISDN | false | 标记号码无效，可选写 snapshot 扩展 |

---

## 7. 幂等与对账

- 引擎步骤幂等：`planId:stepOrder:retryCount`
- 渠道 Redis：`idempotency:channel:{idempotencyKey}`
- 对账：按 `providerMsgId` 与 `TLthSmsSendRecord`（现网表）可选交叉核对

---

## 8. Phase 1 特例

| 场景 | 行为 |
|------|------|
| Push fallback | `FcmPushAdapter` 调用本 Adapter，`idempotencyKey` 后缀 `:sms_fallback`，`metadata.fallback_sms=true` |
| 合规拦截 | 由 `ComplianceExecutionGuard` 在 dispatch 前 BLOCK，不调用 LTH |

---

## 9. 配置项

| 配置 | 说明 |
|------|------|
| LTH SMS URL | `SystemVariable` / `t_channel_config.credentials_ref` |
| Sender ID | 现网已审批 ID，勿在 Phase 1 变更 |

---

## 10. 联调检查清单

- [ ] `primaryPhone` 有值案件可 sendSms 成功
- [ ] 引擎 timeline 有 DELIVERED + providerMsgId
- [ ] plan step 状态 COMPLETED，无 CHANNEL_CALLBACK
- [ ] 重复 `PLAN_STEP_DUE` 幂等跳过
- [ ] Push 无 token 时 fallback SMS 仅一条 timeline（或两条带 fallback 标记，产品可接受）

---

> 现网参考：[case-assign-and-LTH-lifecycle.md](../../AI%20collection/相关资料/case-assign-and-LTH-lifecycle.md) §2.1 `sendSms`
