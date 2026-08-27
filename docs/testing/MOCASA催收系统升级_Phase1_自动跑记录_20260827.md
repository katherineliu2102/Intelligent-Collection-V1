# Phase 1 自动跑记录（2026-08-27）

> **层级**：50 案 Pilot 自然日自动跑（调度 `planStepDue` → 四渠道 dispatch → 步骤终态）。  
> **对比 8/26**：昨晚是挤压冒烟 + 三案加测；今天**不手调时间**，看预写槽是否按钟点发出。  
> **环境**：Pilot `bdp01`，容器 `collection-admin`，镜像 `intelligent-collection-admin:pilot`。上午跑的是 **`a24356c`**（回调按步骤收口 + AI 超时 30 分钟）；**11:22 PHT 换成带波次聚合的构建**（§8），14:30 那波是聚合首跑。  
> **时区**：业务时间为 **PHT（UTC+8）**。  
> **取数截止**：2026-08-27 **16:20 PHT**（全日五槽已结束；傍晚发版见 §11）。GitHub `test_branch` 上午产品代码 `6f0ff38`；傍晚产品代码 **`4ca65f5`**（邮件映射进代码、SETNX 代次、穷尽 0 步升档），**16:32 PHT** 已与白名单 40 案一并发 Pilot。  
> **关联**：[主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) · [Facade 接入说明 §1.1](../channel/MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md) · [发版手册](../channel/MOCASA催收系统升级_Phase1_发版手册.md)

---

## 1. 全日结论

**五个预写槽都按时触发，引擎主路径通过，无悬挂 `EXECUTING`。** 通道失败（AI 未接通、1 封 Email 因缺模板没发出、供应商侧 1 条短信后失败）有出现，步骤都收到终态，不算引擎停摆。

今天 **没有任何真人接通**，CONNECT_AND_STOP 全日无样本。14:30 聚合首跑 **起批成功、回调收口成功**，但并发入批把一代波次拆成了 4 个 Facade 批次（23+1+1+1），还没做到「一槽一批」。

| 口径 | 结果 |
|---|---|
| 应用健康 | 200 |
| 日切 03:35–05:55 | 跑完；`stageChanged=0` `ceased=0` `rollbackSkipped=1` |
| 08:00 SMS | tick `scanned=27`；引擎 27 DELIVERED。BQ `bq_notification.t_history_202608` 用 `request_id` 对上 **27** 条 `label=collection`：delivered 25 / sent 1 / failed 1 |
| 09:15 AI 第 1 通 | 27 全部终态（BUSY 13 / FAILED 10 / NO_ANSWER 4）；一案一批、27 个独立 batch |
| 12:00 PUSH | 当日执行 **33 DELIVERED**；1 条 PENDING 是已还款计划。BQ 12:00 分钟内能看到催收正文，但 **label/user_id 为空** |
| 14:00 EMAIL | **1/1 FAILED**：案 `529878` DPD=4 / S2，`S2_EMAIL_ENTRY` 在 Pilot 没有 SendGrid 模板映射，未调用 SendGrid |
| 14:30 AI 第 2 通 | 26 通终态（BUSY 12 / FAILED 11 / NO_ANSWER 3）；2 条 PENDING 是已取消计划。合成 **4 个批次**，主批 23 案 |
| 悬挂 EXECUTING | **无** |

---

## 2. 与昨晚预估的对照

写冒烟清单时，库内 8/27 仍 PENDING 的量 vs 今早实绩：

| 时间（PHT） | 渠道 | 昨晚预估仍 PENDING | 今早按时发出 | 说明 |
|---|---|---|---|---|
| 08:00 | SMS | ~28 | **27** | 与 tick `scanned=27` 一致。槽身份 DELIVERED=33，多出来的是昨晚窗口 C + 17:18 提前打掉的行 |
| 09:15 | AI 第 1 通 | ~28 | **27** | 同上；另有 1 条槽身份 `ANSWERED` 实为 8/26 12:02 已打 |
| 12:00 | PUSH | ~34 | **33** | 另 1 条槽身份 8/26 已打；1 PENDING = 已还款 plan `855` |
| 14:00 | EMAIL | ~1 | **1 尝试、FAILED** | 缺 `S2_EMAIL_ENTRY` 模板映射，见 §6.5 |
| 14:30 | AI 第 2 通 | ~28 | **26** | 2 PENDING = 已取消计划 `832`/`855`；另 2 SKIPPED 是旧计划 827/828 |

