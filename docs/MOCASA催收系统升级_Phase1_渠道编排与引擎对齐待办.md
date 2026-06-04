# MOCASA Phase 1 — 引擎对齐沟通

> **用途**: 按会议议程组织；说明 **要对齐什么、怎么对齐、可选方案及优劣**。  
> **渠道编排已定**: [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) v1.4（本节仅摘要，方案比选针对 **引擎实现路径**）。  
> **对照**: [核心引擎规格](./MOCASA催收系统升级_Phase1_核心引擎规格.md)、[基础设施交互规范](./MOCASA催收系统升级_Phase1_基础设施交互规范.md)  
> **建议时长**: 约 40–50 分钟

---

## 议程一：E1 + E2 — 停催事件与日切 Job（约 20 min）

### 渠道编排已定（沟通时不必重议）


| 项    | 内容                                                                          |
| ---- | --------------------------------------------------------------------------- |
| 主动催收 | 仅 **S0–S4**，Max DPD **D-3 ~ D+90**（§4.1、§5.2 映射表）                           |
| D+91 | **完全停催**；案件态 `**CEASED`**（≠ 还清 `COMPLETED`）                                 |
| 事件   | 发 `**CASE_CEASED**`；**不**用 `STAGE_CHANGED` 升「第六段」、**不**增 `StageEnum.CEASED` |
| 引擎动作 | cancel 全部 pending steps；**不再** `PlanFactory.create`                         |


### 要对齐什么


| 编号     | 缺口                                                                      | 不对齐的后果                               |
| ------ | ----------------------------------------------------------------------- | ------------------------------------ |
| **E1** | 核心引擎 §2.4 **无** `CASE_CEASED` 分支；`STAGE_CHANGED` 仅表达「换 Stage → 重建 plan」 | D+91 仍可能触发 `PLAN_STEP_DUE`、误发 SMS/AI |
| **E2** | 基础设施 §4 Cron **无** Max DPD 日切任务                                         | S1→S2、S4 内日块、**D+91 停催** 均不会自动发生     |


### 怎么对齐（目标态）

```
每日 0:05 PHT（建议）dpdStageRollHandler
  → 重算 Max DPD
  → 若 1~90 且 Stage 变：发 STAGE_CHANGED → Consumer 取消旧 plan + PlanFactory.create(新 Stage)
  → 若 ≥91 且未 CEASED：写 collection_status=CEASED + 发 CASE_CEASED
       → Consumer：cancel plan/steps，不调 PlanFactory.create

还款：REPAYMENT_RECEIVED（已有）优先；日切仅兜底升阶/停催
```

---

### E1：D+91 停催 — 引擎侧实现方案


| 方案                 | 做法                                                                                    | 优点                                  | 缺点                                                          | 与渠道编排          |
| ------------------ | ------------------------------------------------------------------------------------- | ----------------------------------- | ----------------------------------------------------------- | -------------- |
| **E1-A（推荐，=渠道编排）** | 新事件 `**CASE_CEASED`**；Consumer 专用分支：cancel + 计划终态；**不** create plan                   | 语义清晰；Stage 枚举保持 5 段；报表「主动催收 / 停催」分离 | 引擎需新事件类型 + §2.4 表                                           | **一致**         |
| **E1-B**           | 新增 `StageEnum.CEASED`，发 `**STAGE_CHANGED(S4→CEASED)`**，`PlanFactory` 对 CEASED 返回 null | 复用现有 `STAGE_CHANGED` 管线，改动面看似小      | Stage 混入「非催收强度」桶；易与 S4 报表混淆                                 | **不一致**，需改渠道编排 |
| **E1-C**           | 仅发 `**PLAN_CANCELLED`**（cancel_reason=MAX_DPD），无案件态事件                                 | 改动最小                                | 案件 `**CEASED**` 态靠 ingestion 另写，引擎与 ingestion 易双轨；监 unclear | 部分一致           |


**渠道编排倾向**: **E1-A**。若引擎坚持 B，需回改渠道编排 §4.2/§9 并统一报表口径。

**请引擎确认**: 事件名是否采纳 `CASE_CEASED`？Payload 最小字段（`case_id`, `max_dpd`, `occurred_at`）？

**讨论结论**: _______________

---

### E2：DPD 日切 Job — 基础设施侧方案


