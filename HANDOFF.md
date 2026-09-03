# Phase 1 交接板（活文档）

> **版本**: Phase 1 · 2026-08-13  
> **用途**: 生产缺口、未闭合待办、跨模块契约须确认项。**不是**规格 SSOT。  
> **规格入口**: [`docs/README.md`](./docs/README.md) · 本地启动: [`README.md`](./README.md)  
> **仓库**: https://github.com/katherineliu2102/Intelligent-Collection-V1

---

## 1. 本地 / CI 替身 vs 生产（Pilot 缺口）

内存实现**仅**用于 local/CI。生产依赖 Redis（旧催收已有）；配置 `collection.eventbus=redis` / `collection.idempotency=redis` + 原子频控。Phase 1 单活跃实例即可（峰值 QPS 约 1–3）。

| 项 | 本地 / CI | 生产 / Pilot | 状态 |
|---|---|---|---|
| 事件总线 | `InMemoryEventBus` | `RedisStreamEventBus`：Consumer Group、PEL、背压、DLQ 双写 | 代码有；压测定版待 Pilot |
| 幂等 | `InMemoryIdempotencyService` | Redis SETNX + TTL | 待切生产配置 |
| 合规频控 | 内存计数 | Redis Lua 双计数；断连 fail-close | 待切生产配置 |
| 接入去重 | 内存（重启清空） | Redis 快筛；**最终判据** `t_ai_collection_inbox` | 待切生产配置 |
| 调度 | `@Scheduled`（local/test） | Cloud Scheduler → 调度 Topic → `PubSubScheduleConsumer` | ✅ GCP 侧已通（2026-08-21）：Topic 与订阅已在（订阅参数已纠为 ack 60s / 保留 10m / 永不过期），4 条 Job 已建于 `asia-northeast1` 且 tick 已实测落订阅；**发布者归属＝主架构**。`asia-southeast1` 的三条旧同功能 Job 因缺少 `job` attribute 且日切窗口错误，现为 `PAUSED`，待确认无其他用途后删除。调度 Topic 现为单一发布者。剩 O4 凭证、O7/O8 告警与 `keliu` 的 Pub/Sub 只读权限 |
| 案件投影 | 内存 Repository | `AiCaseProjectionRepository`：inbox + `caseVersion` upsert | ✅ 实现；生产库回收数仓写权限待运维 |
| CaseService | Mock / `real`（旧库） | `collection.case-service=ai` 读 `t_ai_collection` | 联调切 `ai` |
| SPI 硬超时 | — | `SpiInvoker` 已落地 | ✅ |
| 派生事件 | — | `t_event_outbox` + `OutboxPublisher` | ✅ 代码有；需真库验事务（L3-6） |
| 停摆巡检 | — | `StuckPlanReaper` 告警、不自动修 | ✅ |
| 渠道对账 | 无 | 供应商补 timeline | Phase 2 |
| 可观测 | 零散本地日志/测试证据 | Phase 1 简版观测 MVP；后续 Prometheus + 运维 Dashboard | T3o 前须实现并验证最小查询证据；抓取/告警/Dashboard 最迟在移除白名单（T6 准入）前闭合，T3o–T5 以每日人工巡检代偿 |

---

## 2. 契约演进规则（所有开发者）

- **优先**不破坏调用方的扩展（可选字段、枚举新值、重载）。
- **跨模块契约**（SPI 签名、事件 payload、状态机）：群里同步 + 主架构评估对 `collection-engine` 的影响后再改。
- **模块内部**（Mock→真、私有实现）：自行决定。
- 替换 Mock：实现接口 + `@Component`（同名加 `@Primary`），或删同名 Mock；契约不变则引擎零改动。

契约路径：`collection-common` 的 `spi/` · `channel/` · `event/` · `dto/` · `model/` · `enums/` · `repository/` · `service/`。  
`engine.spi` 仅有内部 `SpiInvoker`，不是对外 SPI 包。

---

## 3. 模块未闭合待办

### A · `collection-channel`（编排同事）