白名单 50；活跃计划约 35（`STEP_SCHEDULED`）+ 2 已取消 + 2 已完成。其余白名单案没有活跃计划，日切也没给它们新建（见 §6.2）。

---

## 3. 08:00 短信

- 08:00:04 tick：`job=planStepDue scanned=27`。
- 当日 `executed_at`：27 条 `COMPLETED / DELIVERED`；timeline OUT 同步 27。
- 槽身份 `original_trigger=8/27 08:00` 不全是今早发出的：

| 槽身份状态 | 条数 | 性质 |
|---|---|---|
| COMPLETED / DELIVERED | 33 | 含昨晚已打 |
| FAILED | 1 | step `2168` / plan `849`，8/26 窗口 C CreativeBlue 拒信 |
| SKIPPED / COMPLIANCE_BLOCKED | 1 | 8/26 已跳过 |
| SKIPPED（空 result） | 2 | 旧计划 827 / 828 |
| PENDING | 1 | plan `855` / 案 `527448`，计划已 `PLAN_CANCELLED`（REPAID），扫描故意不捞 |

今早该发的 27 条都发了。PENDING 那 1 条不是漏催，见 §6.1。

BQ `content` 抽样（`label=collection`，08:00:07/08；DPD/金额是计划快照，比当天投影小 1 天 / 略低）：

| 案 | 槽 | 通道 | 正文 |
|---|---|---|---|
| `529878` | S1_SMS_STANDARD | CreativeBlue | MOCASA Collections: EDGARDO III FERNANDEZ CUETO, your account is 3 day(s) overdue. Please settle PHP 1,989.67 promptly. Pay: https://mocasa.com/s/4cTu |
| `528136` | S2_SMS_STANDARD | QHSms | MOCASA Collections: CYRA ARGUS BENUELO, 8 days overdue. See your personalized payment options for PHP 11,722.88. Pay: https://mocasa.com/s/4cTu |
| `519965` | S3_SMS_STANDARD | QHSms | MOCASA Collections: JOSHUA DE GUZMAN SARION, 29 days overdue. Delay may limit your account and future loan eligibility. Please settle PHP 14,443.17 or view your payment options. Pay: https://mocasa.com/s/4cTu |
| `517047` | S4_SMS_STANDARD | CreativeBlue | MOCASA Collections: GLENN CARL VILLANOS ANDALAHAO, final notice. 37 days overdue and at risk of a delinquency record. Please resolve PHP 12,620.50 using your payment options. Pay: https://mocasa.com/s/4cTu |

---

## 4. 09:15 AI 第 1 通

### 4.1 执行

- 09:15:04 tick：`scanned=27`。
- `executed_at` 分布：`09:15:04`–`09:15:26`（约 22 秒内起呼）。
- 27 条全部终态，**无 EXECUTING**。回调收口补丁在批量真拨上站住了。

| 当日执行 result | 条数 |
|---|---|
| BUSY | 13 |
| FAILED | 10 |
| NO_ANSWER | 4 |
| ANSWERED | **0**（今早真拨） |
| 合计 | 27 |

槽身份上的 1 条 `ANSWERED`（step `1684` / plan `843`）`executed_at=2026-08-26 12:02:04`，是联调提前打掉的 8/27 09:15 行。该计划的 8/27 14:30 仍是 PENDING（CONNECT_AND_STOP 当时还没上，不会事后补 skip）。

窗口 C 过夜步骤已关：`2177 BUSY`、`2181 FAILED`、`2461 BUSY`。

### 4.2 是不是「一个批次里同时打很多电话」？

**09:15 那波不是。** 当时是 **一案一批**：每个到期步骤自己 `create batch → upload 1 case → start`。（波次聚合已于当天 11:22 上线，14:30 起改为一批多案，见 §8。）

今早证据：

| 证据 | 值 |
|---|---|
| timeline OUT | 27 条 |
| 互不相同的 `provider_msg_id`（Facade `batchId`） | **27** |
| 日志 `batch started` | 27 行，每行一个 `caseId` + 一个 `batchId` |
| 并发方式 | `engine-consumer-1`…`8` 同时 `start` 多个独立 batch（约 3～4 通/线程） |

