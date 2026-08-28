# Phase 1 自动跑记录（2026-08-27）

> **层级**：50 案 Pilot 自然日自动跑（调度 `planStepDue` → 四渠道 dispatch → 步骤终态）。  
> **对比 8/26**：昨晚是挤压冒烟；今天不手调时间，看预写槽是否按钟点发出。  
> **环境**：Pilot `bdp01`，容器 `collection-admin`。上午 `a24356c`；**11:22 PHT** 换成波次聚合（`6f0ff38`）；**16:32 PHT** 发版 `4ca65f5`（邮件映射进代码、SETNX、穷尽 0 步升档）+ 白名单 40 案。  
> **时区**：PHT（UTC+8）。取数截止 **16:20 PHT**；DPD/金额与表盘点补记于当晚。  
> **关联**：[主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) · [Facade 接入说明](../channel/MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md) · [发版手册](../channel/MOCASA催收系统升级_Phase1_发版手册.md) · [数仓契约](../数仓_PubSub交付契约.md)

## 目录

- [1. 结论](#1-结论)
- [2. 五槽实绩](#2-五槽实绩)
- [3. DPD / 金额对账](#3-dpd--金额对账)
- [4. 催收库表与落库完整性](#4-催收库表与落库完整性)
- [5. 通知中心 BQ](#5-通知中心-bq)
- [6. 问题清单](#6-问题清单)
- [7. 波次聚合（11:22 上线）](#7-波次聚合1122-上线)
- [8. 已改 / 遗留 / 明天](#8-已改--遗留--明天)

---

## 1. 结论

五个预写槽都按时触发，引擎主路径通过，**无悬挂 `EXECUTING`**。通道失败（AI 未接通、1 封 Email 缺模板、1 条短信运营商后失败）有出现，步骤都收到终态。

| 口径 | 结果 |
|---|---|
| 健康 | loopback 200 |
| 日切 03:35–05:55 | `stageChanged=0` `ceased=0` `rollbackSkipped=1` |
| 08:00 SMS | 27 DELIVERED；BQ `label=collection` 27（delivered 25 / sent 1 / failed 1） |
| 09:15 AI | 27 终态，当时一案一批 |
| 12:00 PUSH | 33 DELIVERED；BQ 有催收正文，无 `label`/`user_id` |
| 14:00 EMAIL | 1/1 FAILED（`S2_EMAIL_ENTRY` 当时 Pilot 无映射） |
| 14:30 AI | 26 终态；聚合首跑拆成 **4 批**（23+1+1+1） |
| 真人接通 | **0**，CONNECT_AND_STOP 无样本 |

**DPD/金额**：催收侧映射没有算错。对客文案取发送时刻的 `t_ai_collection`。当天 `caseEvent` **08:35** 才进库（契约要求 03:00 前发完），所以 08:00 短信仍是 8/26 快照；12:00 Push 已是 8/27 数。与 BQ `detail.t_loan_repayment_plan` 按应还日算的 max DPD、未结清本息罚一致。

---

## 2. 五槽实绩

对照昨晚预估（库内仍 PENDING）与今早实绩：

| PHT | 渠道 | 预估 | 实绩 | 说明 |
|---|---|---|---|---|
| 08:00 | SMS | ~28 | **27** | tick `scanned=27`；timeline 27 |
| 09:15 | AI | ~28 | **27** | BUSY 13 / FAILED 10 / NO_ANSWER 4；27 个独立 Facade batch |
| 12:00 | PUSH | ~34 | **33** | 1 PENDING = 已还款 plan `855` |
| 14:00 | EMAIL | ~1 | **1 FAILED** | 案 `529878` / plan `882`，当时无 `S2_EMAIL_ENTRY` 映射 |
| 14:30 | AI | ~28 | **26** | BUSY 12 / FAILED 11 / NO_ANSWER 3；2 PENDING = 已取消 `832`/`855` |

白名单当时 50；约 35 份活跃计划。10 个幽灵 ID 日切查不到投影（见 §6），傍晚已从名单拿掉。

14:30 聚合：`provider_msg_id` 为 `mocasa-20260827-1430-4`（23 案）及 `-3/-2/-1` 各 1 案。原因是代次键并发 `INCR`，傍晚已改 SETNX。

S3 案 `519965` / plan `840` 在 14:30 后穷尽：当天已是 S3 最后一槽，Factory 0 步曾抛 PEL；计满重试后 14:37 建出 S4 plan `883`。傍晚已改为 0 步直接升档/收口。

---

## 3. DPD / 金额对账

催收**不读** `t_loan_repayment_plan`。链路是：

```
BQ detail.t_loan_repayment_plan
  → 数仓算 dpd / overdueAmount（含罚息、不含未到期）
  → Pub/Sub caseEvent
  → t_ai_collection（overdue_amount = total_outstanding = overdueAmount）
  → 发送前 PreFlight 覆盖内存快照的 dpd / 金额
  → SMS/Push 文案 {dpd}/{amount}
```

S1+ 文案金额用 `totalOutstanding`；S0 用 `upcomingAmount`。罚息列 `penalty_amount` Phase 1 模板不渲染。

抽样四案（与 08:00 BQ 正文同一批）：

| 案 | 应还日 | 08:00 短信（send） | 12:00 Push（send） | 8/27 投影 / 还款表 max DPD | 8/27 投影金额 |
|---|---|---|---|---|---|
| `529878` | 08-23 | DPD **3** / 1,989.67 | DPD **4** / 1,999.56 | 4 | 1,999.56（罚息 11.56） |
| `528136` | 08-18 | **8** / 11,722.88 | **9** / 11,771.24 | 9 | 11,771.24 |
| `519965` | 07-28 | **29** / 14,443.17 | **30** / 14,499.90 | 30 | 14,499.90（已还 500，罚息剩余 140.91） |
| `517047` | 07-20 | **37** / 12,620.50 | **38** / 12,667.00 | 38 | 12,667.00（罚息 627） |

inbox：8/26 10:07 的 `caseEvent` 已 `PUBLISHED`（即短信所用的数）；8/27 **08:35** 才落到投影（`occurredAt=02:00:45`，`projection_applied=1`，内部事件 `SKIPPED`——已有周期只刷新投影、不重建计划）。

因此：

1. **映射无误**：`overdueAmount` → 两列金额相同；还款表 `amount - paid` / 本息罚未结清 = 投影；`DATE_DIFF(当天, due_date)` = 投影 DPD。
2. **不是快照冻结 bug**：Push 的 `t_decision_log` 已是新 DPD，计划 `context_snapshot` 仍停在旧值（按设计不回写）。08:00 时投影还是 8/26，所以短信=旧快照。
3. **待数仓侧**：每日 `caseEvent` 契约是 **03:00 PHT 前发完**。晚于 08:00 则早班短信会差 1 天 DPD + 当天新增罚息。催收代码改不了源数据到达时间。

---

## 4. 催收库表与落库完整性

库：`ai_collection_db`。新链路运行时案件源是 `t_ai_collection`（`collection.case-service=ai`），**不读**还款计划表，本机 MySQL 也没有 `t_loan_repayment_plan`。

### 4.1 触达日会写的表

| 表 | 作用 | 8/27 |
|---|---|---|
| `t_ai_collection` | 当前案件投影 | 41 行（白名单命中约 40） |
| `t_ai_collection_inbox` | 入站幂等 + 是否已发内部事件 | 当日 08:35 刷新投影 |
| `t_contact_plan` / `t_contact_plan_step` | 计划与预写步骤 | 步骤终态齐全 |
| `t_contact_timeline` | 触达事实（attempt_key 唯一） | 与当日执行 1:1 |
| `t_decision_log` | 发送时内存快照（含刷新后的 dpd/金额） | 114 条 |
| `t_channel_callback_audit` | AI 回调原文 | 84 条 |
| `t_event_outbox` | 领域事件发件箱 | 116 条，**PENDING=0** |
| `t_event_dlq` | 死信 | **当日 0** |
| `t_email_suppression` | 邮件抑制 | 0 |

当日执行 vs timeline：SMS 27/27、PUSH 33/33、EMAIL 1/1、AI_CALL 53/53，无「COMPLETED 却无 timeline」。

### 4.2 配置 / 后台表（不按槽写）

`t_contact_plan_template`、`t_script_template`、`t_strategy_rule`、`t_compliance_rule`、`t_channel_config`、`t_config_*`、`t_admin_case_freeze` 等。引擎 Phase 1 话术仍以 Nacos/`channel.scripts` 为主。

### 4.3 同库遗留、新链路不作为运行时源

`t_collection`（688）、`t_user_extend`（约 279 万）、`t_user_device_token`（6）、`t_user_profile_ext`（0）。Push token 走投影 `push_token`，不回查 token 表。

---

## 5. 通知中心 BQ

`bq_notification.t_history_202608`，`app_code='mocasa'`。`create_time` 按 DATETIME 当 UTC，对账用 `request_id`，不要用 Manila 小时去滤。

**短信**：27 条 `provider_msg_id` 全部能对上（08:00:07/08）。引擎侧都是 DELIVERED；BQ 1 条事后 `failed`、1 条停在 `sent`（接口成功 ≠ 运营商送达）。

**Push**：引擎 33 条 `enqueued`、0 fallback。JPush 路径不落 `label`/`user_id`。12:00 整分钟 16 条催收正文；12:00–12:10 文案匹配 21 条（S1 “past due / Tap to settle” 不在匹配串里）。对账用时间窗 + 正文，不要用 `label=collection`。

---

## 6. 问题清单

| 项 | 挡触达？ | 状态 |
|---|---|---|
| 取消计划残留 PENDING | 否 | **遗留**。扫描不捞终态计划，不是漏催 |
| 10 个白名单幽灵 ID | 否 | **已拿掉**（50→40）。日切应变 `scanned=40` |
| CONNECT_AND_STOP 无样本 | 否 | 等 `ANSWERED` |
| Email 缺 Nacos 映射 | 当日 1 封没发出 | **已改代码常量**；8/28 14:00 复验 |
| 入批竞态拆 4 批 | 否 | **已改 SETNX**；8/28 09:15 / 14:30 复验 |
| S3 穷尽 0 步抛 PEL | 否 | **已改** 升档/收口 |
| `caseEvent` 晚于 08:00 | 早班短信 DPD/金额差一天 | **数仓 SLA**，催收不改 |
| Push BQ 无 label | 否 | 通知中心限制；对账改文案 |
| 短信运营商 failed/sent | 否 | Phase 1 已知 |

取消计划方案仍是：取消时把非终态步骤标 SKIPPED（先不加新枚举）。

---

## 7. 波次聚合（11:22 上线）

同一触达槽先在 Redis 缓冲，起批时一次性 upload。还款/取消在 `start` 前从缓冲剔除，禁止批级 `cancel`。超时按 `⌈案数÷并发⌉×单通时长+缓冲`，下界 30 分钟。

14:30 首跑：起批与回调成功，因代次 `INCR` 竞态拆成 4 批。回滚：`CHANNEL_FACADE_BATCH_AGGREGATION_ENABLED=false`。

---

## 8. 已改 / 遗留 / 明天

### 8.1 傍晚已上 Pilot（16:32）

产品 `4ca65f5`。白名单 40。loopback health 200。回滚 jar/env：`*.bak.202608270831`。

| 项 | 8/28 怎么验 |
|---|---|
| 邮件代码映射 | **14:00** 案 `519965` / plan `883` / step `3177`，`S4_EMAIL_ENTRY` 应打到 SendGrid |
| SETNX 一槽一批 | **09:15 / 14:30** 基本一个 `mocasa-20260828-0915-1` / `…-1430-1` |
| 穷尽 0 步 | 日志不再出现 `REBUILD did not create successor plan` |
| 白名单 40 | **03:35 日切** `scanned=40`，不再刷那 10 个 WARN |

### 8.2 明天槽位预估（16:20 库内 PENDING）

| PHT | 渠道 | 量 | 验证重点 |
|---|---|---|---|
| 08:00 | SMS | 35 | 主路径；**看当日 caseEvent 是否已进投影**（进了则 DPD 应与还款表当天一致） |
| 09:15 | AI | 36 | SETNX 一槽一批 |
| 12:00 | PUSH | 35 | 引擎 DELIVERED；BQ 用正文 |
| 14:00 | EMAIL | 1 | 代码映射 |
| 14:30 | AI | 28 | SETNX |

案 `519965` 投影可能仍是 S3、计划已是 S4，日切或 `rollbackSkipped`（与今日 `529878` 同类）。若 09:15 仍多代次，先查 Redis `channel:facade:gen:20260828-0915`。