入口：[渠道文档索引](./docs/channel/README_渠道文档索引.md)

| 项 | 说明 |
|---|---|
| SPI 生产化 | 主架构曾临时代写 `@Primary` PlanFactory/Guard/Policy（L4a）；编排须 review 并替换为生产实现 |
| `ComplianceExecutionGuard` | Redis 原子频控；勿用恒放行 Mock 上生产 |
| AI_CALL / Voice | Adapter + Webhook；与 LTH 解绑口径见 §4 |
| Webhook 鉴权 | 供应商签名校验（与 admin 协作） |

SPI 签名与约束 → [核心引擎规格 §6](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)、[执行契约](./docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md)。

### B · `collection-ingestion`（主架构）

规格：[数据接入](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md) · 数仓：[Pub/Sub 交付契约](./docs/数仓_PubSub交付契约.md)

| 项 | 说明 |
|---|---|
| 两类事件联调 | `caseEvent` / `repaymentEvent` 真 Topic 连通 + 样例全链路。测试入口已开通（2026-08-20，`scripts/test/provision-l4-pubsub.py`）：合成源 `intelligent-collection-cases-test1(+sub)`、真实源 `intelligent-collection-cases-v1-l4b-sub`（挂正式 topic 扇出，不影响 `-v1-sub`）、死信 `-dlq(+sub)`。**上游当前未向契约 topic 发布**：独立订阅跨过 03:00 PHT 日切窗口后仍为 0 条（扇出保证创建后的消息必有副本），两个历史 topic 同期也为 0；需数仓确认实际发布 topic 与节奏，探针见 `scripts/test/observe-upstream-topics.py` |
| 日切 Pilot | 全量 keyset + Redis 游标；批次完成门控（固定时间窗 → 水位信号） |
| 日切阶段单调前进 | ✅ 已闭合：有活跃计划时仅投影 stage 更高才发 `STAGE_CHANGED`，回退只计数。无活跃计划时：最近一份 `PLAN_COMPLETED` 且投影档更高 → 续档建当天档（S0→S1 … S3→S4）；`MANUAL_CLEANUP` / `REPAID` / `CEASED` 不续建（[数据接入 §4](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)）；`DpdStageRollHandlerTest` 含续档与 S0 upcoming 续建 |
| 日期解析与 S0 提醒金额 | ✅ 已闭合：`dueDate`/`nextDueDate` 兼容 `yyyy-MM-dd` 与 ISO 时间戳（取日历日），乱码才毒丸；PreFlight / 日切 / Facade 元数据对 S0 认 `upcomingAmount`，避免逾期额 0 把提醒案当没钱可催 |
| 白名单过滤 | ✅ 已闭合：`PubSubCaseConsumer` 在路由前按 `collection.ingestion.loan-id-whitelist` 过滤，名单外 ack 跳过、空名单放行；取不到 `caseId` 时放行交映射层按 poison 处置 |
| 联调隔离闸门 | ✅ 已闭合：`IngestionIsolationGuard` 在 local/test profile 下拒绝「消费保留生产订阅」与「白名单为空」两种配置，在建立订阅前失败；pilot 由 `PilotReadinessValidator` 接管 |
| 无基线还款处置 | ✅ 已闭合：仓储改抛 `MissingCaseBaselineException`，接入层转 `PoisonMessageException` → ack + 告警。此前抛 `IllegalStateException` 会被当瞬态失败 nack，形成永不收敛的重投 |
| `repaymentEvent.stage` 契约冲突 | ✅ 2026-08-21 已闭合（**接入侧忽略**，不要求数仓改契约）：`updateRepaymentDelta` SQL 去掉 `stage` 列、`mergeRepayment`（真库与内存两套）不再复制、`CaseProjectionFields` 删除该字段使还款路径**不解析**它。第三层不可省——继续解析会让一个非法 stage 取值 poison 掉真实还款，丢还款比忽略字段严重得多。守护：L1 单测（非法 stage 不影响还款）+ 仓储单测（保留基线 stage）+ L3 IT `repaymentDelta_neverRewritesStage`（真库 stage 不变、余额已变） |
| inbox 补发 | PENDING 兜底 Job（现依赖 PubSub 重投） |
| **按日 Owner 路由（2026-09-03）** | 数仓须发 `owner=NEW`、同一 `caseId` 每个 PHT 日一条完整 `caseEvent`（同指纹也要按日重发以刷新 `owner_date`）。空收按 payload `date(occurredAt)` 计数，不以 inbox `created_at` 为准。本仓：`caseEvent` 到达只写投影，03:35 对账后才 `CASE_INGESTED` / `CASE_OWNER_RECONCILED`。生产库须执行 `db/schema.sql` 中 `owner` / `owner_date` / `t_ai_owner_reconcile`。L4b-15～18 尚未在隔离 Topic 上取证 |
| v3 端到端与真实 Pilot | 本轮全量重测：所有测试用例状态归零，按投影/inbox 主路径重跑；T4 固定 50 案必须等待 T3o 简版观测 MVP |
| 文档与代码对齐 | 🟡 2026-08-21 已完成核查（11 条漂移，逐条见[测试 SSOT T0-6 核查结论](./docs/testing/MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md#t0-6-核查结论2026-08-21-执行逐条已复核到文件行号)）。已修：回调路由指向 `/channel-callback`（`/lth/voice` 判为死命名，**渠道模块改动需知会编排同事**）、发布脚本真算 MD5 指纹与 UUID `eventId`、`caseEvent` 补快照单调性防护、还款增量彻底忽略 `stage`。Facade 建批**不带**回调地址：2026-08-20 修订说明确认 callback 为账户级配置，当日先加的随单 `callback_url` 已回退，并按同一份说明用单测钉住 `dial_policy` 只准 `timezone`/`windows`/`weekdays`（带已取消字段会 422）。未闭合：接入校验宽于契约（低危）；**Facade 回调入站端点缺失（原 A3，已从「文档登记缺失」升级为缺实现、T4 阻断）**——账户级单一 URL + 对方签名方案 + `session.completed` JSON，与现有靠 query 参数定位步骤、验我方 HMAC 的 `/webhook/channel-callback` 结构对不上；契约、反查口径、结果映射与验收已写入[回调入站交接](./docs/channel/MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md)，**由编排同事实现**。该文档记录了一个必须防的陷阱：引擎 `mapCallbackToResult` 对无法识别的取值默认返回 `ANSWERED`，把供应商原生词（`VOICEMAIL` 等）直接透传会把未接通静默记成已接通真人。**运维高危两条**：基础设施附录 A.2 曾把日频控与静默时段登记为 `engine.compliance.*`，代码只读 `channel.compliance.*`（按旧键配置静默失效，已更正文档）；`collection.notification.owner` 有登记无实现。Pilot 依赖但未登记的键（验签、Redis 去重、频控后端、case-service）已补入附录 A.2b |
| 接入契约收紧待裁定 | `repaymentEvent` 不校验 `eventType`、不拒绝契约禁止字段、`repayTime`/`paidAmount` 非必填；消费者兼容无 `{dataType,data}` 信封的平铺 body。收紧前须与数仓确认现网 payload 合规，否则 T4 会批量 poison |
| 模板版本溯源 | `t_contact_timeline` 的模板版本字段记录应用侧版本，与管理后台发布版本无稳定映射；Phase 2 补齐 |

已落地：v3 路由、投影单写者、静默日刷、dailyRoll 读投影产出阶段/停催。

### C · `collection-service`（服务同事）

| 项 | 说明 |
|---|---|
| CaseService=`ai` | 只读 `t_ai_collection`；`caseId` = 信贷 `loan_id` |
| ProfileService | Phase 1 **可后置**：入案画像走 PubSub 快照；当前几乎无调用方。若 `RealCaseService` 改为委托 Profile，须先接真 |
| ⚠ **契约变更（2026-08-21）**：`ContactPlanRepository.markStepExecuting` `void`→`boolean` | `markExecuting` SQL 新增 `AND status NOT IN ('COMPLETED','SKIPPED','FAILED')`，返回 0 行即「没抢到」。**该谓词是承重的**：它是引擎防「至少一次投递复活已终结步骤」的唯一原子屏障（调用方先读后写不具原子性，终态写入发生在计划行锁之外）。旧库映射改写时若丢掉谓词，仓储会无条件返回「抢到了」，保护静默消失且**无编译错误、无单测失败**。守护断言在 `StepScheduleAuditMapperIT#markExecuting_refusesToResurrectTerminalStep`（真库，三种终态拒绝 + 两种非终态放行），改 SQL 前请先跑它 |
| ⚠ **契约变更（2026-08-21）**：`findDueSteps` / `findTimeoutSteps` 新增 `List<Long> caseIdFilter` 形参 | 对应 `selectDueSteps` / `selectTimeoutSteps` 新增 `AND p.case_id IN (...)`，名单为空/`null` 时不拼该条件（退化为全库扫描）。**承重条件**：扫描 SQL 没有任何租户维度，「连上哪个库」等于「有权对该库全部案件发起触达」。已实测共享库上存在外来实例：本机应用停机期间插入的到期步骤，2 分钟内被另一实例抢占改写为 `EXECUTING`。过滤丢失同样**无编译错误、无单测失败**，只会静默恢复全库扫描。守护断言在 `StepScheduleAuditMapperIT#dueAndTimeoutScans_honourCaseIdFilter`（真库，名单内必扫到 / 名单外必扫不到 / `null` 退化为不过滤）。配置入口 `collection.scan.case-id-whitelist`，`local`/`test` profile 下由 `ScanIsolationGuard` 强制非空。**2026-09-03 追加**：mapper 增加可选 `ownerDate`；`CaseService.requiresOwnerDate()` 为 true 且当日水位未写时仓储直接返回空页，水位已写则 SQL 只拾取 `t_ai_collection.owner_date = 当日` |
| 入站 `data` 类型校验（2026-08-21 已改，接入侧） | `PubSubCaseConsumer.payload()` 不再用 `getJSONObject("data")`：fastjson 对**字符串值**会把它当 JSON 文本再解析一遍并抛 `JSONException`，该异常不是 `PoisonMessageException`，会逃出 poison 判定落进通用 `catch` 变成 **nack** → 一条永远处理不成的消息被反复重投直到耗尽投递次数进 DLQ（L4b-14 实测 5 次）。已改为先判类型再取值。**改这段时别退回 `getJSONObject`**：守护断言在 `PubSubCaseConsumerTest#nonObjectData_isPoisonAndAcked` / `#nullData_isPoisonAndAcked` |
| 时间列口径（2026-08-21 已改） | `ContactPlanMapper` / `ContactPlanStepMapper` / `ContactTimelineMapper` 的时间列不再用 `NOW()`，一律由 `ServiceClock.now()`（`Asia/Manila`，截到秒）传参；**每条 UPDATE 都必须显式写 `updated_at`**，否则表上的 `ON UPDATE CURRENT_TIMESTAMP` 会重新把库端时间写回。其余表（DLQ / 收件箱 / 发件箱 / 回调审计）仍用 `NOW()`，由会话时区与 `DatabaseClockValidator` 保证同轴。理由与历史修法见 D 段「时区口径」|

### D · 基础设施 / 引擎

规格：[基础设施](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md)

| 项 | 说明 |
|---|---|
| Redis 切生产 | 总线 / 幂等 / 频控 / 接入 dedup 配置与隔离验收 |
| 压测 | Consumer 池、队列、ack-deadline vs p99 |
| I/O SPI 超时 | Guard 等须自带 client 超时（`Future.cancel` 掐不断卡死 I/O） |
| **时区口径** | ✅ 已闭合（2026-08-21 17:19 复跑 L3 全套 30 例全绿，L4a 上 31 条步骤行同行时间列全为 PHT）。以下保留问题现场与两条失败修法，供改这块代码时参考。原始问题（2026-08-21 L3-7 首跑暴露）：MySQL `system_time_zone=UTC`，`NOW()` 写出 UTC；而 `ContextAssembler` 按 `Asia/Manila` 算当日频控边界、`StuckPlanReaper` 按 JVM 默认时区算停摆宽限。两者都与 `NOW()` 写入的列（`t_contact_timeline.created_at`、`t_contact_plan.updated_at`）直接比较，恒差 8 小时 → ①当日触达漏计 PHT 00:00–08:00，频控可能超发；②停摆宽限恒被满足。到期/超时扫描与发件箱租约不受影响（比较双方均为应用写入）。修法与验收见[测试文档附录 C](./docs/testing/MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md#附录-c缺口登记)<br>**已试过两条基础设施层修法，均不足**：①Hikari `connection-init-sql`（`application.yml`）只覆盖到部分连接；②JDBC URL 上 `forceConnectionTimeZoneToSession=true`（`start-local.sh` 注入）——注意**必须配数值偏移 `connectionTimeZone=%2B08:00`**，下发命名时区会因该实例未加载时区表而让每条连接都建不起来（`Unknown or incorrect time zone: 'Asia/Shanghai'`）。加上②之后绝大多数列已落 Manila，但实测 `t_contact_plan_step.executed_at` 仍会落 UTC（同一行 `dispatched_at`/`updated_at` 却是 Manila）。**已按第三条修法落地（2026-08-21）**：`collection-service` 三个 mapper（plan / step / timeline）的时间列改为 `ServiceClock.now()` 传参，`ContactPlanRepositoryImpl` / `TimelineRepositoryImpl` 负责下发；新增 `DatabaseClockValidator`（`collection-admin`）在启动时比对应用 PHT 时钟与 `SELECT NOW()`，偏差超 `collection.db.clock-drift-threshold-seconds`（默认 120s）时 pilot 拒启、其余 profile 仅告警。**验收已完成**：带 `L3_IT_DB_URL` 复跑 `ContactPlanMapperIT` / `StepScheduleAuditMapperIT` 全绿，`executed_at` 与同行其余列同为 PHT；回归守卫为 `StepScheduleAuditMapperIT#timeColumns_landOnPhtNotDatabaseSessionTimeZone`（同一行内 `created_at`/`updated_at`/`executed_at` 与应用 PHT 时钟偏差须在 5 分钟内）。改动已通知服务同事 |
| 末步穷尽闸门 / REBUILD 次日首步 | ✅ 2026-08-31：`onStepCompleted` 在仍在催时不采纳 `AdvancementPolicy.PLAN_COMPLETED`，改发 `PLAN_EXHAUSTED`；`REBUILD` 首步 `trigger_time` 不得早于次日 08:00 PHT。[核心引擎 §4.3.2 / §4.5](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md#432-step_completed)。**剩余**：`DefaultAdvancementPolicy` 末步仍返回 `PLAN_COMPLETED`（引擎改写兜住）；编排替换生产策略时建议对齐，不阻断 |

#### D.1 生产就绪差集登记

> 此表是生产基础设施契约的动态实施状态与闭合证据登记；稳定目标和准入门槛见[基础设施交互规范附录 B](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-b容量基线与生产技术准入)。

| 能力 | 生产目标 | 当前实现 | 未闭合差集 | 阻断级别 | 闭合标准 |
|---|---|---|---|---|---|
| 事件消费 | 有界 Consumer Pool 并发消费，成功后 XACK，PEL 可恢复 | 已有有界 Consumer Pool、`CallerRunsPolicy` 背压、MDC 透传、Consumer Group / PEL 恢复 | Pilot 并发、队列和积压阈值尚待压测定版 | 高 | 完成容量压测并固化运行参数与告警阈值 |
| DLQ | Redis 隔离 + MySQL 持久化 + 受控重放 | PEL 超限/不可恢复消息双写 Redis 与 `t_event_dlq`；`/ops/dlq/redrive` 以显式 eventId、必填原因、3 次上限和触达窗口门控执行重放，鉴权复用 `/ops/**` 登录拦截；状态机与门控已单测覆盖 | 真实 MySQL/Redis 联调与告警路由待 T5 环境验收 | 高 | R5/R6 演练证据与告警到达记录 |
| 步骤幂等 | Redis `SET NX EX`，跨实例共享 | `RedisIdempotencyService` 已实现，key 统一为 `collection:lock:plan:` / `collection:idempotency:channel:` | Pilot Redis 环境与运维检索口径待验收 | 高 | 确认物理隔离、TTL 与检索口径 |
| 事件消费去重 | 同一 `eventId` 成功处理后不再重复执行 | 消费入口按 `collection:processed:{event_id}` 判重后 ACK，成功处理后写 24h 标记；失败不写标记，仍留 PEL 重投；Redis 异常降级为不去重。四条路径已单测覆盖 | 真实 Redis 上的重投与 DLQ 重放行为待 Pilot 验证 | 高 | PEL 重投与同一 eventId 重放各一次，确认只执行一次业务 |
| 合规频控 | Redis Lua 原子计数，单渠道与跨渠道日上限 | Pilot 使用 Redis Lua 双计数；local/test 保留内存实现；键名、PHT 过期时间与断连 fail-close 已单测覆盖 | 跨实例上限与真实 Redis 断连行为待 T5 环境验证 | 高 | T5-R8 证据及告警到达记录 |
| 日切去重 | Redis 去重且与旧系统隔离 | `RedisDailyRollDeduplicator` 已使用 `collection:ingestion:` 前缀和 2 天 TTL | 日切 Redis 与生产配置仍待 Pilot 验收 | 高 | 确认物理隔离、配置与运维检索口径 |
| 接入去重 | 消息重投、乱序水位与周期内重复入催跨重启/跨实例一致 | `RedisIngestionDedupStore` 承载三类 key（`dedup:msg` 7d、`last-seen` 90d Lua 水位、`ingested` 90d），内存实现仅留本地与 CI | 真实 Redis 上的重启连续性待 Pilot 验收 | 高 | 重启后重复消息仍被拦截、结清后可再次入案 |
| 调度 | Cloud Scheduler → Pub/Sub → 应用订阅的 Trigger-to-Event | `PubSubScheduleConsumer` + `ScheduledJobRunner` 已实现：单订阅按 `job` 属性路由、按 `publishTime` 丢弃陈旧消息、一律 ack 不重投、按任务单飞、五个 `collection.schedule.*` 指标；`SchedulerEntrypointValidator` 强制配置完整性、阈值 ≤ 周期与调度入口唯一；XXL 运行时（类、依赖、配置、环境变量）已移除；全量 `dailyRoll` 仍按 Redis 游标 keyset 单页扫描并记录当日完成状态。27 例单测覆盖路由、陈旧丢弃、重复投递、并发单飞与入口唯一性 | 调度主题 / 专用订阅 / 双向 IAM / 四条 Cloud Scheduler Job / ack deadline 与消息保留 / Scheduler 失败与 06:00 未完成告警均属运维 GCP 交付（[基础设施 §5.5](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md#55-运维--gcp-交付清单) O1–O8） | 高 | O1–O8 交付完成，且 Pilot 上观测到 `collection.schedule.triggered` 按周期增长、重启后 `stale.discarded` 出现一次尖峰后归零 |
| 还款分流与金额 | 仅全额结清取消；部分还款刷新后续渲染所需运行态 | 已按 `fullRepayTime` / `STATUS=4` 分流；部分还款受控更新活跃计划快照的金额与下一期提醒字段；两类分支已单测覆盖 | 缺真实 PubSub 回归及金额字段质量验收 | 高 | 以真实消息覆盖缺失/负值/重复/终态、部分还款与全额结清 |
| 可观测性 | Stream/PEL/DLQ、线程池、合规与调度均有指标和告警 | `/actuator/prometheus` 暴露基础设施 §7.3 全部指标（事件、PEL、Stream 长度、DLQ、线程池、跳过原因、SPI 超时），消费入口统一写 MDC | 告警规则、通知路由与 Dashboard 依赖 Prometheus/Alertmanager 部署 | 高（2026-08-05 决定：由阻断降级，可与渠道验证、切量并行；2026-08-21 确认闭合点为 T6 准入，不阻塞 T4/T5） | 代偿期内每日人工巡检日志并手工抓取 `/actuator/prometheus` 记录 PEL/DLQ/跳过原因；最迟在申请移除白名单（T6 准入）前完成抓取、告警路由与 Dashboard，并留存告警到达证据 |

### E · `collection-admin`

| 项 | 说明 |
|---|---|
| 调度 GCP | 调度 Topic/订阅、双向 IAM、Scheduler cron、06:00 告警（T5 手册） |
| Webhook 鉴权 | HMAC 验签已实现并单测覆盖（`WebhookControllerTest`：伪签、缺头、缺密钥、畸形签名均拒绝并审计）。缺口在环境：`collection.webhook.signature-required` 本地仍为 `false`，且 HTTPS 域名/证书/密钥未交付 |
| 管理 API | `PlanQueryController` 按需扩展 |

已落地：`PubSubScheduleConsumer` + `ScheduledJobRunner`；生产禁止 `@Scheduled` 双入口。

---

## 4. 跨模块契约须确认（已裁决）

> 文档契约 ≠ 代码已完成。字段 SSOT → [领域模型](./docs/MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；执行行为 → [执行契约](./docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md)。

| Owner | 已裁决 | 须确认 / 实施 |
|---|---|---|
| channel | PUSH token = `device.jpushToken`；空 token 仅同次 dispatch 内 Push→SMS | Adapter/Resolver 不读 `fcmToken`；fallback 不查库 |
| channel | 空地址由 Guard `NO_*`；Resolver `null` 仅策略跳过 | 真实 Guard 实现；Resolver 不用 null 表示空地址 |
| channel | SMS/PUSH/EMAIL：`observationMinutes=0`，dispatch 成功即完成 | PlanFactory 不为三渠道建观察期 |
| channel | timeline **仅引擎写**；channel 只回 `StepResult` | 禁止 channel 写 `t_contact_timeline` |
| channel | 失败三分类：以「请求字节是否已写给供应商」分 `retryable` | 新 Adapter 按 `HttpFailureClassifier`；超时/5xx 不重试 |
| channel | `StepCommand.providerIdempotencyKey` = `{planId}:{stepOrder}` | 与供应商幂等字段对齐；Phase 1 不据此放开结果未知后重试 |
| channel | 文案 `dpd` / `totalOutstanding` 由引擎在解析前用实时 `CaseInfo` 覆盖内存快照 | Resolver 不自行查库校正 |
| channel | `AI_CALL` 解绑 LTH；任务标识落 `provider_msg_id` | Adapter/Webhook 命名不以 LTH 为前提 |
| service | `caseId` = `loan_id`（Long） | Mapper/对账不用 `t_collection.id` 当 caseId |
| service | CaseService 供日切/守卫只读；入案快照不回填 | 确认只读查询能力 |

---

## 5. 文档入口（勿在本文重复规格）

| 关切 | 文档 |
|---|---|
| 总索引 | [`docs/README.md`](./docs/README.md) |
| 数仓 Pub/Sub | [`docs/数仓_PubSub交付契约.md`](./docs/数仓_PubSub交付契约.md) |
| 接入实现 | [`docs/MOCASA催收系统升级_Phase1_数据接入规格.md`](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md) |
| 引擎 / 基础设施 | 核心引擎规格 · 基础设施交互规范 |
| 渠道 | [`docs/channel/README_渠道文档索引.md`](./docs/channel/README_渠道文档索引.md) |
| Pilot / 运维交付 | [`docs/testing/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md`](./docs/testing/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) |

跨模块疑问先联系主架构再改代码。
