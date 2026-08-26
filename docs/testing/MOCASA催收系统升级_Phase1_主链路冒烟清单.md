# Phase 1 主链路冒烟清单

> **用途**：先测通「能建 plan，并对 SMS / PUSH / EMAIL / AI_CALL 四个渠道调用执行」。  
> **不是** T0–T6 全量 SSOT，也**不是** T4（50 案真实白名单 Pilot）。完整矩阵仍以 [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) 为准。  
> **分支**：`test_branch`（底：`ca_branch`）。  
> **闸门**：本轮已按「开测」走真号；测完已把 `pilot.env` 改投/50 案白名单从备份恢复。  
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

环境：Pilot `collection-admin`（`/opt/app/build`，镜像 `:pilot`，`test_branch` `94f5dd8`）　操作人：agent　开始时间（PHT）：2026-08-26 16:03

| ID | 步骤 | 通过标准 | 结果 | 证据（plan/step/msg id） | 备注 |
|---|---|---|---|---|---|
| M0 | 预检：应用健康、Nacos、渠道密钥、静默时段外 | `/actuator/health` 或等价探活成功 | ✅ | loopback 200；Nacos/Redis/调度订阅 UP | 发版后容器 `Up` |
| M1 | 进件（注入或数仓）→ 创建 plan | `t_contact_plan` 一行；步骤含 SMS/PUSH/EMAIL/AI_CALL（按当前模板） | ✅ | case=`489935` plan=`862` S4 | 沿用已有计划，未走数仓 Topic；S4 模板有 EMAIL 条件槽 |
| M2 | SMS 到期执行 | dispatch 成功；步骤 `COMPLETED`；timeline 有 OUT 记录 | ✅ | step=`2467` timeline=`817` requestId=`2a49b9ff-…` | 真号；`testSend` 仍走 HiWaySms |
| M3 | PUSH 到期执行 | 同上；无 token 时允许 SMS fallback，记实际走了哪条 | 🟡 | 当日 12:00 已 `DELIVERED`（timeline `742`）；16:10 step=`2480` 被 `DAILY_LIMIT_EXCEEDED PUSH 2/1` | 今日 PUSH 主路径已在 12:00 成功；二次被频控挡住是预期 |
| M4 | EMAIL 到期执行 | dispatch 成功；步骤同步完成（SendGrid 打开/送达不改步骤） | ⏭ | step=`2639` → `SKIPPED` / `StepResolver returned null` | Phase 1 条件 Email 不生成有效发送；未打 SendGrid |
| M5 | AI_CALL 到期 → 出站 | Facade create→cases→start；步骤 `STEP_EXECUTING` | ✅ | step=`2477` batch=`a55f6ee9-79f4-48d1-ac5c-bb7968c1c581` | 真号，未改投 |
| M6 | AI_CALL 入站回调 | 验签通过；步骤终态；timeline 有结果 | 🟡 | audit=`76` `signature_valid=1` result=`FAILED` reason=`MEDIA_NEGOTIATION_FAILED` session=`bf55465e-…` | 回调已入审计并 `publish CHANNEL_CALLBACK`，步骤仍 `EXECUTING`（重启前未收敛）；timeout 17:08 |
| M7 | 后台可查 | Case Monitor 能看到该 plan 步骤与 timeline | ⏭ |  | 本轮未打开 UI |

结果取值：`✅` / `❌` / `🟡` 部分 / `⏭ 本轮不做`。

测窗内临时把白名单收到 1 案并清空改投；**16:18 已从 `pilot.env.bak.20260826-test_branch` 恢复 50 案白名单与改投**，避免次日真号连打剩余槽位。新镜像仍在跑。

---

## 3. 本轮明确不做（避免把冒烟做成 Pilot）

- T4：50 案审批、三个完整日循环、非白名单零触达冻结
- T3o 全套：毒丸、PEL、DLQ 重放、Redis 断连、200 条压测
- 接通取消当日补呼（CONNECT_AND_STOP）：代码已合入；**等 M5/M6 出现 `ANSWERED` 再验**，不阻塞主链路
- 数仓 Topic 真实性：若注入也能建 plan，主链路算过；数仓源单独立项

---

## 4. 阻碍项（出现再停，不要自行扩大范围）

见对话回复中的推荐方案。本清单执行中若卡住，把阻碍 ID 记到备注，不要改成扫全量 SSOT。
