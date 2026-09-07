# Phase 1 测试文档索引

## 怎么放

| 位置 | 放什么 |
|---|---|
| **本目录** | 可复用手册 / SSOT / 验收清单（长期有效） |
| **[`records/`](./records/)** | **一次性实测记录**：按日自动跑、样本说明、Pilot 脚本台账等 |

之后更新自然日结果，请直接写到 `records/`（命名：`MOCASA催收系统升级_Phase1_自动跑记录_YYYYMMDD.md`），**不要**再散落到本目录或其它规格文档里。

## 长期文档（本目录）

| 文档 | 职责 |
|---|---|
| [主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) | 建 plan + 四渠道执行的冒烟判据（不是 T4） |
| [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) | T0–T6 准入 / 用例 / 出口 |
| [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) | 历史问题与裁定；不作当前状态裁决 |
| [触达内容验收清单](./MOCASA催收系统升级_Phase1_触达内容验收清单.md) | 话术 / 触达内容人工验收 |
| [T5 Pilot 准备与演练手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | Pilot 运维、白名单、切量回滚 |
| [T3o 执行取证手册](./MOCASA催收系统升级_Phase1_T3o执行取证手册.md) | T5-S / T5-R / T3o-O 注入与断言 |
| [L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | topic / Nacos / 查库节奏 |
| [FIRM 渠道测试用例表](./FIRM渠道测试用例表.md) | FIRM 话术用例 |
| [Owner 路由 E2E 手册 T1-7](./MOCASA催收系统升级_Phase1_Owner路由E2E手册_T1-7.md) | mock owner feed 与断言 SQL |

## 实测记录（[`records/`](./records/)）

完整目录与摘要见 [records/README](./records/README.md)。

| 最新 | 摘要 |
|---|---|
| [9/7 自动跑](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260907.md) | 上午收口；AI 0 接通；下午待补 |
| [9/6 自动跑](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260906.md) | 五槽收口；S4 Email×12；接通 4 |
| [9/5 自动跑](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260905.md) | 五槽收口；接通 4 |
| [9/4 自动跑](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260904.md) | Owner 分流全日通过；Email 25；接通 6 |
| [records 全目录](./records/README.md) | 按日索引 |

渠道 `TC-*` 见[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。

## 脚本索引

| 脚本 | 覆盖范围 | 状态 |
|---|---|---|
| `scripts/test/l4a-official-test.sh` | L4a 官方用例及 Guard/REBUILD | 存在 |
| `scripts/test/restart-and-l4a.sh` | 重启、构建、启动、执行 L4a | 存在 |
| `scripts/test/l4b-preflight.sh` | L4b 环境预检 | 存在；不替代 L4b 测试 |
| `scripts/test/l4b-pubsub/publish-test-messages.sh` | L4b 测试 topic 发布 | 存在 |
| `scripts/test/l4b-official-test.sh` | L4b 官方闭环 | 存在 |
| `scripts/test/smoke-level-a.sh` | 本地 Level A 冒烟 | 存在 |
| `scripts/test/provision-scheduler.py` | 调度 / DLQ 纠偏 | 存在 |

完整命令见 [`scripts/README.md`](../../scripts/README.md)。
