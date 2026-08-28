# Phase 1 自动跑记录（2026-08-28 上午）

> **层级**：40 案 Pilot 自然日自动跑（昨晚 `4ca65f5` 发版后的首个完整早班）。  
> **取数**：2026-08-28 **10:03 PHT**。下午 12:00 / 14:00 / 14:30 尚未到点。  
> **环境**：`collection-admin` Up 18h，loopback health 200，白名单 40，波次聚合开。  
> **关联**：[8/27 记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md)

## 目录

- [1. 结论](#1-结论)
- [2. 早班实绩](#2-早班实绩)
- [3. 昨晚改动的验证](#3-昨晚改动的验证)
- [4. 新发现：话术档与计划档不一致](#4-新发现话术档与计划档不一致)
- [5. 下午还要看什么](#5-下午还要看什么)
- [6. 待拍板](#6-待拍板)

---

## 1. 结论

早班主路径通过：**无悬挂 `EXECUTING`，无启动 ERROR，日切 `scanned=40`，09:15 一槽一批，CONNECT_AND_STOP 首次有样本且跳过了当日第二通。**

08:00 短信 DPD/金额已是当天投影（`caseEvent` 08:00:03–05 赶到）。两案短信话术仍按**过期的 snapshot.stage** 选槽，和计划档、当天 DPD 不一致，见 §4。

| 口径 | 结果 |
|---|---|
| 日切 03:35–05:55 | `scanned=40`；幽灵 ID WARN **0**；`stageChanged=0` `ceased=0` `rollbackSkipped=1`（`519965` 当时投影仍 S3、计划已 S4） |
| 08:00 SMS | tick `scanned=33`；33 DELIVERED + timeline 33。槽身份另 2 PENDING=已还款计划 |
| 09:15 AI | **33 通进同一批** `mocasa-20260828-0915-1` / Facade `9edfa82e-…`，`wave started cases=33` |
| 09:15 结果 | BUSY 20 / FAILED 11 / ANSWERED **1** / NO_ANSWER 1 |
| CONNECT_AND_STOP | 案 `513749` 09:15 接通后，14:30 step `1509` 已标 `SKIPPED` |
| 悬挂 / DLQ / outbox PENDING | 全 0 |

对照昨晚预估：SMS 35→实发 33（2 条已还款未捞）；AI 36→实发 33（3 条已还款 PENDING）。不是漏催。

---

## 2. 早班实绩

| PHT | 渠道 | 昨晚预估 PENDING | 今早 | 说明 |
|---|---|---|---|---|
| 03:35 日切 | — | scanned=40 | **40** | 10 个幽灵 ID 已消失 |
| 08:00 | SMS | 35 | **33 发出** | 2 PENDING：`502131`/`527448` `PLAN_CANCELLED/REPAID` |
| 09:15 | AI | 36 | **33 发出、1 批** | 另 3 PENDING 同为已还款（含 `502356`） |
| 12:00 | PUSH | 35 | 尚 35 PENDING | 槽身份 1 条已是 8/26 提前打掉的 `520049` |
| 14:00 | EMAIL | 1 | 仍 PENDING | 案 `519965` / plan `883` / step `3177` |
| 14:30 | AI | 28 | 27 PENDING + 1 已 SKIPPED | SKIPPED 即接通停呼 `513749` |

08:00 tick：`2026-08-28 08:00:04 scanned=33`。  
09:15 起批：`09:15:25 wave=20260828-0915#1 cases=33 callbackDeadline=09:45:25`。

DPD 抽样（发送时 `t_decision_log` vs 投影，四案）：

| 案 | 短信 send DPD / 金额 | 投影 | 冻结快照 DPD |
|---|---|---|---|
| `529878` | 5 / 2009.45 | 5 / 2009.45 | 3 |
| `528136` | 10 / 11819.60 | 10 | 8 |
| `519965` | 31 / 14556.63 | 31 | 30 |
| `517047` | 39 / 12713.50 | 39 | 37 |

inbox 当天 `caseEvent` 08:00:03–05，`projection_applied=1`。比昨天 08:35 早，赶上了短信。

---

## 3. 昨晚改动的验证

| 项 | 结果 |
|---|---|
| SETNX 一槽一批 | **通过**。33 案全部 `mocasa-20260828-0915-1`，只有一次 `wave started` |
| 白名单 40 | **通过**。日切 `scanned=40`，今日 `t_ai_collection 无 case_id` 计数 0 |
| 邮件代码映射 | 待 **14:00** |
| 穷尽 0 步 | 今日无新的 `REBUILD did not create successor`；`519965` 已在 S4 计划上跑 |
| CONNECT_AND_STOP | **通过**。`513749` plan `833` step `1492` ANSWERED → `09:27:11` skip step `1509` |

`rollbackSkipped=1` 是 `519965`：日切窗口里投影还是 S3/dpd=30（当天 caseEvent 08:00 才到），计划已是 S4。与 8/27 预告同类，不是回归。08:00 之后投影已是 S4/dpd=31。

---

## 4. 新发现：话术档与计划档不一致

SMS/Push 的 `scriptSlot` 来自 **快照 `caseContext.stage`**，发送时只刷新 dpd/金额、**不刷新 stage**。升档后的新计划里，快照 stage 有时仍是旧档：

| 案 | 计划档 | 快照/发送档 | 发出的槽 | 当天 DPD（已是新档） |
|---|---|---|---|---|
| `519965` | S4 | **S3** | `S3_SMS_STANDARD` | 31 → 应为 S4 |
| `529878` | S2 | **S1** | `S1_SMS_STANDARD` | 5 → 应为 S2 |
| `528136` | S2 | S2 | `S2_SMS_STANDARD` | 对齐 |
| `517047` | S4 | S4 | `S4_SMS_STANDARD` | 对齐 |

12:00 Push 若仍读这份快照，`519965`/`529878` 还会用 S3/S1 标题。14:00 Email 按 **刷新后的 dpd** 选里程碑，`dpd=31` → `S4_EMAIL_ENTRY`，不受 snapshot.stage 影响。

根因更像是 **建后继计划时 `context_snapshot.stage` 没写成新档**，而不只是「发送不覆盖 stage」。

---

## 5. 下午还要看什么

| 点 | 看什么才算过 |
|---|---|
| 12:00 PUSH | 约 35 条 DELIVERED；BQ 用正文+时间窗；抽 `519965`/`529878` 是否仍 S3/S1 标题 |
| 14:00 EMAIL | step `3177` 不要再是 `SENDGRID_NO_TEMPLATE`；应打到 SendGrid `S4_EMAIL_ENTRY` |
| 14:30 AI | SETNX 再验一槽一批；`513749` step `1509` 保持 SKIPPED、无新 timeline |
| 数仓 | 若要压 SLA：看明日 `caseEvent` 是否仍卡在 08:00 |

---

## 6. 待拍板

见对话：话术跟计划档还是跟快照档；要不要给「投影晚于 08:00」加告警/闸门。推荐方案写在对话里，确认前不改代码。
