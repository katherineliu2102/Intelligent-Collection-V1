# Phase 1 自动跑记录（2026-09-02）

> **层级**：Pilot 自然日自动跑。名单仍空，消费跟订阅。  
> **对比 9/1**：S0 整桶日切续 S1；09:15 **4 通接通**（昨 1 通）；14:30 **准时起批**（昨晚 ~7 分钟）；Hikari 25 **全日无池满**。  
> **环境**：同 8/31 14:19 包；Hikari `maximum-pool-size=25`（9/1 15:40 写入）。  
> **取数**：2026-09-02 **~14:45 PHT**（五槽全日已收口）。  
> **关联**：[9/1 记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260901.md) · [8/29–9/1 综述](./records/MOCASA催收系统升级_Phase1_自动跑综述_20260829-0831.md) · [200 案抽样](./samples/MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md)

## 目录

- [1. 结论](#1-结论)
- [2. 进了多少](#2-进了多少)
- [3. 五槽](#3-五槽)
- [4. 数仓与日切](#4-数仓与日切)
- [5. 催收库表](#5-催收库表)
- [6. 问题清单](#6-问题清单)

---

## 1. 结论

**五槽全日通过；S0→S1 日切、Email D+1 里程碑、14:30 准时起批。** 03:35 `stageChanged=7`（6 户 S0 续 S1 + `507209` 结清取消）。08:00 SMS **134**；12:00 Push **133**；14:00 Email **6**（全新 S1 dpd=1）；09:15 / 14:30 两批 AI 均已收口，`EXECUTING=0`。全日 AI 接通 **5 通**（09:15×4 + 14:30×1）。`FAILED` 74 通均为 Facade 回调 `MEDIA_NEGOTIATION_FAILED`（外呼已拨、媒体层失败，非我们调 API 失败）。

| 口径 | 结果 |
| --- | --- |
| 名单 | **空** |
| inbox `caseEvent` | **168**（03:00:03–16） |
| 200 圈在库 | **199**（在催 **171** / 结清 22 / 停催 5） |
| 日切 03:35 | `scanned=195 stageChanged=7 ceased=0 rollbackSkipped=0` |
| 08:00 SMS | **134** DELIVERED |
| 08:00 PUSH | **0**（无 S0 在催） |
| 09:15 AI | **133 案** `mocasa-20260902-0915-1`；BUSY 58 / FAILED 40 / NO_ANSWER 30 / **ANSWERED 4** |
| 12:00 PUSH | **133** DELIVERED |
| 14:00 EMAIL | **6** DELIVERED · `S1_EMAIL_OVERDUE_NOTICE` |
| 14:30 AI | **97 案** `mocasa-20260902-1430-1` **准时**；BUSY 44 / FAILED 34 / NO_ANSWER 17 / **ANSWERED 1** |
| 原 40 | `513749` `526109` 全日 **SKIPPED** |
| 悬挂 `EXECUTING` | **0** |
| health | **200**；**无 HikariPool 打满** |

---

## 2. 进了多少

| 项 | 值 |
| --- | --- |
| env 两份名单 | **0** |
| `t_ai_collection` | **200** |
| inbox 当日 `caseEvent` | **168** |
| 200 圈在催 | **171** |

### 2.1 S0→S1 日切

昨日 11 户 S0 dpd=0，今日 **无在催 S0**。03:35 续档：

| 动作 | 案号 |
| --- | --- |
| 新建 S1 计划 | `507262` `507333` `507347` `531822` `531860` `531867`（现 S1 dpd=1） |
| 结清取消 | `507209`（`SETTLED`，plan `1077` `PLAN_CANCELLED`） |

另有部分昨日 S0 户此前已结清：`507296` `507323` `507379` `531817` 等。

### 2.2 投影分布（节选）

| 投影 | n |
| --- | --- |
| S3 dpd 20 | 23 |
| S4 dpd 36 | 22 |
| 结清 dpd=0 | 22 |
| S4 dpd 79 | 16 |
| S2 dpd 8 | 15 |
| S1 dpd 1 | 6 |
| 停催 dpd 91 | 5 |

---

## 3. 五槽

| PHT | 渠道 | 今日 | 说明 |
| --- | --- | --- | --- |
| 03:35 日切 | — | 195 扫描 | `stageChanged=7` |
| 08:00 | SMS | **134** DELIVERED | S1×7 + S2×44 + S3×32 + S4×51 |
| 08:00 | PUSH | **0** | 无 S0 |
| 09:15 | AI | **133 收口** | 见 §3.2 |
| 12:00 | PUSH | **133** DELIVERED | 08:00–12:00 |
| 14:00 | EMAIL | **6** DELIVERED | 6 户 S1 dpd=1 |
| 14:30 | AI | **97 收口** | **准时**起批；见 §3.4 |

### 3.1 日切续档 — 通过

6 户 S0→S1 新建 plan，首槽从今日 08:00 SMS 起。`507209` 结清取消，符合预期。

### 3.2 09:15 接通（4 通）

| case_id | 投影 | plan/step | 回调 | 标签 / 摘要 |
| --- | --- | --- | --- | --- |
| **507347** | S1 dpd=1 ₱682 | `1072` / `13644` | 09:16:05 | 新入 S1 首日；`summary` 空 |
| **513849** | S3 dpd=20 ₱11,070 | `996` / `10809` | 09:16:56 | 连续第 2 日接通；`summary` 空 |
| **531516** | S2 dpd=5 ₱2,067 | `1046` / `13079` | 09:18:21 | `incomplete`；音频问题 |
| **529588** | S2 dpd=11 ₱4,036 | `1001` / `11221` | 09:20:09 | `refused_to_discuss` |

四案均 **CONNECT_AND_STOP** → 14:30 第二通 AI **SKIPPED**。

### 3.3 09:15 `FAILED` 40 — Facade 媒体层

40 通 `result=FAILED`，回调 `final_failure_reason=MEDIA_NEGOTIATION_FAILED`，`attempt_count=3`。波次已正常 enroll，**非**催收调 Facade API 失败。需 Facade/LTH 查 SIP/媒体网关。

### 3.4 14:30 AI — 准时收口

| 时刻 | 事件 |
| --- | --- |
| 14:30:05–10 | 步骤派发开始（**无延迟**，对比 9/1 晚 ~7 分钟） |
| 14:30:27 | wave `mocasa-20260902-1430-1` 起批 **97 案** |
| ~14:32+ | 回调陆续返回，**全日收口** `EXECUTING=0` |

终态：BUSY 44 / FAILED 34 / NO_ANSWER 17 / ANSWERED **1** / SENT_NO_RESPONSE 1；SKIPPED 38（含上午 CONNECT_AND_STOP 等）。

#### 14:30 唯一接通 — 案 `520087`

| 项 | 值 |
| --- | --- |
| 投影 | S4 **dpd=36**，逾期额 **₱4,652.56** |
| plan/step | `957` / `7320` |
| 回调 | 14:30:10 `ANSWERED` |
| 摘要 | `summary` 空（短通话，同部分 09:15 案） |

`529588` 因上午已接通，下午 **不在** 本批第二通（符合规格）。

### 3.5 14:00 Email — 通过

6 封全部 `S1_EMAIL_OVERDUE_NOTICE`，对应 6 户新入 S1 dpd=1（`507262` `507333` `507347` `531822` `531860` `531867`）。首次测到 **S1 D+1 Email 里程碑**。

### 3.6 原 40 止血 — 有效

`513749` `526109`：计划 `PLAN_CANCELLED`；全日 SMS/AI/Push **SKIPPED**。

### 3.7 08:00 SMS 槽位

| script_slot | n |
| --- | --- |
| `S4_SMS_STANDARD` | 51 |
| `S2_SMS_STANDARD` | 44 |
| `S3_SMS_STANDARD` | 32 |
| `S1_SMS_STANDARD` | 7 |

全 STANDARD（FIRM 未上线）。

---

## 4. 数仓与日切

### 4.1 caseEvent — 通过

03:00:03–16 进 **168** 条。无 dueDate 毒丸。

### 4.2 日切 — 通过

```
daily roll completed fullScan=true scanned=195 stageChanged=7 ceased=0 rollbackSkipped=0
```

### 4.3 repaymentEvent

当日 inbox **109** 笔；午后仍有圈外 `repaymentEvent` 缺基线毒丸（`533751` 等），ack+skip，不影响 200 圈触达。

---

## 5. 催收库表

| 表 | 9/2 ~14:45 |
| --- | --- |
| `t_ai_collection` | 200 |
| inbox 当日 | 168 `caseEvent` + 109 `repaymentEvent` |
| timeline DELIVERED | SMS 134 / PUSH 133 / EMAIL 6 |
| AI 接通（全日） | **5**（09:15×4 + 14:30×1） |
| 悬挂 `EXECUTING` | **0** |
| Hikari 池 | **25** |

---

## 6. 问题清单

| 项 | 挡触达？ | 状态 |
| --- | --- | --- |
| Hikari 池 | 否 | **已缓解**：全日无打满；14:30 准时（对比 9/1） |
| AI `FAILED` 74 | 否 | Facade `MEDIA_NEGOTIATION_FAILED`；提 session 给 Facade 查 |
| S0 缺投影 | 部分桶 | 仍要数仓补发（[9/1 清单](./e2e200_缺投影清单_20260901.md)） |
| FIRM | 否 | 全 STANDARD；难催字段需求已出 |
| `513849` 再接通 | 否 | 不冻结不加呼（同 9/1） |
| 圈外 repayment 毒丸 | 否 | 午后仍有；圈外 caseId，不影响 e2e200 |
| 话术正文 | 否 | SMS/Push 正文仍抽不到 |

**9/1 vs 9/2**：14:30 延迟消失（Hikari 25 生效）；AI 接通 2→**5**；新增 S1 D+1 Email 6 封；`FAILED` 仍为 Facade 媒体层，非引擎 dispatch 失败。
