# Phase 1 骨架交接说明

> **版本**: Phase 1 · v1.0  
> **日期**: 2026-06-03  
> **撰写**: 主架构负责人  
> **仓库**: https://github.com/katherineliu2102/Intelligent-Collection-V1

---

## 一、当前代码功能与测试进度

### 已实现内容

本次提交完成了 Phase 1 「**接口先行 + Mock SPI 全链路跑通**」骨架，是各模块并行开发的编译期起点。

| 模块 | 内容 | 代码性质 |
|---|---|---|
| `collection-common` | 15 枚举、9 领域模型、5 DTO、**5 个 SPI 接口**、`CollectionEventBus`、`ChannelGateway`、Repository/Service 接口 | 跨模块契约（初版，可演进，改前对齐） |
| `collection-engine` | `EventConsumerDispatcher`（路由）、`PlanLifecycleManager`（6 态状态机）、`StepExecutionOrchestrator`（七步管线）、`PreFlightChecker`、`SpiInvoker`（SPI 硬超时）、内存事件总线、内存幂等 | ✅ 真实实现（主框架） |
| `collection-service` | 4 张新表 MyBatis Mapper + Repository 实现 + Mock CaseService/ProfileService | 持久化真实，业务数据 Mock |
| `collection-channel` | 5 个 SPI Mock + Mock ChannelGateway + Mock 外呼过滤 | Mock，待替换 |
| `collection-ingestion` | 发布领域事件（Mock 入案）、DPD 日切 Job 占位 | Mock，待替换 |
| `collection-admin` | Spring Boot 启动入口、REST 触发/查询、Webhook、Trigger-to-Event 扫描调度 | 骨架，待补全 |
| `db/schema.sql` | 引擎核心表 DDL（t_contact_plan / t_contact_plan_step / t_decision_log / t_contact_timeline / t_user_profile_ext / t_event_dlq） | ✅ 已在催收库执行，含 2026-08-05 的 `t_event_dlq` redrive 列迁移 |

### 已测试通过

- `mvn clean package -DskipTests` → BUILD SUCCESS（JDK 8 / Maven）
- Spring Boot 启动、8080 端口、9 个事件 handler 注册、Hikari 连上测试库
- `POST /mock/ingest?caseId=1002` → 计划落库、状态机推进（PENDING → STEP_EXECUTING → STEP_SCHEDULED）、触达时间线写入
- 端到端链路：`CASE_INGESTED → 建计划 → PLAN_STEP_DUE → 七步管线 → Mock 渠道 → STEP_COMPLETED → 推进 → PLAN_COMPLETED` 完整闭环

### 当前简化项（非生产实现）

