# Intelligent Collection V1 — MOCASA 催收系统升级 Phase 1

MOCASA 催收系统升级（Phase 1），当前仅覆盖**菲律宾市场**。本仓库包含 **规格文档** 与 **Phase 1 框架代码骨架**（接口先行 + Mock SPI 全链路跑通）。

| 项 | 说明 |
|---|---|
| **套件版本** | Phase 1 · v1.0 |
| **技术栈** | Spring Boot 2.1 · Java 8 · MyBatis · Maven 多模块 |
| **事件总线** | Phase 1 默认内存版（无需 Redis）；接口抽象，可平滑切换 Redis Stream |

---

## 一、规格文档（docs/）

建议按依赖关系自上而下阅读：

| 顺序 | 文档 |
|:---:|---|
| 1 | [产品需求文档 (PRD)](./docs/MOCASA催收系统升级_Phase1_产品需求文档_PRD.md) |
| 2 | [架构设计文档](./docs/MOCASA催收系统升级_Phase1_架构设计文档.md) |
| 3 | [核心引擎规格](./docs/MOCASA催收系统升级_Phase1_核心引擎规格.md) |
| 4 | [领域模型与数据定义](./docs/MOCASA催收系统升级_Phase1_领域模型与数据定义.md) |
| 5 | [基础设施交互规范](./docs/MOCASA催收系统升级_Phase1_基础设施交互规范.md) |

---

## 二、代码框架（Phase 1 骨架）

### 模块结构

```text
collection-parent (pom)
├── collection-common      契约层：枚举 / 模型 / DTO / 5个SPI接口 / CollectionEventBus / ChannelGateway / Repository接口
├── collection-engine      ★主框架★ EventConsumerDispatcher · PlanLifecycleManager(状态机) · StepExecutionOrchestrator(七步管线) · PreFlightChecker · 内存事件总线/幂等
├── collection-service     数据服务层：新表 MyBatis 持久化 + CaseService/ProfileService（Phase 1 Mock）
├── collection-channel     渠道编排：5个SPI 的 Mock 实现 + Mock ChannelGateway
├── collection-ingestion   数据接入：发布领域事件（Phase 1 Mock 入案）+ DPD 日切 Job 占位
└── collection-admin       启动入口：REST 触发/查询 · Webhook · Trigger-to-Event 调度 · 装配全部模块
```

### 分层与文档映射

| 模块 | 文档归属 | Phase 1 状态 |
|---|---|---|
| collection-engine | 核心引擎规格 §1-§5 | **真实实现**（主框架，本仓库负责人维护） |
| collection-channel | 渠道编排规格 | Mock，待渠道编排负责人替换 |
| collection-ingestion | 数据接入与事件规格 | Mock，待数据接入负责人替换 |
| collection-service | 领域模型 §3 | Mock CaseService/ProfileService，待服务层负责人映射旧表 |
| collection-admin | 架构 §1.5 | REST/Webhook/调度骨架 |

---

## 三、环境要求

- JDK 8、Maven 3.6+
- 可连通的 MySQL（Phase 1 新测试库 `ai_collection_db`）
- **不需要 Redis**（Phase 1 链路用内存事件总线）

---

## 四、快速开始（链路跑通）

### 1. 建表

```bash
mysql -h34.124.218.94 -uai_collection -p -P3306 ai_collection_db < db/schema.sql
# 可选 mock 数据
mysql -h34.124.218.94 -uai_collection -p -P3306 ai_collection_db < db/mock-data.sql
```

> 数据库连接默认值在 `collection-admin/src/main/resources/application.properties`，
> 可用环境变量覆盖：`DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD`。

### 2. 编译 & 启动

```bash
mvn clean package -DskipTests
java -jar collection-admin/target/collection-admin.jar
# 或：mvn -pl collection-admin -am spring-boot:run
```

### 3. 注入案件，触发全链路

```bash
# 注入新案件（caseId=1001），引擎建计划并开始执行
curl -X POST "http://localhost:8080/mock/ingest?caseId=1001&userId=1001&stage=S1"

# 查询计划状态（含步骤序列）
curl "http://localhost:8080/plans/1"

# 查询触达时间线
curl "http://localhost:8080/plans/timeline/1001"
```

链路：`CASE_INGESTED → PlanFactory 建 3 步计划(PENDING) → 扫描器发 PLAN_STEP_DUE → 七步管线 → Mock 渠道发送 → STEP_COMPLETED → 推进 → … → PLAN_COMPLETED`。
扫描间隔默认 5s（`collection.scan.interval-ms`），步骤间有 1 分钟延迟，可改小延迟/间隔加速观测。

### 4. 验证中断 / 回调链路

```bash
# 还款中断：取消该用户活跃计划（plan → PLAN_CANCELLED, REPAID）
curl -X POST "http://localhost:8080/mock/repayment?userId=1001&caseId=1001"

# 阶段变更：取消旧阶段计划 + 创建新阶段计划
curl -X POST "http://localhost:8080/mock/stage-changed?caseId=1001&stage=S2"

# 异步渠道回调（用于 AI_CALL/TTS 步骤；需计划含异步步骤时）
curl -X POST "http://localhost:8080/webhook/channel-callback?planId=1&stepId=3&result=ANSWERED"
```

观测：直接查 `t_contact_plan` / `t_contact_plan_step` / `t_contact_timeline`（见 `db/mock-data.sql` 末尾参考 SQL）。

---

## 五、各开发者接续指南（契约冻结后并行）

`collection-common` 的 SPI 接口 + DTO + 枚举为**编译期契约**，替换 Mock 即可接续，主框架零改动。

| 负责人 | 替换目标 | 位置 |
|---|---|---|
| 渠道编排 | `MockPlanFactory` / `MockExecutionGuard` / `MockStepResolver` / `MockAdvancementPolicy` / `MockExhaustionPolicy` / `MockChannelGateway` | `collection-channel` |
| 数据接入 | `IngestionService`（接真实 PubSub + 快照生成）、`DpdStageRollHandler` | `collection-ingestion` |
| 服务层 | `MockCaseService` / `MockProfileService`（映射 t_collection / t_user_* 等旧表） | `collection-service` |
| 基础设施 | 新增 `RedisStreamEventBus` / Redis `IdempotencyService`，配置 `collection.eventbus=redis` 切换 | `collection-engine`（或独立模块） |
| 应用层 | `TriggerScanner` 替换为 XXL-Job Handler、Webhook 加鉴权、补管理后台 API | `collection-admin` |

> 替换方式：实现对应接口并加 `@Component`/`@Primary`，或删除同名 Mock 类。Spring 按接口注入，引擎无需改动。

---

## 六、Phase 1 骨架的已知简化（非生产实现，待补全）

- 事件总线为内存版（生产用 Redis Stream + Consumer Group + PEL/看门狗/DLQ，见基础设施规范 §1-§2）
- 幂等锁为内存版（生产用 Redis SETNX）
- SPI 硬超时（10-50ms）未启用线程级强制超时，仅异常捕获（见核心引擎规格 §4.1）
- 调度用 `@Scheduled` 代替 XXL-Job；MDC 跨线程传递、Micrometer 指标未接入
- `CaseService`/`ProfileService` 为合成 mock，未映射真实旧表