所以：

- **产品语义**：不是「一个 Facade 批次里塞进多通电话」。
- **实际观感**：09:15 到期后，引擎线程池 **8**（`EngineProperties.Consumer.threadPoolSize=8`）会在十几秒内并行拉起最多 8 个独立批次，看起来像同时在打。
- Pilot 调度 `max-concurrency: 1` 只限制 **tick 拉取**（每分钟一条调度消息），不限制引擎消费 Redis 事件的并行度。

---

## 5. 12:00 Push

- 12:00:04 前后执行；timeline OUT **33 DELIVERED**（`12:00:06`–`12:00:08`）。
- `provider_msg_id` 全是 NULL：通知中心异步 Push 接口不回传 requestId，Phase 1 已知。
- 1 条槽身份 PENDING：plan `855` / 案 `527448`，`PLAN_CANCELLED / REPAID`，扫描不捞。
- 悬挂 EXECUTING：**0**。

BQ 对账见 §9.2：不靠 `label` 也能对上。异步写入大约在 `12:00:12`–`12:00:54`（入队后数秒到几十秒），`user_id`/`label` 全空。12:00 这一分钟里 16 条正文都是催收话术；12:00–12:10 用文案匹配到 21 条（S1 的 “past due / Tap to settle” 不在匹配串里，33 条里剩下的多半是这类）。

---

## 5b. 14:00 Email（失败）

唯一一封：案 `529878` / plan `882` / step `3128`，投影 `dpd=4` `stage=S2`，邮箱 `gadoiiicueto@gmail.com`。

```
[SendGridEmailAdapter] no template mapping for scriptSlot=S2_EMAIL_ENTRY tid=202
```

步骤 `FAILED`（14:00:05），**没有 HTTP 打到 SendGrid**。原因和改不改代码见 §6.5。

---

## 5c. 14:30 AI 第 2 通（聚合首跑）

- 14:30:06–07：26 条 `enrolled into wave`。
- 14:30:27–32：4 次 `wave started`（本应 1 次），见 §6.6。
- 回调 14:31–14:37 全部收口，早于 15:00 超时哨兵。无悬挂。

| 当日执行 result | 条数 |
|---|---|
| BUSY | 12 |
| FAILED | 11 |
| NO_ANSWER | 3 |
| ANSWERED | **0** |
| 合计 | 26 |

| timeline `provider_msg_id` | 案数 |
|---|---|
| `mocasa-20260827-1430-4` | 23 |
| `mocasa-20260827-1430-3` | 1 |
| `mocasa-20260827-1430-2` | 1 |
| `mocasa-20260827-1430-1` | 1 |

对照 09:15：27 个互不相同的 Facade `batchId`。下午主批已经把 23 案放进同一个 Facade 批次；另外 3 案因入批竞态各自成批。

2 条仍 PENDING：plan `832` 案 `502131`、plan `855` 案 `527448`，都是 `PLAN_CANCELLED / REPAID`。plan `843` step `1703`（昨天被提前接通的那案）今天 14:30 **有打出**，CONNECT_AND_STOP 不回溯 skip，与补丁语义一致。

---

## 6. 发现的问题（不改代码，先讨论）

主路径按时，下面三项是卫生 / 可观测性 / 产品语义，**都不构成今早漏催**。

### 6.1 取消计划后剩余步骤仍是 PENDING

**现象**：案 `527448` / plan `855` 已 `PLAN_CANCELLED`（`REPAID`），仍留 22 条 PENDING（含今天 08:00 短信 step `2330`、09:15 AI step `2337`，以及后续 Push / 14:30）。扫描按计划终态排除，所以没发出。另有更老的取消计划 `60` / `62` 各 2 条 PENDING。

**原因**：`onRepaymentReceived` / `onCaseCeased` / 升档取消只改计划状态，不把未终态步骤标 SKIPPED。

**推荐方案（讨论后再改）**：

1. **推荐**：取消计划时，把该计划下所有非终态步骤标 `SKIPPED`（result 可用 `SKIPPED` 或单独 `PLAN_CANCELLED`）。看板不再出现「已还款还挂着待发」。改动集中在 `PlanLifecycleManager` 取消路径；扫描逻辑可不动。
2. 备选：只改查询/看板，步骤表继续挂 PENDING。对账和人工巡检容易误判漏催。
3. 不建议：靠扫描侧再加一层过滤当「修复」——扫描已经过滤了，问题在脏数据。

