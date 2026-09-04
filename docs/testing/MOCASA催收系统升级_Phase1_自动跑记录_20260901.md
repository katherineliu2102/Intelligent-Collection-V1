# Phase 1 自动跑记录（2026-09-01）

> **层级**：Pilot 自然日自动跑。名单仍空，消费跟订阅。  
> **对比 8/31**：续档代码第一次**自己**在 03:35 跑（不再手清 Redis）；日期解析已在，数仓重发后 S0 进了一部分。  
> **环境**：仍是 8/31 14:19 那包。`StartedAt 2026-08-31T06:18:26Z`。  
> **取数**：2026-09-01 **~15:40 PHT**（14:30 已收口；15:35 停原 40 两案；Hikari 池调至 25 并重启）。14:40 口径已被本篇覆盖。  
> **综述**：[8/29–8/31](./records/MOCASA催收系统升级_Phase1_自动跑综述_20260829-0831.md)  
> **关联**：[8/31 记录](./records/MOCASA催收系统升级_Phase1_自动跑记录_20260831.md) · [200 案抽样](./samples/MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md)

## 目录

- [1. 结论](#1-结论)
- [2. 进了多少](#2-进了多少)
- [3. 五槽](#3-五槽)
- [4. 数仓与日切](#4-数仓与日切)
- [5. 催收库表](#5-催收库表)
- [6. 问题清单](#6-问题清单)

---

## 1. 结论

**自然日续档、S0 部分进件、Email 按 dpd 映射，五槽全日通过。** 03:35 `scanned=195 stageChanged=8`，没有手补。S0 11 户 D0：早 Push + 14:00 `S0_DUE_TODAY_EMAIL` 全 DELIVERED。14:30 虽因 Hikari 池打满晚 ~7 分钟起批，但 **15:03 前 98 通已全部收口**（`EXECUTING=0`）。09:15 接通 `513849` 有争议对话；14:30 **CONNECT_AND_STOP 已 skip**。

**下午处置：** `513749` `526109` 已 `MANUAL_CLEANUP`（原 40 误触达止血）。Hikari `maximum-pool-size` 从默认 10 调至 **25** 并重启，health **200**。S0 模板 dayBlocks **设计上无 SMS**（只有 PUSH + EMAIL），不是日块漏配。

| 口径 | 结果 |
| --- | --- |
| 名单 | **空** |
| inbox `caseEvent` | **183**（200 圈 150 + 圈外 33），03:00:03–25 |
| 200 圈投影 | **159**（昨 143；新进 16） |
| 仍缺 | **41**（含 D91 4） |
| 日切 03:35 | `scanned=195 stageChanged=8 ceased=0 rollbackSkipped=0`，**未手补** |
| 08:00 SMS | **134** DELIVERED（200 圈 132 + 原 40 的 2） |
| 08:00 PUSH | **13** DELIVERED（S0 D0） |
| 09:15 AI | **134 一批**；BUSY 64 / FAILED 40 / NO_ANSWER 29 / ANSWERED **1**（`513849` dispute） |
| 12:00 PUSH | **133** DELIVERED |
| 14:00 EMAIL | **20** DELIVERED：S0×11 `S0_DUE_TODAY_EMAIL` + S2 dpd=4×9 `S2_EMAIL_ENTRY`；`531682` SKIPPED |
| 14:30 AI | **晚 7 分钟**一批 98；**15:03 前收口**；ANSWERED **1**（`529588` vague_commitment） |
| 原 40 | `513749` `526109` 今早打了；**15:35 已 MANUAL_CLEANUP** |
| 悬挂 | **0**（14:30 批次已齐） |
| health | 14:37 曾 **503**；15:40 **200**；Hikari 池 **25** |

---

## 2. 进了多少

| 项 | 值 |
| --- | --- |
| env 两份名单 | **0** |
| `t_ai_collection` | **200**（200 圈 159 + 圈外 41） |
| inbox 当日 `caseEvent` | **183** |
| 其中 200 圈 | **150** |
| 200 圈仍缺投影 | **41** |

### 2.1 S0：16 进投影，11 在催

| 状态 | n | ID |
| --- | --- | --- |
| 在催 S0 dpd=0 | 11 | `507209` `507262` `507296` `507323` `507333` `507347` `507379` `531817` `531822` `531860` `531867` |
| 进投影后已结清 | 5 | `507313` `507398` `531813` `531827` `531851` |

抽样 S0 整桶约 40，今天日历是 **D0**。只到 11 个在催。03:00 死锁 nack 后齐。

### 2.2 200 圈投影（与上午相同）

| 投影 | n |
| --- | --- |
| S0 dpd 0 | 11 |
| S2 dpd 4 / 5 / 7 / 10 | 10 / 12 / 16 / 11 |
| S3 dpd 19 / 26 | 20 / 12 |
| S4 dpd 35 / 65 / 78 / 83 | 19 / 12 / 16 / 4 |
| IN_COLLECTION、stage 空 | 3（负 dpd）+ 后增 `531682` dpd=−27 |
| CEASED dpd 91 | 4 |
| SETTLED | 9 |

`531682`：S1 走完后 03:35 续 S2，14:00 前投影变成 dpd=−27 / stage 空（提前还清、下一期仍远），计划 `1070` `PLAN_CANCELLED`，Email SKIPPED。属预期，不是漏发。

---

## 3. 五槽

| PHT | 渠道 | 今日 | 说明 |
| --- | --- | --- | --- |
| 03:35 日切 | — | 全表 195 | **自然跑**，`stageChanged=8` |
| 08:00 | SMS | **134** | 200 圈 132 + 原 40 的 2。S0 无 SMS 步 |
| 08:00 | PUSH | **13** | 11 个在催 S0 + 2 个 09:00 才取消的 S0 |
| 09:15 | AI | **134 一批** | `mocasa-20260901-0915-1`；接通 1 |
| 12:00 | PUSH | **133** | 预写 134 减 1（`531682` 等取消） |
| 14:00 | EMAIL | **20** | 见 §3.3 |
| 14:30 | AI | **98 一批，晚 ~7 分钟** | `mocasa-20260901-1430-1` / `52172d96-…`；见 §3.6 |

### 3.1 日切续档 — 通过

昨天 8 个 S1 成功走完：7 个建成 S2 并完成 SMS/AI/Push/Email（`531682` 14:00 因余额口径取消）；`506855` 07:00 结清，skipOpen 49 步。

### 3.2 3 案穷尽升档 — 无双计划

`520023` `531516` `531578` 仍各 1 份 S2（1045–1047）。

### 3.3 Email — 按当天 dpd，不是桶名

20 封 timeline：

| n | 投影 | slot | SendGrid |
| --- | --- | --- | --- |
| 11 | S0 dpd=0 | `S0_DUE_TODAY_EMAIL` | `d-9b485bfd24e14950a7811faf33c2b22f` |
| 9 | S2 dpd=4 | `S2_EMAIL_ENTRY` | `d-86ed8faae3b24489ad7db8a11067b8c4` |

11 个在催 S0 早 Push + 这封 Email 齐。Pilot S0 模板 dayBlocks：**dpdDay 0 只有 PUSH 08:00 + EMAIL 14:00，无 SMS**（与种子 `seed-phase1-config.sql` 一致）。若要 D0 短信需改模板并重建计划。

缺投影清单见 [e2e200 缺投影清单](./e2e200_缺投影清单_20260901.md)：S0 仍缺 **24** 户，要数仓补发。

### 3.4 接通

当日 AI 接通 **2 通**：09:15 `513849`（争议）、14:30 `529588`（模糊承诺）。14:30 批 98 通里仅 1 通 ANSWERED。

#### 3.4.1 案 `513849`（09:15）

| 项 | 值 |
| --- | --- |
| 身份 | plan `996` / step `10780` / 审计 id=885 / `session_id=a970dd80-…` |
| 判定 | `was_ai_connected=true` 且 `reason=NORMAL` → `ANSWERED` |
| 时间 | 09:15:08 → 09:23:16，**488 秒** |
| summary | `The customer disputed the outstanding balance and did not provide a payment commitment.` |
| `result_label` | `dispute` |
| 停呼 | 14:30 step `10793` **SKIPPED**，**未进** 14:37 那批 98 |

对话三句：核名 → 借款人说只有 240 → 助手报 ₱11,036.26 / 逾期 19 天（账已核对）。无承诺。金额争议不走 Phase 2 冻结；今天不加呼。

#### 3.4.2 案 `529588`（14:30，本批唯一接通）

| 项 | 值 |
| --- | --- |
| 身份 | plan `1001` / step `11210` / 审计 id=969 / `session_id=a2a9391d-58b5-4285-ab35-e761f91ab31c` |
| 投影 | S2 **dpd=10**，逾期额 **₱4,014.50** |
| 判定 | 回调 `ANSWERED` |
| 起呼 | **14:37:24**（因 Hikari 延迟，比槽晚 ~7 分钟） |
| 回调 | **14:41:34**，通话 **250 秒** |
| `result_label` | `vague_commitment` |
| summary | `The agent repeatedly asked to settle the ₱4,014.50 overdue balance, and the user ended with a vague agreement without a clear payment commitment.` |
| 停呼 | 09:15 同计划为 **BUSY**（非接通），CONNECT_AND_STOP **未触发**；14:30 正常进批 |

与 `513849` 对比：这通有完整 **summary** + `result_label`，属于「谈了但没明确承诺」；`script_url` 有值但转写为空（与 8/30–8/31 核名案同类，待 Facade 侧看格式）。09:15 已 BUSY 过，故下午第二通符合规格。

### 3.5 其他对照

- `531315`：跨日 09:15 NO_ANSWER，应在 14:30 那 98 里（昨天接通不挡今天）。
- 原 40：`513749` `526109` 今早打了；**15:35 已 MANUAL_CLEANUP**。数仓应停推这两户 `caseEvent`，否则日切可能再续档。
- 还款 `skipOpen`：`506855` `505644` 等；`531682` 14:00 取消。

### 3.6 14:30 延迟起批 — 已收口

| 时刻 | 事件 |
| --- | --- |
| 14:35–14:37 | Hikari 池耗尽；ingestion nack；`planStepDue` 扫描失败（**不重投**） |
| 14:37:22 | health db check 超时 30s → **503** |
| 14:37:23 | `planStepDue scanned=99 costMs=17238` |
| 14:37:45 | wave `mocasa-20260901-1430-1` **cases=98**（99 − `513849` skip） |
| ~15:03 | **收口**：BUSY 42 / NO_ANSWER 25 / FAILED 30 / ANSWERED 1 / SKIPPED 30；`EXECUTING=0` |
| ~15:40 | Hikari `maximum-pool-size=25` 写入 `pilot.env` 并重启；health **200** |

调度失败不重投，靠下一拍才扫到。今天下一拍成功；若连续失败会漏整槽。池子已从默认 10 调到 25；根治仍需扫描重试 + 降 ingestion 占连接。

---

## 4. 数仓与日切

### 4.1 caseEvent — 通过

03:00:03–25 进 183 条。死锁 nack 后齐。无非法 dueDate 毒丸。

### 4.2 日切 — 通过

```
daily roll completed fullScan=true scanned=195 stageChanged=8 ceased=0 rollbackSkipped=0
```

### 4.3 repaymentEvent（只记账）

上午 7 案；下午仍有同一批结清案的重复 `REPAID` 记账（`506855` `531851` 等），计划早已取消，skipOpen 不会重复拆。

---

## 5. 催收库表

| 表 | 9/1 ~15:40 |
| --- | --- |
| `t_ai_collection` | 200 |
| inbox 当日 | 183 `caseEvent` + 多笔 `repaymentEvent` |
| 200 圈活跃计划 | 上午 143；随后 `531682` 等取消 |
| 悬挂 `EXECUTING` | **0** |
| outbox / 当日 DLQ | 0 |

timeline 14:00 时：SMS 134、PUSH 146（08:00 的 13 + 12:00 的 133）、EMAIL 20；AI 随 14:30 回调增加。

---

## 6. 问题清单

| 项 | 挡触达？ | 状态 |
| --- | --- | --- |
| **Hikari 池打满** | 14:30 晚 7 分钟 | **已缓解**：池 10→**25** 并重启；发版仍要加重试 |
| 14:30 回调 | — | **已收口**，`EXECUTING=0` |
| S0 未整桶 | S0 仍缺 24 | 清单已出 → [缺投影清单](./e2e200_缺投影清单_20260901.md)；要数仓补发 |
| S0 无 SMS 步 | 设计如此 | Pilot S0 模板只有 PUSH+EMAIL；改模板才加 SMS |
| 原 40 2 案 | 今早误打 | **已 MANUAL_CLEANUP**；数仓应停推 |
| 03:00 死锁 nack | 否 | 重投后齐 |
| 话术正文 | 否 | SMS/Push 正文仍抽不到 |

金额争议 `513849`：账对，Phase 1 不冻结；14:30 已 skip，**不要加呼、不要为核实再打 Facade**。
