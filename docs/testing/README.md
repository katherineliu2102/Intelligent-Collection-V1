# Phase 1 测试文档索引

> **入口**：按信息性质分类，不按日期平铺。判定口径只在测试 SSOT。  
> **上游**：规格与契约见 [`../README.md`](../README.md)。工程/Pilot 缺口见 [`../../HANDOFF.md`](../../HANDOFF.md)。

## 目录

- [怎么选文档](#怎么选文档)
- [规范](#规范)
- [操作手册](#操作手册)
- [抽样](#抽样)
- [实测记录](#实测记录)
- [渠道用例](#渠道用例)
- [脚本索引](#脚本索引)

## 怎么选文档

| 要做的事 | 打开 |
|---|---|
| 看放行灯、改用例、对出口 | [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) |
| 查证据摘要、裁定理由、缺口 | [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) |
| 在某环境里执行或回滚 | 下表「操作手册」，按 L4b / T3o / T5 / 触达内容选 |
| 换抽样圈或核对名单 | [抽样](#抽样) |
| 查某日 Pilot 实绩 | 先读 [8/29–8/31 综述](./records/MOCASA催收系统升级_Phase1_自动跑综述_20260829-0831.md)，再打开分日记录 |
| 跑 Owner 路由隔离 E2E | [Owner 路由 E2E 手册 T1-7](./MOCASA催收系统升级_Phase1_Owner路由E2E手册_T1-7.md) |

目录约定：根目录只放活规范；`runbooks/` 写怎么做、不作放行裁决；`records/` 只追加某次窗口的原文；`samples/` 放抽样方法与名单夹具。尚未迁入子目录的新记录仍暂时放在根目录，索引按职责指向。

## 规范

| 文档 | 职责 |
|---|---|
| [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) | 测试准入、用例、出口与当前状态的完整来源（T0–T6；含 T3o 简版观测门槛） |
| [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) | 历史实测过程、证据摘要、问题定位、环境偏差与裁定理由；不作为当前测试状态的裁决来源 |
| [按日 Owner 路由改造计划](../MOCASA催收系统升级_Phase1_按日Owner路由改造计划.md) | 新旧系统按日分流轮换的机制设计（缺席对账 + 归属日 + 水位门控） |
| [按日 Owner 路由开发计划](./MOCASA催收系统升级_Phase1_按日Owner路由开发计划.md) | 四轨道分工、G1/G2 灰度闸门、已拍板决策与待拍板项；跨团队需求文档入口 |

## 操作手册

物理文件在 `runbooks/`。`docs/README.md` 运维节也链到 T5 手册，用例仍以测试 SSOT 为准。

| 文档 | 适用 | 职责 |
|---|---|---|
| [L4b 环境交接清单](./runbooks/MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | T3 隔离联调 | topic/订阅、Nacos、凭证、操作顺序、查库节奏；不作测试裁决 |
| [T3o 执行取证手册](./runbooks/MOCASA催收系统升级_Phase1_T3o执行取证手册.md) | T3o | T5-S / T5-R / T3o-O 的注入手法、命令与断言字段；判定口径仍以测试 SSOT 为准 |
| [T5 Pilot 准备与演练手册](./runbooks/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | T3o / T4 / T5 | Redis / GCP 运维交付、50 案真实白名单 Pilot、渐进切量与回滚 |
| [触达内容验收清单](./runbooks/MOCASA催收系统升级_Phase1_触达内容验收清单.md) | T3 自持终端与 T4 Pilot 间接取证 | 触达内容的人工验收判据；不定义用例或环境 |
| [Owner 路由 E2E 手册 T1-7](./MOCASA催收系统升级_Phase1_Owner路由E2E手册_T1-7.md) | L4b Owner 路由 | mock owner feed 注入手法、§7.1 八场景矩阵与断言 SQL；E2E-6 已拍板跳过 |

## 抽样

| 文档 / 文件 | 职责 |
|---|---|
| [200 案抽样说明](./samples/MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md) | 200 案分流开测：配额、周末并档、loan_id；开测日验进库时间与还款窗 |
| [e2e200_loan_ids_20260829.csv](./samples/e2e200_loan_ids_20260829.csv) | 白名单用，仅 `loan_id` |
| [e2e200_loans_20260829.csv](./samples/e2e200_loans_20260829.csv) | 回溯用：桶、DPD、产品、金额带 |
| [e2e200 缺投影清单](./e2e200_缺投影清单_20260901.md) | S0 缺 24 + 非 S0 缺 13，发数仓补发 |
| [Owner 路由 DPD30 样本](./MOCASA催收系统升级_Phase1_Owner路由DPD30样本_20260903.md) | 9/3 抽取说明；CSV 夹具同目录 |

抽取脚本：`scripts/test/sample_e2e200_loans.py`。

## 实测记录

只追加、不改判定口径。某窗口的结论若要影响放行灯，回写测试 SSOT。

| 文档 | 窗口 |
|---|---|
| [8/29–8/31 自动跑综述](./records/MOCASA催收系统升级_Phase1_自动跑综述_20260829-0831.md) | 三天结果、问题、改动、验证；8/31 五槽已收口，S0 仍待数仓重发。该时段的阅读入口 |
| [8/25 AI Call 真拨 Webhook](./records/MOCASA催收系统升级_Phase1_AI_Call_引擎真拨Webhook测试记录_20260825.md) | 引擎调度 → Facade → webhook 收口；补 L1 不经引擎的缺口 |
| [8/26 主链路冒烟](./records/MOCASA催收系统升级_Phase1_主链路冒烟清单.md) | 建 plan + SMS/PUSH/EMAIL/AI_CALL 当晚窗口；不是 T4 签核 |
| [8/27 自动跑记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) | 自然日五槽、DPD/金额对账、落库表盘点；傍晚 `4ca65f5` 发版 |
| [8/28 自动跑记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260828.md) | 发版次日完整自然日；数仓 03:00 进库与还款 2h 窗 **8/29 复验** |
| [8/29 自动跑记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260829.md) | **200 案未进测**（白名单仍 40）；当晚 23:12 切 200 名单 |
| [8/30 自动跑记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260830.md) | 200 案首跑：**进 143**；接通 `513849`；S0 整桶未发 |
| [8/31 自动跑记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260831.md) | 空名单全日；8 案补跑实发；S0 仍缺 |
| [9/1 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260901.md) | 五槽全日；14:30 收口；原 40 止血；Hikari 池 25 |
| [9/2 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260902.md) | **五槽全日收口**；S0→S1；AI 接通 5；14:30 准时；FAILED=Facade 媒体层 |
| [9/3 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260903.md) | **五槽全日收口**；SMS/Push 130；Email 0；接通 1（`529588`）；日切无升档；无 plan 40 |

## 渠道用例

渠道 `TC-*` 细节见[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。渠道架构中的 L1/L3 不等于测试 SSOT 的 L1/L3。

| 文档 | 职责 |
|---|---|
| [FIRM 渠道测试用例表](./FIRM渠道测试用例表.md) | FIRM 用户各渠道派发；主验 SMS `*_SMS_FIRM` 槽位；前置数仓 `strategyTone` |

## 脚本索引

| 脚本 | 覆盖范围 | 状态 |
|---|---|---|
| `scripts/test/l4a-official-test.sh` | L4a 官方用例及 Guard/REBUILD | 存在 |
| `scripts/test/restart-and-l4a.sh` | 重启、构建、启动、执行 L4a | 存在 |
| `scripts/test/l4b-preflight.sh` | L4b 环境预检 | 存在；不替代 L4b 测试 |
| `scripts/test/l4b-pubsub/publish-test-messages.sh` | L4b 测试 topic 发布 case/repayment 消息 | 存在；不替代 L4b 测试 |
| `scripts/test/l4b-official-test.sh` | L4b 官方闭环 | 存在；历史结果不替代当前契约下的重测证据 |
| `scripts/test/smoke-level-a.sh` | 本地 Level A 冒烟 | 存在；不是 L4a 替代 |
| `scripts/test/provision-scheduler.py` | 调度 Job / 订阅参数纠偏；`--fix-cases-dlq` 挂案件订阅死信（T3o-7 前置） | 存在；`--fix-cases-dlq` 改变接入失败行为，须显式执行并记录 |
| [Pilot 脚本整理台账](./MOCASA催收系统升级_Phase1_Pilot脚本整理台账.md) | `/tmp` 与 `scripts/dev/_pilot*` 分级、危险脚本、归档与升格计划 | 维护用 |

完整命令索引见 [`scripts/README.md`](../../scripts/README.md)。不执行真实触达时，只可查看脚本和配置，不应运行 L4a/L4b 命令。