### 6.2 日切：白名单 50，投影只命中 40

**现象**：每轮 `dailyRoll` `scanned=50`，约 10 个 ID 打 WARN `t_ai_collection 无 case_id=…`（样例：`468703`、`474696`、`504174`、`529225`、`529877`、`504887`、`530187`、`528834`、`528813`、`489984`）。03:35–05:55 合计约 **290** 条同类 WARN（同一 10 个 ID × 约 29 个 tick）。收口行：`stageChanged=0 ceased=0 rollbackSkipped=1`。

`rollbackSkipped=1` 是案 `529878`：投影 stage S1 低于计划 S2，单调前进跳过回退，**设计如此**。

**原因（待确认哪一种）**：日切把 `collection.ingestion.loan-id-whitelist` 里的值当 `case_id` 去查 `t_ai_collection`。这 10 个 ID（`468703`、`474696`、`504174`、`529225`、`529877`、`504887`、`530187`、`528834`、`528813`、`489984`）**既不是投影 `case_id`，也不是 `user_id`**——可能从未进件，或白名单其实是 loanId。已有活跃计划的案仍按预写槽在催；这 10 个今早也没有 due 步骤。

**处理（8/27 16:32 PHT 发版）**：确认这 10 个在 `COLLECTION_PILOT_LOAN_IDS` / `COLLECTION_SCAN_CASE_IDS` 两份固定 50 案名单里，**不是当天 Pub/Sub 新进件**。已从 Pilot 两份名单拿掉（50→40），日切应变 `scanned=40`。未改「缺投影每天刷 WARN」的代码。

### 6.3 CONNECT_AND_STOP 今天没有样本

今早 27 通无一 `ANSWERED`。plan `843` 的接通发生在补丁上线前（8/26 12:02），14:30 不会被回溯 skip。

**全日仍无样本**（09:15 与 14:30 合计 53 通，ANSWERED=0）。**不改代码。** 等出现真人接通再对当日第二通是否 `SKIPPED`。

### 6.4 通道失败（记一笔，不改引擎）

AI 全日：BUSY/FAILED/NO_ANSWER 合计 53/53（09:15 的 27 + 14:30 的 26）。短信：引擎 27 全部 DELIVERED；BQ 后核对 1 条 bori `failed`、1 条 QHSms 仍 `sent`（接口成功 ≠ 运营商送达，Phase 1 已知）。步骤能终态即引擎过。

### 6.5 邮件失败：缺模板映射（映射应进代码）

**现象**：唯一里程碑邮件 `S2_EMAIL_ENTRY` 被 Adapter 拒绝，error 日志 `tid=202`（步骤上的数字模板号，不是 SendGrid `d-xxx`）。

**原因**：`SendGridEmailAdapter` 当时只认 Nacos/`application-local.yml` 的 `channel.sendgrid.templates.{scriptSlot}`。5 个 `d-xxx` 写在本地 yml，**没有写进 Pilot**。缺映射 fail-close，这封信没出网。

**已改**：映射改到代码 `EmailMilestoneScriptSlots.PHASE1_SENDGRID_TEMPLATE_IDS`，换模板发版。发版后 Pilot 不再依赖 Nacos 模板表。

### 6.6 入批竞态：一槽拆成 4 个 Facade 批次（要改代码）

**现象**：26 案本应进 `20260827-1430#1`。8 个引擎线程同时 `enroll` 时，波次代次 Redis 键还不存在，大家都去 `INCR`，生成 `#1` `#2` `#3` `#4`。主批 23 案，另 3 案各成一批。起批、回调、动态超时都工作。

**推荐**：代次键用 `SETNX` 初始化成 1，禁止并发 `INCR` 抢代次。

**已改**：`FacadeBatchCoordinator.currentGeneration` 在键不存在时 `SETNX 1`。满员才 `INCR` 开下一代。

### 6.7 计划穷尽后 S3 重建失败（要改代码，不挡今天触达）

案 `519965` / plan `840` 在 14:30 回调后走穷尽。S3 模板 **不是空的**（DB `PH1_S3_STANDARD` 有 D+16…D+30 每天 4 槽）。该案当天 `dpd=30`，14:30 已是 S3 最后一天最后一槽，`futureSlots` 按设计不回补 → Factory 0 步 → 抛 `REBUILD did not create successor plan: 840`。

