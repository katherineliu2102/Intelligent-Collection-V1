# Phase 1 自动跑记录（2026-08-31）

> **层级**：Pilot 自然日自动跑。昨晚 **17:39** 已空名单、消费跟订阅。  
> **对比 8/30**：8/30 还按 200 名单滤；今天两份 env 为空，日切全表扫。  
> **环境**：11:53 续档包；**14:19** 再发日期解析 + S0 `upcomingAmount` 守卫。容器 `StartedAt 2026-08-31T06:18:26Z`（= 14:18 PHT），loopback health **200**。  
> **取数**：2026-08-31 **~16:05 PHT**（五槽都已过）。上午 09:44 口径见文中对照，已被本篇覆盖。  
> **三天综述**：[8/29–8/31](./MOCASA催收系统升级_Phase1_自动跑综述_20260829-0831.md)  
> **关联**：[8/30 记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260830.md) · [200 案抽样](./MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md)

## 目录

- [1. 结论](#1-结论)
- [2. 进了多少](#2-进了多少)
- [3. 五槽](#3-五槽)
- [4. 数仓与日切](#4-数仓与日切)
- [5. 催收库表](#5-催收库表)
- [6. 问题清单](#6-问题清单)

---

## 1. 结论

**空名单消费、日切全表、续档补跑、下午三槽都跑通了。** inbox `caseEvent` 仍是凌晨那 **173** 条。08:00 SMS **127**；09:15 AI **126 一批**接通 0；12:00 Push **136**；14:00 Email **12**（全是投影 dpd=4 / `S2_EMAIL_ENTRY`）；14:30 AI **104 一批** `mocasa-20260831-1430-1`，接通 **1**（`531315`，约 80 秒）。悬挂 **0**。当日 ERROR / DLQ / outbox **0**。

上午那 **8** 案 S1 走完没续 S2：11:58 手动日切已建计划 1035–1044，下午 Push / Email / AI **全部实发**。顺带原 40 圈里 `513749` `526109` 被续成 S3，今日 Push+AI 各打了 1 次（空名单后订阅仍有它们）。

S0 整桶仍不在投影。14:19 已放宽到期日解析，但 **已毒丸 ACK 的不会重投**；14:19 之后没有新的 `caseEvent`。缺的还是那 **57**。

| 口径 | 结果 |
| --- | --- |
| 名单 | **空**。订阅有什么进什么 |
| inbox `caseEvent` | **173**（200 圈 139 + 原 40 圈 34），03:00:03–22 |
| 200 圈投影 | 仍 **143**；今日快照 139，少的 4 个是昨已还/结清 |
| 缺 57 | 与 8/30 同一批，S0 整桶仍未进；14:19 后无新 `caseEvent` |
| 日切 03:35 | `scanned=179` `fullScan=true` `stageChanged=0` |
| 11:58 手动日切 | `scanned=179 stageChanged=10 ceased=0 rollbackSkipped=0` |
| 08:00 SMS | **127** DELIVERED（全在 200 圈） |
| 09:15 AI | **126 通一批**；BUSY 56 / FAILED 43 / NO_ANSWER 27；接通 0 |
| 12:00 PUSH | **136** DELIVERED（200 圈 134 + 原 40 的 2） |
| 14:00 EMAIL | **12** DELIVERED；slot=`S2_EMAIL_ENTRY`，SendGrid `d-86ed8faae3b24489ad7db8a11067b8c4`，投影 **dpd=4** |
| 14:30 AI | **104 通一批** `mocasa-20260831-1430-1`；BUSY 47 / FAILED 37 / NO_ANSWER 19 / ANSWERED **1** |
| 原 40 实发 | 上午 0；下午因续档 **2** 案（`513749` `526109`） |
| 悬挂 | **0** |

---

## 2. 进了多少

| 项 | 值 |
| --- | --- |
| env 两份名单 | **0** |
| inbox 当日 `caseEvent` | **173** 案 |
| 其中 200 圈 | **139** |
| 其中原 40 圈 | **34** |
| 200 圈仍在 `t_ai_collection` | **143**（和 8/30 相同） |
| 200 圈今日无 `caseEvent` | **4**：`520142` `530618` `531592` `531713`（昨已还/结清） |
| 200 圈仍缺投影 | **57**，ID 与 8/30 相同 |
| 14:19 后 inbox | 仅 `repaymentEvent`，**无**新 `caseEvent` |

### 2.1 原 40 进 inbox；2 案下午被续档打到

空名单后，订阅里的原 40 不再被 ack 跳过。34 条 `caseEvent` 进了投影。昨晚 `MANUAL_CLEANUP` 停的是当时**活跃**计划，所以上午实发 0。

例外：`513749` `526109` 上一条是 **`PLAN_COMPLETED`**（不是 `MANUAL_CLEANUP`）。11:58 续档按「COMPLETED 且投影档更高」给它们建了 S3，下午 Push DELIVERED、AI BUSY 各 1。空名单口径下这是续档的副作用，不是扫描又捞了已取消计划。

### 2.2 S0 仍整桶不在

缺 57 没变：S0 约 40 + D91_already 4 + 逾期桶里像结清的十几个。`WHITELIST_SKIPPED=0`。根因是数仓到期日写成 `2026-09-01T00:00:00.000`，催收按纯日期解析失败后 **毒丸 ACK**。14:19 已放宽解析并让 S0 认 `upcomingAmount`；**已经 ACK 掉的不会重投**，要数仓按 `yyyy-MM-dd` 重发。D91 4 个可当停催圈。

14:19 之后日志里没有新的「非法 dueDate」；仍有大量 `repaymentEvent 缺完整 caseEvent 基线`（订阅里那些从未进过投影的还款），属预期，不写 inbox。

### 2.3 已进 200 圈的档（今天投影，16:05 与上午相同）

| 投影 | n |
| --- | --- |
| S1 dpd 3 | 11 |
| S2 dpd 4 / 6 / 9 | 12 / 17 / 11 |
| S3 dpd 18 / 25 | 20 / 12 |
| S4 dpd 34 / 64 / 77 / 82 | 19 / 12 / 16 / 4 |
| IN_COLLECTION、stage 空 | 3（负 dpd） |
| CEASED dpd 91 | 4 |
| SETTLED | 2 |

---

## 3. 五槽

| PHT | 渠道 | 今日实发 | 说明 |
| --- | --- | --- | --- |
| 03:35 日切 | — | 全表 179 | `stageChanged=0`；当时还没续档代码 |
| 08:00 | SMS | **127** | 全在 200 圈。另有取消计划上残留 PENDING 6、SKIPPED 32 |
| 09:15 | AI | **126 一批** | `mocasa-20260831-0915-1`；接通 0 |
| 11:58 | 手动日切 | 10 档 | 8 案 S1→S2 + 2 案 S2→S3 |
| 12:00 | PUSH | **136** | 200 圈 134（含 8 案补跑）+ 原 40 的 2 |
| 14:00 | EMAIL | **12** | 原预写 4 + 8 案补跑；全 dpd=4 |
| 14:19 | 发版 | — | 日期解析 + S0 upcoming；health 200 |
| 14:30 | AI | **104 一批** | `mocasa-20260831-1430-1`；含 8 案补跑与 2 案原 40；接通 1 |

取消计划上残留 PENDING（每槽约 6 条）扫描不捞，不是新悬挂。timeline 当日 OUT：SMS 127、PUSH 136、EMAIL 12、AI_CALL 230（= 126+104）。

### 3.1 8 案 S2 补跑 — 通过

`506565` `519633` `531157` `531245` `531278` `531315` `531368` `531446`

计划 1035–1044，档 S2 `STEP_SCHEDULED`。三槽均在 12:00:12 / 14:00:06–07 / 14:30:10–11 落地：

| 渠道 | 结果 |
| --- | --- |
| Push | 8/8 DELIVERED |
| Email | 8/8 DELIVERED，`S2_EMAIL_ENTRY` |
| AI | BUSY 2 / NO_ANSWER 3 / FAILED 2 / **ANSWERED 1**（`531315`） |

上午 08:00 / 09:15 它们还没有计划，SMS/早 AI 空窗是当时漏续档，不是下午又漏。

### 3.2 Email — 模板按 dpd=4，不是桶名

12 封全部：`script_slot=S2_EMAIL_ENTRY`，`template_version=sendgrid:d-86ed8faae3b24489ad7db8a11067b8c4`（代码里 DPD=4 的里程碑）。投影 dpd 全是 4 / S2。timeline **不存正文**，无法从库核对信里是否写出「逾期 4 天」；映射层已对上。

### 3.3 话术槽 vs 当天档

timeline 只留 slot 名，不留 `{dpd}/{amount}` 正文：

| 渠道 | 今日 slot |
| --- | --- |
| SMS | S1 11 / S2 33 / S3 32 / S4 51 |
| PUSH | S1 11 / S2 40 / S3 34 / S4 51 |

槽名跟当天计划档一致（8 案补跑走 `S2_*`）。占位符是否刷新仍抽不到正文。

### 3.4 14:30 接通（案 `531315`）

契约：Callback 带 `ai_result.summary`（可空）+ `media.script_url`（`conversation_history` 全文）。催收库仍不落正文，只存 `canonical_payload` 里的 URL。这一通 **summary 仍空**，全文只有助手核名一句，和 8/28、8/30 同类。

| 项 | 值 |
| --- | --- |
| 身份 | plan `1043` / step `12802` / 审计 id=692 / `session_id=a4ffa548-…` |
| 判定 | `line_outcome.was_ai_connected=true` 且 `reason=NORMAL` → `ANSWERED`；验签通过 |
| 时间 | 14:30:11 起呼，14:31:31 回写，**80 秒**（`timestamp` 06:31:31Z） |
| 批次 | `mocasa-20260831-1430-1` |
| `ai_result` | `summary=null`，`result_label=null`，`promises=[]` |
| 媒体 | `script_url` / `recording_url` 都有；payload 内 **无** `conversation_history` |
| 停呼 | 当日该计划只有这一通 AI，没有同日第二通可 skip；9/1 起仍有预写 AI |

用 Pilot API key 拉 `script_url`（593 字节）。**只有助手一句核名，没有借款人发言，没有承诺。**

1. Magandang araw po, si Maria po ito mula sa Mocasa. Si ERIKA FAITH BEDEO REYES po ba ang kausap ko?

线路算接通，对客没谈成。录音同域，同样要 Authorization。计划仍 `STEP_SCHEDULED` / S2。

### 3.5 S1 末日的两条路径（11 案 dpd=3）

14:30 是 S1 末日最后一通。**末步成功 → `PLAN_COMPLETED`**（8 案，含 `531687`）：按口径等 **次日日切** 再续 S2，今天没有新计划。

**末步 FAILED → `PLAN_EXHAUSTED` → 同档 REBUILD 0 步 → 引擎穷尽升档 S2**（3 案：`520023` `531516` `531578`）：14:37 建计划 1045–1047，步骤从 **9/1 08:00** 起，今日不会多打。投影此时仍是 S1 dpd=3。这是引擎穷尽升档（规格允许的第二个 `STAGE_CHANGED` 发布方），不是 11:58 日切。明日日切时它们已有活跃 S2，投影若升到 S2 则对齐；盯一下会不会双计划。

### 3.6 还款案

- `505901`：计划 `1017` `PLAN_CANCELLED`/`REPAID`，打开步已 SKIPPED，后续还款只记账，EXECUTING=0。
- `531687`：inbox 04 条还款在 **00:00–00:45**；投影仍 `IN_COLLECTION` S1 dpd=3、逾期额 7167.99。**未整笔结清**，12:00 Push / 14:30 AI 继续打是对的。14:32 `PLAN_COMPLETED`。

---

## 4. 数仓与日切

### 4.1 caseEvent — 通过（进了的那些）

| 层 | 8/30 | 8/31 |
| --- | --- | --- |
| inbox `caseEvent` | 03:00:04–27，143（仅 200 圈） | **03:00:03–22，173**（200 圈 139 + 原 40 圈 34） |
| 日切 | `scanned=200`（名单） | `scanned=179` **全表**（184 投影 − 5 CEASED） |

无死锁 nack。新容器时段无 ERROR。

### 4.2 日切全表 + 续档补跑

03:35：`daily roll completed fullScan=true scanned=179 stageChanged=0 ceased=0`（当时还没有「COMPLETED 后续档」）。

11:53 发版后 03:35 不会自动再跑。11:58 清 Redis `collection:ingestion:daily-roll:2026-08-31:completed` 后手动 `dailyRoll`：`scanned=179 stageChanged=10 ceased=0 rollbackSkipped=0`。

### 4.3 repaymentEvent（只记账，不验 2h）

4 案 43 条（00:00–16:00）：`505901`（28，09:15 起）`530618` `531687` `531713`。

---

## 5. 催收库表

| 表 | 8/31 ~16:05 |
| --- | --- |
| `t_ai_collection` | 184（143 新圈 + 原 Pilot 残留） |
| `t_ai_collection_inbox` 当日 | 173 `caseEvent` + 43 `repaymentEvent` |
| 悬挂 `EXECUTING` | **0** |
| `t_event_outbox` PENDING | 0 |
| `t_event_dlq` 当日 | 0 |

---

## 6. 问题清单

| 项 | 挡触达？ | 状态 |
| --- | --- | --- |
| S0 整桶仍无 `caseEvent` | 这 ~40 今天仍无触达 | 代码已放宽日期 + upcoming；**需数仓按 `yyyy-MM-dd` 重发**。已 ACK 不会自愈 |
| 8 案 S1 走完未续 S2 | 上午空窗 | **已补跑**：下午三槽 8/8 实发 |
| 原 40 里 2 案被续档打到 | 空名单后会打 COMPLETED 且档已变的旧圈 | `513749` `526109` 今日 Push+AI；`MANUAL_CLEANUP` 管不到已走完的计划 |
| 3 案末步失败当天升 S2 | 今日不多打（步骤从 9/1） | 穷尽升档 vs 成功走完等次日日切；明日看会否双计划 |
| Email 模板 | 否 | 12/12 已是 dpd=4 的 `S2_EMAIL_ENTRY`；**正文仍抽不到** |
| 话术 `{dpd}/{amount}` | 否 | slot 跟档；正文三天都没抽到 |
| 取消计划残留 PENDING | 否 | 扫描不捞；新取消已 skipOpen |
| 原 40 进 inbox 不打 | 上午符合预期 | 下午被续档的 2 案除外 |

接通质量仍是产品/线路问题，不是进件或调度。
