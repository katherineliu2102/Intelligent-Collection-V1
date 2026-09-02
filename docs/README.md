# MOCASA 催收系统升级 Phase 1 · 文档总索引

> 唯一文档导航入口。Phase 1 仅覆盖菲律宾市场。代码进度与 Mock 替换清单见 [`../HANDOFF.md`](../HANDOFF.md)。

**owner 图例**：🟦 主架构(引擎/契约/接入/入口) ｜ 🟧 编排同事(channel) ｜ 🤝 共享/需协调
**状态图例**：✅ 定稿 ｜ 🟡 进行中/活跃 ｜ 📦 历史结论(只读)

> ⚠️ 协作约定：🟧 编排同事的文档以 `main` 为准。用户在对话中明确授权具体文件或目录后，Agent 可在该范围修改，并逐次经 hook 确认；改动/搬移前须留意与 `main` 的 merge 冲突。

## 〇、文档写作约定（个人 · 跨项目）

体系与单篇写法不在本仓库维护，见个人 skill `documentation`（`~/.cursor/skills/documentation/`）：

| 关切 | 打开 |
|---|---|
| 文档怎么分层、怎么引用、目录怎么长 | `~/.cursor/skills/documentation/references/DOCUMENTATION_SYSTEM_DESIGN.md` |
| 单篇怎么写 | `~/.cursor/skills/documentation/references/DOCUMENTATION_STANDARDS.md` |

本索引是催收文档树的落地（决策 / 系统 / 模块 / 开发）。催收特有的 SSOT 映射见项目 skill `intelligent-collection-dev`。

### 〇.0 本仓落地例外

| 项 | 本仓约定 |
|---|---|
| 文件名 | 维持既有 `MOCASA催收系统升级_Phase1_*.md`（及少数专题名如 `数仓_PubSub交付契约.md`）。不按个人 skill 的英文大写+下划线批量改名，以免打断全仓链接。 |
| 索引状态灯 | 上表「状态图例」（定稿 / 进行中 / 历史结论）只表示**文档在导航里的维护态**，不是规格内某条决策的状态。 |
| 规格决策标记 | 规格与设计正文的决策/设计项只用三套：已确定 / 待深入讨论 / 待确认（写法见 `DOCUMENTATION_STANDARDS` §5）。待深入讨论须附默认理由；待确认须写清谁提供什么输入。 |
| 测试执行态 | 测试主文档与台账另用通过 / 部分通过 / 未执行 / 范围外等执行灯，与规格决策标记分开，互不替换。 |

### 〇.1 进度查阅边界

| 要问的进度 | 文档 |
|---|---|
| T0–T6 放行灯、用例与出口 | [测试主文档](./testing/MOCASA催收系统升级_Phase1_测试文档.md) |
| 已执行证据、测试差集与复核项 | [测试执行记录与问题台账](./testing/MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) |
| 工程/Pilot 缺口、替身与生产差集 | [`../HANDOFF.md`](../HANDOFF.md) |
| 稳定准入门槛 | [基础设施交互规范附录 B](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-b容量基线与生产技术准入)；当前闭合状态仍看 `HANDOFF.md` |

## 一、决策与系统层

跨模块基线：先读这两份，再进具体模块。

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [产品需求文档 PRD](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) | 🤝✅ | 业务目标、功能范围、渠道选型与合规（决策层） |
| [架构设计文档](./MOCASA催收系统升级_Phase1_架构设计文档.md) | ✅ | 分层、模块边界、跨模块要点、技术栈（系统层） |
| [按日 Owner 路由改造计划](./MOCASA催收系统升级_Phase1_按日Owner路由改造计划.md) | 🟡 | 新旧系统按案件每日归属、回迁与效果对比的实施顺序；不替代下游 SSOT |

## 二、跨切 SSOT

被多个模块引用的事实；不替代模块内部规格。

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md) | ✅ | 字段 / 枚举 / EventPayload / DDL（`collection-common` 数据契约） |
| [基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md) | ✅ | Redis / **定时调度（Cloud Scheduler → Pub/Sub → 应用订阅，SSOT §5）** / Repository、**运行配置附录 A**、可观测性 |
| [数仓 Pub/Sub 交付契约](./数仓_PubSub交付契约.md) | ✅ | 数仓对外唯一 SSOT：计算口径、两类事件、可靠性、日切门控、验收 |

### collection-common 契约查阅