PEL 重试会反复进内存 `rebuildCounter`。计满 `max-rebuild-count=2` 后变成 ESCALATE，**14:37:58 建出 S4 plan `883`（212 步，从 8/28 08:00 起到 10/26）**。投影仍是 S3 / DPD=30。日历碰巧从明天 D+31 起，结果能用，但是事故。

**已改**：Factory 0 步不再抛异常进 PEL；有下一档则 ESCALATE，否则 COMPLETE。

---

## 7. AI 批次模型（答疑）

| 问题 | 答案 |
|---|---|
| 一个 Facade batch 里会不会有多通电话？ | **09:15 那波不会。** 当时 `FacadeAiCallAdapter`：create → upload **1** case → start。 |
| 09:15 会不会很多电话几乎一起响？ | **会。** 同一 tick 扫到 27 步，引擎 8 线程并行 start 27 个独立 batch，今早 22 秒内起完。 |
| 调度 `max-concurrency: 1` 会不会变成串行一通一通？ | **不会限制拨打并行。** 它只限制调度订阅拉取。 |

上午的结论是「本期不建议做」。**上午答疑之后决定改**：Facade 的并发额度按批次分配，一案一批等于把 27 通电话摊成 27 套并发，资源不可控——这正是要一批多案的理由。改动见 §8。

---

## 8. 波次聚合上线（11:22 PHT）

上午答疑后决定改成一批多案，同一天内完成开发并部署到 Pilot，**14:30 那波即为首跑**。

### 8.1 做法

案件先缓冲在我方 Redis，起批那一刻才一次性 upload。这样起批前的取消（还款）只是从缓冲里删一条，不需要 Facade 提供 case 级撤单接口——这是选这个顺序而不是「先建批、逐案 upload」的原因。

| 组件 | 职责 |
|---|---|
| `FacadeBatchCoordinator` | 按 `original_trigger_time` 归槽的 Redis 波次缓冲；起批前复检还款并剔除；起批后回写各步骤 `timeout_time` |
| `FacadeBatchFlusher` | 应用内 `@Scheduled` 每 5s 轮询，Redis 锁保证多实例单飞 |
| `FacadeBatchClient` | 从 Adapter 拆出的 create / upload / start，两条路径共用同一套请求体与错误码 |

引擎与表结构均未改动：`t_contact_timeline` 的唯一键是 `attempt_key` 而非 `provider_msg_id`，多个步骤共享同一批次号不冲突；回调身份反查走 `client_metadata.plan_id/step_id`，每案独立。

起批失败或案件被剔除时，不在渠道层改步骤状态，而是把 `timeout_time` 置为当前时刻，交给既有 `callbackTimeout` 哨兵按标准路径收口并推进计划。

### 8.2 关键决策：回调超时改为动态

一批多案后队尾要排队等并发，**固定 30 分钟窗会把还没拨出去的电话误判成 FAILED**。超时改为按 `⌈案数 ÷ 并发⌉ × 单通时长 + 缓冲` 估算，下界 30 分钟（与一案一批时一致），上界 120 分钟且不越过当日拨打窗。Facade 未给出每批并发的确切值，先按并发 5 / 单通 90 秒保守估计；27 案算出来约 24 分钟，仍取下界 30 分钟。

### 8.3 已验证 / 未验证

| 项 | 状态 |
|---|---|
| 单测（聚合、起批前剔除、起批失败补偿、开关关闭回到一案一批） | 15 条通过 |
| 渠道 + 引擎全量单测 | BUILD SUCCESS，无回归 |
| Pilot 部署 | 11:22 起容器，health 200，无 ERROR |
| 开关绑定 + flusher 存活 | Redis 探针：投放空波次后 5 秒内被摘除 |
| **真实一批多案** | **部分通过**：26 案入缓冲并起批，回调收口；拆成 4 个 Facade 批次（§6.6），主批 23 案 |

回滚：`pilot.env` 里 `CHANNEL_FACADE_BATCH_AGGREGATION_ENABLED=false` + 重启，即回到一案一批。

---

## 9. 通知中心对账（BQ `bq_notification.t_history_202608`）

