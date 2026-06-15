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
| `db/schema.sql` | 引擎核心表 DDL（t_contact_plan / t_contact_plan_step / t_decision_log / t_contact_timeline / t_user_profile_ext） | ✅ 已在测试库执行 |

### 已测试通过

- `mvn clean package -DskipTests` → BUILD SUCCESS（JDK 8 / Maven）
- Spring Boot 启动、8080 端口、9 个事件 handler 注册、Hikari 连上测试库
- `POST /mock/ingest?caseId=1002` → 计划落库、状态机推进（PENDING → STEP_EXECUTING → STEP_SCHEDULED）、触达时间线写入
- 端到端链路：`CASE_INGESTED → 建计划 → PLAN_STEP_DUE → 七步管线 → Mock 渠道 → STEP_COMPLETED → 推进 → PLAN_COMPLETED` 完整闭环

### 当前简化项（非生产实现）

| 项 | 当前 | 生产目标 |
|---|---|---|
| 事件总线 | 内存版 | Redis Stream + Consumer Group + PEL/看门狗/DLQ（基础设施规范 §1-§2） |
| 幂等锁 | 内存版 | Redis SETNX（基础设施规范 §3） |
| 调度 | Spring `@Scheduled` | XXL-Job Handler（基础设施规范 §4） |
| SPI 硬超时 | ✅ 已实现：`SpiInvoker` 线程级强制超时（`Future.get`，默认 50/20/50/10/50ms，可配） | 切 Redis 后 I/O 型 SPI 另配 client 级超时作第一道防线 |
| 案件/画像服务 | 合成 Mock 数据 | 映射真实旧库（t_collection 等） |
| 可观测性 | 无 | Micrometer + MDC 跨线程（基础设施规范 §6） |

---

## 二、快速启动（所有开发者阅读）

### 环境要求

- JDK 8、Maven 3.6+
- 可访问测试库 `ai_collection_db`（连接信息向主架构负责人获取，**不写入仓库**）
- **暂不需要 Redis**：事件总线/幂等当前是内存版临时实现，仅用于先把链路跑通；Redis Stream 尚未接入，待基础设施开发者完成后（模块 D）即切换为依赖 Redis

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

步骤执行前合规校验（频率 / 时段 / 放弃率）。

- **接口**：`com.collection.common.spi.ExecutionGuard`
- **方法签名**：`GuardVerdict evaluate(ExecutionContext)`
- **关键约束**
  - 硬超时 20ms（单次 Redis Lua 脚本完成计数器读取+增加+TTL）
  - Redis key 前缀：`compliance:daily:{userId}:{channel}:{date}`
  - 拦截时返回 `GuardVerdict.block(reason, ruleType)`，引擎自动 SKIPPED + 推进下一步
  - 不允许返回 `null`

#### A3. `MockStepResolver` → `DefaultStepResolver`

Guard 通过后决定具体渠道 + 模板 + 目标地址，组装 `StepCommand`。

- **接口**：`com.collection.common.spi.StepResolver`
- **方法签名**：`StepCommand resolve(ExecutionContext)`
- **关键约束**
  - **零 DB I/O**，只读 `ExecutionContext.contextSnapshot`
  - `metadata` 里 `callbackUrl` / `timeoutMinutes` 对异步渠道（AI_CALL/TTS）必填
  - 硬超时 50ms；不允许返回 `null`（应抛异常触发 FAILED）

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
- **8 渠道**：SMS / PUSH / EMAIL / VIBER / WHATSAPP / AI_CALL / TTS / HUMAN_CALL
- **关键约束**
  - 消息类（SMS/PUSH 等）→ `success=true, DELIVERED`；异步类（AI_CALL/TTS）→ `success=true, DELIVERED`（真实结果等 Webhook 回调）
  - 渠道内部熔断/fallback 对引擎完全透明；抛异常引擎一律视为 `retryable`
  - 供应商错误码统一映射为 `StepResult.errorCode`

#### 需新建的渠道编排表（领域模型 §8，DDL 待补充到 `db/schema.sql`）

`t_contact_plan_template` / `t_strategy_rule` / `t_compliance_rule` / `t_compliance_violation` / `t_channel_config` / `t_call_task` / `t_call_task_number` / `t_agent_status`

---

### 模块 B：数据接入 → `collection-ingestion`

**负责人参考文档：《数据接入与事件规格》**

#### B1. `IngestionService` → 真实 PubSub 消费

- 接 GCP PubSub，消费上游信贷系统推送（case_push / repayment / assign_signal）
- 清洗数据 → 写 `t_collection`（EXISTING 表，Phase 1 不变更）
- 生成 `ContextSnapshot`（调 `CaseService.buildContext` + `ProfileService.getFullProfile`），序列化为 JSON
- 发布 Redis Stream 领域事件（CASE_INGESTED / STAGE_CHANGED / REPAYMENT_RECEIVED）
- 现有 `IngestionService.ingestCase()` / `repayment()` 等方法是骨架，真实消费在此替换

#### B2. `DpdStageRollHandler.dailyRoll()` → 实现日切逻辑

