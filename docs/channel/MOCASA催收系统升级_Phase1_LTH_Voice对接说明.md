# MOCASA Phase 1 — AI Call 对接说明

> **版本**: v1.1  
> **日期**: 2026-07-02  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-channel`  
> **关联文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[渠道编排规格 §3.5 / §7](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围)、[架构 §1.1](../MOCASA催收系统升级_Phase1_架构设计文档.md#11-架构总览)

> **边界**：本系统机器轨语音渠道为 **`AI_CALL` 仅此一种**。**TTS / 人工外呼由 LTH 现网独立编排，与本系统无 plan step、无 Adapter、无 Webhook 交互**。

---

## 1. 范围与依赖

| 项 | 说明 |
|----|------|
| Phase 1 | AI 外呼可对话；disposition 驱动分支与中断 |
| channelType | **`AI_CALL` 仅此** |
| snapshot | `basic.primaryPhone` |
| 排队 | **引擎不管** VoiceQueue；09:15/14:30 由 plan `trigger_time` 触发；Wave-2 由 **下一步骤** 或供应商任务模型承接 |

---

## 2. 调用模型

**异步渠道**：

```
dispatch 成功 → plan STEP_EXECUTING + CALLBACK_TIMEOUT(默认60min)
  → AI Call 供应商完成 → POST /webhook/.../voice
  → CHANNEL_CALLBACK → step COMPLETED → STEP_COMPLETED
```

超时：`CALLBACK_TIMEOUT` → step FAILED → 仍 `STEP_COMPLETED` 推进（引擎 §2.3.4）。

---

## 3. StepCommand → 供应商 API

| StepCommand | 供应商 voice API |
|-------------|------------------|
| targetAddress | 被叫号码 |
| templateId / metadata | 脚本 ID、机器人参数、语言 |
| metadata.callbackUrl | 回调基址（Adapter 配置） |
| metadata.timeoutMinutes | 60（S4 D+61~90 仍为 1 呼/日，超时同） |
| channelType=AI_CALL | 可对话机器人 |

**并发**：Adapter 内按供应商能力与运营配置限流；**不**在引擎侧维护 Redis VoiceQueue（§3.5）。

---

## 4. 供应商受理 → StepResult

| 结果 | success | contactResult | 引擎状态 |
|------|---------|---------------|----------|
| 任务创建成功 | true | DELIVERED | STEP_EXECUTING |
| 供应商拒绝/超时 | false | FAILED | 重试或 FAILED |

---

## 5. Webhook → CHANNEL_CALLBACK

### 5.1 路径

`POST /webhook/.../voice` → 解析供应商 JSON → 发布：

```json
{
  "planId": 1,
  "stepId": 10,
  "caseId": 1001,
  "result": "ANSWERED",
  "providerMsgId": "xxx",
  "disposition": "PTP"
}
```

### 5.2 disposition 映射（示例）

| disposition | contactResult | 推进 |
|---|---|---|
| ANSWERED | ANSWERED | 正常推进 |
| NO_ANSWER | NO_ANSWER | 正常推进 |
| BUSY | BUSY | 正常推进 |

> 供应商原始码表确定后，在本表前增加「供应商码 → disposition」映射子表。

---

## 6. 错误与重试

| errorCode | retryable |
|---|---|
| VOICE_TIMEOUT | true |
| VOICE_5XX | true |

---

## 7. 对账与 Override

- 一级：`CALLBACK_TIMEOUT` 哨兵（引擎主路径）
- 二级：超时 Job 触发后，Adapter/Job 查供应商任务状态补写 timeline（引擎 §2.3.4）
- Override：引擎 cancel AI step 后，供应商 **尽力** 撤单/过滤号码（有 API 则调，无则文档记录限制）

---

## 8. 配置项

| 键 | 来源 |
|---|---|
| VOICE_URL | SystemVariable / Nacos `channel.*.voice.*` |

---

> 现网参考：[case-assign-and-LTH-lifecycle.md](./reference/case-assign-and-LTH-lifecycle.md) §2.1、§2.3 Phase 5
