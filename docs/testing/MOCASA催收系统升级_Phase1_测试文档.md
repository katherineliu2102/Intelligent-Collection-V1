# MOCASA 催收系统升级 — Phase 1 测试 SSOT

> 版本：Phase 1 · 测试阶段模型 v3.0
> 范围：仅菲律宾市场。
> 文档定位：本文件是 Phase 1 测试的唯一 SSOT，只定义**准入、用例、出口与当前状态**。
> 禁止事项：本文件不保存一次运行的完整日志、不替代环境 Runbook、不保存凭证，也不授权对真实用户触达。

---

## 1. 术语与范围

### 两个正交维度

| 维度 | 定义 | 取值 |
|---|---|---|
| 测试层级 | 被测集成边界 | L0、L0c、L1、L2、L3、L4a、L4b |
| 测试阶段 | 准入顺序与环境成熟度 | T0、T1、T2、T3a、T3b、T4、T5、T6 |

**Mock（替身）是测试策略，不是测试层级。** 它可用于 SPI、Repository、事件源、供应商或测试数据；必须在每条用例的“允许替身”列中说明。不得把 “Mock” 写成与 L0–L4 并列的层级。

| 层级 | 定义 | 当前主载体 |
|---|---|---|
| L0 | 引擎纯逻辑单测；SPI/Repo/EventBus 可替身 | `collection-engine` unit tests |
| L0c | 渠道纯逻辑单测；Adapter/策略映射 | `collection-channel` unit tests |
| L1 | 引擎内存集成；真实引擎组件 + 内存总线/仓储 | `FullChainIntegrationTest` 等 |
| L2 | 引擎↔渠道执行契约；双方以同一契约断言对接 | `ChannelContractL2RealSpiTest` + `ProductionChannelContractL2Test`（C1–C7 真实实现），`ChannelContractL2Test` 为契约替身基线 |
| L3 | 真实 MyBatis/MySQL 持久化集成 | admin/service integration tests |
| L4a | 合成数据源 + 真实渠道的端到端验证 | `l4a-official-test.sh` |
| L4b | 隔离真实来源 + 真实渠道的端到端联调 | PubSub/旧库/DB/渠道组合 |

### 事实来源与职责边界

