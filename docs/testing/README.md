# Phase 1 测试文档索引

> **入口**：按信息性质分类。判定口径只在测试 SSOT。  
> **上游**：规格与契约见 [`../README.md`](../README.md)。工程/Pilot 缺口见 [`../../HANDOFF.md`](../../HANDOFF.md)。

## 怎么选文档

| 要做的事 | 打开 |
|---|---|
| 看放行灯、改用例、对出口 | [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) |
| 查证据摘要、裁定理由、缺口 | [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) |
| 在某环境里执行或回滚 | 下表「操作手册」（`runbooks/`） |
| 查某日 Pilot 实绩 | [`records/`](./records/README.md) 按日记录 |
| 跑 Owner 路由隔离 E2E | [Owner 路由 E2E 手册 T1-7](./MOCASA催收系统升级_Phase1_Owner路由E2E手册_T1-7.md) |

目录约定：根目录放活规范；`runbooks/` 写怎么做；`records/` 只追加实测原文；`samples/` 放仍保留的名单夹具。

## 规范

| 文档 | 职责 |
|---|---|
| [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) | T0–T6 准入 / 用例 / 出口 |
| [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) | 历史证据与裁定；不作当前状态裁决 |
| [主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) | 建 plan + 四渠道执行判据（不是 T4） |
| [Owner 路由 E2E 手册 T1-7](./MOCASA催收系统升级_Phase1_Owner路由E2E手册_T1-7.md) | mock owner feed 与断言 SQL |

## 操作手册（`runbooks/`）

| 文档 | 职责 |
|---|---|
| [L4b 环境交接清单](./runbooks/MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | topic / Nacos / 查库节奏 |
| [T3o 执行取证手册](./runbooks/MOCASA催收系统升级_Phase1_T3o执行取证手册.md) | T5-S / T5-R / T3o-O 注入与断言 |
| [T5 Pilot 准备与演练手册](./runbooks/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | Pilot 运维、白名单、切量回滚 |
| [触达内容验收清单](./runbooks/MOCASA催收系统升级_Phase1_触达内容验收清单.md) | 触达内容人工验收判据 |

## 抽样 / 夹具

| 文档 / 文件 | 职责 |
|---|---|
| [e2e200_loan_ids](./samples/e2e200_loan_ids_20260829.csv) | 200 案白名单 id（若目录无则见 `records/`） |
| [Owner DPD30 样本说明](./records/MOCASA催收系统升级_Phase1_Owner路由DPD30样本_20260903.md) | 9/3 分流池说明 |
| [owner_route_dpd30_loans CSV](./records/owner_route_dpd30_loans_20260903.csv) | DPD30 全量字段 |

## 实测记录（[`records/`](./records/README.md)）

| 最新 | 摘要 |
|---|---|
| [9/7](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260907.md) | 上午收口；AI 0 接通；下午待补 |
| [9/6](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260906.md) | 五槽收口；S4 Email×12 |
| [9/5](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260905.md) | 五槽收口；接通 4 |
| [9/4](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260904.md) | Owner 分流全日通过 |
| [records 全目录](./records/README.md) | 8/25–9/7 按日索引 |

## 渠道用例

| 文档 | 职责 |
|---|---|
| [FIRM 渠道测试用例表](./FIRM渠道测试用例表.md) | FIRM 话术用例 |

渠道 `TC-*` 见[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。

## 脚本索引

| 脚本 | 覆盖范围 | 状态 |
|---|---|---|
| `scripts/test/l4a-official-test.sh` | L4a | 存在 |
| `scripts/test/restart-and-l4a.sh` | 重启 + L4a | 存在 |
| `scripts/test/l4b-preflight.sh` | L4b 预检 | 存在 |
| `scripts/test/l4b-pubsub/publish-test-messages.sh` | L4b 发布 | 存在 |
| `scripts/test/l4b-official-test.sh` | L4b 闭环 | 存在 |
| `scripts/test/smoke-level-a.sh` | Level A 冒烟 | 存在 |
| `scripts/test/provision-scheduler.py` | 调度 / DLQ | 存在 |
| [Pilot 脚本整理台账](./records/MOCASA催收系统升级_Phase1_Pilot脚本整理台账.md) | `_pilot*` 分级 | 维护用 |

完整命令见 [`scripts/README.md`](../../scripts/README.md)。
