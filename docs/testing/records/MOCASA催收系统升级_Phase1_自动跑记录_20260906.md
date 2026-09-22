# Phase 1 自动跑记录（2026-09-06）

> **层级**：Pilot 自然日（Owner NEW 分流续跑；白名单空）。  
> **环境**：同 9/3 起容器；Hikari 25。  
> **取数**：2026-09-07 回捞全日。  
> **关联**：[9/5](./MOCASA催收系统升级_Phase1_自动跑记录_20260905.md) · [9/7](./MOCASA催收系统升级_Phase1_自动跑记录_20260907.md) · [records 目录](./README.md)

## 1. 结论

**五槽全日收口；首次测到 `S4_EMAIL_ENTRY` 里程碑 bulk。** 迁出 4 + 升档重建 1；迁出后再触达 **0**；`EXECUTING=0`。

| 口径 | 全日 |
| --- | --- |
| inbox `caseEvent` | **213**（03:00:04–20） |
| repayment inbox | 526 |
| 新建 plan | **24** |
| 取消 | `REPAID` 7 · `ROUTED_TO_LEGACY` **4** · `STAGE_UPGRADE` **1** |
| 日切 03:40 | scanned=374 · `stageChanged=1`（`533850`） |
| 08:00 SMS / Push | **191** / **18** DELIVERED |
| 09:15 AI | wave **191**；ANSWERED **3** |
| 12:00 Push | **191** DELIVERED |
| 14:00 Email | **32** DELIVERED + 12 SKIPPED |
| 14:30 AI | wave **174**；ANSWERED **1** |

## 2. 五槽

| PHT | 渠道 | 结果 |
| --- | --- | --- |
| 08:00 | SMS | DELIVERED **191**（S2×83 S3×60 S4×34 S1×14） |
| 08:00 | PUSH | DELIVERED **18**（S0 URGENT 14 / DUE_TODAY 4） |
| 09:15 | AI | BUSY 99 / FAILED 58 / NO_ANSWER 29 / SNR 2 / **ANSWERED 3**；SKIPPED 105 |
| 12:00 | PUSH | DELIVERED **191** |
| 14:00 | EMAIL | DELIVERED **32**：`S2_EMAIL_ENTRY` **13** · `S4_EMAIL_ENTRY` **12** · S0×4 · S1×3 |
| 14:30 | AI | BUSY 77 / FAILED 64 / NO_ANSWER 31 / SNR 1 / **ANSWERED 1**；SKIPPED 73 |

## 3. 接通与 CONNECT_AND_STOP

| 槽 | case | label |
| --- | --- | --- |
| 09:15 | `513849` | summary 空 |
| 09:15 | `519977` | `incomplete`（仅 Hello） |
| 09:15 | `534282` | `incomplete`（未确认本人） |
| 14:30 | `528596` | `follow_up_required` |

上午 3 接通 → 下午全部 **SKIPPED**。

## 4. 分流 / 日切

| 断言 | 结果 |
| --- | --- |
| `ROUTED_TO_LEGACY` | **4**（`520036` `529556` `534785` `534896`） |
| 迁出后再 DELIVERED | **0** |
| `STAGE_UPGRADE` | **1**：`533850` @03:40（与 `stageChanged=1` 对齐） |

## 5. 问题

| 项 | 严重度 | 状态 |
| --- | --- | --- |
| 五槽 / 分流 / CONNECT_AND_STOP / S4 Email | — | **通过**（S4 里程碑首次 bulk） |
| AI `MEDIA_NEGOTIATION_FAILED` | 渠道 | session 计 **118** |
| 接通多为 incomplete / 空 summary | 质量 | 持续 |
| FIRM | 未测 | 仍 STANDARD |
| S4 SMS/Push 高 dpd 仍在池内 | 口径 | 对数仓快照（续前） |
