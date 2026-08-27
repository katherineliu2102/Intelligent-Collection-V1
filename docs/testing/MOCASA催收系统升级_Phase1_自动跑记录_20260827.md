# Phase 1 自动跑记录（2026-08-27）

> **层级**：50 案 Pilot 自然日自动跑（调度 `planStepDue` → 四渠道 dispatch → 步骤终态）。  
> **对比 8/26**：昨晚是挤压冒烟 + 三案加测；今天**不手调时间**，看预写槽是否按钟点发出。  
> **环境**：Pilot `bdp01`，容器 `collection-admin`，镜像 `intelligent-collection-admin:pilot`。上午跑的是 **`a24356c`**（回调按步骤收口 + AI 超时 30 分钟）；**11:22 PHT 换成带波次聚合的构建**（§8），14:30 那波是聚合首跑。  
> **时区**：业务时间为 **PHT（UTC+8）**。  
> **取数截止**：2026-08-27 **09:51 PHT**（§1–§7）；§8 记 11:22 的发版。12:00 Push / 14:00 Email / 14:30 AI 待补。  
> **关联**：[主链路冒烟清单](./MOCASA催收系统升级_Phase1_主链路冒烟清单.md) · [发版手册](../channel/MOCASA催收系统升级_Phase1_发版手册.md)

---

## 1. 结论（上午）

**08:00 短信、09:15 AI 第 1 通按时执行，引擎主路径通过。** 无悬挂 `EXECUTING`；health 200。通道失败（BUSY / FAILED / NO_ANSWER、短信拒信）有出现，但步骤都收到终态，不算引擎停摆。

今早 **没有真人接通**，CONNECT_AND_STOP 未在今天的 09:15 上验证。槽身份上看到的 1 条 `ANSWERED` 是 8/26 提前打掉的 8/27 09:15 行（plan `843` / step `1684`），不是今早真拨。

| 口径 | 结果 |
|---|---|
| 应用健康 | 200 |
| 日切 03:35–05:55 | 跑完；`stageChanged=0` `ceased=0` `rollbackSkipped=1` |
| 08:00 SMS | tick `scanned=27`；当日 `executed_at` **27 DELIVERED** |
| 09:15 AI 第 1 通 | tick `scanned=27`；当日执行 **27 全部终态**（BUSY 13 / FAILED 10 / NO_ANSWER 4） |
| 悬挂 EXECUTING | **无** |
| 12:00 / 14:00 / 14:30 | 检查时未到点，仍 PENDING（见 §5） |

---

## 2. 与昨晚预估的对照

写冒烟清单时，库内 8/27 仍 PENDING 的量 vs 今早实绩：

| 时间（PHT） | 渠道 | 昨晚预估仍 PENDING | 今早按时发出 | 说明 |
|---|---|---|---|---|
| 08:00 | SMS | ~28 | **27** | 与 tick `scanned=27` 一致。槽身份 DELIVERED=33，多出来的是昨晚窗口 C + 17:18 提前打掉的行 |
| 09:15 | AI 第 1 通 | ~28 | **27** | 同上；另有 1 条槽身份 `ANSWERED` 实为 8/26 12:02 已打 |
| 12:00 | PUSH | ~34 | 未到点 | 现仍 34 PENDING；另 1 条槽身份已 DELIVERED（历史提前） |
| 14:00 | EMAIL | ~1 | 未到点 | 1 PENDING |
| 14:30 | AI 第 2 通 | ~28 | 未到点 | 28 PENDING；另 2 SKIPPED 是旧计划 827/828，不是今天 CONNECT_AND_STOP |

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

若产品要的是「一个 batch 里多 callee」，那是未做的批次聚合，需要另开需求，不是今天跑偏了。

---

## 5. 尚未到期（写文档时）

| 时间 | 渠道 | PENDING | 备注 |
|---|---|---|---|
| 12:00 | PUSH | 34 | 另 1 条槽身份已 DELIVERED（历史） |
| 14:00 | EMAIL | 1 | 仅里程碑 DPD 真 SendGrid |
| 14:30 | AI 第 2 通 | 28 | 今早无 ANSWERED，CONNECT_AND_STOP 不会减少这 28 条 |

下午再扫一轮才能写全日结论。plan `843` 的 14:30（step `1703`）仍会打：昨天接通的是被提前的 09:15 行，补丁不会回溯 skip。

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

**原因（待确认哪一种）**：日切把 `collection.ingestion.loan-id-whitelist` 里的值当 `case_id` 去查 `t_ai_collection`。这 10 个 ID 不在投影里——可能从未进件，也可能白名单是 loanId、投影主键是另一套 caseId。已有活跃计划的案仍按预写槽在催，所以 08:00/09:15 没受影响；这 10 个本身也没有在今早被扫到的 due 步骤。

**推荐方案（讨论后再改）**：

1. **先核对这 10 个 ID**：是否应在白名单、进件是否被 ack 跳过、loanId 与 caseId 是否同一列。若本就不该催，从白名单拿掉，WARN 立刻消失。
2. **推荐（代码，确认口径后）**：日切对「投影不存在」降级为 **每个 ID 每天一条**（或 DEBUG），避免 5 分钟刷一次；缺投影 skip 可以保留。
3. 不建议：为了消 WARN 在日切里按别的列盲查乱建计划。

### 6.3 CONNECT_AND_STOP 今天没有样本

今早 27 通无一 `ANSWERED`。plan `843` 的接通发生在补丁上线前（8/26 12:02），14:30 不会被回溯 skip。

**推荐**：不改代码。下午看 14:30 是否 28 通都出站；以后出现今早 `ANSWERED` 再对当日第二通是否 `SKIPPED`。若产品要求「只要历史上这天上午接通过就不再打下午」，那是另一条规则，和当前「只在本次 ANSWERED 推进时 skip 同日 PENDING」不同。

### 6.4 通道失败（记一笔，不改引擎）

AI：BUSY / FAILED / NO_ANSWER 合计 27/27。短信：CreativeBlue 拒信已在昨晚关成 FAILED。步骤能终态即引擎过；供应商侧另跟。

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
| **真实一批多案** | **未验证**——Pilot 没有 `/mock` 入口（`@Profile local/test`），只能等 14:30 |

回滚：`pilot.env` 里 `CHANNEL_FACADE_BATCH_AGGREGATION_ENABLED=false` + 重启，即回到一案一批。

---

## 9. 下午要看的清单

1. 12:00 Push：tick `scanned` 是否约 34，步骤是否终态。
2. 14:00 Email：那 1 封是否真发 SendGrid。
3. **14:30 AI（聚合首跑）**：日志应有 27 条 `enrolled into wave` + **1 条** `wave started ... cases=27`；timeline 的 distinct `provider_msg_id` 应为 **1**（对照上午 09:15 是 27）；各步骤 `timeout_time` 应被统一改写成同一时刻。
4. 若下午出现 `ANSWERED`，核对其同日剩余 AI 是否 `SKIPPED`。

观测已排程到 Pilot 后台（`/tmp/wave1430-watch.log`，14:31 / 14:35 / 14:45 三次采样）。
