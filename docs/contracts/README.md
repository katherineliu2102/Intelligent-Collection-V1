# 契约对齐文档索引(contracts/)

> 主架构维护**跨模块契约对齐流程**（SPI / ContextSnapshot 用法 / 执行运行时语义）。
<<<<<<< HEAD
> **字段定义 SSOT** 见 [领域模型 §3/§9](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；SPI 签名见 [核心引擎 §6](../MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)。本目录**不重复字段类型表**。
> 任何字段/语义增删先在本目录对齐，再改 `collection-common`，并通知编排/服务同事。
> 总索引见 [`../README.md`](../README.md)；common 契约索引见 [架构 §1.1](../MOCASA催收系统升级_Phase1_架构设计文档.md#11-架构总览)。

**真相源优先级**：`collection-common` Java 代码 > 领域模型 §3/§6/§9 > 核心引擎 §6 / 基础设施 §2/§5 > 本目录用法表 > 其他文档。
=======
> **字段定义 SSOT** 见 [领域模型 §2/§3/§4/§5/§6](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；SPI 签名见 [核心引擎 §6](../MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)。本目录**不重复字段类型表**。
> 任何字段/语义增删先在本目录对齐，再改 `collection-common`，并通知编排/服务同事。
> 总索引见 [`../README.md`](../README.md)；common 契约索引见 [架构 §1.1](../MOCASA催收系统升级_Phase1_架构设计文档.md#11-架构总览)。

**真相源优先级**：`collection-common` Java 代码 > 领域模型 §2–§6 > 核心引擎 §6 / 基础设施 §2/§5 > 本目录用法表 > 其他文档。
>>>>>>> origin/ca_branch

## 活跃契约(✅ 定稿)

| 文档 | 用途 |
|------|------|
| [README_ContextSnapshot契约对齐.md](./README_ContextSnapshot契约对齐.md) | 快照字段最小必填集、来源、金额 SSOT、开放问题 |
| [ContextSnapshot.sample.json](./ContextSnapshot.sample.json) | **冻结的快照样例**，编排/服务同事开发依据 |
| [README_编排同事对齐清单.md](./README_编排同事对齐清单.md) | 要对齐的契约代码 + SPI 实现/超时 + E1–E8 一页清单 |
| [MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md](./MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md) | dispatch 回填 / metadata / 观察期 / 空地址 4 项(**已定稿 2026-06-11**) + token 口径 |

## 历史结论(📦 只读)

| 文档 | 说明 |
|------|------|
| [MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md](./_archive/MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md) | 编排同事一次性审阅回复(2026-06-09)；已拍板结论已并入 [活跃契约](./README_ContextSnapshot契约对齐.md) |

## 定稿要点(速查)

- **token 口径**：PUSH/Message 经内部 notification 系统，token = `device.jpushToken`（非 fcmToken）。
- **StepResult**：3 情形——发送受理 / 网络超时(retryable) / 其他异常(不重试)；SMS 受理与送达(DLR)两段。
- **观察期**：PUSH/EMAIL 无；SMS 等 DLR，默认 10min。
- **空地址**：方案 A(Guard block NO_EMAIL/NO_PHONE/NO_TOKEN→SKIPPED)；PUSH 叠加 fallback SMS。
- **幂等 key**：`plan:stepOrder:retryCount`。

> 关联：执行语义冻结值同步在 `.cursor/rules/ic-v1-channel-contract.mdc`；C1–C7 测试见 `../testing/MOCASA催收系统升级_Phase1_测试文档.md`。
