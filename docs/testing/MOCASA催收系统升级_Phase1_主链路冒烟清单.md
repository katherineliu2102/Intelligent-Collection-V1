# Phase 1 主链路冒烟清单

> **用途**：先测通「能建 plan，并对 SMS / PUSH / EMAIL / AI_CALL 四个渠道调用执行」。  
> **不是** T0–T6 全量 SSOT，也**不是** T4（50 案真实白名单 Pilot）。完整矩阵仍以 [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) 为准。  
> **分支**：`test_branch`（底：`ca_branch`）。  
> **闸门**：AI_CALL **正式拨打前必须口头确认**（决策 D2）。未确认前只做到 dispatch 前检查，或走 `test-callee` 改投。  
> **日期**：2026-08-26

---

## 1. 本轮目标 vs 现有测试文档

| 文档 | 是否覆盖你的目标 | 建议 |
|---|---|---|
| 测试 SSOT + 问题台账 + T3o 手册 | 过重。覆盖可靠性、死信、观测、50 案 Pilot、切量 | **保留**作后续阶段，本轮不按表逐条跑 |
| 本清单 | 对准「建计划 + 四渠道执行」 | **本轮唯一执行表**；每条记下结果后再进入其它场景 |

通过标准：至少 1 个案件生成 `t_contact_plan` 及步骤；SMS / PUSH / EMAIL 各有一次真实 dispatch 并步骤终态；AI_CALL 完成出站受理（`STEP_EXECUTING`）并在确认拨打后收到 `/webhook/facade-callback` 写入终态。还款冻结、50 案循环、接通取消补呼等列为加分项。

---

## 2. 主链路用例与结果

环境：____________　操作人：____________　开始时间（PHT）：____________

| ID | 步骤 | 通过标准 | 结果 | 证据（plan/step/msg id） | 备注 |
|---|---|---|---|---|---|
| M0 | 预检：应用健康、Nacos、渠道密钥、静默时段外 | `/actuator/health` 或等价探活成功 | ⬜ |  |  |
| M1 | 进件（注入或数仓）→ 创建 plan | `t_contact_plan` 一行；步骤含 SMS/PUSH/EMAIL/AI_CALL（按当前模板） | ⬜ | plan= |  |
| M2 | SMS 到期执行 | dispatch 成功；步骤 `COMPLETED`；timeline 有 OUT 记录 | ⬜ | step= |  |
| M3 | PUSH 到期执行 | 同上；无 token 时允许 SMS fallback，记实际走了哪条 | ⬜ | step= |  |
| M4 | EMAIL 到期执行 | dispatch 成功；步骤同步完成（SendGrid 打开/送达不改步骤） | ⬜ | step= |  |
| M5 | AI_CALL 到期 → 出站 | **拨打前确认**。确认后：Facade create→cases→start；步骤 `STEP_EXECUTING` | ⬜ | step= batch= | 未确认则停在本行 |
| M6 | AI_CALL 入站回调 | `POST /webhook/facade-callback` 验签通过；步骤终态；timeline 有结果 | ⬜ | result= | 当前常见 `NO_ANSWER` 也算链路通 |
| M7 | 后台可查 | Case Monitor 能看到该 plan 步骤与 timeline | ⬜ |  |  |

结果取值：`✅` / `❌` / `⏭ 本轮不做`。

---

## 3. 本轮明确不做（避免把冒烟做成 Pilot）

- T4：50 案审批、三个完整日循环、非白名单零触达冻结
- T3o 全套：毒丸、PEL、DLQ 重放、Redis 断连、200 条压测
- 接通取消当日补呼（CONNECT_AND_STOP）：代码已合入；**等 M5/M6 出现 `ANSWERED` 再验**，不阻塞主链路
- 数仓 Topic 真实性：若注入也能建 plan，主链路算过；数仓源单独立项

---

## 4. 阻碍项（出现再停，不要自行扩大范围）

见对话回复中的推荐方案。本清单执行中若卡住，把阻碍 ID 记到备注，不要改成扫全量 SSOT。