| 关切 | SSOT 文档 |
|---|---|
| 字段 / 枚举 / EventPayload / DDL | [领域模型](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md) §2 / §3 / §6；DDL 权威 [`../db/schema.sql`](../db/schema.sql) |
| SPI / 共享 DTO / 调用语义 | [核心引擎规格 §6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约) |
| EventBus / Redis 键 / Repository | [基础设施 §2/§3/§5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md) |
| 跨模块用法对齐（非字段 SSOT） | [contracts/](./contracts/README.md) |
| 索引与变更规则 | [架构 §1.1](./MOCASA催收系统升级_Phase1_架构设计文档.md#11-架构总览) |

## 三、模块规格

按 Maven 模块取走对应文档即可独立推进。系统层边界见 [架构 §1.2](./MOCASA催收系统升级_Phase1_架构设计文档.md#12-系统边界北向入站)。

| 模块 | 规格（SSOT） | 状态 | owner | 说明 |
|------|-------------|:--:|:--:|------|
| `collection-engine` | [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md) | ✅ | 🟦 | 事件路由、状态机、七步管线、SPI、容错 |
| `collection-ingestion` | [数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md) | 🟡 | 🟦 | 消费 / 投影 / 日切 / 迁移；外部字段与 Topic → [数仓契约](./数仓_PubSub交付契约.md) |
| `collection-admin` | [管理后台设计文档](./MOCASA催收系统升级_Phase1_管理后台设计文档.md) | 🟡 | 🟦 | 信息架构、权限、页面闭环、配置热更新（v1.4）；操作见 [管理后台操作手册](./MOCASA催收系统升级_Phase1_管理后台操作手册.md) |
| `collection-channel` | [渠道文档索引](./channel/README_渠道文档索引.md) | 🟡 | 🟧 | 编排 / Adapter / 模板；**改前须授权**，以 `main` 为协作基线 |
| `collection-service` | 无独立规格 | ✅ | 🟦 | 读 [领域模型](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md) 与 [基础设施 §6](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#6-持久层与跨存储一致性)；不另写模块文档 |
| `collection-common` | 上表「契约查阅」 | ✅ | 🟦 | 编译期契约；字段与 SPI 定义仍以上游规格为准 |

## 四、契约对齐(🟦 主架构维护 · 跨模块 · **用法对齐，非字段 SSOT**)

入口见 [`contracts/README.md`](./contracts/README.md)。要点：

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [ContextSnapshot 契约对齐](./contracts/README_ContextSnapshot契约对齐.md) + [样例 JSON](./contracts/ContextSnapshot.sample.json) | ✅ | 快照字段/来源/SSOT；StepResolver 唯一数据源 |
| [引擎渠道执行契约](./contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md) | ⚠️ | 已定稿的 dispatch/metadata/观察期/空地址/token 语义；供应商幂等透传与 Facade AI_CALL 回调未闭合 |

## 五、测试(🟦 主架构)

入口见 [`testing/README.md`](./testing/README.md)。

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [测试主文档（SSOT）](./testing/MOCASA催收系统升级_Phase1_测试文档.md) | 🟡 | L0–L4b 测试层级、T0–T6 放行阶段、接入 v3 重测与 T3o 观测门槛 |
| [T5 Pilot 准备与演练手册](./testing/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | 🟡 | T3o 生产等价演练、50 案真实白名单 Pilot、渐进切量与回滚 |

> L2 渠道联调 C1–C7 骨架：`collection-engine/.../integration/ChannelContractL2Test`。

## 六、运维/操作(🤝)

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [操作说明 Nacos 本地启动](./操作说明_Nacos本地启动.md) | ✅ | 本地 / Docker / Nacos；与根 `../README.md` 互补 |
| [管理后台操作手册](./MOCASA催收系统升级_Phase1_管理后台操作手册.md) | ✅ | 后台 UI（5173）启动、登录、页面与排障；设计 SSOT 仍是 [管理后台设计文档](./MOCASA催收系统升级_Phase1_管理后台设计文档.md) |
| [T5 Pilot 准备与演练手册](./testing/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | 🟡 | Redis/GCP 交付、50 案 Pilot、渐进切量、证据归档与回滚；测试用例 SSOT 仍是测试主文档 |

## 七、渠道(🟧 编排同事维护 · 谨慎修改)

入口见 [`channel/README_渠道文档索引.md`](./channel/README_渠道文档索引.md)。含编排规格、总规格、Notification（SMS/Push）/ SendGrid / Facade AI Call 对接说明及策略迭代手册。邮件模板见 [`email-templates/`](./email-templates/)。编排同事以 `main` 为协作基线；用户在对话中明确授权具体文件或目录后，Agent 可在该范围修改，并逐次经 hook 确认。