| 方案           | 做法                                                  | 优点                                       | 缺点                        | Phase 1  |
| ------------ | --------------------------------------------------- | ---------------------------------------- | ------------------------- | -------- |
| **E2-A（推荐）** | 仅 `**dpdStageRollHandler`** 日批（0:05 PHT）            | 与现有 `planStepDueHandler` 风格一致；易批量处理 D+91 | 升阶最长约 24h 滞后（降阶/还款仍靠实时事件） | **默认**   |
| **E2-B**     | 仅上游 ETL：每次 DPD 变更即发 `STAGE_CHANGED` / `CASE_CEASED` | 阶段切换近实时                                  | 依赖上游质量；重复/乱序需幂等           | 有 ETL 再做 |
| **E2-C**     | **A + B** 双轨，B 日常、A 对账兜底                            | 最稳                                       | 两套逻辑必须共用 §5.2 映射；测试量大     | 有余力      |


**怎么对齐**: 在 **基础设施交互规范** 登记 Job 名称、调度、cron、发布事件类型；实现归属 **ingestion**（或数据接入模块），引擎 Consumer **不改** Cron，只消费事件。

**请基础设施/引擎确认**: Job 归属模块？是否与 `planStepDueHandler` 同 XXL-Job 应用？

**讨论结论**: _______________

---

## 议程二：E3 + E4 — Override 中断 + 禁用 HUMAN_CALL（约 15 min）

### 渠道编排已定


| 项        | 内容                                                                                                                                                         |
| -------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Override | `DISPUTE` / `NEEDS_HUMAN` / `COMPLAINT_FROZEN` / `PTP_EXPIRED` 等 → **cancel 当日 pending AI/TTS**；写 `human_dial_override` 等标签；争议时机器 SMS/Push/Email **BLOCK** |
| 人工外呼     | **不进** 机器轨 plan；**LTH** 读标签外呼；**不** `ChannelGateway(HUMAN_CALL)`                                                                                           |
| 互斥       | `CONNECT_AND_STOP` 只挡机器 Wave-2；**不挡** Override 后 LTH 核实外呼                                                                                                  |
| D+91 后   | 零主动外呼；**客户进线** 仍可回拨（§7.2）                                                                                                                                  |


### 要对齐什么


| 编号     | 缺口                                                 | 不对齐的后果                        |
| ------ | -------------------------------------------------- | ----------------------------- |
| **E3** | 引擎 §2.4 **未列** Override 相关事件                       | 争议/需人工时 Wave-2 仍执行，**同案互斥失效** |
| **E4** | `HUMAN_CALL` 在 `StepCommand` 中合法，可走 ChannelGateway | 机器轨误调度人工步，与「AI 主 + LTH 例外池」冲突 |


### 怎么对齐（目标态）

```
Override 类事件 → EventConsumerDispatcher
  → SELECT FOR UPDATE plan
  → cancel SCHEDULED/EXECUTING 的 AI_CALL/TTS steps（当日/或全 plan 待定）
  → 释放 voice_lock（若有）
  → 不写 HUMAN_CALL step；案件表/Redis 写 human_dial_* 标签（ingestion 或 Consumer 副作用边界需约定）

PlanFactory / StepResolver：Phase 1 永不输出 channelType=HUMAN_CALL
```

---

### E3：Override — 引擎事件与中断方案


| 方案           | 做法                                                                                                  | 优点                | 缺点                                   |
| ------------ | --------------------------------------------------------------------------------------------------- | ----------------- | ------------------------------------ |
| **E3-A（推荐）** | Phase 1 最小集：`DISPUTE`、`NEEDS_HUMAN`、`COMPLAINT_FROZEN` + 已有 `REPAYMENT`；均走 §2.4 **cancel AI steps** | 覆盖 §7.2 拦截风险；工期可控 | `PTP_EXPIRED`、`CUSTOMER_INBOUND` 可下期 |
| **E3-B**     | 渠道编排 §9 **全量**事件进引擎                                                                                 | 与 §9 完全一致         | 联调面大；ingestion 来源多                   |
| **E3-C**     | 仅写标签，引擎 **不** cancel step                                                                           | 实现快               | **不可验收** Override；与渠道编排矛盾            |


**子问题：标签谁写？**


| 子方案       | 做法                                 | 优劣                |
| --------- | ---------------------------------- | ----------------- |
| **E3-i**  | Consumer 内写案件标签（引擎侧少量 DB 写）        | 时序紧、与 cancel 原子性好 |
| **E3-ii** | Consumer 只 cancel；ingestion 同事务写标签 | 职责清               |


**渠道编排倾向**: **E3-A + E3-i 或 ii（二选一，需引擎拍板）**。

**讨论结论**: _______________

---

### E4：人工外呼 — 引擎是否调度 HUMAN_CALL


