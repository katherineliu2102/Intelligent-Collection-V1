# Phase 1 自动跑记录（2026-09-05）

> **层级**：Pilot 自然日（Owner NEW 分流续跑；白名单空）。  
> **环境**：容器自 `2026-09-03T09:46:11Z`；Hikari 25。  
> **取数**：2026-09-07 回捞全日。  
> **关联**：[9/4](./MOCASA催收系统升级_Phase1_自动跑记录_20260904.md) · [9/6](./MOCASA催收系统升级_Phase1_自动跑记录_20260906.md) · [records 目录](./README.md)

## 1. 结论

**五槽全日收口；分流仍有效。** 迁出 4 户后再触达 **0**；`EXECUTING=0`；FIRM 未测。

| 口径 | 全日 |
| --- | --- |
| inbox `caseEvent` | **232**（03:00:03–20） |
| repayment inbox | 816 |
| 新建 plan | **34** |
| 取消 | `REPAID` 16 · `ROUTED_TO_LEGACY` **4** |
| 日切 03:40 | scanned=374 · `stageChanged=0` |
| 08:00 SMS / Push | **196** / **36** DELIVERED |
| 09:15 AI | wave **196**；ANSWERED **1** |
| 12:00 Push | **196** DELIVERED |
| 14:00 Email | **17** DELIVERED + 13 SKIPPED |
| 14:30 AI | wave **179**；ANSWERED **3** |

## 2. 五槽

| PHT | 渠道 | 结果 |
| --- | --- | --- |
| 08:00 | SMS | DELIVERED **196**（S2×75 S3×71 S1×28 S4×22） |
| 08:00 | PUSH | DELIVERED **36**（S0 REMINDER 16 / URGENT 10 / DUE_TODAY 10） |
| 09:15 | AI | BUSY 105 / FAILED 58 / NO_ANSWER 30 / SNR 2 / **ANSWERED 1**；SKIPPED 97 |
| 12:00 | PUSH | DELIVERED **196**（S1×28 S2×75 S3×71 S4×22） |
| 14:00 | EMAIL | DELIVERED **17**：S0×7 + S2_ENTRY×6 + S1×4 |
| 14:30 | AI | BUSY 89 / FAILED 60 / NO_ANSWER 26 / SNR 1 / **ANSWERED 3**；SKIPPED 65 |

## 3. 接通与 CONNECT_AND_STOP

| 槽 | case | label / summary |
| --- | --- | --- |
| 09:15 | `533850` | `incomplete`（等 Donna） |
| 14:30 | `513849` | summary 空（短通） |
| 14:30 | `528596` | `follow_up_required`（未本人 / 无承诺） |
| 14:30 | `534282` | `incomplete`（仅招呼） |

上午接通 `533850` → 下午 **SKIPPED**（CONNECT_AND_STOP 通过）。

## 4. 分流

| 断言 | 结果 |
| --- | --- |
| 当日 `ROUTED_TO_LEGACY` | **4**（`522833` `522918` `534521` `534672` @03:35） |
| 迁出后再 DELIVERED | **0** |

## 5. 问题

| 项 | 严重度 | 状态 |
| --- | --- | --- |
| 五槽 / 分流 / CONNECT_AND_STOP | — | **通过** |
| AI `MEDIA_NEGOTIATION_FAILED` | 渠道 | 全日 session 计 **118**（与 BUSY/NO_ANSWER 并列主因） |
| 接通质量多为 incomplete / 空 summary | 质量 | 库路径正常；Facade 短通/代接 |
| FIRM | 未测 | 仍 STANDARD |
| blacklist 未按日刷新 | 对照噪音 | 续 9/4 项 |