口径：`app_code='mocasa'` 且 `label='collection'`。表里的 `create_time` 是 MySQL DATETIME 被 Datastream 当成 UTC，所以 `Asia/Manila` 转换会 +8 小时；对账用 `request_id` / `user_id`，不要用「PHT 小时」直接滤。

### 9.1 短信：对得上

引擎 08:00 的 27 条 `provider_msg_id` 全部能在 BQ 找到，时间戳均为 `2026-08-27 08:00:07/08`（即业务 08:00 PHT）。

| BQ status | 通道 | 条数 |
|---|---|---|
| delivered | HiWaySms | 9 |
| delivered | QHSms | 9 |
| delivered | CreativeBlue | 5 |
| delivered | bori | 2 |
| sent | QHSms | 1 |
| failed | bori | 1 |
| 合计 | | **27** |

引擎侧 27 条都是 DELIVERED（通知中心同步接口 `requestSuccess=true`）。BQ 里 1 条事后 `failed`、1 条停在 `sent`，符合对接说明：接口成功不等于运营商送达，Phase 1 不拿 `t_history` 回写步骤。

全库当天 `label=collection` 的短信还有几百条（约 20 点档 471 条），是生产催收其它流量，不是这 50 案 Pilot。

### 9.2 Push：有 12:00 记录，但没有 label / user_id

你的判断对：没有 label 不该推出「12 点没写库」。引擎 12:00:06–07 **33 条 `enqueued`，0 条 fallback**。BQ **去掉 label、也不按 user_id**：

| 窗口 | 结果 |
|---|---|
| 12:00:00–12:00:20 | 1 条 `app_notification`（12:00:12，S3 文案，`failed`） |
| 12:00 整分钟 | **16** 条，正文全是催收话术（S2/S3/S4），`label`/`user_id` 全空 |
| 12:00–12:10 文案匹配 | **21** 条（`Tap to view` / `Tap now` / `personalized payment options` / `days overdue`） |

样例（12:00:43，`sent`，无 user_id）：`See your personalized payment options for PHP 11,272.00. Tap to view.`；S4 有一条金额 `PHP 12,667.00` 对得上案 `517047`。

之前按 33 个 `user_id` 对不上，是因为 JPush 路径不落 `user_id`（接口也没有这个字段），不是没发。异步历史晚于入队几十秒，所以 20 秒窄窗会漏掉 12:00:43 那一簇。S1 正文是 “past due / Tap to settle”，没进上面的匹配，33−21 的缺口优先按这个解释。

我方 Adapter 只传 `token/title/body/data`，文档写明 `label` 不会落库。对账不能再用 `label=collection`。

---

## 10. 问题清单（8/27 傍晚）

| 项 | 挡今天触达？ | 8/27 傍晚 |
|---|---|---|
| 取消计划残留 PENDING（§6.1） | 否 | **遗留**，未改代码 |
| 日切投影缺失 WARN（§6.2） | 否 | **已从 Pilot 白名单拿掉 10 个 ID**（50→40） |
| CONNECT_AND_STOP 无样本（§6.3） | 否 | 遗留，等接通 |
| Email 缺映射（§6.5） | 这 1 封没发出 | **已改代码**；8/28 14:00 复验 |
| 入批竞态拆成 4 批（§6.6） | 否 | **已改 SETNX**；8/28 09:15/14:30 复验 |
| S3 重建抛异常（§6.7） | 否 | **已改** 0 步升档/收口 |
| BQ Push 无 label（§9.2） | 否 | 有记录；对账改文案。通知中心侧遗留 |

观测日志：`/tmp/wave1430-watch.log`（14:31 / 14:35）。14:45 那次采样时步骤已全部终态。

---

## 11. 8/27 傍晚：已解决 / 遗留 / 明天

### 11.1 今天测到了什么（不变）

五个预写槽都按时跑完，无悬挂 `EXECUTING`。无真人接通。14:30 聚合首跑起批和回调成功，但拆成 4 个 Facade 批次。Email 因映射只在本地 yml、Pilot 没有而 FAILED。Push 在 BQ 有 12:00 催收正文，无 `label`/`user_id`。

### 11.2 讨论后已经改掉的

