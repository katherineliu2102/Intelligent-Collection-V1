# Phase 1 测试文档索引

先看灯，再看怎么做。

| 你要什么 | 读哪 |
|---|---|
| 现在过到哪、出入口 | [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) |
| 未闭合项、T4 核对、缺陷与裁定 | [台账 §1 / §3](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) |
| Pilot 主链路实绩（不是 T4） | [主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) · [8/27 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) |
| Pilot 上怎么操作（含 T3o 取证命令） | [Pilot 操作手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) |
| L4b 隔离联调怎么配 | [L4b 环境交接](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) |
| 终端正文对不对 | [触达内容验收](./MOCASA催收系统升级_Phase1_触达内容验收清单.md) |

## 职责

| 文档 | 职责 |
|---|---|
| [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) | 准入、用例、出口、当前状态。唯一裁决 |
| [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) | 未闭合项、T4 核对、缺陷/环境、已闭合裁定与证据。不维护阶段灯 |
| [主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) | 建 plan + SMS/PUSH/EMAIL/AI_CALL 执行；记录每次结果。不是 T4 |
| [8/27 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) | 50 案自然日：08:00 短信 / 09:15 AI 实绩；下午槽待补 |
| [Pilot 操作手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | 部署、隔离、T3o 注入命令（§5.3）、回滚、T4/T5。不裁决 |
| [L4b 环境交接](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | 隔离 topic / 订阅。**禁止**往生产 topic 发测试消息 |
| [触达内容验收](./MOCASA催收系统升级_Phase1_触达内容验收清单.md) | 文案与漏发/多发。不定义用例 |

渠道 `TC-*` 见[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。
0825 真拨通次在[台账附录 A](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md#附录-a历史会话2026-08-25-ai_call-真拨)；T3o 命令在 Pilot 手册 §5.3。

## 脚本

完整命令见 [`scripts/README.md`](../../scripts/README.md)。

| 脚本 | 覆盖 |
|---|---|
| `scripts/test/l4a-official-test.sh` | L4a 官方用例 |
| `scripts/test/restart-and-l4a.sh` | 重启并跑 L4a |
| `scripts/test/l4b-preflight.sh` | L4b 预检 |
| `scripts/test/l4b-official-test.sh` | L4b 官方闭环 |
| `scripts/test/provision-scheduler.py` | 调度 Job；`--fix-cases-dlq` 挂案件死信 |
| `scripts/test/publish-schedule-tick.py` | 本地向调度 topic 发 tick |
| `scripts/test/publish-cases-dlq-drill.py` | T3o-7 死信演练 / 重放 |
| `scripts/test/facade-callback-probe.py` | L2-CB 回调探针 |

不执行真实触达时，只看脚本，不跑 L4a/L4b。