| 方案                 | 做法                                                                                 | 优点                  | 缺点                               |
| ------------------ | ---------------------------------------------------------------------------------- | ------------------- | -------------------------------- |
| **E4-A（推荐，=渠道编排）** | Phase 1 **禁止** plan 内 `HUMAN_CALL`；人工 100% **LTH + 标签**；timeline 可由 LTH Webhook 回写 | 贴合两物理系统；Override 简单 | Phase 1 人工触达可能不在引擎 timeline      |
| **E4-B**           | plan 含 `HUMAN_CALL`，`ChannelGateway` → LTH Adapter                                 | 统一 plan/timeline/幂等 | 引擎要管人工 step；与「不进预测式名单」需额外 filter |
| **E4-C**           | 双轨：老 LTH 名单 + 引擎 HUMAN step                                                        | 现网改动小               | 重复外呼、互斥失效                        |


**D+91 后**: 三种方案均应允许 **无 plan** 时 LTH 读 `human_dial_override` 回拨——请引擎确认 **CEASED 不阻断** 标签读取。

**渠道编排倾向**: **E4-A**。

**讨论结论**: _______________

---

## 议程三：E5 + E6 — Voice 简化 + snapshot Offer（约 10 min）

### 渠道编排已定


| 项     | 内容                                                                                                         |
| ----- | ---------------------------------------------------------------------------------------------------------- |
| Voice | S1+ 槽位 **09:15 / 14:30**（Wave-1/2）；Phase 1 **不要求** 引擎 VoiceQueue；并发 **LTH 排队**                             |
| Offer | **ingestion** 写 `context_snapshot` offer 字段；`StepResolver` **只读** 填模板；**不发券**；账务 **App/信贷按 Bill** 核定；话术含免责 |


### 要对齐什么


| 编号     | 缺口                                                 | 不对齐的后果                    |
| ------ | -------------------------------------------------- | ------------------------- |
| **E5** | 渠道编排曾写「滚动入队」；引擎只有 `trigger_time`                   | 若引擎组误以为要做 VoiceQueue，工期膨胀 |
| **E6** | SPI **禁止** StepResolver 调外部；Offer 若在 ④ 调 F10/信贷则违约 | 违规二次开发；减免客诉/超发风险          |


### 怎么对齐（目标态）

```
Voice：PlanFactory 为 AI_CALL 写入 trigger_time（09:15、14:30 等）
  → planStepDueHandler → Orchestrator → ChannelGateway(AI_CALL) → LTH

Offer：入案 / 重建 plan 前 ingestion 跑 F10/t_discount_rule
  → 写入 context_snapshot（offer_eligible, offer_tier_label, …）
  → StepResolver 仅做变量替换 → StepCommand.templateId + metadata
```

---

### E5：外呼调度 — 方案


| 方案                 | 做法                                                                 | 优点                 | 缺点                   |
| ------------------ | ------------------------------------------------------------------ | ------------------ | -------------------- |
| **E5-A（推荐，=渠道编排）** | 引擎 **固定 trigger_time**；**无** VoiceQueue；LTH 承担排队与 `max_concurrent` | 零引擎新抽象；与 Cron 模型一致 | 09:15 可能齐射；「滚动」在 LTH |
| **E5-B**           | `collection-channel` 对 AI_CALL 做 Redis 批内队列                        | 贴滚动语义；引擎控「可外呼槽」    | channel 2–3 人周；双状态监控 |
| **E5-C**           | 独立 Job 扫表推 LTH                                                     | —                  | 与 plan 双轨；**不推荐**    |


**组合**: **E5-A + LTH 自有队列**（渠道编排 Phase 1 默认）；指标恶化再 **A→B**。

**请引擎确认**: Phase 1 **不**在引擎实现 `max_concurrent` / VoiceQueue？

**讨论结论**: _______________

---

### E6：Offer 注入 — 方案


| 方案                 | 做法                                                | 优点                | 缺点                                      |
| ------------------ | ------------------------------------------------- | ----------------- | --------------------------------------- |
| **E6-A（推荐，=渠道编排）** | **ingestion** 写 snapshot offer 字段；StepResolver 只读 | 符合 SPI 零 I/O、无副作用 | snapshot 与还款时点偏差 → 靠免责 + 还款 cancel step |
| **E6-B**           | StepResolver / ④ 前调信贷 **发券**，短信带 coupon_id        | 短信与优惠一致           | **违反** SPI；跨系统失败面大                      |
| **E6-C**           | `ChannelGateway` 渲染时调 F10                         | SPI 仍纯            | 每次 dispatch 调外部；幂等/熔断在 channel          |
| **E6-D**           | 短信仅泛泛宣传，**无** snapshot offer 字段；App 实时 F10        | 引擎最省事             | 模板能力弱；与 §5.3「F10 入 snapshot」略弱          |