| 项 | 当前（本地/CI 替身） | 生产实现（Phase 1 依赖） |
|---|---|---|
| 事件总线 | `InMemoryEventBus`（local/CI） | Pilot `RedisStreamEventBus`：Consumer Group、PEL reclaim、有界 Consumer 池 + 背压、`collection:processed:{eventId}` 消费去重、DLQ 双写 Redis 与 `t_event_dlq`；线程数/队列阈值待压测定版 |
| DLQ 重放 | 无（内存总线不产生 DLQ） | `POST /ops/dlq/redrive`：显式 eventId + 必填原因，3 次上限，触达窗口外的 `PLAN_STEP_DUE` 延后 |
| 幂等锁 | `InMemoryIdempotencyService`（local/CI） | Pilot `RedisIdempotencyService`：Redis SETNX + TTL |
| 合规频控 | `InMemoryComplianceCounterService` | `RedisComplianceCounterService`：Lua 原子双计数（单渠道日上限 + 跨渠道日总上限），断连 fail-close |
| 接入去重 | `InMemoryIngestionDedupStore`（重启即清空） | `RedisIngestionDedupStore`：`dedup:msg` 7d、`last-seen` 90d（Lua 仅当更大才写）、`ingested` 90d，跨重启与跨实例一致 |
| 调度 | Spring `@Scheduled`（仅 local/test） | Cloud Scheduler → 调度专用 Pub/Sub 主题 → 应用侧专用订阅（`PubSubScheduleConsumer`），按消息属性 `job` 路由 `planStepDue` / `callbackTimeout` / `dailyRoll`；无执行器、无固定端口、无入站网络 |
| SPI 硬超时 | ✅ 已实现：`SpiInvoker` 线程级强制超时（`Future.get`，默认 50/20/50/10/50ms，可配） | I/O 型 SPI（Redis Lua 等）另配 client 级超时作第一道防线 |
| 案件/画像服务 | 合成 Mock 数据 | 映射真实旧库（t_collection 等） |
| 派生事件不丢 | ✅ 已实现：`t_event_outbox` 随状态迁移同事务落盘 + `OutboxPublisher` 兜底重发（[引擎 §7.4 A](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md#74-跨存储一致性修复)）。**需真实 MySQL 验证事务边界**（测试文档 L3-6） | 同实现；`collection.outbox.failed` / `pending` 接入告警 |
| 停摆巡检 | ✅ 已实现：`StuckPlanReaper` 检测「非终态但无步骤可被扫描拾取」并计数告警，**不自动修复** | 同实现；`collection.plan.stuck` 接入告警 + 运维处置 SOP |
| 渠道对账 | 无（Phase 2） | 查供应商补 `t_contact_timeline`，覆盖「触达已发出但状态未落盘」这类不可自动修复的残留（[引擎 §7.4 B](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md#74-跨存储一致性修复)） |
| 可观测性 | 本地 `SimpleMeterRegistry`（指标不外发） | `/actuator/prometheus` 暴露事件/PEL/Stream/DLQ/线程池/跳过原因/SPI 超时/发件箱/停摆；消费入口统一写 MDC。抓取、告警与 Dashboard 由运维配置 |

### Phase 1 生产拓扑与 Redis 依赖

Phase 1 **生产依赖 Redis**（旧催收系统已使用 Redis）：事件总线、幂等、合规频控的生产实现均基于 Redis。`InMemoryEventBus` / `InMemoryIdempotencyService` / 内存频控**仅用于本地开发与 CI 链路验证**（跨重启不保留、跨实例不共享），不用于生产。

- **上线前置**：完成 Redis 实现接入（D1/D2）并配置 `collection.eventbus=redis` / `collection.idempotency=redis` + Redis 原子频控。
- **部署形态**：Phase 1 单活跃实例即可满足吞吐（峰值 QPS 1–3）；Redis 保证幂等、频控与事件可靠投递，扩容多实例无需改业务代码。
- `collection-common` 已抽象接口，内存 ↔ Redis 切换对业务代码零改动。

---

## 二、快速启动（所有开发者阅读）

### 环境要求

- JDK 8、Maven 3.6+
- 可访问测试库 `ai_collection_db`（连接信息向主架构负责人获取，**不写入仓库**）
- **本地开发不依赖 Redis**：事件总线/幂等/频控可用内存替身（缺省 `memory`）跑通全链路。**生产依赖 Redis**（Stream、SETNX、原子频控），上线前须完成 Redis 实现接入（模块 D）。

### 建表（第一次拉代码后执行一次）

```bash
# 连接信息向主架构负责人获取；-p 后不跟值会交互式提示输入密码（不进 shell 历史、不暴露）
mysql -h<DB_HOST> -u<DB_USER> -p -P<DB_PORT> <DB_NAME> < db/schema.sql
```

### 编译启动

```bash
cd Intelligent-Collection-V1
mvn clean package -DskipTests

# 以下连接信息向主架构负责人获取，勿写入仓库
export DB_HOST=<DB_HOST>
export DB_PORT=<DB_PORT>
export DB_NAME=<DB_NAME>
export DB_USER=<DB_USER>
export DB_PASSWORD='<DB_PASSWORD>'

java -jar collection-admin/target/collection-admin.jar
```

### 验证链路

```bash
# 注入案件
curl -X POST "http://localhost:8080/mock/ingest?caseId=1001&userId=1001&stage=S1"

# 等 10 秒，查计划状态
curl -s "http://localhost:8080/plans/active/by-case/1001"

# 查触达时间线
curl -s "http://localhost:8080/plans/timeline/1001"
```

计划状态从 `PENDING` 最终变为 `PLAN_COMPLETED`（含 3 步，约 2 分钟走完）即为链路正常。

---

## 三、替换 Mock 的核心规则（所有开发者必读）

> ⚠️ 这一版只是**初步搭好的框架**。`collection-common` 的 SPI 接口、DTO、枚举、状态机不是冻死的——随着各模块深入（如编排引擎需要新增事件类型、计划/步骤状态、接口入参出参等），**这些契约是可以、也预期会演进的**。

约定的是**改的方式**，不是禁止改：

- **优先**：在不破坏现有调用方的前提下扩展（如 DTO 加可选字段、枚举加新值、新增方法重载）。
- **涉及跨模块的契约改动**（改 SPI 方法签名、改事件 payload、加/改状态机状态等）：先在群里同步并与主架构负责人对齐，确认对 `collection-engine` 主框架的影响后再改，避免各模块各改一份导致编译/语义冲突。
- **纯模块内部实现**（Mock 换真实、内部类、私有方法）：自行决定，无需对齐。

**替换 Mock 的方式**：实现对应接口，加 `@Component`（与 Mock 同名时加 `@Primary` 覆盖），或直接删除同名 Mock 类。只要契约不变，**主框架（collection-engine）零改动**。

---

## 四、各模块接续工作

---

### 模块 A：渠道编排 → `collection-channel`

**负责人接续替换以下 6 个 Mock 类，参考文档：**

| 文档 | 路径（**唯一定稿**：`docs/`） |
|------|--------------------------------|
| **文档索引** | [docs/README_渠道文档索引.md](./docs/README_渠道文档索引.md) |
| 渠道编排规格 v1.4（含 §3.5 Phase 1 范围） | [docs/MOCASA催收系统升级_Phase1_渠道编排规格.md](./docs/MOCASA催收系统升级_Phase1_渠道编排规格.md) |
| 核心引擎规格 | [docs/MOCASA催收系统升级_Phase1_核心引擎规格.md](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md) |
| collection-channel 总规格 | [docs/MOCASA催收系统升级_Phase1_collection-channel总规格.md](./docs/MOCASA催收系统升级_Phase1_collection-channel总规格.md) |
| Notification（SMS+Push）/ SendGrid Email / LTH Voice | 见 [文档索引](./docs/channel/README_渠道文档索引.md) · [Notification 对接说明](./docs/channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) |

#### A1. `MockPlanFactory` → `DefaultPlanFactory`

触达计划创建，依据 `t_contact_plan_template`（stage × product）匹配模板、实例化计划与步骤序列。

- **接口**：`com.collection.common.spi.PlanFactory`
- **方法签名**：`ContactPlan create(CaseInfo, Stage, ContextSnapshot)`
- **关键约束**
  - 返回 `null` = 该案件不需建计划（正常值，引擎会跳过）
  - 同一 case_id + stage 不重复创建（幂等）
  - 禁止写 DB / 调外部服务 / 发事件
  - Phase 1 **禁止**在 steps 里放 `HUMAN_CALL`（对齐待办 E4）

#### A2. `MockExecutionGuard` → `ComplianceExecutionGuard`

步骤执行前合规校验（频率 / 时段 / 空地址）。呼损率自动降级为 Phase 2。

- **接口**：`com.collection.common.spi.ExecutionGuard`
- **方法签名**：`GuardVerdict evaluate(ExecutionContext)`
- **关键约束**
  - 硬超时 20ms（单次 Redis Lua 脚本完成计数器读取+增加+TTL）
  - Redis key 前缀：`collection:compliance:daily:{userId}:{channel}:{date}`
  - 默认允许窗口为 08:00–21:00 PHT；时段外返回带 `deferUntil` 的裁定并重排
  - 空地址/频控等正常拦截返回 `GuardVerdict.block(reason, NO_EMAIL/NO_PHONE/NO_TOKEN/FREQUENCY_LIMIT)`；引擎写 `COMPLIANCE_BLOCKED` timeline 后推进
  - SPI 异常/超时才 fail-close 为 `SKIPPED`
  - 不允许返回 `null`

#### A3. `MockStepResolver` → `DefaultStepResolver`

Guard 通过后决定具体渠道 + 模板 + 目标地址，组装 `StepCommand`。

- **接口**：`com.collection.common.spi.StepResolver`
- **方法签名**：`StepCommand resolve(ExecutionContext)`
- **关键约束**
  - **零 DB I/O**，只读 `ExecutionContext.contextSnapshot`
  - `metadata` 里 `callbackUrl` / `timeoutMinutes` 对异步渠道（AI_CALL）必填
  - 硬超时 50ms；返回 `null` = **策略性主动跳过该步**（引擎标 `SKIPPED` 推进，非失败）；空地址必须由前置 Guard 拦截；异常/超时 = FAILED

#### A4. `MockAdvancementPolicy` → `DefaultAdvancementPolicy`

步骤完成后决定：推进下一步 / 计划完成 / 计划穷尽。

- **接口**：`com.collection.common.spi.AdvancementPolicy`
- **方法签名**：`AdvancementDecision decide(ExecutionContext, StepResult)`
- **返回枚举**：`ADVANCE_NEXT` / `PLAN_COMPLETED` / `PLAN_EXHAUSTED`
- **关键约束**：硬超时 10ms；不允许返回 `null`

#### A5. `MockExhaustionPolicy` → `DefaultExhaustionPolicy`

所有步骤走完且未还款时决定：续建 / 升档 / 停止。

- **接口**：`com.collection.common.spi.ExhaustionPolicy`
- **方法签名**：`ExhaustionResult handle(ContactPlan, CaseInfo, ContextSnapshot)`
- **返回字段约束**
  - `REBUILD` → `templateId` 必填，`targetStage = null`
  - `ESCALATE` → `targetStage` 必填，`templateId = null`
  - `COMPLETE` → 两者均 null
- **关键约束**：续建次数受 `engine.plan.max-rebuild-count`（默认 2）限制

#### A6. `MockChannelGateway` → 真实 `ChannelGateway`

模板渲染 → 幂等校验 → 熔断 → ChannelAdapter 调供应商 API。

- **接口**：`com.collection.common.channel.ChannelGateway`
- **方法签名**：`StepResult dispatch(StepCommand)`
- **机器轨渠道（Phase 1）**：SMS / PUSH / EMAIL / AI_CALL（TTS / HUMAN_CALL 由 LTH 域外独立编排）
- **关键约束**
  - SMS / PUSH / EMAIL 成功 dispatch 即 `success=true, DELIVERED`，`observationMinutes=0`；异步类（AI_CALL）真实结果等 Webhook 回调
  - 渠道内部 fallback 对引擎完全透明；`retryable` 的判定依据是**请求字节是否已写给供应商**：熔断未调用、凭证缺失、DNS/连接被拒、供应商显式 429 拒绝受理 → `retryable=true`；读超时、写后中断、5xx、抛异常 → `retryable=false`（结果未知，重试即重复触达）
  - 供应商错误码统一映射为 `StepResult.errorCode`
  - channel 只返回 `StepResult`，不写 `t_contact_timeline`

#### 需新建的渠道编排表（领域模型附录 B，DDL 待补充到 `db/schema.sql`）

`t_contact_plan_template` / `t_strategy_rule` / `t_compliance_rule` / `t_compliance_violation` / `t_channel_config` / `t_call_task` / `t_call_task_number` / `t_agent_status`

---

### 模块 B：数据接入 → `collection-ingestion`

**负责人参考文档：[《数据接入规格》](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md)**（事件 payload 字段见 [领域模型 §6](./docs/MOCASA催收系统升级_Phase1_领域模型与数据定义.md#6-eventpayload-字段定义)；Stream/DLQ 运行时见 [基础设施 §2](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)）

#### B1. `IngestionService` → 真实 PubSub 消费

- 接 GCP PubSub，消费上游信贷系统推送（`case_push` / `repayment_push_and_load`；`assign_signal` Phase 1 不路由）
- 校验 → 组装 `CASE_INGESTED` payload → publish 领域事件；payload 优先，缺 `dpd` / `product` / `totalOutstanding` / `penaltyAmount` / `dueDate` 时可经 CaseService **只读**回填；不回写任何库，不自行组装快照（见 [数据接入 §3.1](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md#34-与-caseservice--profileservice-的调用边界)）
- 同一 `loan_id` 本周期仅首次 publish `CASE_INGESTED`；`repayment_push_and_load`：全额结清 publish `REPAYMENT_RECEIVED`，部分还款 publish `CASE_BALANCE_UPDATED`
- `context_snapshot` 由**引擎**据完整 `CASE_INGESTED` payload 组装并写入 plan（[§3.1](./docs/MOCASA催收系统升级_Phase1_数据接入规格.md#34-与-caseservice--profileservice-的调用边界)）；引擎不在建快照时补库
- 现有 `IngestionService` 骨架方法在此替换为真实 PubSub Consumer

#### B2. `DpdStageRollHandler.dailyRoll()` → 实现日切逻辑

- 每日 0:35 PHT 起（Cloud Scheduler 在 00:35–02:55 PHT 每 5 分钟发一条 `job=dailyRoll` 调度消息，每次推进一页 keyset；账务数据落库至少 30 分钟后）在并行期读取旧库 `t_collection.overdue_days`；切量后才按 bill 级 Max DPD 重算（均只读）
- DPD 1–90 且 Stage 变化 → 发 `STAGE_CHANGED`（含 Stage 回退）
- DPD ≥ 91 → **仅**发 `CASE_CEASED`（不写旧库 CEASED 列）
- **不改引擎 Consumer，引擎只消费事件**

---

### 模块 C：数据服务 → `collection-service`

**负责人参考文档：《领域模型与数据定义》§3**

#### C1. `MockCaseService` → 真实 `CaseService`

- 所有 `caseId` = 信贷 `loan_id`，旧库关联一律使用 `t_collection.loan_id`，不得使用 `t_collection.id`
- 为 ingestion 提供缺失金融字段的只读回填：仅 `dpd` / `product` / `totalOutstanding` / `penaltyAmount` / `dueDate`，不得覆盖 payload 联系方式
- `getCaseInfo(caseId)` → 查 `t_collection`，填充 `CaseInfo`（含实时还款状态 `isRepaid`；争议冻结 `isFrozen` 为 Phase 2）
- `buildContext(caseId)` → 多表 JOIN（`t_collection` + `t_user_repayment_plan`）构建 `CaseContext`
- `buildContactHistory(userId, caseId)` → 聚合 `t_contact_timeline` 构建 `ContactHistory`
- `getContextSnapshot(caseId)` → 读 `t_contact_plan.context_snapshot`，JSON 反序列化
- `isRepaid(caseId)` → 实时查还款状态（仅引擎 `PreFlightChecker` 调用；PTP 为 Phase 2）

#### C2. `MockProfileService` → 真实 `ProfileService`（**上线前必做**）

- **定位**：不在主入案链路。联系方式与 Phase 1 最小画像由 `CASE_INGESTED` payload 带出；引擎只消费完整 payload 组装快照。ProfileService 可用于约定外的后续兜底/对账，不得替代接入层金融字段回填。
- **为何上线前必做**：兜底路径若仍是 Mock，则 payload 不完整时会回**假画像**污染真实触达；须与 `RealCaseService`（C1）同步接真。
- `getFullProfile(userId)` → 映射 8+ 张旧表（`t_user_basis` / `t_user_work` / `t_user_telephone_book` / `t_user_equipment` / `t_user_profile_ext`）
- Phase 1 未填充的字段返回 `null`；所有 SPI 实现和模板渲染须做 **null 防御处理**

---

### 模块 D：基础设施 → `collection-engine`（或新独立模块）

**负责人参考文档：《基础设施交互规范》§1–§6**

#### D1. `RedisStreamEventBus` → 替换内存事件总线

- 实现 `CollectionEventBus` 接口：`publish()`（XADD）、`subscribe()`（XREADGROUP Consumer Group）
- 配置切换：`collection.eventbus=redis`，业务代码**零改动**
- 已实现：有界 Consumer 线程池 + CallerRuns 背压、PEL 拾取、DLQ 双写（Redis stream + `t_event_dlq`）与受控重放接口
- 待 Pilot：线程数与队列容量压测定版、连接假死观察

#### D2. `RedisIdempotencyService` → 替换内存幂等

- 实现 `IdempotencyService` 接口：Redis SETNX + TTL
- 配置切换：`collection.idempotency=redis`
- key 格式：`lock:plan:{step_idempotency_key}`

#### D3. SPI 硬超时强制执行 ✅ 已完成（2026-06-11）

- 引擎调用 5 个 SPI 经 `SpiInvoker`（`engine.spi`）用 `Future.get(timeoutMs)` 强制超时（核心引擎规格 §4.1）
- 各接口超时阈值：PlanFactory 50ms / ExecutionGuard 50ms / StepResolver 50ms / AdvancementPolicy 10ms / ExhaustionPolicy 50ms（`engine.spi.*-timeout-ms` 可配）
- 单个共享有界线程池（大小=Consumer 池，预热）+ `MdcTaskDecorator` 语义跨线程传递 MDC；超时转 `SpiTimeoutException`，失败语义沿用调用方 try-catch（Guard fail-close、Resolver FAILED、其余 NACK）
- ⚠ 遗留：`Future.cancel` 掐不断卡死 I/O，I/O 型 SPI（ExecutionGuard Redis Lua 等）真实化时须自带 client 级超时

#### D4. 可观测性 ✅ 代码侧已完成（2026-08-05）

- `CollectionMetrics` 统一注册：事件发布/消费/耗时、PEL 深度、Stream 长度、DLQ 入列与大小、Consumer 线程池（`ExecutorServiceMetrics`）、跳过原因、SPI 超时；经 `/actuator/prometheus` 暴露
- 消费入口写 MDC（eventId / caseId / planId / stepId）并跨工作线程传递，logback pattern 已输出
- 剩余：Prometheus 抓取、Alertmanager 路由与 Dashboard 属运维交付物（见 T5 手册 §3.1）

---

### 模块 E：应用层 → `collection-admin`

**负责人参考文档：《架构设计文档》§1.7**

#### E1. `TriggerScanner` → 调度订阅 ✅ 代码侧已完成（2026-08-06）

- 生产调度入口为 Cloud Scheduler → 调度专用 Pub/Sub 主题 → 应用侧专用订阅：`PubSubScheduleConsumer`（`SmartLifecycle` 流式拉取，与 `PubSubCaseConsumer` 同款）按消息属性 `job` 路由到 `ScheduledJobRunner`，复用 `PlanStepTriggerPublisher.publishDueSteps()/publishTimeoutSteps()` 与 `DpdStageRollHandler.dailyRoll()`
- 语义不变：仅扫表发事件，毫秒级返回，禁止业务 I/O；调度消费者不得直接调渠道
- 这条链路特有的三条纪律（不可省）：按 `publishTime` 丢弃陈旧消息（默认 60s，`dailyRoll` 300s）、触发后一律 ack 不 nack 重投、按任务进程内单飞
- `@Scheduled` 的 `TriggerScanner` 仅保留给 `local`/`test`；`SchedulerEntrypointValidator` 保证生产只有一个调度入口生效
- 剩余：调度主题/订阅、双向 IAM、Scheduler Job、告警属运维 GCP 交付（见 T5 手册 §3.2 O1–O8）

#### E2. `WebhookController` → 加鉴权

- 统一接收供应商回调，鉴权（Shiro + 供应商签名校验）后发布 `CHANNEL_CALLBACK` 事件
- 当前骨架已实现事件发布，缺鉴权层

#### E3. 管理后台 REST API

- 案件详情 / 触达时间线 / 计划状态 / 合规记录 可视化
- `PlanQueryController` 已有基础查询，按业务需求继续扩展

---

## 五、跨模块契约（改动前请同步对齐）

以下是被多个模块共享的契约文件。**可以演进**（这版只是初版框架），但因为改动会影响所有依赖方，**改前请先在群里同步并与主架构负责人对齐**，由主架构评估对 `collection-engine` 的影响后统一改、统一发版：

### 2026-07-24 Phase 1 SSOT 契约变更通知（须确认）

> 本节记录已裁决的文档契约，**不是**“代码已完成”声明。owner 须在实现或联调前确认；字段定义以 [领域模型](./docs/MOCASA催收系统升级_Phase1_领域模型与数据定义.md) 为 SSOT，执行行为以 [引擎↔渠道执行契约](./docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md) 为 SSOT。

| 接收方 / owner | 已裁决契约 | 需确认 / 实施动作 |
| --- | --- | --- |
| `collection-channel` | PUSH token 统一为 `device.jpushToken`（JPush Registration ID），不使用 `fcmToken`；token 为空且有手机号时仅允许同一次 dispatch 内 Push→SMS fallback | 确认 Adapter / Resolver 不再读取或要求 `fcmToken`，且 fallback 不查库 |
| `collection-channel` | `StepResolver=null` 仅策略性跳过；空地址由 `ExecutionGuard` 返回 `NO_EMAIL` / `NO_PHONE` / `NO_TOKEN`，引擎写 `COMPLIANCE_BLOCKED` 后推进 | 在真实 Guard 实现该规则；Resolver 不得用 null 表达空地址 |
| `collection-channel` | SMS / PUSH / EMAIL 均成功 dispatch 即完成，`observationMinutes=0`；AI_CALL 等回调 | 确认 PlanFactory 不为三消息渠道生成观察期或 DLR 完成路径 |
| `collection-channel` | `t_contact_timeline` 只由核心引擎写；channel 只返回 `StepResult` | 移除/禁止 channel 对 timeline 的直接写入 |
| `collection-channel` | **渠道失败三分类**：可证明未发出（`retryable=true`）／结果未知（`retryable=false`）／确定性失败（`retryable=false`）。判定边界是请求字节是否已写给供应商，不是「返回还是抛异常」。`NotificationClient` 的渠道侧短重试已收窄到只重可证明未发出的故障——读超时与 5xx 不再重试 | 新增渠道 / Adapter 按 `HttpFailureClassifier.provablyNotSent` 分档；不得把 socket 超时或 5xx 判为可重试 |
| `collection-channel` | **新增 `StepCommand.providerIdempotencyKey`** = `{planId}:{stepOrder}`，跨引擎重试稳定；尝试级 `idempotencyKey` 保持 `{planId}:{stepOrder}:{retryCount}` 不变 | 确认通知中心 / SendGrid / AI Call 合作方是否提供幂等或去重字段及字段名，确认后把该键透传给供应商。**Phase 1 只建键，不据此放开「结果未知后重试」** |
| `collection-service` | `caseId` 统一是信贷 `loan_id`（Long）；旧库关联使用 `t_collection.loan_id` | 确认 Mapper、CaseService 查询和对账不使用 `t_collection.id` 作为 caseId |
| `collection-service` | CaseService 为 ingestion 提供缺失金融字段的只读回填（`dpd` / `product` / `totalOutstanding` / `penaltyAmount` / `dueDate`），并为引擎提供实时还款守卫 | 暴露或确认只读查询能力；回填不得覆盖 payload 的 phone / email / jpushToken |
| `collection-channel` | **`AI_CALL` 供应商解绑 LTH**：`AI_CALL` 由独立 AI Call 合作方承接，其底层是否复用 LTH SIP 线路属该合作方内部实现，对引擎与契约不可见；`PredictiveDialerService` javadoc 已同步去除 LTH 绑定 | Adapter / Webhook / 配置键命名不以 LTH 为前提；dispatch 成功须回传合作方任务标识并落 `t_contact_timeline.provider_msg_id`，否则 Phase 2 对账无锚点（[引擎 §4.3.4](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md#434-callback_timeout)）；`metadata.timeoutMinutes` 按合作方实际回调时延校准，不沿用 LTH 口径 |

```
collection-common/src/main/java/com/collection/common/spi/        # 5 个 SPI 接口
collection-common/src/main/java/com/collection/common/channel/    # ChannelGateway
collection-common/src/main/java/com/collection/common/event/      # CollectionEventBus + CollectionEvent（新增事件类型在此）
collection-common/src/main/java/com/collection/common/dto/        # 5 个 DTO（入参/出参）
collection-common/src/main/java/com/collection/common/model/      # 领域模型（计划/步骤/状态字段）
collection-common/src/main/java/com/collection/common/enums/      # 所有枚举（PlanStatus / EventType / Stage 等）
```

> 各模块**内部**的代码（Mock 替换、私有实现、模块自有的表与类）无需对齐，自行决定。

---

## 六、规格文档索引

| # | 文档 | 覆盖内容 |
|:---:|---|---|
| 1 | 产品需求文档 (PRD) | 业务目标、功能需求、渠道选型、合规约束 |
| 2 | 架构设计文档 | 分层、SPI 边界、关键机制、技术栈 |
| 3 | 核心引擎规格 | 事件路由、状态机、步骤管线、SPI 接口完整定义 |
| 4 | 领域模型与数据定义 | 模型字段、枚举值、DDL |
| 5 | 基础设施交互规范 | Redis / 定时调度（Cloud Scheduler → Pub/Sub → 应用订阅）/ Repository、配置、可观测性 |
| 6 | 渠道编排规格 | 计划状态机、决策规则、合规检查、渠道适配器、模板 |
| 7 | 数据接入规格 | PubSub 消费、消息路由、清洗写库、DPD 日切、迁移双写（事件 payload→领域 §9；总线运行时→基础设施 §2） |
| 8 | 运维与协作 | 指标、告警、Grafana Dashboard |

---

> 有任何接口疑问或跨模块对齐需求，联系主架构负责人后再动代码。
