# Phase 1 自动跑记录（2026-09-07）

> **层级**：Pilot 自然日（Owner NEW 分流续跑；白名单空）。  
> **环境**：同 9/3 起容器（StartedAt `2026-09-03T09:46:11Z`）；Hikari 25。  
> **取数**：2026-09-07 **~14:54 PHT**（五槽全日收口）。上午 ~09:41 口径已被本篇覆盖。  
> **关联**：[9/6](./MOCASA催收系统升级_Phase1_自动跑记录_20260906.md) · [records 目录](./README.md)

## 1. 结论

**五槽全日准时收口；全日接通 0。** 迁出 1 + 升档 1；迁出后再触达 **0**；`EXECUTING=0`、无 PENDING。

| 口径 | 全日 |
| --- | --- |
| inbox `caseEvent` | **204**（03:00:03–22） |
| repayment inbox | **146** |
| 新建 plan | **14** |
| 取消 | `REPAID` 5 · `ROUTED_TO_LEGACY` **1** · `STAGE_UPGRADE` **1** |
| 日切 03:40 | scanned=374 · `stageChanged=1`（`534282`） |
| 投影（取数时点） | 在催 **302** / 结清 72 / 停催 5；活跃 plan **201** |
| 08:00 SMS / Push | **194** / **10** DELIVERED |
| 09:15 AI | wave **194**；ANSWERED **0** |
| 12:00 Push | **194** DELIVERED |
| 14:00 Email | **18** DELIVERED + 13 SKIPPED |
| 14:30 AI | wave **180**；ANSWERED **0** |

## 2. 分槽

| PHT | 渠道 | 结果 |
| --- | --- | --- |
| 08:00 | SMS | DELIVERED **194**（S2×79 S3×72 S4×34 S1×9） |
| 08:00 | PUSH | DELIVERED **10**（全 `S0_DUE_TODAY`） |
| 09:15 | AI | BUSY **97** / FAILED **58** / NO_ANSWER **36** / SNR **3** / ANSWERED **0**；SKIPPED 99 |
| 12:00 | PUSH | DELIVERED **194**（S2×79 S3×72 S4×34 S1×9） |
| 14:00 | EMAIL | DELIVERED **18**（`S2_EMAIL_ENTRY`×8 · `S0_DUE_TODAY_EMAIL`×7 · `S1_EMAIL_OVERDUE_NOTICE`×3） |
| 14:30 | AI | BUSY **89** / FAILED **63** / NO_ANSWER **25** / SNR **3** / ANSWERED **0**；SKIPPED 64 |

wave：`mocasa-20260907-0915-1` cases=**194**（`09:15:34`）；`mocasa-20260907-1430-1` cases=**180**（`14:30:36`）。

## 3. 接通 / SNR

全日引擎 `ANSWERED` **0**；`was_ai_connected=1` **0**。两波 SNR 同一批 3 案（均非接通）：

| 槽 | case | 说明 |
| --- | --- | --- |
| 09:15 / 14:30 | `507262` | 信箱 |
| 09:15 / 14:30 | `528596` | 筛选/信箱 |
| 09:15 / 14:30 | `533850` | 09:15 信箱；14:30 筛选助手 |

无上午接通 → CONNECT_AND_STOP 无样本（14:30 未因接通被 SKIPPED）。

## 4. 分流 / 日切

| 断言 | 结果 |
| --- | --- |
| `ROUTED_TO_LEGACY` | **1**：`535159` @03:35 |
| 迁出后再 DELIVERED | **0** |
| `STAGE_UPGRADE` | **1**：`534282` @03:40（与 `stageChanged=1` 对齐） |

## 5. 问题

| 项 | 严重度 | 状态 |
| --- | --- | --- |
| 五槽 / 分流 / 迁出后再触达 | — | **通过** |
| 全日接通 0 | 观测 | 不挡跑；两波均偏 BUSY/FAILED |
| AI `MEDIA_NEGOTIATION_FAILED` | 渠道 | 全日 session **120** |
| FIRM | 未测 | 仍 STANDARD |
| blacklist 未按日刷新 | 运维已知 | 自 9/4 起 |
