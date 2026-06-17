# 渠道编排与渠道执行 — 文档索引

> **维护位置**：本目录 `docs/channel/` 为 **渠道规格 + L3 执行说明** 的唯一定稿位置，后续请只在此修改。  
> **结构**：本目录 **12 个文件 · 平铺**（无子目录；跨目录资源见下方标注「docs 根」）。  
> **引擎对齐**：Phase 1 渠道编排与核心引擎已对齐（`CASE_CEASED`、七步管线、禁止 `HUMAN_CALL` step 等）；交叉引用见 [核心引擎规格](../MOCASA催收系统升级_Phase1_核心引擎规格.md)、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md)。

---

## 快速导航

| 角色 | 从这里开始 |
|------|-----------|
| 开发 | [开发执行指南](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md) → [开发进度](./MOCASA催收系统升级_Phase1_collection-channel开发进度.md) |
| 策略 / 运营 | [策略迭代与测试操作手册](../MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md)（docs 根） |
| QA | [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) |
| 模板 / 运营 | [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) → [email-templates/](../email-templates/)（docs 根） |
| 本地启动 | [操作说明_Nacos本地启动](../操作说明_Nacos本地启动.md)（docs 根） |

---

## L1 编排策略

| 文档 | 说明 |
|------|------|
| [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) | PlanFactory / Guard / Stage 槽位；**§3.5 Phase 1 实现范围** |

---

## L3 渠道执行（collection-channel）

### 手册与总规格

| 文档 | 说明 |
|------|------|
| [开发执行指南](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md) | **开发手册**：分阶段写代码、Nacos 配置、Checklist |
| [开发进度](./MOCASA催收系统升级_Phase1_collection-channel开发进度.md) | **进度 SSOT**：Checklist 状态、现网对照、下一步、变更日志 |
| [策略迭代与测试操作手册](../MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md) | **策略/运营手册**（docs 根）：怎么测、怎么改、DB 与 Nacos 分工 |
| [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | **测试手册**：TC 用例、curl、验收标准 |
| [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md) | ChannelGateway、契约、Webhook；**附录 A** → [渠道模板清单](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) |

### 渠道模板（全渠道 SSOT）

| 文档 / 资源 | 说明 |
|-------------|------|
| [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) | **SMS / Push / Email / Voice** scriptSlot 总表、Nacos 配置 |
| [email-templates/](../email-templates/) | Email HTML、`_layouts/`、`subjects.md`、`email-templates-test/` Test Data（docs 根） |

### 契约对齐

| 文档 | 说明 |
|------|------|
| [ContextSnapshot 字段透传说明](./MOCASA催收系统升级_Phase1_ContextSnapshot字段透传说明.md) | 快照 → StepCommand → 供应商 API 全链字段映射 SSOT |

### 供应商 Adapter 对接

| 文档 | 说明 |
|------|------|
| [Notification 对接说明](./MOCASA催收系统升级_Phase1_Notification对接说明.md) | **SSOT**：`NotificationSmsAdapter` + `NotificationPushAdapter`（SMS / App Push 均经通知中心） |
| [SendGrid Email 对接说明](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) | `SendGridEmailAdapter` |
| [LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) | `LthVoiceAdapter`（Phase 1 仅 TTS） |

> 已废止/合并：催收 **SMS、App Push** 统一由 **Notification 对接说明**（§1 / §2）描述；旧 `SMS / App_Push / LTH_SMS / FCM_Push 对接说明` 独立跳转页已删除（不再使用 FCM 直连、LTH 直发短信）。

---

## 引擎 / 架构（docs 根级，交叉引用）

| 文档 | 说明 |
|------|------|
| [核心引擎规格](../MOCASA催收系统升级_Phase1_核心引擎规格.md) | 七步管线、`CASE_CEASED` Consumer、CHANNEL_CALLBACK |
| [领域模型与数据定义](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md) | StepCommand、ContactResult、运行态表 |
| [基础设施交互规范](../MOCASA催收系统升级_Phase1_基础设施交互规范.md) | Redis Stream、XXL-Job、`dpdStageRollHandler` |
| [架构设计文档](../MOCASA催收系统升级_Phase1_架构设计文档.md) | 模块边界、部署拓扑 |
| [产品需求文档 PRD](../MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) | Phase 1 产品范围 |
| [HANDOFF](../HANDOFF.md) | 模块接续与 Mock 替换清单 |

---

## 外部参考（`AI collection/`，不随本目录迁移）

| 文档 | 说明 |
|------|------|
| [SendGrid催收邮件接入指南.md](../../AI%20collection/SendGrid催收邮件接入指南.md) | SendGrid API 附录（`POST /v3/mail/send` 等） |
| [MOCASA催收策略编排_行业调研报告_v1.md](../../AI%20collection/MOCASA催收策略编排_行业调研报告_v1.md) | 行业编排对照 |
| [philippines_fintech_channel_vendor_selection_report.md](../../AI%20collection/philippines_fintech_channel_vendor_selection_report.md) | 菲律宾渠道选型 |
| [notification-send-api.md](../../AI%20collection/相关资料/notification-send-api.md) | **通知中心 API SSOT**（SMS / App Push） |
| [case-assign-and-LTH-lifecycle.md](../../AI%20collection/相关资料/case-assign-and-LTH-lifecycle.md) | LTH 现网生命周期（外呼） |
| [QH SMS 接口.md](../../AI%20collection/相关资料/QH%20SMS%20接口.md) | 通知中心底层排障（非引擎直连） |
| [HiwayIO-API 1.5.2.docx](../../AI%20collection/相关资料/HiwayIO-API%201.5.2.docx) | 同上 |
| [【BORI】HTTP 对接开发文档1.0.docx](../../AI%20collection/相关资料/【BORI】HTTP%20对接开发文档1.0.docx) | 同上 |
