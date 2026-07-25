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
| L2 | 引擎↔渠道执行契约；双方以同一契约断言对接 | `ChannelContractL2Test`（C1–C7） |
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
| T0-3 | L4b 联调隔离、白名单、渠道沙箱 | 合成 loan_id/测试地址 | `./scripts/test/l4b-preflight.sh --strict`；Nacos 指向 `collection-cases-test1-sub` | preflight 输出、Nacos 片段 | 主架构 + 运维 | 🟡 |
| T0-4 | 测试订阅**独占消费**（无其他活跃 consumer 抢消息） | 不允许多实例共享同一 test-sub | 开跑前确认无他机 consumer；任选一 loan_id publish 一次，本机日志命中 `[Ingestion]` | 同 loan_id publish 后本地 consumer 必命中 | 运维 | 🟡 |
| T0-5 | 数据库表、只读旧库访问、脱敏输出 | 测试 seed | preflight + SQL 连通性 | 表存在、账号范围确认 | 服务同事 + 运维 | 🟡 |

### 出口

- T1 可在 T0-1 通过后开始。
- T3a 需要 T0-5。
- T3b 需要 T0-2 和受控测试渠道配置。
- T4 需要 T0-3、T0-4、T0-5，且必须使用独立测试 topic；禁止向生产 `collection-cases` 发布测试消息。
- **T0-3 与 T0-4 不可混为一谈**：独立 topic/订阅 + Nacos 白名单 = T0-3；同一 test-sub 上只有你方一个 consumer = T0-4。
- **T0-4 关于 2026-07-07**：当时为同事误开他机 consumer 的一次性事件，非订阅配置错误。日后联调只要开跑前确认无其他 consumer，并对任一白名单 loan_id 做一次 publish→本机 `[Ingestion]` 命中，即可将 T0-4 收口为 ✅；无需永久保留 🟡。

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

| ID | 契约主题 | 允许替身 | 当前证据 | 状态 | Owner |
|---|---|---|---|---|---|
| C1 | 计划结构与步骤顺序 | 编码契约替身 | `ChannelContractL2Test` | 🟡 骨架绿 |
| C3 | Guard block → SKIPPED | 编码契约替身 | `ChannelContractL2Test` | 🟡 骨架绿 |
| C4/C5 | 解析/渠道异常、重试语义 | 编码契约替身 | `ChannelContractL2Test` | 🟡 骨架绿 |
| C6 | 步骤完成/推进语义 | 编码契约替身 | `ChannelContractL2Test` | 🟡 骨架绿 |
| C7 | 重复 due 不重复 dispatch | 编码契约替身 | `ChannelContractL2Test` | 🟡 骨架绿 |
| L2-CB | AI_CALL 异步回调契约 | 不允许长期替身 | 未见真实渠道契约用例 | ⬜ | 主架构 + 编排同事 |

### 出口

- C1–C7 必须以编排同事真实 SPI/Gateway 再跑一次，替身结果不能直接标记为真实对接通过。
- `StepCommand`、`StepResult`、`GuardVerdict`、`providerMsgId` 与 ContextSnapshot 断言遵循 [引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)。
- T3a/T3b 可在 T2 骨架绿时并行准备；进入 T4/T5 前需关闭影响真实链路的 L2 契约差集。

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
| L4a-1 | 三渠道顺序完成 | SMS→PUSH→EMAIL、providerMsgId、计划完成 | ✅ | 2026-07-25 `restart-and-l4a.sh`（timeline 三渠道） |
| L4a-2 | PUSH 无 token → SMS fallback | fallback 元数据、SMS 投递、步骤完成 | ✅ | 2026-07-25 `restart-and-l4a.sh` |
| L4a-3 | 还款取消 | `PLAN_CANCELLED(REPAID)`，后续不触达 | ✅ | 2026-07-25 `restart-and-l4a.sh` |
| L4a-4 | 升档取消并新建 | 旧计划 `STAGE_UPGRADE`、新计划正确 | ✅ | 2026-07-25 `restart-and-l4a.sh` |
| L4a-5 | 停催 | `PLAN_CANCELLED(CEASED)`，不重建 | ✅ | 2026-07-25 `restart-and-l4a.sh` |
| L4a-6 | SMS 同步完成冒烟 | 同步 `COMPLETED`，不进入 `WAITING` | ✅ | 2026-07-25 `restart-and-l4a.sh` |
| L4a-7 | 重复 ingest 幂等 | 仅一个活跃计划、仅一轮投递 | ✅ | 2026-07-25 `restart-and-l4a.sh` |
| L4a-8 | Email scriptSlot × stage | scriptSlot、providerMsgId、模板差异 | ✅ | 2026-07-25 `restart-and-l4a.sh`（S0/S1/S2 Email 与 Gmail timeline） |
| L4a-G | Guard / rebuild | NO_PHONE、FREQUENCY、穷尽路径 | ✅ | 2026-07-25 `restart-and-l4a.sh` |

