# Phase 1 自动跑记录（2026-09-04）

> **层级**：Pilot 自然日。数仓按 Owner 分流池（`risk_intelligent_collection_blacklist_case.status=1` / DPD≤30 样本）只发 NEW；名单空，消费跟订阅。  
> **对比 9/3**：投影 200→**379**；**S0 首次整桶**；**Owner 缺席迁出**生效。  
> **环境**：容器 `StartedAt 2026-09-03T09:46:11Z`（PHT 17:46）；Hikari 25；白名单空。  
> **取数**：2026-09-04 **~15:25 PHT**（五槽全日收口；上午 § 保留，下午已补验）。  
> **关联**：[9/3](./MOCASA催收系统升级_Phase1_自动跑记录_20260903.md) · [Owner DPD30 样本](./MOCASA催收系统升级_Phase1_Owner路由DPD30样本_20260903.md) · Owner 路由计划

## 目录

- [1. 结论：分流机制是否有效](#1-结论分流机制是否有效)
- [2. 应迁出的 case 是否正常迁出](#2-应迁出的-case-是否正常迁出)
- [3. 推送的 case 是否都进入新催收流程](#3-推送的-case-是否都进入新催收流程)
- [4. 「未进投影」的 18 户是什么](#4-未进投影的-18-户是什么)
- [5. 五槽实绩](#5-五槽实绩)
- [6. 接通 summary 落库与对话分析](#6-接通-summary-落库与对话分析)
- [7. Email 正文可观测性与历史实发](#7-email-正文可观测性与历史实发)
- [8. 问题清单](#8-问题清单)

---

## 1. 结论：分流机制是否有效

**有效。** 今天同时验到三件事：只收 NEW、OUT 池停打、IN 池走完建档→触达。

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| 发布圈 ≈ 直播应发 | **261 = 279 − 18** | 18 户结清/出窗，见 §4 |
| 多发（inbox 不在样本池） | **0** | inbox ∩ 279 = 261 |
| 缺席迁出 | **51 户 `ROUTED_TO_LEGACY`** | 03:35；均为池外旧案 |
| 迁出后再触达 | **0**（全日仍 0） | 今日 DELIVERED 不含这 51 |
| 池外仍有活跃 plan | **0** | OUT279 ∧ active = 0 |
| 推送案进入流程 | **261/261 有投影**；活跃 plan 午前 **257** → 午后 **245** | 日中 `REPAID`/`NO_DUE_BALANCE` 取消 |
| 触达未出圈 | SMS **201**、Push **254**、Email **25**；迁出圈 **0** | — |

```text
数仓只发 NEW (261)
        ↓ 03:00 进 inbox / 写投影
03:35 Owner 对账
   ├─ 归属日=今日 → 建 plan（新入 182）或保留（续档 79）
   └─ 归属日≠今日 → ROUTED_TO_LEGACY（51）
        ↓
08:00 / 09:15 只打仍有活跃 plan 的 IN 池案
```

| 口径 | 全日（~15:25） |
| --- | --- |
| inbox `caseEvent` | **261**（03:00:03–24） |
| 投影 | **379**（在催 **338** / 结清 **36** / 停催 5） |
| 迁出 | **51**（全日再触达 **0**） |
| 新建 plan | 今日创建 **204**（午前新建口径曾记 182） |
| 活跃 plan | **245**（`PENDING/EXECUTING=0`） |
| 08:00 SMS / Push | **201** / **58** DELIVERED |
| 09:15 AI | wave **199**；接通 **4** |
| 12:00 Push | **196** DELIVERED |
| 14:00 Email | **25** DELIVERED + **6** SKIPPED |
| 14:30 AI | wave **178**；接通 **2**；`EXECUTING=0` |

---

## 2. 应迁出的 case 是否正常迁出

**是。**

对象：昨天还在新系统打、今天不在 NEW 发布里的案（主要是原 e2e200 里 DPD>30 的 S4 等）。

| 断言 | 结果 |
| --- | --- |
| 03:35 取消计划 | **51** 份，`cancel_reason=ROUTED_TO_LEGACY` |
| 是否误伤样本池 | **0**（51 全部 ∉ 279 CSV） |
| 取消后是否还有活跃 plan | **0** |
| 08:00 是否仍 DELIVERED | **0** |
| 打开步骤 | 日志 `[cancel] … skipped … after ROUTED_TO_LEGACY` |

抽查迁出案：`487480` S4 dpd=80、`520087` S4、`493332` 等——均为池外高 DPD 旧圈。

**结论**：缺席对账按设计停掉了不应再由新系统催的案；没有「迁出了还在打」。

---

## 3. 推送的 case 是否都进入新催收流程

**是（261 条发布全部进投影；该建档的都建了；该触达的上午已触达）。**

以当日 inbox `CASE_INGESTED` = 261 为「推送集合」：

| 步骤 | n | 说明 |
| --- | --- | --- |
| 有投影 | **261 / 261** | 无毒丸丢案 |
| 当日新建 plan | **182** | 首次进 NEW |
| 沿用旧 plan | **~79** | 与样本「e2e200 保留 79」一致 |
| 现有活跃 plan | **257** | — |
| 进了 inbox 但无活跃 plan | **4** | 见下表（正常停催，非漏建） |
| 08:00 SMS | **201** | S1+；均 ∈ 池 |
| 08:00 Push | **58** | 全 S0；**58/58 有 plan 且 DELIVERED** |
| 09:15 AI | **199** | 一批收口 |

无活跃 plan 的 4 户（均已进投影，属流程内取消）：

| 原因 | n | 含义 |
| --- | --- | --- |
| `REPAID` | 3 | 当日结清，计划取消 |
| `NO_DUE_BALANCE` | 1 | 无应还余额取消 |

S0 链路首次打通：在催 S0 **58** → Push **58** → 无「S0 无 plan」。

下午待验：14:00 Email PENDING **30**（S0 D0 + S1 D+1），属正常里程碑库存。

---

## 4. 「未进投影」的 18 户是什么

**不是催收漏消费，也不是「status=1 发了但没进库」。**  
是 **9/3 写入 blacklist 的 279 里，有 18 户到 9/4 发布时已不该再发 `caseEvent`**，Publisher 没发，催收库从无记录。

### 4.1 催收侧证据

| 检查 | 结果 |
| --- | --- |
| 今日 / 历史 inbox | **0** |
| `t_ai_collection` | **0** |
| `t_contact_plan` | **0** |

→ 消息从未到达接入层（或从未针对这些 caseId 发布过完整 caseEvent）。

### 4.2 BQ 结清 / 入催窗（2026-09-04 查）

| 类型 | n | case_id | 是否应发 caseEvent |
| --- | --- | --- | --- |
| **已结清**（各期 `clear_time` 齐 / 无欠款） | **14** | `509721` `509731` `509809` `510292` `533691` `534016` `534212` `534234` `534291` `534332` `534361` `534409` `534856` `535449` | **否** |
| **出窗**（仍有未结清期，但 `max_open_dpd` ≈ **−28～−30**，远早于入催窗 `dpd≥−3`） | **4** | `521914` `521973` `522511` `534507` | **否** |

9/3 抽样时这 18 户多在 S0/S1、尚有 overdue；隔夜结清或提到下期后，直播不再落入 Publisher 的在催窗。  
blacklist 表仍显示 `status=1, cal_dt=2026-09-03`（**表未按日刷新**），但 **实际发布 = 261 = 279 − 18**，与结清/出窗一致。

部分 caseId 出现在「repayment 缺基线」毒丸日志：有还款增量、无 caseEvent——符合「已结清/出窗不再发快照、还款若未过滤仍会撞毒丸」。

### 4.3 一句话

| 问 | 答 |
| --- | --- |
| 是因为已经结清了吗？ | **大部分是（14/18）**；另 **4/18 是提前还清当期、下一期很远（出窗）** |
| 算分流失败吗？ | **不算**；不应催的案本来就不该进新系统 |
| 要改什么？ | blacklist `status=1` 应按日重算/剔除结清与出窗，避免「表 279、实发 261」对照时误报漏发 |

---

## 5. 五槽实绩

| PHT | 渠道 | 今日 | 说明 |
| --- | --- | --- | --- |
| 03:35 | Owner 对账 | 迁出 51 + 建档 | `ROUTED_TO_LEGACY` |
| 03:40 | 日切 | scanned=374 | `stageChanged=0` |
| 08:00 | SMS | **201** DELIVERED | S1×36 + S2×74 + S3×69 + S4×22；SKIPPED 86 |
| 08:00 | PUSH | **58** DELIVERED | `S0_REMINDER` 31 + `URGENT` 11 + `DUE_TODAY` 16 |
| 09:15 | AI | wave **199** | BUSY 97 / FAILED 65 / NO_ANSWER 31 / SNR 2 / **ANSWERED 4**；SKIPPED 88 |
| 12:00 | PUSH | **196** DELIVERED | S1×32 + S2×74 + S3×68 + S4×22；SKIPPED 91；`12:00:05–15` |
| 14:00 | EMAIL | **25** DELIVERED | S0×13 + S1×12；SKIPPED **6**；`14:00:04–06` |
| 14:30 | AI | wave **178** | BUSY 76 / FAILED 68 / NO_ANSWER 30 / SNR 2 / **ANSWERED 2**；SKIPPED 56；`EXECUTING=0` |

全日 timeline DELIVERED：**SMS 201 / PUSH 254 / EMAIL 25**。FIRM：**0**（仍全 STANDARD）。

### 5.1 09:15 接通（引擎 `ANSWERED`=4）

详见 §6.1。上午 FAILED 65 ≈ 全部 `MEDIA_NEGOTIATION_FAILED`。

### 5.2 12:00 Push

| script_slot | n |
| --- | --- |
| `S2_PUSH_STANDARD` | 74 |
| `S3_PUSH_STANDARD` | 68 |
| `S1_PUSH_STANDARD` | 32 |
| `S4_PUSH_STANDARD` | 22 |
| **合计** | **196** |

准时收口；无 PENDING。

### 5.3 14:00 Email

| 结果 | n | 说明 |
| --- | --- | --- |
| DELIVERED | **25** | `S0_DUE_TODAY_EMAIL` **13** + `S1_EMAIL_OVERDUE_NOTICE` **12** |
| SKIPPED | **6** | `510158` `534440` `534668` 已 **SETTLED**；`522810` `534071` `534174` 投影 dpd≈**−29/−30**（出窗） |

午前库存约 PENDING 30 → 下午实发步 **31**（25+6）。SendGrid 均 `accepted`（msgId 齐）。用户已于午前把 S0/S1 模板改回 `{{payment_link}}`，**14:00 这批应已走正确变量**（15:25 复核控制台：S0/S1 `href={{payment_link}}`，无坏字面量）。

### 5.4 14:30 AI

| 项 | 值 |
| --- | --- |
| wave | `mocasa-20260904-1430-1`，**178** 案，`14:30:33` 起批 |
| ANSWERED | **2**：`529588`、`534282` |
| CONNECT_AND_STOP | 上午接通 4 户（`513849` `519977` `533850` `533831`）下午 **全部 SKIPPED** |
| FAILED | 68（session：`MEDIA_NEGOTIATION_FAILED` **64** + TEMP_UNAVAILABLE 2 + FAILED/FORBIDDEN 各 1） |
| SNR | 2：`507262` / `528596`（VOICEMAIL） |

### 5.5 口径备注（非分流失败）

样本设计 DPD≤30，但有 **22** 户 SMS/Push 槽为 `S4_*`（投影高 dpd）。属 **stage/dpd 快照与抽样 live 口径不一致**；触达仍在样本 loan_id 集合内，未打到迁出圈。

---

## 6. 接通 summary 落库与对话分析

**结论：有落库，但是「有条件落库」——Facade 回了 `ai_result.summary` / `result_label` 才写入 `t_ai_call_session`；全文对话不落催收库，只存 `script_url`，需用 Facade key 拉。**

| 层 | 存什么 | 今日情况 |
| --- | --- | --- |
| `t_ai_call_session.summary` / `result_label` | 回调 `ai_result.*` | 有对话的接通多数有摘要；仅核名常空 |
| `t_channel_callback_audit.canonical_payload` | 完整回调 + `media.script_url` | 有 |
| 转写正文 | **不落库**；代理设计见管理后台 v1.3 | 上午 4 通已从 `script_url` 拉过 |

近 7 日 `was_answered=1` 填充分：有实质标签的接通从 9/1 起稳定回传；仅核名/短通仍常 `summary` 空（与 8/28–8/31 同类）。

### 6.1 09:15 引擎 ANSWERED（4）

| case_id | 投影 | label | summary | 转写要点 |
| --- | --- | --- | --- | --- |
| `513849` | S3 dpd=22 ₱11,137 | 空 | **空** | **1 轮**：助手 Tagalog 核名「Patrick…」后结束；无借款人发言 |
| `519977` | S2 dpd=7 ₱2,638 | 空 | **空** | **1 轮**：助手核名「Alyssa…」后结束；无借款人发言 |
| `533850` | S1 dpd=2 ₱1,921 | `incomplete` | 有（Tagalog）：告知逾期 ₱1,921.14，未拿到明确回应/承诺 | **8 轮**：对方像筛选/代接；助手对 Donna 留言 |
| `533831` | S1 dpd=2 ₱8,534 | `refused_to_pay` | 有（Tagalog）：不同意付逾期、无约定日 | **21 轮**：ASR 嘈杂；用户有一句清晰 **「Eh, no」** |

### 6.2 14:30 引擎 ANSWERED（2）

| case_id | 投影 | label | summary |
| --- | --- | --- | --- |
| `529588` | S2 dpd=13 ₱4,080.35 | `incomplete` | 尝试催收逾期，用户回应不清、未 settle（跨日再通；今日上午未接通） |
| `534282` | S1 dpd=1 ₱1,926.19 | `incomplete` | 仅核名阶段用户回「Hello?」，未进入催收话题 |

### 6.3 近期有摘要接通（对照）

| 日 | case | label | 摘要要点 |
| --- | --- | --- | --- |
| 9/1 | `513849` | `dispute` | 争议余额、无承诺（同案今日仅核名） |
| 9/1 | `529588` | `vague_commitment` | 含糊同意、无明确承诺 |
| 9/2 | `529588` | `refused_to_discuss` | 无具体还款计划 |
| 9/2 | `531516` | `incomplete` | 音频问题中断 |
| 9/3 | `529588` | `follow_up_required` | 确认本人、告知逾期、无付款约定（见 9/3 日记录） |

---

## 7. Email 正文可观测性与历史实发

**催收库拿不到渲染后的邮件全文。** Phase 1 设计如此：`t_contact_timeline.content_summary` 只记元数据（`channel=…;slot=…;fields=[dynamicTemplateData]`），**不含** subject/HTML/变量值；`content_hmac` 今日历史实发多为空（未配 HMAC key）。正文托管在 SendGrid Dynamic Template。

| 能拿到什么 | 怎么拿 |
| --- | --- |
| 发了哪个槽 / 哪个 `d-xxx` | timeline `script_slot` + `template_version` |
| 模板 HTML/subject（未渲染） | SendGrid API `GET /v3/templates/{d-xxx}`（本次已拉） |
| 仓内草稿对照 | `collection-admin/.../catalog/email-templates/*.html` |
| 某封「借名人+金额」渲染结果 | **库中无**；需 SendGrid Activity / 收件箱 |

### 7.1 今日 + 历史 DELIVERED（PHT）

| 日 | slot | n |
| --- | --- | --- |
| **9/4** | `S0_DUE_TODAY_EMAIL` | **13** |
| **9/4** | `S1_EMAIL_OVERDUE_NOTICE` | **12** |
| 9/2 | `S1_EMAIL_OVERDUE_NOTICE` | 6 |
| 9/1 | `S0_DUE_TODAY_EMAIL` | 11 |
| 9/1 | `S2_EMAIL_ENTRY` | 9 |
| 8/31 | `S2_EMAIL_ENTRY` | 12 |
| 8/28 | `S4_EMAIL_ENTRY` | 1 |
| 9/3 | — | **0**（无里程碑日） |

### 7.2 SendGrid `payment_link` 质检

| 时点 | S0/S1 | 说明 |
| --- | --- | --- |
| 午前抽检 | **坏** | href 曾写成 `{{https://mocasa.com/s/4cTu}}` |
| 用户修复后（~10:21 起） | **好** | 改为 `{{payment_link}}` |
| 15:25 复核 + 14:00 实发 | **好** | 控制台 S0/S1 均为 `{{payment_link}}`；引擎注入 `COLLECTION_REPAYMENT_URL_TEMPLATE=https://mocasa.com/s/4cTu` |

S1 subject 仍缺文档前缀 `Overdue:`（文案小差，非阻断）。9/2 那 6 封仍属坏链历史窗口。

---

## 8. 问题清单

| 项 | 严重度 | 状态 |
| --- | --- | --- |
| 分流：只收 NEW / 迁出停打 / 入催建档 | — | **全日通过**（迁出 51 再触达仍 0） |
| 五槽准时收口 | — | **通过**（12:00 / 14:00 / 14:30 齐；`EXECUTING=0`） |
| CONNECT_AND_STOP | — | **通过**（上午 4 接通 → 下午全 SKIPPED） |
| blacklist 未按日刷新 | 对照噪音 | 表仍 279×status=1@9/3 |
| 池内 S4 高 dpd | 口径 | 对数仓 caseEvent 快照 |
| AI FAILED（09:15×65 + 14:30×68） | 渠道 | 主因 Facade `MEDIA_NEGOTIATION_FAILED` |
| 接通仅核名 → summary 空 / `incomplete` | 质量 | Facade/短通；库路径正常 |
| S0/S1 Email Pay Now | 曾高 | **已修**；今日 14:00 批次走正确变量 |
| Email SKIPPED 6 | 预期 | 结清 3 + 出窗 3 |
| 圈外 repayment 毒丸 | 噪音 | 建议还款与 NEW 同集合 |
| FIRM | 未测 | 仍 STANDARD |

**全日一句话**：Owner 分流全日成立；下午三槽准时；Email 25 封已发且 `payment_link` 已修；AI 再接通 2，上午接通未复打。
