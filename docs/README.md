# MOCASA 催收系统升级 Phase 1 · 文档总索引

> 唯一文档导航入口。Phase 1 仅覆盖菲律宾市场。代码进度与 Mock 替换清单见 [`../HANDOFF.md`](../HANDOFF.md)。

**owner 图例**：🟦 主架构(引擎/契约/接入/入口) ｜ 🟧 编排同事(channel) ｜ 🤝 共享/需协调
**状态图例**：✅ 定稿 ｜ 🟡 进行中/活跃 ｜ 📦 历史结论(只读)

> ⚠️ 协作约定：🟧 编排同事的文档以 `main` 为准，**本分支只读、勿改/勿移**（改动/搬移会在 merge 时与其冲突）。需要更新时 `pull main`。

## 一、核心规格(🟦 主架构 · 稳定基线)

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [产品需求文档 PRD](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) | 🤝✅ | 业务目标/功能/渠道选型/合规（位置：docs 根；与 zoe 的 channel/ 版需统一） |
| [架构设计文档](./MOCASA催收系统升级_Phase1_架构设计文档.md) | ✅ | 分层、SPI 边界、关键机制、技术栈 |
| [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md) | ✅ | 事件路由、状态机、七步管线、SPI 定义 |
| [领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md) | ✅ | 模型字段、枚举、DDL |
| [基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md) | ✅ | Redis/XXL-Job/Repository、**运行配置附录 A**、可观测性（生产目标） |
| [数据接入规格](./MOCASA催收系统升级_Phase1_数据接入规格.md) | 🟡 | PubSub 消费/路由/清洗/日切/迁移（窄规格，模块 B 参考）；payload 字段见领域 §9、配置键见 infra 附录 A |

### collection-common 契约查阅

| 关切 | SSOT 文档 |
|---|---|
| 字段 / 枚举 / EventPayload / DDL | [领域模型与数据定义](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md) §3/§6/§9 |
| SPI / 共享 DTO / 调用语义 | [核心引擎规格 §6](./MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约) |
| EventBus / Redis 键 / Repository | [基础设施 §2/§3/§5](./MOCASA催收系统升级_Phase1_基础设施交互规范.md) |
| 跨模块用法对齐（非字段 SSOT） | [contracts/](./contracts/README.md) |
| 索引与变更规则 | [架构 §1.1](./MOCASA催收系统升级_Phase1_架构设计文档.md#11-架构总览) |

## 二、契约对齐(🟦 主架构维护 · 跨模块 · **用法对齐，非字段 SSOT**)

入口见 [`contracts/README.md`](./contracts/README.md)。要点：

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [ContextSnapshot 契约对齐](./contracts/README_ContextSnapshot契约对齐.md) + [样例 JSON](./contracts/ContextSnapshot.sample.json) | ✅ | 快照字段/来源/SSOT；StepResolver 唯一数据源 |
| [编排同事对齐清单](./contracts/README_编排同事对齐清单.md) | ✅ | SPI 实现/超时/E1–E8 一页清单 |
| [引擎渠道执行契约对齐(待编排确认)](./contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md) | ✅ | dispatch 回填/metadata/观察期/空地址 4 项**已定稿 2026-06-11**；token=jpushToken |
| [ContextSnapshot 契约对齐回复 _re（归档）](./contracts/_archive/MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md) | 📦 | 编排同事一次性审阅；结论已并入 [活跃契约](./contracts/README_ContextSnapshot契约对齐.md) |

## 三、测试(🟦 主架构)

入口见 [`testing/README.md`](./testing/README.md)。

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [测试主文档（SSOT）](./testing/MOCASA催收系统升级_Phase1_测试文档.md) | 🟡 | L0–L4 测试地图、链路 × 层级矩阵、§L4a 用例与脚本索引 |
| [L4a 编排同事补全清单](./testing/MOCASA催收系统升级_Phase1_L4a全量前置_编排同事补全清单.md) | 🟡 | L4a-全 SPI 切换前置与官方脚本说明 |

> L2 渠道联调 C1–C7 骨架：`collection-engine/.../integration/ChannelContractL2Test`。

## 四、运维/操作(🤝)

| 文档 | 状态 | 说明 |
|------|:--:|------|
| [操作说明 Nacos 本地启动](./操作说明_Nacos本地启动.md) | ✅ | 本地/Docker 启动；与根 `../README.md` 互补 |

## 五、渠道(🟧 编排同事维护 · 本分支只读)

入口见 [`channel/README_渠道文档索引.md`](./channel/README_渠道文档索引.md)。包含：渠道编排规格、collection-channel 总规格、引擎对齐待办(E1–E8)、4 个 adapter 对接说明(LTH SMS/Voice、SendGrid Email、FCM Push)。
根目录另有编排同事维护的：开发执行指南、开发进度、功能测试指南、渠道模板清单与配置、策略迭代与测试操作手册，以及 [`email-templates/`](./email-templates/) 邮件模板全套。

---

> 2026-06-26：`docs/testing/`、`scripts/dev/` + `scripts/test/` 已落地；核心规格仍留 `docs/` 根。
