# MOCASA Phase 1 — LTH Voice（AI 外呼 / TTS）对接说明

> **版本**: v1.0 · **日期**: 2026-06-03  
> **供应商**: LTH SIP（`LthFunction.voiceNotification`）  
> **上级文档**: [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md)、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §3.5、§7.2、§7.10  
> **代码目标**: `com.collection.channel.adapter.LthVoiceAdapter`

---

## 1. 范围与依赖

| 项 | 说明 |
|----|------|
| Phase 1 | **AI 外呼可对话**；disposition 驱动分支与中断 |
| channelType | `AI_CALL`（主）；`TTS`（AI 不可用或编排显式降级） |
| snapshot | `basic.primaryPhone` |
| 排队 | **引擎不管** VoiceQueue；09:15/14:30 由 plan `trigger_time` 触发；Wave-2 由 **下一步骤** 或 LTH 任务模型承接 |

---

## 2. 调用模型

**异步渠道**：

```
dispatch 成功 → plan STEP_EXECUTING + CALLBACK_TIMEOUT(默认60min)
  → LTH 外呼完成 → POST /webhook/lth/voice
  → CHANNEL_CALLBACK → step COMPLETED → STEP_COMPLETED
```

超时：`CALLBACK_TIMEOUT` → step FAILED → 仍 `STEP_COMPLETED` 推进（引擎 §2.3.4）。

---

## 3. StepCommand → LTH API

| StepCommand | LTH voiceNotification |
|-------------|----------------------|
| targetAddress | 被叫号码 |
| templateId / metadata | 脚本 ID、机器人参数、语言 |
| metadata.callbackUrl | 回调基址（Adapter 配置） |
| metadata.timeoutMinutes | 60（S4 D+61~90 仍为 1 呼/日，超时同） |
| channelType=AI_CALL | 可对话机器人 |
| channelType=TTS | 单向播报（metadata 可设 `voice_mode=TTS`） |

**并发**：`LthVoiceAdapter` 内按 LTH 能力与运营配置限流；**不**在引擎侧维护 Redis VoiceQueue（§3.5）。

---

## 4. LTH 受理 → StepResult

| 结果 | success | contactResult | 引擎状态 |
|------|---------|---------------|----------|
| 任务创建成功 | true | DELIVERED | STEP_EXECUTING |
| LTH 拒绝/超时 | false | FAILED | 重试或 FAILED |

---

## 5. Webhook → CHANNEL_CALLBACK

### 5.1 路径

`POST /webhook/lth/voice` → 解析 LTH JSON → 发布：

```json
{
  "planId": 1,
  "stepId": 10,
  "caseId": 1001,
  "result": "ANSWERED",
  "disposition": "CONNECTED_NO_PAY",
  "providerMsgId": "lth-call-xxx"
}
```

可与现网 `POST /lth/agentCallBack` 并存：admin 内部分流 **机器轨**（带 planId/stepId）与 **人工轨** 话单。

### 5.2 disposition → 引擎动作（Phase 1 最小集）

| disposition | result (ContactResult) | 引擎 / 编排副作用 |
|-------------|------------------------|-------------------|
| CONNECTED_NO_PAY | ANSWERED | `STEP_COMPLETED`；Guard **CONNECT_AND_STOP** 挡当日 Wave-2 |
| NO_ANSWER | NO_ANSWER | 推进；下一 Wave-2 step 由 plan 调度 |
| BUSY | BUSY | 同 NO_ANSWER |
| PTP_CAPTURED | ANSWERED | admin 写 `t_collection_ptp_info` + 发 PTP 相关事件；暂停常规催 |
| DISPUTE | ANSWERED | 发争议类中断；cancel 当日 pending AI；`human_dial_override`；机器 SMS/Push/Email BLOCK |
| NEEDS_HUMAN | ANSWERED | 发需人工事件；cancel pending AI；Override；**不** 创建 HUMAN_CALL step |

> LTH 原始码表确定后，在本表前增加「LTH 码 → disposition」映射子表。

### 5.3 DefaultAdvancementPolicy

`onChannelCallback` 仅写 `result` 时：`AdvancementPolicy` 在 `STEP_COMPLETED` 后执行。  
**DISPUTE / NEEDS_HUMAN / PTP** 须在 callback 处理链中 **额外发布中断事件**（或扩展 `onChannelCallback`），与 [渠道编排 §9](./MOCASA催收系统升级_Phase1_渠道编排规格.md) 一致。

---

## 6. 错误码与 retryable

| errorCode | retryable |
|-----------|-----------|
| LTH_VOICE_TIMEOUT | true |
| LTH_VOICE_5XX | true |
| INVALID_MSISDN | false |

---

## 7. 幂等与对账

- 同一 `providerMsgId` 重复回调：引擎 `onChannelCallback` 对非 EXECUTING 态 **静默吸收**
- 二级对账：超时 Job 触发后，Adapter/Job 查 LTH 任务状态补写 timeline（引擎 §2.3.4）

---

## 8. Phase 1 特例

| 场景 | 行为 |
|------|------|
| Override | 引擎 cancel AI step 后，LTH **尽力** 撤单/过滤号码（有 API 则调，无则文档记录限制） |
| 接通未还 | 不取消当日 SMS/Push/Email（§7.2） |
| Wave-2 | 14:00+ 独立 plan step，`AI_CALL` + 条件「Wave-1 未接通」（PlanFactory/Guard 实现） |

---

## 9. 配置项

| 项 | 说明 |
|----|------|
| LTH_VOICE_URL | SystemVariable |
| 回调 URL | 指向 collection-admin 公网地址 |
| 机器人/脚本 ID | 按 Stage×Tone 配置 |

---

## 10. 联调检查清单

- [ ] dispatch 后 plan 保持 STEP_EXECUTING
- [ ] `POST /webhook/lth/voice` + ANSWERED → step COMPLETED
- [ ] 60min 无回调 → CALLBACK_TIMEOUT → FAILED → 仍推进
- [ ] disposition=DISPUTE 后当日无第二个 AI step 执行
- [ ] 重复回调幂等

---

## 11. 待 LTH 确认

| 项 | 用途 |
|----|------|
| voiceNotification 请求/响应 JSON 样例 | Adapter 开发 |
| 回调字段与 disposition 枚举 | 映射表 |
| 撤单/过滤 API | Override 联调 |

---

> 现网参考：[case-assign-and-LTH-lifecycle.md](../../AI%20collection/相关资料/case-assign-and-LTH-lifecycle.md) §2.1、§2.3 Phase 5
