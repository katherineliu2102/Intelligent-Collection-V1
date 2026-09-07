# Phase 1 主链路冒烟清单

> **用途**：验证「能建 plan，并对 SMS / PUSH / EMAIL / AI_CALL 四个渠道真实 dispatch 并落到步骤终态」。  
> **不是** T0–T6 全量 SSOT，也**不是** T4 全量 Pilot 签核。完整矩阵以 [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) 为准。  
> **一次性实测**：8/26 冒烟窗口与 8/27 起按日结果一律放在 [`records/`](./records/README.md)，本文件只保留可复用判据，不再堆日记。

## 通过标准

至少 1 个案件有 `t_contact_plan`；SMS / PUSH / EMAIL / AI_CALL 能真实 dispatch 并落到步骤终态（AI 须 webhook 收口，超时默认 30 分钟）。

| ID | 步骤 | 通过标准 |
|---|---|---|
| M0 | 预检 | health 200；Nacos / Redis / 调度订阅 UP |
| M1 | 进件 → plan | `t_contact_plan` 有行 |
| M2 | SMS | dispatch + 步骤终态（`DELIVERED` / `FAILED` 均可，须关步） |
| M3 | PUSH | 同上（注意日频控） |
| M4 | EMAIL | 里程碑 DPD 才真发；非里程碑 `SKIPPED` 为设计行为 |
| M5 | AI 出站 | Facade start 受理 |
| M6 | AI 回调收口 | 步骤离开 `EXECUTING`；计划已离开本步时不二次推进 |
| M7 | Case Monitor（可选） | UI 可见本轮 case / plan |

## 本轮明确不做

- T4 全量签核、三完整日循环、非白名单零触达冻结专项  
- T3o 毒丸 / PEL / DLQ / Redis 断连 / 压测  
- 把次日全槽提前到当晚（易空掉正式日）

## 已知问题（判据仍有效；细节见台账 / records）

| ID | 问题 | 处置口径 |
|---|---|---|
| F-回调吞掉 | 计划已离开本步时旧引擎按计划态吞回调 → AI 永久 `EXECUTING` | 已修：按步骤 `EXECUTING` 收口 |
| F-计划预写 | 进案预写多日槽；改 `trigger_time` 易误伤「那天那一行」 | 先不改 PlanFactory；运维忌乱提前 |
| F-EMAIL 非里程碑 | 非 DPD 0/1/4/31/75 → `SKIPPED` | Phase 1 设计如此 |
| F-通道拒信/失败 | 短信拒信 / Facade `MEDIA_NEGOTIATION_FAILED` / `BUSY` | 步骤能终态即引擎过 |
| F-取消残留 PENDING | `PLAN_CANCELLED` 后未终态步仍 PENDING | 扫描已排除终态计划，不是漏催 |
| F-日切投影缺失 | 白名单命中 < 名单长度 | 先对 ID / caseId 口径 |

## 阻碍项（出现再停）

步骤大面积停在 `EXECUTING` 超过 30 分钟、白名单外真打、或改投被重新打开。

## 实测入口

| 类型 | 入口 |
|---|---|
| 最新自动跑 | [records/ 最新日](./records/README.md) |
| 8/26～8/27 冒烟当日细节 | [8/27 自动跑记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) |
| 问题台账 | [测试执行记录与问题台账](./MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md) |
