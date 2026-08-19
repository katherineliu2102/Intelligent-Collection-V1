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
| 调度 | `@Scheduled`（local/test） | Cloud Scheduler → 调度 Topic → `PubSubScheduleConsumer` | 代码有；GCP IAM/Job 待运维 |
| 案件投影 | 内存 Repository | `AiCaseProjectionRepository`：inbox + `caseVersion` upsert | ✅ 实现；生产库回收数仓写权限待运维 |
| CaseService | Mock / `real`（旧库） | `collection.case-service=ai` 读 `t_ai_collection` | 联调切 `ai` |
| SPI 硬超时 | — | `SpiInvoker` 已落地 | ✅ |
| 派生事件 | — | `t_event_outbox` + `OutboxPublisher` | ✅ 代码有；需真库验事务（L3-6） |
| 停摆巡检 | — | `StuckPlanReaper` 告警、不自动修 | ✅ |
| 渠道对账 | 无 | 供应商补 timeline | Phase 2 |
| 可观测 | 零散本地日志/测试证据 | Phase 1 简版观测 MVP；后续 Prometheus + 运维 Dashboard | T3o 前须实现并验证最小查询证据；抓取/告警为 T5 放量前待办 |

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

入口：[渠道文档索引](./docs/channel/README_渠道文档索引.md) · [开发执行指南](./docs/channel/MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md)

| 项 | 说明 |
|---|---|
| SPI 生产化 | 主架构曾临时代写 `@Primary` PlanFactory/Guard/Policy（L4a）；编排须 review 并替换为生产实现 |
| `ComplianceExecutionGuard` | Redis 原子频控；勿用恒放行 Mock 上生产 |
| AI_CALL / Voice | Adapter + Webhook；与 LTH 解绑口径见 §4 |
| Webhook 鉴权 | 供应商签名校验（与 admin 协作） |

SPI 签名与约束 → [核心引擎规格 §6](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)、[执行契约](./docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)。

### B · `collection-ingestion`（主架构）

规格：[数据接入](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md) · 数仓：[Pub/Sub 交付契约](./docs/数仓_PubSub交付契约.md)

| 项 | 说明 |
|---|---|
| 两类事件联调 | `caseEvent` / `repaymentEvent` 真 Topic 连通 + 样例全链路 |
| 日切 Pilot | 全量 keyset + Redis 游标；批次完成门控（固定时间窗 → 水位信号） |
| 日切阶段单调前进 | ✅ 已闭合：`DpdStageRollHandler.rollOne` 仅在投影 stage 严重度更高时发 `STAGE_CHANGED`，回退只计数不发事件，避免与引擎 ESCALATE 形成降档 ping-pong（[数据接入 §4](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md#4-dpd-日切)）；`DpdStageRollHandlerTest` 8 例覆盖 |
| inbox 补发 | PENDING 兜底 Job（现依赖 PubSub 重投） |
| v3 端到端与真实 Pilot | 本轮全量重测：所有测试用例状态归零，按投影/inbox 主路径重跑；T4 固定 50 案必须等待 T3o 简版观测 MVP |
| 文档与代码对齐 | 重测前需闭合测试 SSOT T0-6：接入契约、事件语义、回调入口、配置键、脚本入参的漂移核对与修正 |
| 模板版本溯源 | `t_contact_timeline` 的模板版本字段记录应用侧版本，与管理后台发布版本无稳定映射；Phase 2 补齐 |

已落地：v3 路由、投影单写者、静默日刷、dailyRoll 读投影产出阶段/停催。

### C · `collection-service`（服务同事）

| 项 | 说明 |
|---|---|
| CaseService=`ai` | 只读 `t_ai_collection`；`caseId` = 信贷 `loan_id` |
| ProfileService | Phase 1 **可后置**：入案画像走 PubSub 快照；当前几乎无调用方。若 `RealCaseService` 改为委托 Profile，须先接真 |

### D · 基础设施 / 引擎

规格：[基础设施](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md)

| 项 | 说明 |
|---|---|
| Redis 切生产 | 总线 / 幂等 / 频控 / 接入 dedup 配置与隔离验收 |
| 压测 | Consumer 池、队列、ack-deadline vs p99 |
| I/O SPI 超时 | Guard 等须自带 client 超时（`Future.cancel` 掐不断卡死 I/O） |

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
| 可观测性 | Stream/PEL/DLQ、线程池、合规与调度均有指标和告警 | `/actuator/prometheus` 暴露基础设施 §7.3 全部指标（事件、PEL、Stream 长度、DLQ、线程池、跳过原因、SPI 超时），消费入口统一写 MDC | 告警规则、通知路由与 Dashboard 依赖 Prometheus/Alertmanager 部署 | 高（2026-08-05 决定：由阻断降级，可与渠道验证、切量并行） | 代偿期内每日人工巡检日志并手工抓取 `/actuator/prometheus` 记录 PEL/DLQ/跳过原因；最迟 T6 受控切量前完成抓取、告警路由与 Dashboard，并留存告警到达证据 |

### E · `collection-admin`

| 项 | 说明 |
|---|---|
| 调度 GCP | 调度 Topic/订阅、双向 IAM、Scheduler cron、06:00 告警（T5 手册） |
| Webhook 鉴权 | 骨架已发事件，缺鉴权 |
| 管理 API | `PlanQueryController` 按需扩展 |

已落地：`PubSubScheduleConsumer` + `ScheduledJobRunner`；生产禁止 `@Scheduled` 双入口。

---

## 4. 跨模块契约须确认（已裁决）

> 文档契约 ≠ 代码已完成。字段 SSOT → [领域模型](./docs/MOCASA催收系统升级_Phase1_领域模型与数据定义.md)；执行行为 → [执行契约](./docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)。

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
