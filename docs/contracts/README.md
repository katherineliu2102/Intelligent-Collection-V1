# 契约对齐文档索引(contracts/)

> 主架构维护**跨模块契约**(SPI / ContextSnapshot 快照 / 执行运行时语义)。
> 任何字段/语义增删先在本目录对齐，再改 `collection-common`，并通知编排/服务同事。
> 总索引见 [`../README.md`](../README.md)。

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
| [MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md](./MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md) | 编排同事一次性审阅回复(2026-06-09)；已拍板结论已并入活跃契约。**待联合重组时移入 `_archive/`** |

## 定稿要点(速查)

- **token 口径**：PUSH/Message 经内部 notification 系统，token = `device.jpushToken`（非 fcmToken）。
- **StepResult**：3 情形——发送受理 / 网络超时(retryable) / 其他异常(不重试)；SMS 受理与送达(DLR)两段。
- **观察期**：PUSH/EMAIL 无；SMS 等 DLR，默认 10min。
- **空地址**：方案 A(Guard block NO_EMAIL/NO_PHONE/NO_TOKEN→SKIPPED)；PUSH 叠加 fallback SMS。
- **幂等 key**：`plan:stepOrder:retryCount`。

> 关联：执行语义冻结值同步在 `.cursor/rules/ic-v1-channel-contract.mdc`；C1–C7 测试见 `../测试总览_Phase1.md`。
