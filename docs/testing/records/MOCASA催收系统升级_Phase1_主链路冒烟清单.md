# Phase 1 主链路冒烟清单

> **用途**：先测通「能建 plan，并对 SMS / PUSH / EMAIL / AI_CALL 四个渠道调用执行」。  
> **不是** T0–T6 全量 SSOT，也**不是** T4 全量 Pilot 签核。完整矩阵仍以 [测试 SSOT](../MOCASA催收系统升级_Phase1_测试文档.md) 为准。  
> **分支**：`test_branch`（底：`ca_branch`）。当前 Pilot 镜像：`intelligent-collection-admin:pilot`，代码 `a24356c`（回调按步骤收口 + AI 超时 30 分钟）。  
> **日期**：2026-08-26 冒烟 + 加测；[8/27 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md)（上午截止 09:51 PHT）

通过标准：至少 1 个案件有 `t_contact_plan`；SMS / PUSH / EMAIL / AI_CALL 能真实 dispatch 并落到步骤终态（AI 须 webhook 收口）。

---

## 1. 本轮目标 vs 现有测试文档

| 文档 | 是否覆盖你的目标 | 建议 |
|---|---|---|
| 测试 SSOT + 问题台账 + T3o 手册 | 过重 | **保留**作后续阶段 |
| 本清单 | 对准「建计划 + 四渠道执行」+ 当晚加测 + 次日自动跑准备 | **本轮执行记录** |

---

## 2. 窗口 A：16:03–16:18 单案挤压冒烟（案 `489935` / plan `862`）

环境：当时镜像仍为 `94f5dd8`。为避免 50 案真打，临时把扫描白名单收到 1 案并清空改投；把 4 个 PENDING 步挤进约 10 分钟。

| ID | 步骤 | 通过标准 | 结果 | 证据 | 备注 |
|---|---|---|---|---|---|
| M0 | 预检 | health 200 | ✅ | loopback 200；Nacos/Redis/调度订阅 UP | |
| M1 | 进件 → plan | `t_contact_plan` 一行 | ✅ | case=`489935` plan=`862` S4 | 沿用已有计划，未走数仓 Topic |
| M2 | SMS | dispatch + 步骤终态 | ✅ | step=`2467` timeline=`817` requestId=`2a49b9ff-…` | 真号；`testSend` → HiWaySms |
| M3 | PUSH | 同上 | 🟡 | 当日 12:00 已 `DELIVERED`（timeline `742`）；16:10 step=`2480` `DAILY_LIMIT_EXCEEDED PUSH 2/1` | 主路径已在 12:00 成功；二次频控是预期 |
| M4 | EMAIL | 真 SendGrid | ⏭ | step=`2639` → `SKIPPED` / `StepResolver returned null` | 非里程碑 DPD（当时 S4 DPD 67），Phase 1 不发 |
| M5 | AI 出站 | Facade start | ✅ | step=`2477` batch=`a55f6ee9-79f4-48d1-ac5c-bb7968c1c581` | 真号 |
| M6 | AI 回调收口 | 步骤终态 | 🟡→补丁后可关 | audit=`76` 验签通过 `FAILED` / `MEDIA_NEGOTIATION_FAILED` | **根因**：计划已被后续 due 推到 `STEP_SCHEDULED`，旧引擎按计划态吞掉回调。步骤悬挂 `EXECUTING`。**16:40 SQL 订正** `2477` → `COMPLETED`/`FAILED`（`completed_at=16:15:13`），plan 仍 `STEP_SCHEDULED` / `current_step=27` |
| M7 | Case Monitor | UI 可见 | ⏭ | | 本窗口未打开 UI |

16:18 曾从 `pilot.env.bak.20260826-test_branch` 恢复 50 案白名单**与改投**（避免次日连打）。该状态随后被窗口 B 取代。

---

## 3. 窗口 B：引擎补丁、订正、清改投、数仓消费（约 16:36–16:49）

| 项 | 结果 | 说明 |
|---|---|---|
| 引擎收口 | ✅ 已发版 `a24356c` | 步骤仍 `EXECUTING` 即按回调/超时关步；计划已离开本步则**不二次推进**。超时 **30 分钟**（Resolver + 引擎默认一致） |
| 单测 | ✅ | Pilot 上 `PlanLifecycleManagerTest` 等 79 条通过 |
| 步骤 `2477` | ✅ 已订正 | 见 M6；timeline `818` 同步为 `FAILED` |
| 四条改投 | ✅ 已清 | AI / 短信 / 邮件 / Push 测试号均 unset。备份 `pilot.env.bak.20260826-084844-before-clear-redirects` |
| `sms-test-mode` | 保持 true | 仍走 `/v1/sms/testSend`，收件人是借款人真号 |
| 接入 / 调度 | ✅ 已在跑 | `COLLECTION_INGESTION_ENABLED` unset → yml 默认 true；调度 true。案件订阅 `intelligent-collection-cases-v1-sub` UP。白名单外 **ack 跳过** |
| 白名单 | 50 / 50 | 接入 `COLLECTION_PILOT_LOAN_IDS` 与扫描 `COLLECTION_SCAN_CASE_IDS` 均为 50 |