**L4a-6 裁决：** 旧版“消息类观察期（`observation-minutes=1`、等待约 90 秒）”已于 2026-07-18 撤销。Phase 1 SMS/PUSH/EMAIL 观察期为 0；不得再将该旧观察期 curl、配置或等待时间作为 L4a 用例。

### 必跑命令与出口

```bash
# App 已启动时
./scripts/test/l4a-official-test.sh

# 重启、编译、启动后执行
./scripts/test/restart-and-l4a.sh
```

出口已满足：2026-07-25 以 `COLLECTION_CASE_SERVICE=mock`、`ENGINE_SPI_TIMEOUT_ENABLED=false` 运行 `restart-and-l4a.sh`，固定案例先清理并获得 `PASS=23 FAIL=0`；输出位于 `logs/run/l4a.last.log`，应用日志位于 `logs/run/admin.log`。T3b 已完成；T3a 也已闭合，因此下一授权阶段为 T4/L4b 的严格预检与隔离真实来源联调。

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

| ID | 场景 | 真实触发/断言 | 状态 | 未关闭项 |
|---|---|---|---|---|
| L4b-1 | case_push 入案、建计划、投递、timeline | PubSub → plan/step/timeline | 🟡 | 独占订阅、official 脚本 |
| L4b-2 | 还款取消 | `repayment_push_and_load` → REPAID | 🟡 | 历史 `/mock/repayment` 不算全证据；需真实 PubSub 重跑 |
| L4b-3 | 日切升档 | 手动 daily-roll → STAGE_UPGRADE | 🟡 | official 脚本 |
| L4b-4 | 日切停催 | 手动 daily-roll → CEASED | 🟡 | official 脚本 |
| L4b-5 | 快照字段溯源 | enriched payload == context_snapshot | 🟡 | official 脚本 |
| L4b-6 | due scanner 执行 | `trigger_time<=now` → 投递/timeline | 🟡 | official 脚本 |
| L4b-7 | NACK 重投与幂等 | 真实 PubSub 下游失败→重投一次 | ⬜ | 可控故障注入、official 脚本 |
| L4b-8 | 日切幂等 | 同日重复 daily-roll 不重复升档/触达 | 🟡 | official 脚本 |

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
① seed 旧库（t_collection）          → 可选：SELECT 确认 9900000x 行存在
② preflight + 启动 App              → 不查业务表
③ publish case（6 案）              → 查 t_contact_plan / _step（L4b-1、L4b-5）
④ 等待 TriggerScanner（~1–3 min）   → 查 _step 状态 + t_contact_timeline（L4b-6）
⑤ publish repay 99000001            → 查 plan cancel_reason=REPAID（L4b-2）
⑥ 日切 daily-roll（升档/停催/幂等）  → 查多行 plan + cancel_reason（L4b-3/4/8）
⑦ 全部用例跑完                      → 跑 db/l4b-assert.sql 或按 loan_id 总查一遍
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
./scripts/test/l4b-preflight.sh --strict
export GCP_PUBSUB_TEST_TOPIC=collection-cases-test1
./scripts/test/l4b-pubsub/publish-test-messages.sh case
./scripts/test/l4b-pubsub/publish-test-messages.sh repay 99000001
```

| 未关闭项 | 裁决 |
|---|---|
| `scripts/test/l4b-official-test.sh` | **缺失**；preflight 与 publish 不能替代官方闭环 |
| 独占消费 | 共享订阅分流不能视为 L4b-1 通过 |
| L4b-7 | 需可控 NACK 路径 |
| T3a 出口 | L3 自动化未收口前，T4 不能宣告闭合 |

### 出口

T4 入口须 **T3a + T3b + T0 L4b 预检** 均通过。T4 通过需 L4b-1…8 证据、独占订阅、真实 PubSub 的 L4b-2 及 official 脚本；当前 **🟡 未闭合**。

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
| T4 | 隔离 PubSub/旧库/DB/渠道 | 不允许 mock ingress；旧库仅兜底/对账 | preflight + publish + future official script | PubSub、SQL、日志 | 主架构+服务+运维 | L4b-1…8、独占订阅、official 脚本 |
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
