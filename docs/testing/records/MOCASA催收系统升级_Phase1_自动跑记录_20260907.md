# Phase 1 自动跑记录（2026-09-07）

> **层级**：Pilot 自然日（Owner NEW 分流续跑；白名单空）。  
> **环境**：同 9/3 起容器；Hikari 25。  
> **取数**：2026-09-07 **~09:41 PHT**（08:00 / 09:15 已收口；**12:00 / 14:00 / 14:30 尚未跑**）。  
> **关联**：[9/6](./MOCASA催收系统升级_Phase1_自动跑记录_20260906.md) · [records 目录](./README.md)

## 1. 结论（上午）

**上午两槽准时收口；接通 0；下午三槽仍 PENDING。** 迁出 1 + 升档 1；迁出后再触达 **0**；`EXECUTING=0`。

| 口径 | 上午 |
| --- | --- |
| inbox `caseEvent` | **204**（03:00:03–22） |
| repayment inbox | 88（取数时尚早） |
| 新建 plan | **2** |
| 取消 | `REPAID` 2 · `ROUTED_TO_LEGACY` **1** · `STAGE_UPGRADE` **1** |
| 日切 03:40 | scanned=374 · `stageChanged=1`（`534282`） |
| 投影（取数时点） | 在催 **305** / 结清 69 / 停催 5；活跃 plan **204** |
| 08:00 SMS / Push | **194** / **10** DELIVERED |
| 09:15 AI | wave **194**；ANSWERED **0** |
| 12:00 / 14:00 / 14:30 | PENDING 194 / 21 / 180 |

## 2. 已跑槽

| PHT | 渠道 | 结果 |
| --- | --- | --- |
| 08:00 | SMS | DELIVERED **194**（S2×79 S3×72 S4×34 S1×9） |
| 08:00 | PUSH | DELIVERED **10**（全 `S0_DUE_TODAY`） |
| 09:15 | AI | BUSY **97** / FAILED **58** / NO_ANSWER **36** / SNR **3** / ANSWERED **0**；SKIPPED 99 |

wave：`mocasa-20260907-0915-1`，cases=**194**，`09:15:34` 起批。

SNR 3：`507262` 信箱；`528596` 筛选/信箱；`533850` 信箱（均非引擎 ANSWERED）。

## 3. 分流 / 日切

| 断言 | 结果 |
| --- | --- |
| `ROUTED_TO_LEGACY` | **1**：`535159` @03:35 |
| 迁出后再 DELIVERED | **0** |
| `STAGE_UPGRADE` | **1**：`534282` @03:40 |

## 4. 待补（下午）

| 槽 | 库存 |
| --- | --- |
| 12:00 PUSH | PENDING **194** |
| 14:00 EMAIL | PENDING **21**（另 SKIPPED 10） |
| 14:30 AI | PENDING **180**（另 SKIPPED 64） |

## 5. 问题

| 项 | 严重度 | 状态 |
| --- | --- | --- |
| 上午 SMS/Push/AI 收口 | — | **通过** |
| 分流缺席迁出 | — | **通过**（1 户，零再触达） |
| 09:15 零接通 | 观测 | 不挡跑；通道仍偏 BUSY/FAILED |
| AI `MEDIA_NEGOTIATION_FAILED` | 渠道 | 上午 session **58**（与 FAILED 对齐） |
| 下午三槽 | 待补 | 12:00 / 14:00 / 14:30 |
| FIRM | 未测 | 仍 STANDARD |

**下午建议补验**：Push/Email/AI 是否准时；FAILED 是否仍以 Facade 媒体层为主；迁出案是否仍零触达。
