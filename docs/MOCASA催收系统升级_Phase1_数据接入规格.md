# MOCASA 催收系统升级 — Phase 1 数据接入规格

> **版本**: Phase 1 · 仅覆盖菲律宾市场
> **日期**: 2026-08-17
> **外部消息 SSOT**: [数仓 Pub/Sub 交付契约](./数仓_PubSub交付契约.md)
> **本文范围**: `collection-ingestion` 的消费、校验、投影、日切和内部事件实现。

---

## 目录

- [1. 定位与边界](#1-定位与边界)
- [2. 消费运行](#2-消费运行)
  - [2.1 消费者配置与外部资源依赖](#21-消费者配置与外部资源依赖)
  - [2.2 消费结果、重试与死信处置](#22-消费结果重试与死信处置)
- [3. 案件消息处理主链路](#3-案件消息处理主链路)
  - [3.1 消息路由与契约校验](#31-消息路由与契约校验)
  - [3.2 投影写入、Inbox 与幂等](#32-投影写入inbox-与幂等)
  - [3.3 按消息类型的处理矩阵](#33-按消息类型的处理矩阵)
  - [3.4 内部事件发布与 PENDING 补发](#34-内部事件发布与-pending-补发)
- [4. DPD 日切](#4-dpd-日切)
- [5. 迁移与 replay](#5-迁移与-replay)
- [附录：Phase 1 运行清单](#附录phase-1-运行清单)

---

## 1. 定位与边界

`collection-ingestion` 是北向入站边界：消费案件 Pub/Sub、写入 `t_ai_collection`、在事务提交后发布内部领域事件；`dailyRoll` 由调度 Topic 触发，只读投影并产出阶段变化或停催事件。

```text
案件 Topic → 校验 → t_ai_collection_inbox + t_ai_collection → 内部 EventBus
调度 Topic → DpdStageRollHandler（只读 t_ai_collection）→ 内部 EventBus
```

- 接入层是 `t_ai_collection` 的唯一写入者；数仓不得直连业务库。
- 接入不重算 DPD、金额或催收状态；外部字段、Publisher、Topic、口径与验收以[数仓交付契约](./数仓_PubSub交付契约.md)为准。
- 接入不写 plan、不调用渠道；引擎负责计划和触达。
- 两份冻结 message 样例为 [caseEvent](./contracts/caseEvent.sample.json) 与 [repaymentEvent](./contracts/repaymentEvent.sample.json)。每份 body 均为单案 `{dataType, data}` envelope，不是批量 envelope。

## 2. 消费运行
<a id="31-pubsub-消费"></a>

### 2.1 消费者配置与外部资源依赖

| 配置项 | Phase 1 默认值 | 消费侧语义 |
| --- | --- | --- |
| `GCP_PUBSUB_PROJECT` | 环境注入 | GCP 项目 ID |
| `GCP_PUBSUB_SUBSCRIPTION` | 环境注入 | 案件 Consumer 专用订阅 |
| `GOOGLE_APPLICATION_CREDENTIALS` | 部署 Secret | Consumer SA 凭证；不得入仓 |
| `collection.ingestion.enabled` | 本地 / CI `false`；联调 / 生产 `true` | 是否启动 `PubSubCaseConsumer` |
| `collection.ingestion.ack-deadline-seconds` | `60` | 须与 Subscription 的 ack deadline 一致 |
| `collection.ingestion.max-concurrency` | `4` | 单实例拉取并发；按 DB 连接池和 p99 处理时长调优 |

案件 Topic、Subscription、IAM、消息保留与 DLQ 以[数仓交付契约 §1](./数仓_PubSub交付契约.md#1-封面与边界)和上线资源清单为准；接入侧仅消费环境注入的 Subscription。部署配置索引见[基础设施附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录运行配置与环境)。

### 2.2 消费结果、重试与死信处置
<a id="23-消费可靠性"></a>

消费为 at-least-once。以下是所有案件消息的统一处置；按消息类型的校验、幂等与投影规则见[§3](#3-案件消息处理主链路)。

| 结果 | 处置 |
| --- | --- |
| 成功处理、重复 `eventId`、相同指纹或陈旧还款 | ACK；不重复写投影或发布领域事件 |
| 不可恢复契约错误 | 记录 poison 和告警后 ACK |
| 投影事务或事件发布的瞬态失败 | NACK，由 Pub/Sub 重投 |
| 超过 Subscription 最大投递次数 | Pub/Sub 自动转入 DLQ；保留原始 payload、失败原因与投递信息，修复后以原 `eventId` 重放 |

ACK、DLQ、重放与 poison 的外部行为见[数仓交付契约 §3](./数仓_PubSub交付契约.md#3-发布可靠性)。

## 3. 案件消息处理主链路
<a id="3-入案处理主链路校验幂等与边界"></a>

### 3.1 消息路由与契约校验

字段集、必填性、样例和 Publisher 约束见[数仓交付契约 §2](./数仓_PubSub交付契约.md#2-计算口径与事件契约)。接入仅定义消息路由和错误处置。

| `dataType` / `eventType` | 路由 | 契约错误处置 |
| --- | --- | --- |
| `caseEvent` / `CASE_INGESTED` | `CasePayloadMapper.mapAiSnapshot`，进入完整快照处理 | poison 后 ACK |
| `repaymentEvent` / `REPAYMENT` | `mapRepaymentDelta`，进入还款增量处理 | poison 后 ACK |
| 外部 `CASE_STAGE_CHANGED` / `CASE_CEASED` | 拒绝；阶段和停催仅由日切产出 | poison 后 ACK 并告警 |
| 未知 `dataType` | 拒绝 | ACK、记录指标；持续出现告警 |

`repaymentEvent` 只携带还款后的 `dpd`、`stage`、金额和下一期提醒字段，不得带 `caseVersion`、产品、借款人或设备。已结清、D+91 与在催状态由投影组装时派生；接入不信任或依赖外部 `collectionStatus`。

### 3.2 投影写入、Inbox 与幂等 <a id="34-与-caseservice--profileservice-的调用边界"></a><a id="投影写入与单写者约束"></a><a id="读库"></a><a id="c-i-入案联调确认"></a>

```text
消息校验 → 映射 CaseProjection → 同事务写 inbox 和投影
        → 提交 → 发布内部事件 → inbox 标记 PUBLISHED
```

<a id="33-接入幂等键"></a>

收件箱为最终幂等判据：同一 `eventId` 重投命中收件箱即跳过；`caseEvent` 指纹相同或 `repaymentEvent.occurredAt` 早于投影时，收件箱标记 `SKIPPED` 且不覆盖投影。Redis key（`collection:ingestion:*`）仅用于快速去重、日切 keyset 游标和完成标记，并与旧催收隔离。具体键名见[基础设施附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录运行配置与环境)。

### 3.3 按消息类型的处理矩阵
<a id="222-repaymentevent"></a>

| 场景 | 投影行为 | 内部事件 |
| --- | --- | --- |
| 新 `caseEvent` | 无行则插入；指纹变化则刷新 | 首次入催为 `CASE_INGESTED` |
| 指纹相同的 `caseEvent` | 收件箱标记 `SKIPPED` | 无 |
| 已有周期的变更 `caseEvent` | 刷新投影 | 不重复建计划；阶段变化由日切处理 |
| 新的整笔结清 `repaymentEvent` | 行锁读取完整快照，合并增量并派生 `SETTLED` | `REPAYMENT_RECEIVED` |
| 新的部分还款 `repaymentEvent` | 合并运行态字段并派生 `IN_COLLECTION` | `CASE_BALANCE_UPDATED` |
| 无完整快照基线的 `repaymentEvent` | 视为异常；不从旧库回填产品、借款人或设备 | poison 后 ACK |
| 早于投影的 `repaymentEvent` | 收件箱标记 `SKIPPED` | 无 |

### 3.4 内部事件发布与 PENDING 补发

接入在投影事务提交后经 `CollectionEventBus` 发布事件；字段定义见[领域模型 §6](./MOCASA催收系统升级_Phase1_领域模型与数据定义.md#6-eventpayload-字段定义)，传输可靠性见[基础设施 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)。

| 事件 | 触发条件 |
| --- | --- |
| `CASE_INGESTED` | 首次有效 `caseEvent` 入催 |
| `REPAYMENT_RECEIVED` | `repaymentEvent.isFullCleared=true` |
| `CASE_BALANCE_UPDATED` | 非结清 `repaymentEvent` 合并投影成功 |

投影与收件箱同事务提交。若提交后内部事件尚未发布，收件箱保留 `PENDING`；同一消息重投时只补发内部事件，不重复写投影，成功后标记 `PUBLISHED`。

## 4. DPD 日切 <a id="4-阶段变更与-dpd-日切"></a><a id="42-读库与扫描"></a><a id="42-读库与演进"></a><a id="43-日切流程"></a><a id="44-产出事件"></a>

`DpdStageRollHandler` 在调度 Topic 的 `dailyRoll` tick 触发后，读取 `t_ai_collection` 的 DPD、stage 与催收状态；不重算金融字段，不写投影。

| 项 | 行为 |
| --- | --- |
| 时间 | 03:35–05:55 PHT，每 5 分钟处理一个 keyset 分页；06:00 PHT 前完成，否则告警 |
| 前置 | 当日 `caseEvent` 批次已消费完毕；批次门控目标与延迟处置见[数仓交付契约 §5](./数仓_PubSub交付契约.md#5-日切窗口与批次门控) |
| 扫描 | 联调使用 `loan-id-whitelist`；生产按 `case_id` keyset 分页，Redis 保存游标与完成标记。单轮上限为 `collection.ingestion.daily-roll-batch-size`（Pilot `1000`）；按日案件量与单页耗时调优 |
| 阶段变化 | 活跃计划的 stage 与投影不同（含回退）时发布 `STAGE_CHANGED` |
| 停催 | `dpd >= 91` 且仍有活跃计划时发布 `CASE_CEASED` |
| 重跑 | 同日同一目标 stage 或停催事件不重复发布 |

`STAGE_CHANGED` / `CASE_CEASED` 只能由该 Job 产出；外部 Topic 的同名事件必须按 poison 处理。

## 5. 迁移与 replay
<a id="6-迁移与双写"></a><a id="61-联调隔离"></a>

Phase 1 当前由数仓直发 Pub/Sub 驱动入案；无论触达 owner 如何切换，接入层仍消费案件事实并作为 `t_ai_collection` 的唯一写入者。

| 场景 | 接入约束 |
| --- | --- |
| 联调 | 可使用 `collection.ingestion.loan-id-whitelist` 限定案件；不在名单内的消息 ack 跳过 |
| 生产切量 | 接入默认全量消费；D-3～D0 触达归属由 `collection.notification.owner` 和信贷侧停发共同控制 |
| replay | 使用 Topic 保留期重放原消息；保持原 `eventId`，由收件箱与投影规则幂等吸收 |
| 历史数据 | 不通过旁路 SQL 写 `t_ai_collection`；需要回补时使用契约消息或受控迁移流程 |

生产切片、回滚和通知 owner 的业务规则见[PRD §10](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)；未闭合工程任务见[HANDOFF](../HANDOFF.md#3-模块未闭合待办)。

---

## 附录：Phase 1 运行清单
<a id="c-p-基础设施与可靠性"></a>

本附录只保留保障稳定运行的最小证据。字段验收见[数仓交付契约 §6](./数仓_PubSub交付契约.md#6-上线验收)，配置键见[基础设施附录 A](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录运行配置与环境)。

| 关注点 | 必须观察 | 异常时先查 |
| --- | --- | --- |
| 消费健康 | Pub/Sub lag、ack / nack / poison / DLQ | Publisher 重试、Subscription 堆积、Consumer 日志 |
| 投影与事件交接 | `t_ai_collection_inbox` 的 `PENDING`、投影 `synced_at` | 投影事务、内部 EventBus 发布、补发任务 |
| 日切完成 | Redis 游标、当日完成标记、`STAGE_CHANGED` / `CASE_CEASED` 数量 | 批次就绪、扫描配置、调度 tick、活跃计划 |
| 每日对账 | 数仓按 `dataType` 的发布量与接入 ack / nack / poison / dedup 对比 | 批次完成信号、DLQ、投影写入失败 |

运行阈值、告警级别、Dashboard 和 Runbook 由运维在上线单维护，不在本文重复定义。