| 信息 | 权威来源 | 本文的处理方式 |
|---|---|---|
| 测试准入、用例、出口、当前状态 | **本文** | 唯一 SSOT |
| 领域、契约、数据接入语义 | 核心引擎规格、契约文档、数据接入规格 | 作为断言依据引用，不复制为第二份规格 |
| 渠道 `TC-*` 细节与供应商参数 | 渠道功能测试指南 | 本文仅索引 TC-ID |
| L4b topic、订阅、Nacos、凭证、操作 | [L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | Runbook，不承担测试裁决 |
| 单次执行日志、测试结果 | 历史报告/日志 | 证据，不改变本文状态 |

### 通用状态与证据规则

| 标记 | 含义 |
|---|---|
| ✅ | 用例已满足退出条件，且有可追溯证据 |
| 🟡 | 可运行或部分通过，但存在明确未关闭项 |
| ⬜ | 未满足准入或未执行 |
| ⏭ | Phase 2 或范围外 |
| ❓ | 事实待核对；不得据此放行下一阶段 |

每个状态必须能指向命令输出、JUnit/CI 记录、SQL/日志摘要或一次运行报告；报告事实只能更新“证据”列，**不得自行覆盖本文的当前状态**。

---

## 2. T0 环境预检

### 目标

证明所选环境、隔离范围、配置与可观测性允许开始后续测试；T0 不验证业务语义，不触达真实用户。

### 准入与用例

| ID | 预检项 | 允许替身 | 必跑命令/操作 | 证据 | Owner | 当前状态 |
|---|---|---|---|---|---|---|
| T0-1 | 本地/CI JDK、Maven、模块可发现 | 不适用 | `mvn -B -ntp clean test` | CI job | 主架构 | ✅ |
| T0-2 | L4a App、Nacos、健康检查 | 仅合成案 | `/actuator/health`、启动日志 | health=UP、配置加载日志 | 主架构 | ✅ |
| T0-3 | L4b 联调隔离、白名单、渠道沙箱 | 合成 loan_id/测试地址 | `./scripts/test/l4b-preflight.sh --strict`；Nacos 指向 `collection-cases-test1-sub` | preflight 输出、Nacos 片段 | 主架构 + 运维 | ✅：2026-07-27 strict preflight `19 PASS / 0 WARN / 0 FAIL`；订阅 `collection-cases-test1-sub` 为 `ACTIVE` 且指向测试 topic，App、Nacos 沙箱/白名单、fault-injection、MySQL 均通过 |
| T0-4 | 测试订阅**独占消费**（无其他活跃 consumer 抢消息） | 不允许多实例共享同一 test-sub | 开跑前确认无他机 consumer；任选一 loan_id publish 一次，本机日志命中 `[Ingestion]` | 同 loan_id publish 后本地 consumer 必命中 | 运维 | ✅：运维确认独占；2026-07-27 publish 唯一消息后，本机命中 `CASE_INGESTED case=99000000` |
| T0-5 | 数据库表、只读旧库访问、脱敏输出 | 测试 seed | preflight + SQL 连通性 | 表存在、账号范围确认 | 服务同事 + 运维 | ✅：六张业务/审计表及 timeline 审计字段齐备；`t_collection` 6 条 `IC_TEST_%` 行可读写，事务回滚验证无痕 |

### 出口

- T1 可在 T0-1 通过后开始。
- T3a 需要 T0-5。
- T3b 需要 T0-2 和受控测试渠道配置。
- T4 需要 T0-3、T0-4、T0-5；仅可使用独立测试 topic，禁止向生产 `collection-cases` 发布。
- T0-3 验证隔离/白名单，T0-4 验证独占消费，二者不可互相替代。
- T0-1～T0-5 均已通过；L4b 官方闭环已于 2026-07-27 实跑通过（见 [§7](#7-t4-真实来源--真实渠道的隔离联调l4b)）。

---

## 3. T1 CI / Mock：L0、L0c、L1

### 目标与准入

以纯逻辑与内存集成测试验证引擎状态机、七步管线、异常兜底、幂等和异步回调基础语义。替身仅用于隔离外部边界。

| 层 | 覆盖 | 必跑命令 | 当前状态 | Owner |
|---|---|---|---|---|
| L0 | 生命周期、管线、PreFlight、SPI 超时 | `mvn -pl collection-common,collection-engine -am test -Dsurefire.failIfNoSpecifiedTests=false` | ✅ 59 例 | 主架构 |
| L0c | 渠道 Adapter 映射与策略 | `mvn -pl collection-channel -am test` | ✅ 27 例 | 编排同事 |
| L1 | 内存总线/仓储的全链路和回调 | 同 L0 命令 | ✅ 6 例 | 主架构 |
| CI 全仓 | 格式门禁与全仓单测 | `mvn -B -ntp clean test` | ✅ | 各模块 owner |

### 必测用例集合

| 域 | 用例/资产 | 退出条件 |
|---|---|---|
| 生命周期 | `PlanLifecycleManagerTest`；入案、还款、升档、停催、重建 | 状态迁移和取消原因正确 |
| 七步管线 | `StepExecutionOrchestratorTest`、`PreFlightCheckerTest` | Guard/Resolver/Gateway 的 fail-close、retryable、终态拦截正确 |
| SPI | `SpiInvokerTest` | 硬超时、异常透传、MDC 传递正确 |
| 内存闭环 | `FullChainIntegrationTest`、`AsyncCallbackChainL1Test` | 事件到计划/步骤推进闭环正确 |
| 渠道逻辑 | channel `strategy/*`、`adapter/*` | 映射与本地策略断言通过 |

### 出口

所有命令必须通过；新增或变更契约语义时，T1 绿不是 T2 免检。未覆盖真实并发/跨存储故障项保留为 L3/T5 或 Phase 2 差集。

---

## 4. T2 引擎↔渠道契约：L2

### 目标

冻结引擎调用渠道的语义边界，验证真实渠道实现接入后不改变引擎对 `PlanFactory`、`ExecutionGuard`、`StepResolver`、`ChannelGateway`、推进/穷尽策略的约束。

| ID | 契约主题 | 允许替身 | 验证要点 / 当前证据 | 状态 | Owner |
|---|---|---|---|---|---|
| C1 | 计划结构、步骤顺序与成功落库 | 仅供应商 HTTP | SMS→PUSH→EMAIL 全部 `COMPLETED`，计划完成；3 条 timeline 含 `providerMsgId` 与审计元数据，且无 PII 摘要 | ✅ | 主架构 + 编排同事 |
| C2 | PUSH 无 token → 同槽 fallback SMS | 仅供应商 HTTP | 仅发 1 次 SMS、0 次 Push；timeline 仍记 `PUSH` | ✅ | 主架构 + 编排同事 |
| C3 | Guard block → SKIPPED | 无 | `NO_PHONE` → `SKIPPED` + `COMPLIANCE_BLOCKED`，无出网 | ✅ | 主架构 + 编排同事 |
| C4/C5 | 渠道异常与重试 | 仅供应商 HTTP | 503 → `PENDING`/退避/不写 timeline；业务拒绝 → `FAILED` + 终态 timeline | ✅ | 主架构 + 编排同事 |
| C6 | 步骤完成/推进语义 | 仅供应商 HTTP | EMAIL 同步完成，不进入 `STEP_WAITING` | ✅ | 主架构 + 编排同事 |
| C7 | 重复 due 不重复 dispatch | 仅供应商 HTTP | 单次供应商请求、单条 timeline、步骤保持终态；迟到 due 为 no-op | ✅ | 主架构 + 编排同事 |
| L2-CB | AI_CALL 异步回调契约 | 不允许长期替身 | 尚无真实 AI_CALL Adapter；Phase 1 L4a/L4b 不含该渠道，非 T4 阻塞 | ⬜ | 编排同事 + 主架构 |

| 证据层 | 测试类 | 覆盖 |
|---|---|---|
| 渠道子图 | `ProductionChannelContractL2Test`（channel，7 tests） | 真实 PlanFactory/Guard/Resolver/Gateway/Adapter 的命令构造、结果映射、渠道幂等 |
| 引擎状态推进 | `ChannelContractL2RealSpiTest`（engine，8 tests） | 真实 SPI 驱动的状态、timeline 审计、退避重试；覆盖 C1–C7/C7b |
| 契约基线 | `ChannelContractL2Test`（engine，7 tests） | 编码契约替身下的回归基线 |

| 已发现缺陷 | 根因与影响 | 修复 / 回归保障 |
|---|---|---|
| C7 终态步骤回退（2026-07-27） | 重复 `PLAN_STEP_DUE` 在幂等锁前将终态步骤改回 `EXECUTING`；扫描重叠、重投或多实例下可能造成重复外呼 | `StepStatus.isTerminal()` 直接 no-op；幂等 TTL 取 `max(idempotencyTtl, callbackTimeout)`，覆盖异步回调窗口 |

| AI_CALL 回调差集 | 当前结论 | 后续闭合 |
|---|---|---|
| Adapter | 仅 SMS/PUSH/EMAIL 三个真实 Adapter；AI_CALL 只能落 Mock，不能以 Mock 改绿 | 编排补 AI_CALL/LTH 下单与回调签名；主架构补超时、幂等、终态断言 |
| 受理证据 | SMS/PUSH/Email 以 `DELIVERED + providerMsgId` 为证据，不要求回调审计行 | AI_CALL 的异步回调由 L2-CB 单独闭合，L4a/L4b 不可替代 |

### 出口

- 2026-07-27 全量 `mvn -o test`：channel 38、engine 90、ingestion 12，均 `0 failures / 0 errors`；JUnit 见各测试类对应的 `target/surefire-reports/TEST-*.xml`。
- 真实 SPI/Gateway 已在 engine 状态机中验证；仅 Notification/SendGrid HTTP 使用 WireMock，未替身渠道逻辑。
- 断言遵循 [引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)。T3 可并行；进入 T4/T5 前关闭影响真实链路的契约差集。

---

## 5. T3a 真实持久化：L3

### 目标

在真实 MySQL/MyBatis 环境中验证计划、步骤、timeline、快照和幂等的持久化语义。L3 不允许用内存 Repository 代替被测持久化边界。

| ID | 用例 | 必跑命令/操作 | 证据 | 状态 | Owner |
|---|---|---|---|---|---|
| L3-1 | plan/step/timeline Mapper 往返与 timeline 幂等 upsert | `mvn -pl collection-admin -am test -Dgroups=integration` | `ContactPlanMapperIT` 2026-07-25 受控 MySQL：9 tests 全绿 | ✅ | 服务同事 + 主架构 |
| L3-2 | `context_snapshot` JSON 往返 | 同上 | `ContactPlanMapperIT` 2026-07-25 受控 MySQL：语义字段断言通过 | ✅ | 服务同事 |
| L3-3 | 取消/完成状态与 `completed_at` | 同上 | `ContactPlanMapperIT` 2026-07-25 受控 MySQL：plan/step 时间戳断言通过 | ✅ | 服务同事 |
| L3-4 | 单活跃计划、并发串行化 | 同上 | 2026-07-25：唯一约束、REBUILD handoff、两连接 `FOR UPDATE` 阻塞/释放断言通过 | ✅ | 服务同事 + 主架构 |
| L3-5 | due scanner 查询与状态推进 | 同上 | 2026-07-25：Mapper 查询 + `PlanStepTriggerPublisherIT` scan→event bus→dispatcher 的 due/timeout 状态断言通过 | ✅ | 主架构 + 服务同事 |

### 当前技术障碍

**Mapper/DDL 本身不是主障碍**——L4b 手工联调已证明 `ContactPlanRepositoryImpl`、相关 Mapper 与 `db/schema.sql` 可读写真实 MySQL。L3 未闭合的原因在**自动化与门禁**，不在“能不能落库”：

| 障碍 | 说明 | 影响 |
|---|---|---|
| 专用环境依赖 | 2026-07-25 受控 MySQL：`ContactPlanMapperIT` 9 tests、`PlanStepTriggerPublisherIT` 2 tests 全绿；Surefire 默认排除 `integration` | 默认 CI 保持不连库；后续变更仍须在专用环境复跑并保存 JUnit/SQL 证据 |
| 环境依赖 | 需可达 MySQL；连接信息不入仓，CI 默认不连库 | 本地/专用集成环境可跑，默认 CI 不能代替 L3 出口 |
| 用例缺口 | L3-1…5 自动化已覆盖；真实渠道回调、PubSub NACK 与跨存储故障不属于 L3 | 分别由 T2/L4b/T5 覆盖，不得以 L3 替代 |

**结论**：L3-1…5 已在受控 MySQL 自动化收口。仅 L4b 手工 SQL 证据不能替代 L3 出口，但可作为后续 T4 的附加证据。

### 受控 MySQL 执行

集成测试只接受 MySQL 8+ 的非生产受控库；连接信息仅通过环境变量注入，不写入仓库。先在该库应用 `db/schema.sql`，再执行：

```bash
export L3_IT_DB_URL="jdbc:mysql://HOST:3306/ai_collection_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Manila"
export L3_IT_DB_USER="ai_collection"
export L3_IT_DB_PASS="从主架构负责人获取"
mvn -pl collection-admin -am test -Dgroups=integration
```

`ContactPlanMapperIT` 的 mapper 用例默认在单一 MyBatis session 内回滚；跨连接锁测试和 `PlanStepTriggerPublisherIT` 为跨连接可见性而提交 sentinel 数据，并在 `finally` 按专用 caseId 删除。默认 CI 通过 Surefire 排除 `integration` 标签，仍保持不连库。

### 出口

真实 DB 上的上述用例全绿，连接信息不入仓。T3a 与 T3b **可并行**；T4 必须等待二者均通过。

---

## 6. T3b 真实渠道 + 合成数据源：L4a

### 目标与边界

L4a 使用 `MockTriggerController`/`*CaseRegistry` 的合成案件驱动真实渠道，验证引擎到渠道的完整投递链路。它不接真实 PubSub、真实旧库或 L3 落库；不得将合成数据源误称为“Mock 测试层级”。

当前装配采用临时 `Default*`/`ConfigurableExecutionGuard` 与真实 `DefaultStepResolver`、`ChannelGatewayImpl`。因此 L4a 是**临时实现可运行的全链路验证**，不是编排生产策略已评审完毕的证明；A1–A6 生产实现替换仍须经过 T2/T3b 回归。

### L4a 用例

| ID | 场景 | 关键断言 | 当前状态 | 证据/命令 |
|---|---|---|---|---|
| L4a-1 | 三渠道顺序完成 | SMS→PUSH→EMAIL、providerMsgId、计划完成 | ✅ | 2026-07-27 `restart-and-l4a.sh`（timeline 三渠道） |
| L4a-2 | PUSH 无 token → SMS fallback | fallback 元数据、SMS 投递、步骤完成 | ✅ | 2026-07-27 `restart-and-l4a.sh` |
| L4a-3 | 还款取消 | `PLAN_CANCELLED(REPAID)`，后续不触达 | ✅ | 2026-07-27 `restart-and-l4a.sh` |
| L4a-4 | 升档取消并新建 | 旧计划 `STAGE_UPGRADE`、新计划正确 | ✅ | 2026-07-27 `restart-and-l4a.sh` |
| L4a-5 | 停催 | `PLAN_CANCELLED(CEASED)`，不重建 | ✅ | 2026-07-27 `restart-and-l4a.sh` |
| L4a-6 | SMS 同步完成冒烟 | 同步 `COMPLETED`，不进入 `WAITING` | ✅ | 2026-07-27 `restart-and-l4a.sh` |
| L4a-7 | 重复 ingest 幂等 | 仅一个活跃计划、仅一轮投递 | ✅ | 2026-07-27 `restart-and-l4a.sh`（独立 case=92002） |
| L4a-8 | Email scriptSlot × stage | S0/S1/S2 preview scriptSlot；S0 SMS、S1/S2 Email 的 providerMsgId | ✅ | 2026-07-27 `restart-and-l4a.sh`（stage 关联计划与 timeline） |
| L4a-G | Guard / rebuild | NO_PHONE、FREQUENCY、穷尽路径 | ✅ | 2026-07-27 `restart-and-l4a.sh` |

**L4a-6 裁决：** 旧版“消息类观察期（`observation-minutes=1`、等待约 90 秒）”已于 2026-07-18 撤销。Phase 1 SMS/PUSH/EMAIL 观察期为 0；不得再将该旧观察期 curl、配置或等待时间作为 L4a 用例。

### 必跑命令与出口

```bash
# App 已启动时
./scripts/test/l4a-official-test.sh

# 重启、编译、启动后执行
./scripts/test/restart-and-l4a.sh
```

出口已满足：2026-07-27 以 `COLLECTION_CASE_SERVICE=mock`、`ENGINE_SPI_TIMEOUT_ENABLED=false` 运行 `restart-and-l4a.sh`，固定案例先清理并获得 `PASS=25 FAIL=0`；L4a-8 将 preview 的 scriptSlot 与实际计划的 stage、channel、providerMsgId 关联验证。输出位于 `logs/run/l4a.last.log`，应用日志位于 `logs/run/admin.log`。T3b 已完成；T3a 也已闭合，因此下一授权阶段为 T4/L4b 的严格预检与隔离真实来源联调。

---

## 7. T4 真实来源 + 真实渠道的隔离联调：L4b

### 目标

L4b 将 L4a 的合成入口替换为**真实 PubSub 消费 + 真实旧库 seed + 真实 MySQL 落库 + 渠道沙箱**，验证“来源真实性”与 L4a 已验过的渠道语义在落库后可复现。

### 隔离如何实现（方案 B）

“隔离真实来源”指：**走真实 GCP PubSub/Consumer/ingestion 代码路径，但与生产信贷链路物理隔离**。实现由五层叠加：

| 层 | 做法 | 作用 |
|---|---|---|
| **独立 topic** | 联调用 `collection-cases-test1`，不用生产 `collection-cases` | 旧系统仍消费生产 topic，测试消息**零污染**生产 |
| **独立订阅** | 新系统联调订阅 `collection-cases-test1-sub` | 与生产扇出订阅 `collection-cases-ai-v1-sub` 分离 |
| **发布护栏** | `publish-test-messages.sh` 拒绝向 `collection-cases` 发布 | 防止误操作 |
| **应用白名单** | Nacos `loan-id-whitelist: 99000000–99000005` | Consumer 只处理合成案 |
| **触达沙箱** | `sms-test-mode`、`push-test-token`、受控测试邮箱/手机号 | 真实 adapter 代码、受控投递地址 |

旧库 `t_collection` 用 `db/seed-test-cases.sql` 造数；日切在 L4b 用手动 `POST /mock/daily-roll` 调真实 `DpdStageRollHandler`（XXL-Job 留到 T5/T6）。

### 是否最佳方案

在 Phase 1 约束下（信贷**不能**改共享 topic 发布逻辑；旧系统仍消费 `collection-cases`），**方案 B 是当前最优解**：

| 方案 | 优点 | 为何未选 / 局限 |
|---|---|---|
| **B：独立测试 topic（当前）** | 真实 PubSub 全链路；不碰生产；上线仅改 Nacos `subscription` | 需运维建 topic/IAM；**测不到生产 topic 扇出**（留 T5） |
| A：生产 topic + 白名单 | 拓扑与生产一致 | 测试消息会被**旧系统消费**，已明确禁止 |
| Mock PubSub / 继续 `/mock/ingest` | 无运维依赖 | 不算“真实来源”，不能替代 L4b |
| 信贷改发布逻辑只发测试案 | 可共用生产 topic | Phase 1 **不可行** |

因此 L4b 的“隔离”是**环境级隔离**（独立 topic + 白名单 + 沙箱），不是 mock ingestion。生产等价拓扑（含真实 topic 扇出、XXL-Job）在 **T5** 再验。

### 订阅与快照

| 场景 | topic / subscription |
|---|---|
| L4b 隔离联调 | `collection-cases-test1` / `collection-cases-test1-sub` |
| Pilot/生产并行 | `collection-cases` / `collection-cases-ai-v1-sub`（T5/T6；禁止复用 `collection-cases-sub`） |

快照主路径：

```text
case_push → IngestionService（必要字段回填/清洗）→ CASE_INGESTED payload
→ buildSnapshotFromEvent → t_contact_plan.context_snapshot
```

`CASE_INGESTED` payload 是 L4b-5 主断言来源；`t_collection`/`RealCaseService` 仅用于 ingestion 缺字段回填、还款守卫及可选对账。

### L4b 用例与当前状态

2026-07-27 以 `l4b-official-test.sh` 实跑取证：**PASS=41 FAIL=0 SKIP=0**（run=`20260727-192112`，
证据 `logs/run/l4b-rerun.log` / `logs/run/l4b.last.log`，应用日志 `logs/run/admin.log`）。
本轮 SPI 硬超时按生产默认值启用（`timeoutEnabled=true`，PLAN_FACTORY/STEP_RESOLVER 各 50ms），
全程 **0 次 SPI 超时、0 次 `RESOLVER_ERROR`**。

| ID | 场景 | 真实触发/断言 | 状态 | 实跑证据 |
|---|---|---|---|---|
| L4b-1 | case_push 入案、建计划、投递、timeline | PubSub → plan/step/timeline | ✅ | 5 案各建计划（S0/S1/S2/S3/S4，步数 1/3/4/3/2）；99000005（dpd=95）按 D91+ 拒建 |
| L4b-2 | 还款取消 | `repayment_push_and_load` → REPAID | ✅ | 99000001 经真实 PubSub 还款消息 → `PLAN_CANCELLED`/`REPAID`，且无残留活跃计划 |
| L4b-3 | 日切升档 | 手动 daily-roll → STAGE_UPGRADE | ✅ | 99000002 dpd 4→20：旧 S2 计划 `STAGE_UPGRADE` 取消 + 新建 S3 计划 |
| L4b-4 | 日切停催 | 手动 daily-roll → CEASED | ✅ | 99000003 dpd 20→95：活跃计划 `CEASED` 取消，静置 20s 未重建 |
| L4b-5 | 快照字段溯源 | enriched payload == context_snapshot | ✅ | 99000001/02/04 的 dpd、primaryPhone、stage 与旧库/计划三方一致 |
| L4b-6 | due scanner 执行 | `trigger_time<=now` → 投递/timeline | ✅ | SMS/PUSH/EMAIL 均 `DELIVERED` + 真实 `provider_msg_id`；`script_slot`、`template_version=db:37` 已写入；`content_summary` 无 PII |
| L4b-7 | NACK 重投与幂等 | 真实 PubSub 下游失败→重投一次 | ✅ | 注入命中未 ack → 重投后只新增 1 个计划；静置 30s 无重复，无重复 `provider_msg_id` |
| L4b-8 | 日切幂等 | 同日重复 daily-roll 不重复升档/触达 | ✅ | 收敛后重复 daily-roll：计划数 6、取消数 3、timeline 14 三项均不变 |

**本轮暴露并修复的产品缺陷（非测试脚本问题）**：`ConfigTemplateProvider` 在 `StepResolver` /
`PlanFactory` 热路径上同步查库——`ensureFresh()` 按 TTL 轮询版本号，`getCurrentConfigVersion()`
更是每次调用都无缓存直查 `t_config_version_seq`。跨区域 MySQL 单次往返约 300ms，必然击穿两者 50ms
硬超时（[引擎 §6.1](../MOCASA催收系统升级_Phase1_核心引擎规格.md#61-接口总览) 要求 StepResolver 零 DB I/O），
表现为步骤全量 `FAILED`/`RESOLVER_ERROR`、计划提前进终态，并连带压垮 L4b-4/5/6。
修复：版本轮询与 reload 移到启动预热（`ApplicationReadyEvent`）+ 后台单线程刷新，读方法只读 volatile
缓存；`getCurrentConfigVersion()` 返回**已生效**的缓存版本——这对审计字段 `template_version` 的语义也更准确
（记录本次渲染实际使用的配置版本，而非查询瞬间的库中最新版本）。

> L4a 曾以 `ENGINE_SPI_TIMEOUT_ENABLED=false` 运行，正是该开关掩盖了此缺陷；L4b 按生产默认启用硬超时才暴露。
> 结论：**不应放宽 50ms 阈值**，SPI 侧消除 I/O 才是符合规格的修法。

### `template_version` 语义与溯源边界

2026-07-28 补跑 `L4B_ONLY=1,6` 取证：**PASS=28 FAIL=0 SKIP=0**（证据 `logs/run/l4b-audit.log`）。

首轮 L4b 全部投递被标成 `db:37`，但 `t_config_version_seq.current_version` 是**管理后台每写一次配置就自增的全局
epoch**，不是话术版本号，更不是模板数量。原实现按「DB 配置源整体是否可用」选标记，而
`ScriptLibrary` 是**逐槽位** DB→YAML 回落的，导致 11 条里 9 条把 YAML/SendGrid 的内容误标为 DB 来源。
已改为按该槽位的真实来源标记，本轮三种来源均取到证据：

| 来源 | 标记格式 | 本轮实例 |
|---|---|---|
| DB `t_script_template` 命中 | `db:<该行 config_version>` | `S1_SMS_STANDARD`→`db:1`、`S2_SMS_STANDARD`→`db:35`、`S1_PUSH_STANDARD`→`db:36` |
| 回落 Nacos/YAML | `nacos:<scripts.release-version>` | 库中无 ACTIVE 行的 `S0_DUE_TODAY`、`S3/S4_SMS_STANDARD`、`S2/S3_PUSH_STANDARD` |
| EMAIL（正文托管在 SendGrid） | `sendgrid:<Dynamic Template ID>` | `S4_EMAIL_ENTRY`→`sendgrid:d-658d5be1…` |

全局 epoch 仍保留在 `t_contact_timeline.config_version`，回答"进程当时加载的是第几代配置"，与逐槽位版本互补。

**溯源能力的边界（重要）**：标记能**唯一指认**是哪一版内容，但不总能**复原文本**。
`t_script_template` 的唯一键是 `(tenant_id, script_slot, channel, locale)`，**一槽一行、原地 UPDATE**，
没有内容历史表；`t_config_change_log` 只记 `from_version→to_version` 与一句 `diff_summary`
（形如 `update script SMS/S2_SMS_STANDARD`），**不存新旧正文**。因此：

| 标记 | 能否复原当时正文 |
|---|---|
| `db:N` | 仅当该行仍停在版本 N；一旦被再次编辑，N 版正文在库中已不可得 |
| `nacos:<release>` | 依赖 Nacos 配置历史留存，且要求内容变更时确实 bump `release-version`（当前为人工维护） |
| `sendgrid:d-xxx` | 只锁定模板，不锁定 SendGrid 侧的模板版本 |

`content_hmac` 补的是**校验**而非**复原**：可以证明某份候选文本确实是当时发出的那份，但无法凭它反推正文。
若要做到任意时点完全可复原，需要给 `t_script_template` 增加只写历史表（按
`script_slot + channel + config_version` 留存正文快照）——该项属于管理后台配置治理，不阻塞 T4/T5，建议记入 Phase 2。

### 落库核对时机

**哪些阶段需要查库、哪些不需要：**

| 阶段 | 是否查 MySQL | 说明 |
|---|---|---|
| T0–T2（L0/L1/L2） | **否** | 纯内存/JUnit，无持久化 |
| T3b / L4a | **否** | 合成入口 + 内存仓储；用 API/日志/终端触达断言 |
| T3a / L3 | **是** | 集成测试跑完后查 Mapper 往返（自动化收口前可手工 SQL 抽检） |
| **T4 / L4b** | **是（主路径）** | 每个真实触发动作后按下面节奏查 |

**L4b 推荐操作顺序与查库点：**

```text
① seed 旧库 + 清白名单案落库          → SELECT 确认 9900000x 存在、t_contact_plan 计数为 0
② preflight + 重启 App               → 不查业务表（重启是为清内存幂等标记）
③ publish case（6 案）               → 查 t_contact_plan / _step（L4b-1、L4b-5）
④ 立即停催 99000003（dpd→95 + roll） → 查 cancel_reason=CEASED（L4b-4，须趁计划仍活跃）
⑤ 等待 TriggerScanner（~1–3 min）    → 查 _step 状态 + t_contact_timeline（L4b-6）
⑥ publish repay 99000001             → 查 plan cancel_reason=REPAID（L4b-2）
⑦ 日切升档 99000002（dpd→20 + roll） → 查 STAGE_UPGRADE + 新 stage 计划（L4b-3）
⑧ 等收敛后重复 daily-roll            → 查计划数/取消数/timeline 三项不变（L4b-8）
⑨ 故障注入 + 重投 99000000           → 查只新增 1 计划、无重复 provider_msg_id（L4b-7）
⑩ 全部用例跑完                       → 跑 db/l4b-assert.sql 或按 loan_id 总查一遍
```

| 刚完成的动作 | 立刻查什么 | 期望 |
|---|---|---|
| `publish-test-messages.sh case` | `t_contact_plan` + `context_snapshot` | 每案有 plan；快照字段与 seed/payload 一致 |
| 扫描器跑完（多步案如 99000002/03） | `t_contact_plan_step` + `t_contact_timeline` | 步推进、timeline 有 provider_msg_id |
| `publish-test-messages.sh repay` | 最新 `t_contact_plan` | `PLAN_CANCELLED` / `REPAID` |
| `POST /mock/daily-roll` | 同 case 全部 plan 行 | 升档：`STAGE_UPGRADE` + 新 stage；停催：`CEASED`；幂等：无新增取消 |

工具：`db/l4b-assert.sql`（改 `@caseId`）；或 REST `GET /plans/by-case/{id}/history` 作快速预览，**终态裁决仍以 SQL 为准**。

### 可执行资产与未关闭项

```bash
source scripts/test/l4b-env.local.sh
export DB_HOST=... DB_PORT=3306 DB_USER=... DB_PASS=... DB_NAME=ai_collection_db
export GCP_PUBSUB_TEST_TOPIC=collection-cases-test1

./scripts/test/l4b-preflight.sh --strict
./scripts/dev/start-local.sh --detach           # 每轮必须重启：幂等标记是进程内内存态
./scripts/test/l4b-official-test.sh             # L4b-1…8 官方闭环
L4B_ONLY=1,5,6 ./scripts/test/l4b-official-test.sh   # 分段重跑
L4B_RESET=0 ./scripts/test/l4b-official-test.sh      # 保留历史落库（默认清零）
```

**可重复性前置**（缺一即产生假失败，均已在脚本内固化）：

| 前置 | 原因 |
|---|---|
| 清空白名单案的 `t_contact_plan`/`_step`/`_timeline` | 上轮遗留的终态计划与旧快照会污染 L4b-1 建计划判定与 L4b-5 逐字段溯源 |
| 重放 `db/seed-test-cases.sql` | L4b-3/L4b-4 会改写 `t_collection.overdue_days`（S2→20、S3→95），不重放则第二轮 dpd 起点已被污染 |
| 重启应用 | `IngestionDedupStore` 的 `messageId`/`ingested` 标记是内存态，不重启则本轮 `case_push` 被上轮标记直接跳过 |
| L4b-4 紧跟 L4b-1 执行 | `onCaseCeased` 只取消 `findActivePlansByCase` 的结果；测试环境步骤延迟被压缩，S3 案 3 步计划约 90s 即转终态，放到 L4b-6 之后将无活跃计划可取消 |
| L4b-8 先等测试案件全部收敛终态 | TriggerScanner 每 5s 独立扫描，若仍有未跑完步骤，timeline 会在观察窗内自然增长，使"重复触达"断言假失败 |

`l4b-official-test.sh` 的裁决口径：入口一律走真实 topic（`publish-test-messages.sh`），断言一律查
`t_contact_plan` / `t_contact_plan_step` / `t_contact_timeline`，不接受 REST 预览作为终态证据；
L4b-3/L4b-4 通过改写 `t_collection.overdue_days`（仅 `IC_TEST_%` 行）制造跨档与 D91+ 条件后触发
`POST /mock/daily-roll`；L4b-6 额外断言 `script_slot` 已写入且 `content_summary` 不含姓名。

L4b-7 的故障注入采用**热态受控注入，不重启进程**：`POST /mock/ingestion-fault/arm?count=1` 预约后，
`PubSubCaseConsumer` 在**落库与幂等标记之前**对下一条白名单 `case_push` 抛瞬态异常 → 不 ack → PubSub 重投，
第二次处理走完整真实路径并成功。这样断言的是纯粹的重投幂等，不会把「内存幂等在重启后失效」这个变量混进来
（后者是 T5-5 多实例幂等拓扑评审的议题）。三重安全约束：`collection.ingestion.fault-injection-enabled`
默认 false、只对白名单 `loan_id` 生效、每次 arm 只失败一次；端点位于 `@Profile({"local","test"})` 的
`MockTriggerController`。

| 未关闭项 | 裁决 |
|---|---|
| `scripts/test/l4b-official-test.sh` | ✅ 2026-07-27 已实跑取证 `PASS=41 FAIL=0 SKIP=0` |
| **Nacos YAML 重复 `channel:` 键** | ✅ 2026-07-27 已修复；preflight「顶层键无重复」PASS |
| gcloud `pubsub.subscriptions.get` | ✅ 2026-07-27 已验证：`describe` 返回测试订阅、`ACTIVE` 状态及目标 topic；strict preflight 订阅检查 PASS |
| 独占消费 | ✅ 2026-07-27 运维已确认测试订阅独占消费 |
| 旧库 seed 写权限 | ✅ 2026-07-27 回滚验证通过：`UPDATE t_collection … WHERE id LIKE 'IC_TEST_%'` 影响 6 行且回滚无痕；preflight 已固化该检查 |
| L4b-7 注入开关 | ✅ 2026-07-27 已实跑取证：注入命中→未 ack→重投→幂等收敛（**仅联调环境，生产必须 false**） |
| SPI 零 DB I/O 约定 | ✅ 2026-07-27 修复 `ConfigTemplateProvider` 热路径查库；本轮 0 次 SPI 超时。**归属 `collection-channel`（编排同事模块），需同步告知** |
| 内容审计 HMAC | ✅ 2026-07-28 注入 `CONTENT_AUDIT_HMAC_KEY`/`CONTENT_AUDIT_KEY_ID`（写入 gitignore 的 `.env`）后取证：9 条投递均带 `content_hmac` 与 `content_key_id=l4b-test-20260728`。**生产须换用 Secret 注入并纳入轮换** |
| `template_version` 来源误标 | ✅ 2026-07-28 修复，见下文 |
| T3a 出口 | ✅ 已闭合（见 §5） |

### 出口

T4 入口 **T3a + T3b + T0 L4b 预检** 均已通过。T4 出口所需的 L4b-1…8 证据、独占订阅、真实 PubSub 的
L4b-2 与 official 脚本实跑均已具备（`PASS=41 FAIL=0`），且 `mvn test` 全量回归 140 用例全绿。
当前仅剩**内容审计 HMAC 待注入密钥后取证**一项；该项不阻塞触达链路正确性，可与 T5 并行关闭。

---

## 8. T5 Pilot / 预发生产等价拓扑

### 目标

在不影响真实客户的前提下，验证生产等价拓扑、并行消费、调度与告警。T5 必须等待 T4 通过。

| ID | 准入/用例 | 允许替身 | 证据 | Owner | 状态 |
|---|---|---|---|---|---|
| T5-1 | T4 全部出口已通过 | 无 | T4 证据包 | 主架构 | ⬜ |
| T5-2 | 生产订阅 `collection-cases-ai-v1-sub` 独立扇出 | 测试白名单 | 运维拓扑/消费证据 | 运维 | ⬜ |
| T5-3 | XXL-Job `dailyRoll` 0:35 PHT 注册与演练 | 可用白名单数据 | 调度日志/告警 | 运维 + 主架构 | ⬜ |
| T5-4 | 渠道 sandbox、白名单、脱敏、限频 | 测试地址 | 配置审查 | 编排同事 + 运维 | ⬜ |
| T5-5 | 多实例事件总线/幂等拓扑评审 | 不允许 memory-only 假设 | 架构评审 | 主架构 | ⬜ |

### 出口

生产等价拓扑经演练，监控与回滚机制可操作，且所有真实触达均在白名单/沙箱范围内。

---

## 9. T6 受控切量准入与回滚

### 准入

| ID | 准入项 | 退出条件 | Owner | 状态 |
|---|---|---|---|---|
| T6-1 | T5 已通过 | T5 证据包完整 | 主架构 | ⬜ |
| T6-2 | 切量白名单/比例/观察窗口 | 已批准、可审计、可停止 | 业务 + 运维 | ⬜ |
| T6-3 | 回滚开关与责任人 | subscription、路由、渠道开关、恢复步骤已演练 | 运维 + 主架构 | ⬜ |
| T6-4 | 幂等/事件总线生产化 | 多实例方案不依赖内存 EventBus/幂等 | 主架构 | ⬜ |
| T6-5 | 供应商额度与合规 | 限频、模板、审计、投诉处理就绪 | 编排同事 | ⬜ |

### 回滚原则

回滚不得通过删除数据或重放未知消息实现。必须先停止新路由/新订阅消费，保全证据，按已演练的配置切回旧系统，再进行对账与受控补偿。

---

## 10. 全局矩阵：阶段 × 环境 × 允许替身 × 命令 × 证据 × Owner × 出口

| 阶段 | 环境 | 允许替身 | 必跑命令 | 最小证据 | Owner | 退出条件 |
|---|---|---|---|---|---|---|
| T0 | CI/local/L4 环境 | 基础设施可模拟 | `mvn -B -ntp clean test`；L4b preflight | CI、health、预检 | 主架构/运维 | 环境适用且隔离 |
| T1 | CI/local | SPI、Repo、EventBus | engine/channel Maven tests | JUnit/CI | 模块 owner | L0/L0c/L1 全绿 |
| T2 | CI/local | 契约替身仅临时允许 | L2 test suite | C1–C7 结果 | 主架构+编排 | 真实实现对接复跑 |
| T3a | 集成 MySQL | 不允许内存持久化替代 | integration Maven tests（待补） | SQL + JUnit | 服务+主架构 | L3 持久化语义通过 |
| T3b | 合成案+渠道沙箱 | 仅合成事件源/临时策略实现 | `l4a-official-test.sh` | 脚本、终端、API | 主架构+编排 | L4a 用例完成 |
| T4 | 隔离 PubSub/旧库/DB/渠道 | 不允许 mock ingress；旧库仅兜底/对账 | `l4b-preflight.sh --strict` + `l4b-official-test.sh` | PubSub、SQL、日志 | 主架构+服务+运维 | L4b-1…8、独占订阅、official 脚本 |
| T5 | Pilot/预发等价拓扑 | 仅白名单/沙箱 | Runbook 演练 | 监控、调度、拓扑 | 全员 | T4 已过且演练通过 |
| T6 | 生产受控切量 | 不允许测试替身 | 批准的切量/回滚 Runbook | 变更记录、监控 | 业务+运维+主架构 | 可切、可停、可回滚 |

**依赖关系：**

```text
T0 → T1 → T2
          ├→ T3a ─┐
          └→ T3b ─┴→ T4 → T5 → T6
```

T3a 与 T3b 可并行；T4 必须等待两者均通过；T5 必须等待 T4 通过。

---

## 附录 A：业务链路覆盖索引

| 业务链路 | T1/T2 | T3a | T3b | T4 |
|---|---|---|---|---|
| 入案建计划 | L0/L1、C1 | Mapper/快照 | L4a-1/7 | L4b-1/5 |
| 调度执行 | L0/L1、C3–C5/C7 | scanner/step | L4a-1/2/6/8 | L4b-6 |
| 结果回收 | L0/L1、C6 | 状态/timeline | L4a-6 | L4b-1/6 |
| 异步回调 | L0/L1；L2-CB 差集 | 回调落库 | 范围外 | 后续真实 Voice/AI Call |
| 中断与重建 | L0/L1 | 取消/唯一约束 | L4a-3/4/5 | L4b-2/3/4/8 |

## 附录 B：外部测试资产索引

- 渠道测试细节：[`docs/channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md`](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)
- ContextSnapshot 契约：[`docs/contracts/README_ContextSnapshot契约对齐.md`](../contracts/README_ContextSnapshot契约对齐.md)
- 引擎渠道执行契约：[`docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md`](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)
- L4b 环境和操作：[`MOCASA催收系统升级_Phase1_L4b环境交接清单.md`](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md)
- 脚本目录：[`scripts/README.md`](../../scripts/README.md)
