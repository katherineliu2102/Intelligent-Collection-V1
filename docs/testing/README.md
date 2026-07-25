# Phase 1 测试文档索引

## 入口与边界

| 文档 | 职责 |
|---|---|
| [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) | 测试准入、用例、出口、当前状态的唯一来源（T0–T6） |
| [L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | L4b 的 topic/订阅、Nacos、凭证约定和操作 Runbook；不作测试裁决 |
| [L4b 测试报告 20260707](./MOCASA催收系统升级_Phase1_L4b测试报告_20260707.md) | 2026-07-07 单次运行事实；不作 SSOT |
| [L4b 触达内容核对清单](./MOCASA催收系统升级_Phase1_L4b触达内容核对清单.md) | 手工终端核对工作纸；不定义用例或环境 |
| [L4a 编排同事补全清单](./MOCASA催收系统升级_Phase1_L4a全量前置_编排同事补全清单.md) | L4a 协作/临时实现历史与后续评审项 |
| [_archive/L4a 对齐纪要](./_archive/L4a_对齐纪要_20260622.md) | 只读历史纪要 |
| [Admin P0 自测](./admin-p0-test.md) | Admin 独立 P0 自测 |

渠道 `TC-*` 细节见[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。渠道架构中的 L1/L3 不等于测试 SSOT 的 L1/L3。

## 脚本索引

| 脚本 | 覆盖范围 | 状态 |
|---|---|---|
| `scripts/test/l4a-official-test.sh` | L4a 官方用例及 Guard/REBUILD | 存在 |
| `scripts/test/restart-and-l4a.sh` | 重启、构建、启动、执行 L4a | 存在 |
| `scripts/test/l4b-preflight.sh` | L4b 环境预检 | 存在；不替代 L4b 测试 |
| `scripts/test/l4b-pubsub/publish-test-messages.sh` | L4b 测试 topic 发布 case/repayment 消息 | 存在；不替代 L4b 测试 |
| `scripts/test/l4b-official-test.sh` | L4b 官方闭环 | **缺失，T4 未关闭项** |
| `scripts/test/smoke-level-a.sh` | 本地 Level A 冒烟 | 存在；不是 L4a 替代 |

完整命令索引见 [`scripts/README.md`](../../scripts/README.md)。不执行真实触达时，只可查看脚本和配置，不应运行 L4a/L4b 命令。
