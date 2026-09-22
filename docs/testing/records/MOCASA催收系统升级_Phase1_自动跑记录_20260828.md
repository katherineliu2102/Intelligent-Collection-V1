# Phase 1 自动跑记录（2026-08-28）

> **层级**：40 案 Pilot 自然日自动跑（昨晚 `4ca65f5` 发版后的首个完整自然日）。  
> **对比 8/27**：白名单 40、SETNX、邮件代码映射、穷尽 0 步升档均已在库上；今天看五槽是否按钟点发出。  
> **环境**：Pilot `bdp01`，容器 `collection-admin` 自 8/27 **16:32 PHT** 起未再发版（Up ~22h），白名单 40，波次聚合开。  
> **取数**：截止 **15:13 PHT**（五槽实绩）；**18:18** 补记数仓侧：`caseEvent` 进库改到 **03:00**、`repaymentEvent` 回看窗 **10h→2h**，8/29 验证。  
> **关联**：[8/27 记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) · [8/29 二百案抽样](../samples/MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md) · [主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) · [数仓契约](../../数仓_PubSub交付契约.md)

## 目录

- [1. 结论](#1-结论)
- [2. 五槽实绩](#2-五槽实绩)
- [3. DPD / 金额对账](#3-dpd--金额对账)
- [4. 催收库表与落库完整性](#4-催收库表与落库完整性)
- [5. 通知中心 BQ](#5-通知中心-bq)
- [6. 问题清单](#6-问题清单)
- [7. 波次聚合与接通停呼](#7-波次聚合与接通停呼)
- [8. 已改 / 遗留 / 明天](#8-已改--遗留--明天)

---

## 1. 结论

五个预写槽都按时触发，引擎主路径通过，**无悬挂 `EXECUTING`，无启动 ERROR，发件箱 PENDING=0，当日 DLQ=0。** 昨晚三项改动均验过：日切 40、09:15 / 14:30 各一批、14:00 邮件 `S4_EMAIL_ENTRY` 打到 SendGrid。CONNECT_AND_STOP 首次有样本，当日第二通未打出。

| 口径 | 结果 |
|---|---|
| 日切 03:35–05:55 | `scanned=40`；幽灵 ID WARN **0**；`stageChanged=0` `ceased=0` `rollbackSkipped=1` |
| 08:00 SMS | tick `scanned=33`；33 DELIVERED + timeline 33 |
| 09:15 AI | **33 通同一批** `mocasa-20260828-0915-1`；BUSY 20 / FAILED 11 / ANSWERED **1** / NO_ANSWER 1 |
| 12:00 PUSH | tick `scanned=32`；32 DELIVERED；BQ 催收正文 **32** |
| 14:00 EMAIL | **1/1 DELIVERED**，`S4_EMAIL_ENTRY`，SendGrid `OGfsOoSJTU2aLs2vuLS11g` |
| 14:30 AI | **24 通同一批** `mocasa-20260828-1430-1`；BUSY 11 / FAILED 11 / NO_ANSWER 2 |
| 真人接通 | **1**（案 `513749`）；当日第二通未打出 |

对照昨晚预估：SMS 35→33、Push 35→32、09:15 AI 36→33、14:30 AI 28→24 拨出。差额都是已还款计划未捞，不是漏催。

**DPD/金额**与今天还款表 leftover 一致（抽样四案）。**句子档**仍按过期 `snapshot.stage`，12:00 Push 同样走旧档，要对齐今天档并改代码（§6.1）。`caseEvent` 今早催收库仍是 **08:00:03** 才进（§6.2）；数仓晚间已改到 **03:00 进库**、还款回看 **2 小时**，**8/29 复验**。

---

## 2. 五槽实绩

对照昨晚 16:20 库内 PENDING 与今日实绩：

| PHT | 渠道 | 预估 | 实绩 | 说明 |
|---|---|---|---|---|
| 03:35 日切 | — | scanned=40 | **40** | 10 个幽灵 ID 已消失 |
| 08:00 | SMS | 35 | **33** | tick `08:00:04 scanned=33`；2 PENDING=`502131`/`527448` `REPAID` |
| 09:15 | AI | 36 | **33 发出、1 批** | 另 3 PENDING 已还款（含 `502356`） |
| 12:00 | PUSH | 35 | **32** | tick `12:00:07 scanned=32`；3 PENDING 同为已还款 |
| 14:00 | EMAIL | 1 | **1 DELIVERED** | 案 `519965` / plan `883` / step `3177` |
| 14:30 | AI | 28 | **24 拨出、1 批** | 3 PENDING 已还款；接通停呼 2 条 SKIPPED（见 §7） |

槽身份上还能看到 8/26 提前打掉的 `520049`（`original_trigger` 仍写着今天），不计入今日实发。

09:15 起批：`09:15:25 wave=20260828-0915#1 cases=33` / Facade `9edfa82e-bacc-40a7-86c0-f60e11f5494e`。  
14:30 起批：`14:30:24 wave=20260828-1430#1 cases=24` / Facade `6501c284-624a-48d2-b8b5-e974aeebe729`。

已还款未捞（扫描不捞终态计划，**流程正常**）：

| 案 | 计划 | 客户还清 | 催收取消计划 |
|---|---|---|---|
| `527448` | `855` | 8/26 11:14:43，2045.70 | 8/26 11:30:13 `PLAN_CANCELLED/REPAID` |
| `502131` | `832` | 8/27 13:12:37，2050.18 | 8/27 13:30:27 取消，投影 SETTLED |
| `502356` | `861` | 同为已还款 | 今早 09:15 / 12:00 / 14:30 均 PENDING |

BQ 还款表 `clear_date` 与上表一致。`502131` 结清后被 15 分钟窗口反复发 `repaymentEvent` 直到 23:00+（当时数仓取近 10 小时），计划只在首次取消，不影响今日不发。数仓已改近 2 小时，8/29 验 §6.3。

---

## 3. DPD / 金额对账

催收**不读** `t_loan_repayment_plan`。链路与 8/27 相同：还款表 → 数仓 → `caseEvent` → `t_ai_collection` → 发送前 PreFlight 覆盖内存 dpd/金额 → 文案。S1+ 金额用 `totalOutstanding`。罚息列 Phase 1 模板不渲染。

四案 08:00:06 发出；当天 `caseEvent` **08:00:03–05** 已进库，数字赶上了今天还款表（昨天短信还是 8/26 数）。

| 案 | 应还日 | 08:00 短信 DPD / 金额 | 12:00 Push 槽 | 还款表 leftover / max DPD | 罚息 leftover |
|---|---|---|---|---|---|
| `517047` | 07-20 | 39 / 12713.50，`S4_SMS_STANDARD` | `S4_PUSH_STANDARD` | **一致** 12713.50 / 39 | 643.50 |
| `519965` | 07-28 | 31 / 14556.63，**`S3_SMS_STANDARD`** | **`S3_PUSH_STANDARD`** | **一致** 14556.63 / 31 | 161.04 |
| `528136` | 08-18 | 10 / 11819.60，`S2_SMS_STANDARD` | `S2_PUSH_STANDARD` | **一致** 11819.60 / 10 | 171.60 |
| `529878` | 08-23 | 5 / 2009.45，**`S1_SMS_STANDARD`** | **`S1_PUSH_STANDARD`** | **一致** 2009.45 / 5 | 14.45 |

`519965` 账单总额 15056.63、已付 500，短信用 leftover，也对。

数字跟今天表；句子档两案仍旧（S3/S1 而非 S4/S2）。Email 按刷新后 **dpd** 选里程碑，`519965` 14:00 走了 `S4_EMAIL_ENTRY`。见 §6.1。

`request_id`：`517047`=`bb131ce3-…`；`519965`=`1300006095787397181`；`528136`=`394002865770988544`；`529878`=`cf429f42-…`。

运营商：`519965` 催收步骤 DELIVERED，BQ 该条短信 **failed / bori**。另三案 delivered。通道后失败，不是金额算错。

---

## 4. 催收库表与落库完整性

库：`ai_collection_db`。运行时案件源 `t_ai_collection`（`collection.case-service=ai`）。

### 4.1 触达日会写的表

| 表 | 作用 | 8/28 15:13 |
|---|---|---|
| `t_ai_collection` | 当前案件投影 | 41 行 |
| `t_ai_collection_inbox` | 入站幂等 | 当日 **59**（08:00 一批 34 条 `caseEvent`，其余多为重复 `repaymentEvent`） |
| `t_contact_plan` / `t_contact_plan_step` | 计划与步骤 | 五槽终态齐全；悬挂 EXECUTING **0** |
| `t_contact_timeline` | 触达事实 | 当日 124；与 COMPLETED 1:1，无「完成却无 timeline」 |
| `t_decision_log` | 发送时内存快照 | 当日 123 |
| `t_channel_callback_audit` | AI 回调原文 | 当日 59 |
| `t_event_outbox` | 发件箱 | **PENDING=0** |
| `t_event_dlq` | 死信 | **当日 0** |

当日执行 vs timeline：SMS 33/33、PUSH 32/32、EMAIL 1/1、AI_CALL 拨出 57/57（09:15 的 33 + 14:30 的 24）。

### 4.2 配置表

与 8/27 相同，不按槽写。话术仍以 Nacos / `channel.scripts` 为主。

---

## 5. 通知中心 BQ

`bq_notification.t_history_202608`，`app_code='mocasa'`。`create_time` 当 UTC DATETIME，对账用 `request_id` / 正文，不要用 Manila 小时去滤。

**短信**：引擎 33 条 `provider_msg_id` 能对上。抽样四案正文 DPD/金额与还款表一致。`519965` BQ `failed/bori`。

**Push**：引擎 32 `DELIVERED`、0 fallback。无 `label`/`user_id`。12:00–12:10 催收正文匹配 **32**。抽样：

| 案 | BQ 正文要点 | 与决策槽 |
|---|---|---|
| `517047` | Resolve PHP 12,713.50 now… | S4 |
| `519965` | Delay may limit… PHP 14,556.63. Tap now. | **S3**（应为 S4） |
| `528136` | See your personalized payment options for PHP 11,819.60 | S2 |
| `529878` | EDGARDO… past due. Tap to settle（S1 句无金额） | **S1**（应为 S2） |

**Email**：SendGrid 直出，14:00 窗口 `t_history` 无催收邮件行，以引擎 timeline + adapter 日志为准。

---

## 6. 问题清单

| 项 | 挡触达？ | 状态 |
|---|---|---|
| 句子档跟旧 `snapshot.stage` | 对客措辞错档 | **要改代码**，尚未动。见 §6.1 |
| `caseEvent` 08:00 才进催收库 | 日切用昨日投影；短信数字今早赶上了 | **数仓已改 03:00 进库**，8/29 验 inbox `created_at`。见 §6.2 |
| 结清后反复 `repaymentEvent` | 否 | 原近 **10 小时**扫描；**已改近 2 小时**。8/29 验同一笔不会再拖到晚上。见 §6.3 |
| 取消计划残留 PENDING | 否 | 扫描不捞终态，不是漏催 |
| 短信运营商 failed | 否 | `519965` bori；Phase 1 已知 |
| Push BQ 无 label | 否 | 对账用正文 |
| 接通对话不在催收库 | 否 | 只存 URL，浏览器无 Authorization 打不开。见 §7.2 |
| CONNECT_AND_STOP 穷尽续建 | 否 | 同计划 skip + 新计划被 Guard 拦住，当日未再打。见 §7.1 |

### 6.1 话术档应跟当天档走（确认要改代码）

产品结论：**句子跟旧快照是错的，发送时应跟今天的档走。** 数字已经刷新，档位没有。12:00 Push 已按旧档发出。

对照：

| 词 | 小白 | 专业 |
|---|---|---|
| 档 S1–S4 | 四本教材，逾期越久措辞越硬 | `Stage`：S1 DPD 1–3，S2 4–15，S3 16–30，S4 31+ |
| 计划 | 这本教材的课程表 | `t_contact_plan`；`plan.stage` 标明用哪本 |
| 快照 | 开课封面：当时哪本、几天、多少钱 | `context_snapshot`，建计划时冻结 |
| 穷尽日历 | 这本的课上完了 | 最后一步完成或 Factory 0 个未来槽 → `PLAN_EXHAUSTED` |
| 发送时刷新数字 | 黑板上的天数和金额改成今天的 | `refreshVolatileFields` 覆盖 dpd/金额 |
| 发送时不刷新档 | 还按封面那本念课文 | `deriveMsgScriptSlot` 读 `snapshot.stage` |

今早：`519965` 计划已是 S4、封面仍 S3；`529878` 计划 S2、封面仍 S1。

根因两条：① 发送故意不覆盖 stage（规格怕「同一计划串话术」；Email 已按 dpd 选里程碑，短信/Push 没有）。② 穷尽升档时旧计划先 COMPLETED，事件不带快照，新计划回读当时投影——日历穷尽时 DPD 往往还没跨档，于是 `plan.stage=S4` 而 `snapshot.stage=S3`。

建议（点头后再动代码）：

- **主修**：`refreshVolatileFields` 增加 `ctx.setStage(info.getStage())`，与 dpd/金额同一处，只改内存。SMS/Push 跟 Email、跟今天还款表档一致。
- **辅修**：`createPlanForStage` 落库前 `snapshot.stage = 该计划 stage`。
- **不建议**只读 `plan.getStage()`（caseEvent 晚于日切时仍对不上今天）；也不用投影去拆正在跑的计划。

### 6.2 caseEvent 几点进库

| 层 | 8/28 实测 | 含义 |
|---|---|---|
| 数仓 `occurredAt` | **02:00:42** | 当天快照已算完 |
| 催收 inbox | **08:00:03–05**（34 条 `caseEvent`） | Pub/Sub 进催收库 |
| 08:00 SMS | **08:00:06** | 贴着进库发出 |

契约一直是 **03:00 PHT 前发完**。今早日切 03:35–05:55 仍读昨日投影，故 `519965` `rollbackSkipped`。

**8/28 晚间数仓已把进库改到 03:00。** 8/29 验证：inbox 当日 `caseEvent` 的 `created_at` 应落在 **03:00 前**（至少早于 03:35 日切），日切能用当天 stage；08:00 短信不再贴着进库。催收侧不加等待闸门。

### 6.3 repaymentEvent 回看窗口（10h → 2h）

今早 `502131` 结清后从 13:30 一直被 15 分钟窗口重复投到 23:00+，计划只在首次取消。数仓原来说是取近 **10 小时**的还款，所以同一笔会反复发。

**已改为近两个小时。** 8/29 验证：抽一笔当日结清（或昨日结清仍在窗内的），inbox 里同一 `repayTime` 的 `repaymentEvent` 不应再铺一整天；条数应明显少于今日。15 分钟节奏还在，两小时窗口最多大约 8 次，不再是 10 小时那一长串。

---

## 7. 波次聚合与接通停呼

### 7.1 SETNX 与 CONNECT_AND_STOP

同一槽 Redis 缓冲后一次 upload。代次键 SETNX，禁止批级 cancel。

| 槽 | 批次 | 结果 |
|---|---|---|
| 09:15 | 仅 `mocasa-20260828-0915-1`，一次 `wave started` | **通过** |
| 14:30 | 仅 `mocasa-20260828-1430-1`，24 案 | **通过** |

接通案 `513749` / plan `833` / step `1492` ANSWERED（09:27:10，回调审计 id=207）：

1. 同计划 14:30 step **`1509` 已 SKIPPED**（`CONNECT_AND_STOP skip plan 833 step 1509`），无新 timeline。
2. 12:00 Push 后 plan `833` 穷尽，**同档续建** plan `884`（1 步：当天 14:30 AI step `3386`）。
3. 14:30 扫描捞到 `3386`，Guard **`CONNECT_AND_STOP` → `COMPLIANCE_BLOCKED`**，未进 Facade 批（批内 24 案不含他）。

当日第二通没有打出。同计划 skip 盖不住穷尽续建；Guard 兜住了。改话术档时不必顺手改这条，但要知道续建会再排一通同日 AI。

### 7.2 接通对话（案 `513749`）

催收库 **没有对话正文**，只有 `canonical_payload` 里的媒体 URL。浏览器直接打开会 `VALIDATION_ERROR`（缺 `Authorization`）。11:15 已用 Pilot API key 拉回 `script_url`。`conversation_history` 只有助手四句，没有借款人发言。会话 09:16:23–09:27:09 PHT：

1. Magandang araw po, si Maria po ito mula sa Mocasa. Si JOSIE BAGA-AN DEPLIYAN po ba ang kausap ko?
2. Opo, Ma'am Josie. Nais ko lang pong i-follow up ang inyong overdue balance na ₱8,725.75 sa Mocasa na dapat po ay binayaran noong August 13. Paano po natin masasaayos ang payment nito ngayong araw?
3. Ma'am Josie, nandito pa po ba kayo? Naririnig ninyo po ba ako tungkol sa inyong overdue balance?
4. Sige po, Ma'am Josie, mukhang hindi niyo po maasikaso ngayon. Magpapatuloy po kami sa pag-follow up ng inyong account. Magandang araw po.

录音同域，同样要 key。`ai_result.summary` 为空。

---

## 8. 已改 / 遗留 / 明天

### 8.1 昨晚已上 Pilot、今日已验

| 项 | 8/28 结果 |
|---|---|
| 邮件代码映射 | **通过**。step `3177` `S4_EMAIL_ENTRY`，SendGrid accepted |
| SETNX 一槽一批 | **通过**。09:15=33、14:30=24，各一批 |
| 穷尽 0 步 | 无 `REBUILD did not create successor`；`519965` 已在 S4 上跑 |
| 白名单 40 | **通过**。日切 `scanned=40` |
| CONNECT_AND_STOP | **通过**（同计划 skip + 续建被 Guard 拦） |

### 8.2 遗留（催收代码未改）

- 发送时刷新 `stage`（主修）+ 建计划写对 snapshot.stage（辅修）。规格「不覆盖 stage」那句一并改。待产品点头。8/29 若仍是 40 案旧计划，句子档还会按旧封面。

### 8.3 8/29 要验什么

**数仓（40 案与 200 案都看）：**

| 项 | 怎么算过 |
|---|---|
| `caseEvent` 03:00 进库 | 当日 inbox `dataType=caseEvent` 的 `created_at` 在 **03:00 前**，且早于 03:35 日切；日切应用当天 stage，少出现今早那种 `rollbackSkipped` |
| `repaymentEvent` 2 小时窗 | 同一结清 `repayTime` 不再从中午铺到晚上；重复条数相对 8/28 明显下降 |

**200 案分流**（主场，不是原 40 案槽位表）：见 [8/29 二百案抽样说明](../samples/MOCASA催收系统升级_Phase1_e2e200抽样说明_20260829.md)。白名单同时配 `COLLECTION_PILOT_LOAN_IDS` 与 `COLLECTION_SCAN_CASE_IDS`（200 个 `loan_id`）。与现行 40 案 **零交集**。周末并档：本轮能对上 Email 关键日的是 **D+1 / D+4 / D+75**；D0 / D+31 本周末无库存。

**原 40 案若仍留在名单里**（15:13 库内 PENDING，仅作对照，不是 8/29 主路径）：

| PHT | 渠道 | 量 | 验证重点 |
|---|---|---|---|
| 08:00 | SMS | 33 | 仅当 40 案未撤名单时 |
| 09:15 | AI | 34 | SETNX |
| 12:00 | PUSH | 34 | BQ 用正文 |
| 14:00 | EMAIL | **0** | 下一封在 9/3 |
| 14:30 | AI | 26 | 接通案不应再进批 |

`513749` 8/28 已 `PLAN_COMPLETED` 两份 S2 计划；跨日应恢复可打。
