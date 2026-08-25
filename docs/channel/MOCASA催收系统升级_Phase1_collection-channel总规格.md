# collection-channel 执行规格

> 适用：`collection-channel` 执行子层。编排规则见[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md)；跨模块运行时语义见[引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md)。

## 职责

`collection-channel` 只消费 `ExecutionContext` 和 `StepCommand`，不查询业务库。`StepResolver` 负责取址、选槽和渲染；`ChannelGateway` 路由 Adapter 并返回最终 `StepResult`；Adapter 只做供应商请求映射、鉴权和结果分类。

| 范围 | SSOT |
|---|---|
| 计划槽位、Guard、频率与阶段 | [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) |
| 快照字段、寻址和 metadata | [ContextSnapshot 字段透传](./MOCASA催收系统升级_Phase1_ContextSnapshot字段透传说明.md) |
| `StepResult`、重试、空地址、完成时机 | [引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md) |
| scriptSlot、模板 ID、Nacos 配置 | [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) |

## 执行链路

```text
PLAN_STEP_DUE
  → Engine：Guard → StepResolver → ChannelGateway
  → Adapter：供应商受理 / 可证明未发送 / 结果未知或确定失败
  → Engine：同步完成、退避重试或失败推进
```

| 渠道 | Adapter | 完成方式 |
|---|---|---|
| SMS | `NotificationSmsAdapter` | 供应商受理即完成 |
| PUSH | `NotificationPushAdapter` | 入队即完成；无 token 时同槽 fallback SMS |
| EMAIL | `SendGridEmailAdapter` | SendGrid 受理即完成 |
| AI_CALL | `FacadeAiCallAdapter` | 异步；等待 Facade 回调或超时 |
| HUMAN_CALL | — | Phase 1 禁止生成或调度 |

消息渠道的送达、打开和点击只能补充 timeline，不得改变步骤终态。AI_CALL 的出站能力与入站阻断分别见[Facade 接入说明](./MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md)和[回调入站交接](./MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md)。

## Webhook

| 入口 | 用途 | 状态 |
|---|---|---|
| `POST /webhook/channel-callback` | 内部口径的通用回调与联调 | 已有 |
| `POST /webhook/facade-callback` | Facade 账户级 `session.completed` / `batch.completed` 回调 | 待实现，T4 阻断 |
| SendGrid Event Webhook | 时间线 enrichment / 抑制名单 | 不参与步骤完成 |

Facade 专用入口须使用 `session_id` 幂等、验证 `X-Valubo-Signature`，并将供应商结果映射为 `ContactResult` 后再发布 `CHANNEL_CALLBACK`。不得把 `VOICEMAIL`、`CALL_SCREENING` 等原生值直接传给引擎。

## 配置与运行边界

- Phase 1 配置由 Nacos `channel.*` 管理；密钥不入仓库。
- 引擎步骤幂等键为 `{planId}:{stepOrder}:{retryCount}`；供应商去重与结果未知后的重试边界按执行契约处理。
- 合规 Guard 在调用 Adapter 前执行；Adapter 不重复写 timeline 或扣频控额度。
- LTH 仅保留人工轨现网说明，见[LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md)，不替代 Facade AI_CALL。

## 供应商文档

| 渠道 | 文档 |
|---|---|
| SMS / PUSH | [Notification 对接说明](./MOCASA催收系统升级_Phase1_Notification对接说明.md) |
| EMAIL | [SendGrid Email 对接说明](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) |
| AI_CALL 外部 API | [Facade 客户接入手册](./FACADE客户接入手册.md) |
| AI_CALL 出站 / 入站 | [Facade 接入说明](./MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md) / [回调入站交接](./MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md) |
| 人工轨 | [LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) |