| 项 | 怎么改的 | 8/28 怎么验 |
|---|---|---|
| Email `d-xxx` 只在 Nacos/yml（§6.5） | 常量 `EmailMilestoneScriptSlots.PHASE1_SENDGRID_TEMPLATE_IDS`；Adapter 只读代码 | **14:00** 案 `519965` / plan `883` / step `3177`，邮箱 `joshsarion@gmail.com`，应走 `S4_EMAIL_ENTRY` 打到 SendGrid，步骤不要再是 `SENDGRID_NO_TEMPLATE` |
| 入批竞态拆 4 批（§6.6） | 代次键 `SETNX` 初始化为 1 | **09:15 / 14:30** timeline 上同一槽应基本是 **一个** `mocasa-YYYYMMDD-0915-1` / `…-1430-1`（满员才会出现 `-2`） |
| S3 穷尽 0 步抛 PEL（§6.7） | Factory 空计划 → ESCALATE 或 COMPLETE，不抛 | 日志不应再出现 `REBUILD did not create successor plan`。案 `519965` 已有 S4 计划，不必等它再炸一次 |
| 白名单 10 个幽灵 ID（§6.2） | 已从 Pilot `COLLECTION_PILOT_LOAN_IDS` 与 `COLLECTION_SCAN_CASE_IDS` 拿掉（50→40） | **03:35 起日切** `scanned=40`，不应再刷这 10 个 `t_ai_collection 无 case_id` |

### 11.3 还没改 / 验不了的

| 项 | 状态 | 明天 |
|---|---|---|
| 取消计划残留 PENDING（§6.1） | **未改**（不加新 result 枚举；步骤仍可能挂 PENDING） | 仍可能看到已还款计划下的 PENDING；扫描不会发出 |
| CONNECT_AND_STOP（§6.3） | 无代码可改，缺 `ANSWERED` 样本 | 09:15 或 14:30 若出现接通，看当日第二通是否 `SKIPPED` |
| Push `label`/`user_id` 空（§9.2） | 通知中心接口限制，本期不改 | 对账继续用时间窗 + 正文，不要用 `label=collection` |
| 短信运营商 failed/sent（§6.4） | Phase 1 已知 | 不作为引擎失败 |

### 11.4 明天（8/28）槽位预估

库内 `STEP_SCHEDULED` 约 33 份计划。8/28 **PENDING** 预写量（16:20 取数）：

| PHT | 渠道 | PENDING | 验证重点 |
|---|---|---|---|
| 08:00 | SMS | 35 | 主路径按时；BQ `request_id` 仍可对 |
| 09:15 | AI_CALL | 36 | **SETNX：一槽一批** |
| 12:00 | PUSH | 35 | 引擎 DELIVERED；BQ 用正文不对 user_id |
| 14:00 | EMAIL | **1** | **代码映射**：案 `519965` S4 进段邮件 |
| 14:30 | AI_CALL | 28 | **SETNX：一槽一批** |

日切 03:35–05:55：白名单改为 40 后，`scanned` 应为 40。案 `519965` 投影仍可能是 S3、计划已是 S4，日切按单调前进可能 `rollbackSkipped`（与今天 `529878` 同类，不是回归）。

若 09:15 仍出现多个 `mocasa-20260828-0915-*`，先看 Redis 代次键是否被旧 INCR 残留；必要时清 `channel:facade:gen:20260828-0915` 后再观察 14:30。

### 11.5 发版落地（16:32 PHT）

| 项 | 值 |
|---|---|
| GitHub `test_branch` | 产品修复 `4ca65f5`（已上 Pilot）；本段为发版后补记 |
| Pilot 镜像 | `intelligent-collection-admin:pilot`，容器 `Up`，loopback health **200** |
| 白名单 | `COLLECTION_PILOT_LOAN_IDS` / `COLLECTION_SCAN_CASE_IDS` 均为 **40** |
| 编包抽检 | jar 含 `S4_EMAIL_ENTRY` + `d-658d5be1…`、`REBUILD produced no successor`、SETNX 入批 |
| 单测 | `SendGridEmailAdapterTest` 7、`FacadeBatchCoordinatorTest` 9、`PlanLifecycleManagerTest` 49，均通过 |
| 回滚 | jar `/opt/app/build/collection-admin.jar.bak.202608270831`；env `/opt/app/pilot.env.bak.202608270831` |

公网 `https://collection-admin.mocasa.com/actuator/health` 返回 403（与本机 loopback 200 并存，按网关/WAF 限制理解，不以 403 判发版失败）。

