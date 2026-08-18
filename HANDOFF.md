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
| 可观测 | 本地 MeterRegistry | `/actuator/prometheus` + 运维 Dashboard | 代码有；抓取/告警待运维 |

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
| inbox 补发 | PENDING 兜底 Job（现依赖 PubSub 重投） |

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
