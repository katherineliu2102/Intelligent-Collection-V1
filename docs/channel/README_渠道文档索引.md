# 渠道编排与渠道执行 — 文档索引

> **维护位置**：本目录 `docs/channel/` 为渠道规格与渠道执行说明的唯一定稿位置，后续请只在此修改。
> **结构**：规格文档 **12 个文件 · 平铺**；外部供应商/参考资料统一归入 [`reference/`](./reference/)（不与规格平铺混放）。  
> **引擎对齐**：Phase 1 渠道编排与核心引擎已对齐（`CASE_CEASED`、七步管线、禁止 `HUMAN_CALL` step 等）；交叉引用见 [核心引擎规格](../MOCASA催收系统升级_Phase1_核心引擎规格.md)、[渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md)。
> **术语边界**：下文“编排策略 / 渠道执行”是渠道模块架构分区，不是测试层级。测试 L0–L4a/L4b 与 T0–T6 以[测试 SSOT](../testing/MOCASA催收系统升级_Phase1_测试文档.md)为准。

---

## 快速导航

| 角色 | 从这里开始 |
|------|-----------|
| 开发 | `collection-channel` 源码 · 进度板 [HANDOFF §3A](../../HANDOFF.md) |
| 策略 / 运营 | [策略迭代与测试操作手册](./MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md) |
| QA | [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) |
| 模板 / 运营 | [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) → [email-templates/](../email-templates/)（docs 根） |
| 本地启动 | [操作说明_Nacos本地启动](../操作说明_Nacos本地启动.md)（docs 根） |

---

## 编排策略（渠道模块架构）

| 文档 | 说明 |
|------|------|
| [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) | PlanFactory / Guard / Stage 槽位；**§3.5 Phase 1 实现范围** |

---

## 渠道执行（collection-channel 模块架构）

### 手册与总规格

| 文档 | 说明 |
|------|------|
| [策略迭代与测试操作手册](./MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md) | **策略/运营手册**：怎么测、怎么改、DB 与 Nacos 分工 |
| [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | **测试手册**：TC 用例、curl、验收标准 |
| [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md) | ChannelGateway、契约、Webhook；**附录 A** → [渠道模板清单](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) |

### 渠道模板（全渠道 SSOT）

| 文档 / 资源 | 说明 |
|-------------|------|
| [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) | **SMS / Push / Email / Voice** scriptSlot 总表、Nacos 配置 |
| [email-templates/](../email-templates/) | Subject / 叙事原则 / 5 封联调案例（docs 根） |

### 契约对齐

| 文档 | 说明 |
|------|------|
| [ContextSnapshot 字段透传说明](./MOCASA催收系统升级_Phase1_ContextSnapshot字段透传说明.md) | 快照 → StepCommand → 供应商 API 全链字段映射 SSOT |
| [引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md) | dispatch / 观察期 / 空地址 / token 等执行语义（已定稿） |

### 供应商 Adapter 对接

| 文档 | 说明 |
|------|------|
| [Notification 对接说明](./MOCASA催收系统升级_Phase1_Notification对接说明.md) | **SSOT**：`NotificationSmsAdapter` + `NotificationPushAdapter`（SMS / App Push 均经通知中心） |
| [SendGrid Email 对接说明](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) | `SendGridEmailAdapter` |
| [AI Call Facade 接入说明](./MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md) | `FacadeAiCallAdapter`；当前仅 L1 / 单 step 联调，回调与 Wave-2 未完成 |
| [AI Call Facade **回调入站交接**](./MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md) | **T4 阻断 · 待编排同事实现**：账户级回调 URL 与现有 `/webhook/channel-callback` 结构不兼容，需新增专用端点；含反查、结果映射（含「未知取值默认 ANSWERED」陷阱）、幂等与验收 |
| [Facade 客户接入手册](./FACADE客户接入手册.md) | 外部 API SSOT：账户级回调、`dial_policy`、手工重拨、结果码与验签 |
| [LTH Voice 对接说明](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) | LTH 人工轨例外外呼；不作为 Facade AI Call 的实现说明 |

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
| [HANDOFF](../../HANDOFF.md) | 模块接续与 Mock 替换清单 |

---

## 外部参考资料（`reference/`）

> 供应商 API 文档与背景资料，统一放在 [`reference/`](./reference/) 子目录。
> **硬依赖**（adapter 实现依据）：`notification-send-api.md`（通知中心 SSOT）；其余为排障 / 背景资料。

| 文件 | 说明 |
|------|------|
| [notification-send-api.md](./reference/notification-send-api.md) | **通知中心 API SSOT**（SMS `/v1/sms/send` + App Push `/v1/app_notification/send`） |
| [case-assign-and-LTH-lifecycle.md](./reference/case-assign-and-LTH-lifecycle.md) | LTH 现网外呼生命周期 |
| [mocasa_channel_vendor_selection_v1.md](./reference/mocasa_channel_vendor_selection_v1.md) | 菲律宾渠道选型报告 |
| [QH SMS 接口.md](./reference/QH SMS 接口.md) | 通知中心底层供应商排障（QH） |

HiwayIO 与 BORI 的供应商原始资料不在仓库；需要排障时向渠道供应商或运维索取当前版本。