---

## 4. 窗口 C：17:20 三案加测（只提前次日短信 + 09:15 AI）

目的：在 21:00 静默前看真短信和 AI 回调收口；**不**提前 Push/邮件，以免拆掉明天主样本。  
约定：这 3 案 **8/27 08:00 短信和 09:15 AI 用掉**；**8/27 12:00 Push 与 8/28 起每日槽仍在**。其余约 31 案未改 `trigger_time`。

| 案件 | plan | 短信 | AI 09:15 |
|---|---|---|---|
| `497382` | 856 | step=`2454` 17:23 **COMPLETED / DELIVERED**（QHSms `116632107`） | step=`2461` 17:33 出站 batch=`30ffd2aa-…`；**25s 回调收口 COMPLETED / BUSY**，audit=`84` 验签通过。超时窗日志 **30min**。计划推进到次日 12:00 Push（step `2469`） |
| `497405` | 849 | step=`2168` 17:25 **FAILED / `NOTIFICATION_SMS_REJECTED`**（CreativeBlue 拒信） | step=`2181` 17:43 出站已受理（timeline `DELIVERED`），写清单时仍 `EXECUTING`，timeout `18:13`（等回调，验证收口补丁） |
| `503696` | 853 | step=`2170` 17:27 **COMPLETED / DELIVERED**（CreativeBlue `393711819038131200`） | step=`2177` 排 **17:52**（写清单时尚未到期） |

要点：

- 引擎补丁已在 `2461` 上闭环：回调 `BUSY` 时计划仍是 `STEP_EXECUTING`，步骤当场终态并推进，**不再悬挂**。
- 短信供应商会拒信（CreativeBlue），步骤仍能关成 `FAILED`，不算引擎停摆。
- **17:18** 另有 3 案的 8/27 08:00 短信被调度发出（`466438` / `482686` / `493696`，均 `DELIVERED`）。不是本次抽的 3 案。原因待查（`trigger_time` 被改到当时到期）。明天 08:00 短信存量因此少于原先的 34。

---

## 5. 8/27 自动跑：预估 vs 上午实绩

详细证据见 [8/27 自动跑记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md)。取数截止 **09:51 PHT**。

**不用手调。** 调度每分钟 `planStepDue`；步骤仍是 `PENDING` 且时间到了就会发。日切 03:35–05:55 只在升档/停催时重建计划，**不会**给同阶段再铺一套 8/27 槽。

写清单时库内 **8/27 仍 PENDING** 的量，以及今早实绩：

| 时间（PHT） | 渠道 | 昨晚预估仍 PENDING | 今早实绩（09:51） | 备注 |
|---|---|---|---|---|
| 08:00 | SMS | **~28** | ✅ tick `scanned=27`，当日 27 DELIVERED | 槽身份 DELIVERED=33（含昨晚已打） |
| 09:15 | AI 第 1 通 | **~28** | ✅ tick `scanned=27`，27 全部终态 | BUSY 13 / FAILED 10 / NO_ANSWER 4；无悬挂 |
| 12:00 | PUSH | **~34** | 未到点，34 PENDING | |
| 14:00 | EMAIL | **~1** | 未到点，1 PENDING | 仅里程碑日真 SendGrid |
| 14:30 | AI 第 2 通 | **~28** | 未到点，28 PENDING | 今早无 ANSWERED，CONNECT_AND_STOP 未减量 |

白名单 50，投影命中 40；约 10 个 ID 日切查不到 `t_ai_collection`（见记录 §6.2）。已有计划的案仍按预写槽催，不是这 27 的复制。

### 8/27 上午不构成停跑的项

| 项 | 状态 |
|---|---|
| 应用健康 / 调度订阅 / 案件订阅 | UP |
| 改投 | 已清，真客户 |
| 回调悬挂 | 补丁已上；`2461` 已验证 |
| 人手改时间 | 不需要 |

### 建议盯着、但不挡下午槽

