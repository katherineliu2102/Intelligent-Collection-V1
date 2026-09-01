# Phase 1 测试文档索引

## 入口与边界

| 文档 | 职责 |
|---|---|
| [主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) | **当前优先**：建 plan + SMS/PUSH/EMAIL/AI_CALL 执行；记录每次结果。不是 T4 |
| [8/27 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) | 自然日五槽、DPD/金额对账、落库表盘点；傍晚 `4ca65f5` 发版与 8/28 预估 |
| [8/28 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260828.md) | 发版次日完整自然日：五槽、SETNX、邮件映射、接通停呼；数仓 03:00 进库与还款 2h 窗 **8/29 复验** |
| [8/29 二百案抽样](./MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md) | 200 案分流开测：配额、周末并档、loan_id；开测日验进库时间与还款窗 |
| [8/29–8/31 自动跑综述](./MOCASA催收系统升级_Phase1_自动跑综述_20260829-0831.md) | 三天结果、问题、改动、验证；8/31 五槽已收口，S0 仍待数仓重发 |
| [8/29 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260829.md) | **200 案未进测**（白名单仍 40）；原 40 案五槽；当晚 23:12 切 200 名单 |
| [8/30 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260830.md) | 200 案首跑：**进 143**；接通 `513849`；S0 整桶未发；14:30 悬挂 2；17:39 空名单 |
| [8/31 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260831.md) | 空名单全日：SMS 127 / Push 136 / Email 12（dpd=4）/ AI 两批；8 案补跑实发；S0 仍缺 |
| [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) | 测试准入、用例、出口的完整来源（T0–T6；含 T3o 简版观测门槛） |
| [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) | 历史实测过程、证据摘要、问题定位、环境偏差与裁定理由；不作为当前测试状态的裁决来源 |
| [L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | L4b 的 topic/订阅、Nacos、凭证、操作顺序、查库节奏与可重复性前置；不作测试裁决 |
| [触达内容验收清单](./MOCASA催收系统升级_Phase1_触达内容验收清单.md) | 触达内容的人工验收判据，覆盖 T3 自持终端与 T4 Pilot 间接取证两种模式；不定义用例或环境 |
| [T5 Pilot 准备与演练手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | Redis / GCP 运维交付、50 案真实白名单 Pilot、渐进切量与回滚操作 |
| [T3o 执行取证手册](./MOCASA催收系统升级_Phase1_T3o执行取证手册.md) | T5-S / T5-R / T3o-O 逐条的注入手法、命令与断言字段；判定口径仍以测试 SSOT 为准 |

渠道 `TC-*` 细节见[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。渠道架构中的 L1/L3 不等于测试 SSOT 的 L1/L3。

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

完整命令索引见 [`scripts/README.md`](../../scripts/README.md)。不执行真实触达时，只可查看脚本和配置，不应运行 L4a/L4b 命令。