**引擎需确认**: `ContextSnapshot` DTO 是否增加字段（如 `offer_eligible`, `penalty_waive_pct_preview`, `bill_scope_hint`）？谁负责在 **ingestion** 填充（引擎不管 F10 规则，只读 snapshot）？

**渠道编排倾向**: **E6-A**；**禁止 E6-B** 在 StepResolver 内实现。

**讨论结论**: _______________

---

## 议程四：E7 + E8 — PTP 来源、进线事件（按需）

### 渠道编排已定


| 项   | 内容                                                               |
| --- | ---------------------------------------------------------------- |
| PTP | 主动催收期内：捕获承诺 → 暂停常规催 + 到期处理；AI 无 disposition 时 **优雅降级**（§9、§7.10） |
| 进线  | `CUSTOMER_INBOUND`：允许人工回拨，**含 D+91+**；可不新增机器 step                |


### 要对齐什么


| 编号     | 缺口                                                       |
| ------ | -------------------------------------------------------- |
| **E7** | 引擎 §2.6：**仅坐席 App** 产生 PTP；与「AI 可捕获 PTP」表述需统一 Phase 1 范围 |
| **E8** | `CUSTOMER_INBOUND` 未进 Consumer；是否必须引擎 cancel AI          |


---

### E7：PTP 来源 — 方案


| 方案                  | 做法                                                       | 优点         | 缺点                       |
| ------------------- | -------------------------------------------------------- | ---------- | ------------------------ |
| **E7-B1（推荐对齐引擎现稿）** | Phase 1 **仅坐席 App** 写 `t_collection_ptp_info`；AI 谈判须人工补录 | 数据质量、法务可追溯 | AI 可对话能力浪费               |
| **E7-B2**           | AI disposition → 引擎/ingestion 写 PTP + `PTP_CAPTURED` 事件  | 闭环快        | LTH Schema、防伪造、与 §2.6 冲突 |
| **E7-B3**           | App 为主；AI 捕获 **只展示** 不驱动子流程                              | 折中         | 与渠道编排「PTP 子流程」部分脱节       |


**怎么对齐**: 若选 **B1**，渠道编排 §9 保持「优雅降级」即可，**无需改引擎**；若选 B2，须扩 §2.6 与事件表。

**讨论结论**: _______________

---

### E8：客户进线 — 方案


| 方案                   | 做法                                           | 优点           | 缺点                   |
| -------------------- | -------------------------------------------- | ------------ | -------------------- |
| **E8-A**             | 引擎消费 `CUSTOMER_INBOUND`：可选 cancel 当日 AI + 打标 | 与 §9 一致；时序统一 | 非 P0                 |
| **E8-B（推荐 Phase 1）** | **仅 LTH/客服** 打标 + 回拨；**不进** 引擎 Consumer      | 工期最小         | 与 plan 无联动 cancel AI |
| **E8-C**             | 并入 `NEEDS_HUMAN` 一种来源                        | 少事件类型        | 语义略宽                 |


**渠道编排倾向**: Phase 1 可接受 **E8-B**；若下午进线时 Wave-2 仍在，依赖 **E3** 客服标 `NEEDS_HUMAN`。

**讨论结论**: _______________

---

## 会后动作与结论汇总


| 负责人  | 动作                                                                       |
| ---- | ------------------------------------------------------------------------ |
| 引擎   | 按议程结论更新：§2.4（E1/E3）、事件路由、禁止 plan 内 HUMAN_CALL（E4）、ContextSnapshot 字段（E6） |
| 基础设施 | `dpdStageRollHandler`（E2）写入基础设施规范并实现                                     |
| 渠道编排 | 仅当引擎事件命名与 `CASE_CEASED` 不一致时改 §4.2/§9                                    |



| 议程  | 结论（填写） |
| --- | ------ |
| E1  |        |
| E2  |        |
| E3  |        |
| E4  |        |
| E5  |        |
| E6  |        |
| E7  |        |
| E8  |        |


---

## 速查：渠道编排倾向（供主持人拍板）


| 项   | 倾向                                           |
| --- | -------------------------------------------- |
| E1  | **CASE_CEASED**，不增 Stage CEASED              |
| E2  | 先 **日批 Job**，有余力 **+ETL 实时**                 |
| E3  | **最小 Override 事件集** + cancel AI              |
| E4  | **无 HUMAN_CALL** plan                        |
| E5  | **固定 trigger_time**，VoiceQueue Phase 1 不做    |
| E6  | **snapshot + ingestion F10**，StepResolver 只读 |
| E7  | Phase 1 倾向 **仅 App PTP（B1）**                 |
| E8  | Phase 1 倾向 **LTH 打标（B）**                     |


---

> 渠道编排产品变更请先改 [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md)，再同步本稿 E 项。