1. **AI 供应商失败率**（`MEDIA_NEGOTIATION_FAILED` / `BUSY` / `NO_ANSWER`）会继续出现；只要步骤收口就算引擎过。未接通才打 14:30 第二通。  
2. **短信拒信**（CreativeBlue `NOTIFICATION_SMS_REJECTED`）是通道问题，步骤会 `FAILED` 并推进。  
3. **`sms-test-mode` 仍 true**：真号，接口是 `/testSend`。要切正式 `/send` 需另发一版/改 env。  
4. **邮件几乎测不到**：50 案里明天只有约 1 封里程碑。  
5. **CONNECT_AND_STOP**：代码已在；**今早 27 通无 ANSWERED**，未验证。等出现真人接通再对当日第二通是否 `SKIPPED`。  
6. **17:18 那 3 案误到期**：明天 08:00 少 3 条短信；若再发现大面积 `original_trigger` 被提前，再查谁改了 `trigger_time`。  
7. **21:00–08:00 静默**：08:00 是窗起点，整点应能发；若 08:00 tick 略晚，Guard 不应再 defer。  
   **8/27 实绩**：08:00:04 / 09:15:04 均按时扫到 27 步，静默窗未挡住整点。

---

## 6. 本轮明确不做

- T4 全量签核、三完整日循环、非白名单零触达冻结（已在消费并 ack 跳过，未做「冻结」专项）  
- T3o 毒丸 / PEL / DLQ / Redis 断连 / 压测  
- 后台 Case Monitor 逐案点开（M7）  
- 把 34 案明天全槽提前到今晚（已否决，以免空掉正式日）

---

## 7. 测试发现的问题

本轮暴露的问题。**未改代码的项只记结论，不在本轮实现。**

| ID | 问题 | 严重度 | 处置 |
|---|---|---|---|
| F-回调吞掉 | 计划已被后续 due 推到 `STEP_SCHEDULED` 时，回调/超时按**计划态**静默 return，AI 步永久 `EXECUTING`（`2477` / audit `76`） | 高 | **已修** `a24356c`：按步骤 `EXECUTING` 收口；计划已离开本步则不二次推进。超时 30 分钟。`2461` BUSY 25s 已验证 |
| F-计划预写 | 进案时把本阶段后续每天的槽一次性写成多行（各带绝对日历时间）。日切同阶段 **不** 再铺次日步骤。「每天催」靠的是早已写好的 8/28、8/29 行，不是每天新建计划。联调提前 `trigger_time` 会把「那一天的那一行」用掉，容易理解成「明天没催收了」 | 中（模型/产品） | **先不改代码**。更贴运维语言的是：数仓快照 → 日切 → **只落地当天（或今明 24～48h）步骤**。预写全阶段的好处是 08:00 不依赖日切一定成功、调度实现简单。代价是与「每天一份日程」心智不符、未来槽不随 DPD 纠正而改写、改 `trigger_time` 易误伤。权重（日切失败能否空手 vs 必须跟当天快照一致）未拍板前不改 PlanFactory |
| F-17:18 误到期 | 3 案（`466438` / `482686` / `493696`）的 8/27 08:00 短信在 17:18 被调度发出，非窗口 C 所抽 | 中 | 待查谁改了 `trigger_time`。明天 08:00 少 3 条，不挡其余样本 |
| F-EMAIL 非里程碑 | 非 DPD 0/1/4/31/75 的 EMAIL 步 `SKIPPED`（`2639`） | 低 | Phase 1 设计如此，不是 SendGrid 挂了 |
| F-通道拒信/失败 | CreativeBlue 短信 `NOTIFICATION_SMS_REJECTED`；Facade `MEDIA_NEGOTIATION_FAILED` / `BUSY` | 低（通道） | 步骤能终态即引擎过；供应商侧另跟。8/27 09:15：BUSY 13 / FAILED 10 / NO_ANSWER 4 |
| F-取消残留 PENDING | 计划 `PLAN_CANCELLED` 后剩余步骤仍 PENDING（8/27：plan `855` REPAID 留 22 条，含当天 08:00/09:15） | 低（卫生） | 扫描已排除终态计划，**不是漏催**。推荐取消时把未终态步骤标 SKIPPED。**先不改代码** |
| F-日切投影缺失 | 白名单 50，`t_ai_collection.case_id` 命中 40；日切对缺失 ID 每 5 分钟 WARN（03:35–05:55 约 290 条） | 低（可观测） | 先核对这 10 个 ID 是否该在白名单 / loanId 与 caseId 是否同一列；确认后再考虑降噪。**先不改代码** |

---

## 8. 阻碍项（出现再停）

8/27 上午**没有**必须停跑的引擎/环境闸门。08:00 / 09:15 已按时发出并收口。若下午出现：步骤大面积停在 `EXECUTING` 超过 30 分钟、白名单外真打、或改投被重新打开，再停并记到本节约。
