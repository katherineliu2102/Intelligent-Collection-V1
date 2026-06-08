# 渠道编排与渠道执行 — 文档索引

> **维护位置**：本目录 `Intelligent-Collection-V1/docs/` 为 **渠道规格 + L3 执行说明** 的唯一定稿位置，后续请只在此修改。

## L1 编排策略

| 文档 | 说明 |
|------|------|
| [MOCASA催收系统升级_Phase1_渠道编排规格.md](./MOCASA催收系统升级_Phase1_渠道编排规格.md) | PlanFactory / Guard / Stage 槽位；**§3.5 Phase 1 实现范围** |
| [MOCASA催收系统升级_Phase1_渠道编排与引擎对齐待办.md](./MOCASA催收系统升级_Phase1_渠道编排与引擎对齐待办.md) | 引擎侧 E1–E8 对齐会议稿 |

## L3 渠道执行（collection-channel）

| 文档 | 说明 |
|------|------|
| [MOCASA催收系统升级_Phase1_collection-channel总规格.md](./MOCASA催收系统升级_Phase1_collection-channel总规格.md) | ChannelGateway、契约、Webhook、附录 A 映射表 |
| [MOCASA催收系统升级_Phase1_LTH_SMS对接说明.md](./MOCASA催收系统升级_Phase1_LTH_SMS对接说明.md) | `LthSmsAdapter` |
| [MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) | `SendGridEmailAdapter` |
| [SendGrid催收邮件接入指南.md](./SendGrid催收邮件接入指南.md) | SendGrid API 附录 |
| [MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) | `LthVoiceAdapter`（AI/TTS） |
| [MOCASA催收系统升级_Phase1_FCM_Push对接说明.md](./MOCASA催收系统升级_Phase1_FCM_Push对接说明.md) | `FcmPushAdapter` |

## 同目录引擎/架构（交叉引用）

| 文档 | 说明 |
|------|------|
| [MOCASA催收系统升级_Phase1_核心引擎规格.md](../MOCASA催收系统升级_Phase1_核心引擎规格.md) | 七步管线、CHANNEL_CALLBACK |
| [MOCASA催收系统升级_Phase1_领域模型与数据定义.md](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md) | StepCommand、ContactResult |
| [HANDOFF.md](../../HANDOFF.md) | Mock 替换清单（模块 A） |

## 外部参考（`AI collection/`，不随本目录迁移）

- [行业调研报告](../../../AI%20collection/MOCASA催收策略编排_行业调研报告_v1.md)
- [渠道选型报告](../../../AI%20collection/philippines_fintech_channel_vendor_selection_report.md)
- [LTH 现网生命周期](../../../AI%20collection/相关资料/case-assign-and-LTH-lifecycle.md)