- 每日 0:05 PHT（XXL-Job）重算 Max DPD
- DPD 1–90 且阶段变化 → 发 `STAGE_CHANGED`
- DPD ≥ 91 且未停催 → 写 `collection_status=CEASED` + 发 `CASE_CEASED`（对齐待办 E1/E2）
- **不改引擎 Consumer，引擎只消费事件**

---

### 模块 C：数据服务 → `collection-service`

**负责人参考文档：《领域模型与数据定义》§3**

#### C1. `MockCaseService` → 真实 `CaseService`

- `getCaseInfo(caseId)` → 查 `t_collection`，填充 `CaseInfo`（含实时还款状态 `isRepaid`、冻结状态 `isFrozen`）
- `buildContext(caseId)` → 多表 JOIN（`t_collection` + `t_user_repayment_plan`）构建 `CaseContext`
- `buildContactHistory(userId, caseId)` → 聚合 `t_contact_timeline` 构建 `ContactHistory`
- `getContextSnapshot(caseId)` → 读 `t_contact_plan.context_snapshot`，JSON 反序列化
- `isRepaid(caseId)` → 实时查还款状态（PreFlightChecker 和 PTP 处理调用）

#### C2. `MockProfileService` → 真实 `ProfileService`

- `getFullProfile(userId)` → 映射 8+ 张旧表（`t_user_basis` / `t_user_work` / `t_user_telephone_book` / `t_user_equipment` / `t_user_profile_ext`）
- Phase 1 未填充的字段返回 `null`；所有 SPI 实现和模板渲染须做 **null 防御处理**

---

### 模块 D：基础设施 → `collection-engine`（或新独立模块）

**负责人参考文档：《基础设施交互规范》§1–§6**

#### D1. `RedisStreamEventBus` → 替换内存事件总线

- 实现 `CollectionEventBus` 接口：`publish()`（XADD）、`subscribe()`（XREADGROUP Consumer Group）
- 配置切换：`collection.eventbus=redis`，业务代码**零改动**
- 须实现：Consumer 线程池（8 线程）、PEL 拾取（防崩溃丢消息）、看门狗（防连接假死）、DLQ（死信队列）

#### D2. `RedisIdempotencyService` → 替换内存幂等

- 实现 `IdempotencyService` 接口：Redis SETNX + TTL
- 配置切换：`collection.idempotency=redis`
- key 格式：`lock:plan:{step_idempotency_key}`

#### D3. SPI 硬超时强制执行 ✅ 已完成（2026-06-11）

- 引擎调用 5 个 SPI 经 `SpiInvoker`（`engine.spi`）用 `Future.get(timeoutMs)` 强制超时（核心引擎规格 §4.1）
- 各接口超时阈值：PlanFactory 50ms / ExecutionGuard 20ms / StepResolver 50ms / AdvancementPolicy 10ms / ExhaustionPolicy 50ms（`engine.spi.*-timeout-ms` 可配）
- 单个共享有界线程池（大小=Consumer 池，预热）+ `MdcTaskDecorator` 语义跨线程传递 MDC；超时转 `SpiTimeoutException`，失败语义沿用调用方 try-catch（Guard fail-close、Resolver FAILED、其余 NACK）
- ⚠ 遗留：`Future.cancel` 掐不断卡死 I/O，I/O 型 SPI（ExecutionGuard Redis Lua 等）真实化时须自带 client 级超时

#### D4. 可观测性

- 注册 Micrometer 指标（事件发布/消费/耗时/Consumer 线程利用率/Stream 积压量/DLQ 大小）
- 关键路径日志加 SLF4J MDC（caseId / planId / stepId / eventType / eventId），Consumer 跨线程用 `MdcTaskDecorator`

---

### 模块 E：应用层 → `collection-admin`

**负责人参考文档：《架构设计文档》§1.5**

#### E1. `TriggerScanner` → XXL-Job Handler

- 把 `@Scheduled` 替换为 XXL-Job Handler（`planStepDueHandler` / `callbackTimeoutHandler` / `ptpExpiredHandler`）
- 语义不变：仅扫表发事件，毫秒级返回，禁止业务 I/O
- 扫描超出 LIMIT=1000 时触发告警

#### E2. `WebhookController` → 加鉴权

- 统一接收供应商回调，鉴权（Shiro + 供应商签名校验）后发布 `CHANNEL_CALLBACK` 事件
- 当前骨架已实现事件发布，缺鉴权层

#### E3. 管理后台 REST API

- 案件详情 / 触达时间线 / 计划状态 / 合规记录 可视化
- `PlanQueryController` 已有基础查询，按业务需求继续扩展

---

## 五、跨模块契约（改动前请同步对齐）

以下是被多个模块共享的契约文件。**可以演进**（这版只是初版框架），但因为改动会影响所有依赖方，**改前请先在群里同步并与主架构负责人对齐**，由主架构评估对 `collection-engine` 的影响后统一改、统一发版：

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
| 5 | 基础设施交互规范 | Redis / XXL-Job / Repository、配置、可观测性 |
| 6 | 渠道编排规格 | 计划状态机、决策规则、合规检查、渠道适配器、模板 |
| 7 | 数据接入与事件规格 | 事件总线契约、PubSub 消费、迁移 |
| 8 | 运维与协作 | 指标、告警、Grafana Dashboard |

---

> 有任何接口疑问或跨模块对齐需求，联系主架构负责人后再动代码。
