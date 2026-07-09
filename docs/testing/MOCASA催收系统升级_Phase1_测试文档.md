# MOCASA 催收系统升级 — Phase 1 测试主文档（链路 × 层级）

> **版本**: Phase 1 · v2.0  
> **日期**: 2026-06-29  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: —  
> **关联文档**: [核心引擎规格](../MOCASA催收系统升级_Phase1_核心引擎规格.md)、[领域模型与数据定义](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)、[数据接入规格](../MOCASA催收系统升级_Phase1_数据接入规格.md)、[contracts/](../contracts/)、[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)（索引外链）

---

## 图例与状态约定

| 标记 | 含义 |
|---|---|
| ✅ **现状已覆盖** | 已有可执行测试类/方法或 curl 脚本，CI 或本地可跑通 |
| 🟡 **骨架绿/替身** | 已有测试骨架，但用编码契约的替身（非编排同事真实实现），对接真实实现后转 ✅ |
| ⬜ **Phase 1 应补** | Phase 1 范围内应补、当前缺；标注待办归属与环境依赖 |
| ⏭ **Phase 2 延后** | 不在 Phase 1 范围，明确延后 |
| ❓ **待澄清** | 文档↔文档或文档↔代码不一致，见 §11 |

## 视角与轴

- **视角 A（工程层级）**：`L0` 单测 / `L1` 内存集成 / `L2` 引擎↔渠道契约 / `L3` 落库 / `L4` 端到端。（系统现为 5 层 L0–L4；用户提及的 "L5" 在 Phase 1 无对应载体，预留 Phase 2 性能/混沌层。）
- **视角 B（业务链路）**：① 入案建计划链 ② 调度执行链 ③ 结果回收链 ④ 异步回调链 ⑤ 中断与重建链。
- **主轴 = 链路**：§3–§7 每条链路独立成章，章内按 L0–L4 切分；§8 横切维度（异常/幂等/终态/退避）跨层打标；§9 链路×层级矩阵 + §10 场景全集反推与差集，共同构成「测全」的正交证据。

---

## 修订版大纲 v2（带章节号）

| 章节 | 标题 | 内容 | 来源 |
|---|---|---|---|
| **§0** | 文档定位与单一信息源 | SSOT 声明、吸收/外链清单、运行入口 | 吸收：测试总览「运行入口/待办」 |
| **§1** | 全局层级总表 | L0–L4 定义、依赖、连库、责任人、现状 | 吸收：测试总览「分层模型」 |
| **§2** | 模块责任边界 | 6 模块职责、主架构 vs 编排同事分工、协作断言点（契约对齐） | 新增（依据 pom + contracts） |
| **§3** | 链路① 入案建计划链 | 定义卡 + L0–L4 覆盖 | 吸收：矩阵 #20/#21、总览 L1 |
| **§4** | 链路② 调度执行链 | 定义卡 + L0–L4 覆盖 | 吸收：矩阵 #1–#13/#28–#32/#5a–#5e、总览 C1/C3/C4/C5/C7 |
| **§5** | 链路③ 结果回收链 | 定义卡 + L0–L4 覆盖 | 吸收：矩阵 #14–#18/#25、总览 C6 |
| **§6** | 链路④ 异步回调链 | 定义卡 + L0–L4 覆盖 | 吸收：矩阵 #22/#23/#30；外链：渠道 TC-VOICE-* |
| **§7** | 链路⑤ 中断与重建链 | 定义卡 + L0–L4 覆盖 | 吸收：矩阵 #19/#24/#26/#27；外链：渠道 TC-CANCEL/TC-CEASED |
| **§8** | 横切维度总表 | 异常 / 幂等 / 终态 / 退避 / SPI 硬超时，跨层落点 | 吸收：测试总览「横切维度」 |
| **§9** | 链路 × 层级覆盖矩阵 | 5 链路 × L0–L4，单元格填测试类/TC-ID 或 ⬜ | 新增（交叉证明） |
| **§10** | 场景全集反推与差集清单 | 状态迁移/七步分支/守卫返回值/StepResult/渠道×异常组合 期望全集 vs 已覆盖 → 差集 | 新增（测全证据） |
| **§11** | 待澄清清单 | 文档↔文档、文档↔代码 不一致 | 新增 |
| **§12** | 现状 / Phase 1 应补 / Phase 2 延后 | 三态分流，含待办归属与环境依赖 | 新增 |
| 附录 A | 测试资产索引（真实测试类/方法清单） | 可执行性落点 | 新增 |
| 附录 B | 外链引用清单（渠道指南/对接说明，不复制） | 单一信息源边界 | 新增 |

---

## §0 文档定位与单一信息源

### 0.1 吸收来源（本文已并入，原文可删）

| 原文档 | 已吸收内容 | 落点 |
|---|---|---|
| `docs/测试总览_Phase1.md` | 分层模型（L0–L4 定义/责任人/状态）、横切维度表、运行入口命令、L1/L2 覆盖描述、C1–C7 契约用例表 | §1、§4–§6、§8、附录 A |
| `docs/测试矩阵_engine阶段1.md` | 七步管线 #1–#13/#28–#32、状态机 #14–#27、PreFlight #5a–#5e、测试文件映射、全链路集成描述、运行方式 | §3–§7、§9、§10、附录 A |

> **替代声明**：上述两文的所有测试断言与场景编号在本文 §9/§10/附录 A 中均有承接（沿用 `#n` / `Cn` / `#5x` 旧编号，保证可追溯）。两文合并 review 通过后删除。

### 0.2 外链引用（不复制全文，归编排同事维护）

见附录 B。核心一条：渠道功能测试（L2/L3 curl、真实供应商、模板/合规/计划结构）以 `docs/channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md` 的 `TC-*` 为准，本文仅按链路索引引用其 TC-ID。

### 0.3 运行入口（CI 常绿基线）

```bash
# L0 + L1 + L2（纯逻辑/内存集成，不连库；CI 同此，JDK8）
mvn -pl collection-common,collection-engine -am test -Dsurefire.failIfNoSpecifiedTests=false
# 渠道侧单测（Adapter 映射 + 策略逻辑）
mvn -pl collection-channel -am test
# 全量（CI：先 spotless:check 格式门禁，再 clean test）
mvn -B -ntp clean test
# 连库集成（L3，需环境，默认 CI 不跑）
mvn -pl collection-admin -am test -Dgroups=integration
```

> CI 工作流：`.github/workflows/ci.yml` —— commit message 门禁（Angular 规范）→ Spotless 增量格式门禁 → `mvn clean test`（纯逻辑单测，无需 MySQL/Redis）。

---

## §1 全局层级总表

| 层 | 名称 | 依赖 | 连库 | 责任人 | 现状 | 载体（真实路径） |
|---|---|---|---|---|---|---|
| **L0** | 引擎纯逻辑单测 | mock SPI/Repo/EventBus | 否 | 主架构 | ✅ | `collection-engine/src/test/.../lifecycle/*`、`.../spi/SpiInvokerTest` |
| **L0c** | 渠道纯逻辑单测 | mock | 否 | 编排同事 | ✅ | `collection-channel/src/test/.../strategy/*`、`.../adapter/*` |
| **L1** | 引擎全链路内存集成 | 同步内存总线 + 内存仓储 + 真实引擎组件 | 否 | 主架构 | ✅ | `collection-engine/.../integration/FullChainIntegrationTest` |
| **L2** | 引擎↔编排执行契约 | 编码契约替身（StepResolver/Guard/Gateway）+ 真实引擎 | 否 | 主架构 + 编排同事 | 🟡 骨架绿 | `collection-engine/.../integration/ChannelContractL2Test`（C1–C7） |
| **L3** | 数据落库集成 | MyBatis + MySQL/Testcontainers | 是 | 服务同事 + 主架构 | ⬜ 待环境 | `collection-admin` `@SpringBootTest @Tag("integration")`（待建） |
| **L4a** | 端到端（mock 数据源 + 真实渠道） | 全模块装配 + Nacos + `MockTriggerController`/`/mock/*` + `*CaseRegistry` 合成案件 + **真实供应商** | 是 | 主架构 + 编排同事 | 🟢 **L4a-全可跑**（A1–A6 临时 `Default*`@Primary，见 §L4a.0） | **§L4a 用例清单**（L4a-1…8 + Guard/REBUILD）+ `scripts/test/l4a-official-test.sh` |
| **L4b** | 端到端（真实数据源 + 真实渠道） | L4a 基础上换数据源：真实 GCP PubSub（B1 `IngestionService`）+ 真实旧库（`RealCaseService`/`t_collection`）+ DPD 日切（B2 `DpdStageRollHandler`）+ L3 落库 | 是 | 全员 | ⬜ 待 B1/B2 真实化 + L3 环境 | **§L4b 用例清单**（L4b-1…8）+ 真实 ingestion + 渠道指南 `TC-*` |

> **L4 拆分依据**：端到端有两个独立变量——「数据源」（mock ingest ↔ 真实 PubSub/旧库）与「渠道触达」（mock ↔ 真实供应商）。**L4a 先固定数据源为合成案件、只放开真实渠道**，用 `*CaseRegistry`（内置测试号/邮箱：`wzynju@126.com`、94101–94103 真号、94200 真 JPush token）跑通整条触达链路；**L4b 仅替换数据源**接真实 ingestion。L4a 是 L4b 的前置门禁，避免双变量叠加导致定位困难。

> **L0/L1/L2 当前全绿**（`@Test` 数已与代码核对，2026-06-22；含 L0/L1 差集回补 +17）：
> - **L0 引擎纯逻辑 = 59**：`SpiInvokerTest`(5) + `StepExecutionOrchestratorTest`(17，+D20) + `PreFlightCheckerTest`(5) + `PlanLifecycleManagerTest`(28，+D1/D16/D17/D18/D21/D22×2/D23/D25/D26/D28/D29-L0) + `MessageChannelHappyPathTest`(4)。
> - **L1 = 6**：`FullChainIntegrationTest`(4，+D24/D29-L1) + `AsyncCallbackChainL1Test`(2，D3 链路④异步回调)；**L2 = 7**：`ChannelContractL2Test`（C1–C7，替身骨架绿）。
> - **引擎模块合计 = 72**（L0 59 + L1 6 + L2 7）；**渠道 L0c = 27**；**全仓合计 = 99**。明细见附录 A。
> （注：2026-06-22 回补 L0/L1 差集 17 例：引擎 55→72、全仓 82→99；旧矩阵"43"、旧总览"55"为历史口径已过时，本文以上表为准。）

---

## §2 模块责任边界

### 2.1 六模块职责（pom 模块 + 测试归属）

| 模块 | 职责 | 测试归属层 | 负责人 |
|---|---|---|---|
| `collection-common` | 契约层：DTO（ExecutionContext/StepCommand/StepResult/…）、枚举、领域模型、5 个 SPI 接口 + `ChannelGateway` 接口 | L0（被各层引用） | 主架构 |
| `collection-engine` | 核心引擎：`EventConsumerDispatcher` / `PlanLifecycleManager` / `StepExecutionOrchestrator` / `PreFlightChecker` / `SpiInvoker` / `ContextAssembler` | **L0/L1/L2** | 主架构 |
| `collection-channel` | 渠道编排：5 个 SPI 的真实实现（策略子层）+ `ChannelGateway`/Adapter（执行子层：SMS/PUSH/EMAIL/Voice） | L0c / L2 真实化 / L4 | 编排同事 |
| `collection-ingestion` | 数据接入：消费上游 → 校验 / 组装 payload → 发布 `CASE_INGESTED/STAGE_CHANGED/REPAYMENT_RECEIVED`；DPD 日切 | L4（事件入口） | 主架构/数据接入 |
| `collection-admin` | 管理后台 + Webhook 回调（鉴权后发 `CHANNEL_CALLBACK`）+ L3 落库集成测试宿主 | **L3** | 服务同事 + 主架构 |
| `collection-service` | 服务实现：`CaseService`/`ProfileService` 真实实现、MyBatis Mapper、Repository、Mock 测试数据 | L3（Mapper 往返） | 服务同事 |

### 2.2 主架构 vs 编排同事：链路环节分工

| 链路环节 | 主架构（engine/common/admin/ingestion） | 编排同事（channel） | 协作断言点（契约对齐） |
|---|---|---|---|
| 事件路由/线程模型 | ✅ 全责（Dispatcher/锁/终态拦截） | — | — |
| 状态机 §2（建计划/中断/穷尽/PTP） | ✅ 全责（`PlanLifecycleManager`） | — | `PlanFactory.create` 返回的计划结构（步数/渠道/观察期） |
| 七步管线骨架 §3 | ✅ 全责（`StepExecutionOrchestrator`） | — | ③④⑤ 通过 SPI/Gateway 调用 |
| ③ 业务守卫 `ExecutionGuard` | 声明接口 + 调用 + fail-close 兜底 | ✅ 实现合规逻辑（频率/时段/放弃率/空地址） | **GuardVerdict 语义**：block→SKIPPED（C3/TC-EMAIL-02） |
| ④ 步骤解析 `StepResolver` | 声明接口 + 调用 + null/异常兜底 | ✅ 实现取址/模板/metadata | **StepCommand 字段**：targetAddress 取号口径、templateId、metadata |
| ⑤ 渠道调度 `ChannelGateway` | 声明接口 + 调用 + retryable 兜底 | ✅ 实现发送/熔断/fallback/对账 | **StepResult 3 情形**（受理/超时/异常）+ providerMsgId |
| 推进/穷尽策略（Advancement/Exhaustion） | 调用 + 状态落地 | ✅ 实现决策规则 | 三值枚举 + ExhaustionResult 字段约束 |
| 渠道发送/回调 | admin 收 Webhook → 发 `CHANNEL_CALLBACK` | ✅ 供应商对接 + 回调载荷 | **CHANNEL_CALLBACK payload**（result/providerMsgId）→ 映射 ContactResult |
| 落库映射 | ✅ Mapper/DDL（service/admin） | 读 `t_contact_plan_step`（SPI 取号） | `context_snapshot` JSON 往返、字段映射 |

> **协作断言点 = 契约对齐基线**：`ChannelContractL2Test`（C1–C7）即为双方对接的**验收基线**——编排同事真实化 SPI/Gateway 后，契约语义一致则「对接即绿」。契约定稿见 [`引擎渠道执行契约对齐_待编排确认.md`](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)；快照字段契约见 [`ContextSnapshot契约对齐_re.md`](../contracts/README_ContextSnapshot契约对齐.md)；生命周期 E1–E8 见 [`README_编排同事对齐清单.md`](../contracts/README_编排同事对齐清单.md)。

---

## §3 链路① 入案建计划链

### 3.1 链路定义卡

| 项 | 内容 |
|---|---|
| **触发事件** | `CASE_INGESTED`（新案件入库）/ `STAGE_CHANGED`（为新阶段建计划，与链路⑤共用） |
| **经过组件（模块/负责人）** | `EventConsumerDispatcher`（engine/主架构）→ `PlanLifecycleManager.onCaseIngested`（engine/主架构）→ `CaseService.getCaseInfo/getContextSnapshot`（service/服务同事）→ `PlanFactory.create`（**SPI**，channel/编排同事） |
| **关键分支** | ① `PlanFactory` 返回非空 → 建计划；② 返回 null → 该案件不建计划（如 D+91/CEASED）；③ 同 `caseId+stage` 已有活跃计划 → 幂等跳过（单活跃计划约束） |
| **终态/输出** | 计划落库 `PENDING` + 首步 `trigger_time` 写入（同事务）；不产生触达 |
| **数据落点** | `t_contact_plan`（status=PENDING、stage、context_snapshot JSON、idempotency_key）、`t_contact_plan_step`（首步 PENDING + trigger_time） |
| **验收断言** | savePlan 被调用且 `status==PENDING`、`stage` 正确；幂等场景 `planFactory.create` 与 `savePlan` 均**不**被调用；snapshot 在创建时冻结写入 |

### 3.2 单场景级可执行用例表（就地展开，可直接替换旧分层覆盖表）

> 本节把链路①从「定义卡」就地展开为**步骤分解 + 单场景用例 + 分工/契约 + §10 映射 + 覆盖结论 + 待澄清/骨架**六部分。引用方法名与附录 A 已核对清单一致；未实现用例标 ⬜ 写清归属/环境，**不计入 §1 计数表**。定义卡 §3.1 保留。

#### 3.2.0 步骤分解（建计划，有序）

| # | 步骤 | 组件 / 模块 / 负责人 | 输入 | 输出 | 关键分支 | 代码位置（类#方法） |
|---|---|---|---|---|---|---|
| ① | 事件路由 | `EventConsumerDispatcher` / engine / 主架构 | `CASE_INGESTED` / `STAGE_CHANGED` | 路由到 `onCaseIngested` / `onStageChanged` | 已注册 | `EventConsumerDispatcher#registerHandlers`（L35–36） |
| ② | 入案分发 | `PlanLifecycleManager#onCaseIngested` / engine / 主架构 | caseId + stage（缺省则取 `getCaseInfo().getStage()`） | 调 `createPlanForStage(caseId, stage)` | stage 取自事件 / 回退 caseInfo | `PlanLifecycleManager#onCaseIngested`（L47–57） |
| ②' | 阶段变更分发（与链路⑤共用） | `PlanLifecycleManager#onStageChanged` / engine / 主架构 | caseId + newStage | 取消旧阶段计划(STAGE_UPGRADE) + `createPlanForStage(newStage)` | 旧 plan stage≠newStage→`PLAN_CANCELLED(STAGE_UPGRADE)` | `PlanLifecycleManager#onStageChanged`（L59–80） |
| ③ | 建计划守卫链 | `PlanLifecycleManager#createPlanForStage` / engine / 主架构 | caseId, stage | 继续 / 跳过 | stage=null→跳；已有活跃同阶段计划→幂等跳；`caseStatus=CEASED`→跳；`snapshot.caseContext.collectionStatus=CEASED`→跳 | `PlanLifecycleManager#createPlanForStage`（L322–345） |
| ④ | 读案件+快照 | `CaseService.getCaseInfo/getContextSnapshot` / service / 服务同事 | caseId | `CaseInfo` / `ContextSnapshot` | 读失败→异常上抛（NACK，无 fail-close） | `createPlanForStage`（L334/L339） |
| ⑤ | 计划工厂（SPI，硬超时 50ms） | `PlanFactory.create` / channel / 编排同事 | caseInfo, stage, snapshot | `ContactPlan`（步序/渠道/语气/观察期）/ null | 返回 null→不建计划；异常/超时→NACK | `createPlanForStage`（L347–357）；拒建逻辑 `MockPlanFactory#shouldRejectPlan`（channel） |
| ⑥ | 计划组装 + 首步 + 快照冻结 | `PlanLifecycleManager#createPlanForStage` / engine / 主架构 | 工厂返回 plan | PENDING + 冻结 snapshot JSON + 首步 trigger_time | status=PENDING；`contextSnapshot=JsonUtil.toJson(snapshot)`（冻结）；首步 stepOrder=1、`triggerTime=now+max(0,delay)`、PENDING；`idempotencyKey=caseId:stage:millis` | `createPlanForStage`（L358–376） |
| ⑦ | 落库 | `ContactPlanRepository.savePlan` / engine 调用·服务同事落库 | 组装好的 plan | 计划+步骤落库 | — | `createPlanForStage`（L377） |

#### 3.2.1 单场景用例表（每分支一行）

> 前置统一：caseId=1002 / userId=9001。「层级」L0=纯逻辑、L1=内存集成、L2=引擎↔渠道契约。

| 场景ID | 链路步骤 | 前置（caseId/stage/画像/案件态） | 触发（事件+载荷） | 期望结果 | 数据断言（plan.status/stage/snapshot/idemKey、step.trigger_time、savePlan/create 调用） | 层级 | 已覆盖载体（真实#方法 / TC-ID / ⬜归属·环境） | 状态 |
|---|---|---|---|---|---|---|---|---|
| **①-S1** | ②③⑤⑥⑦ 正常建计划 | 案件存活，stage=S1，无同阶段活跃计划，工厂返回非空计划 | `CASE_INGESTED`（caseId, stage=S1） | 落库 PENDING，stage 正确 | `savePlan` 调用且 `status==PENDING`、`stage==S1` | L0 | `PlanLifecycleManagerTest#onCaseIngested_createsPlan`（#20） | ✅ |
| **①-S2** | ⑥ 首步 trigger_time | 同 S1，首步 delay=0 / delay>0 | `CASE_INGESTED` | delay=0→`trigger_time≈now`；delay>0→`now+delay` | 捕获 `savePlan` 计划首步 `triggerTime`：delay=0≈now、delay=N≈now+N | L0 | ⬜ 主架构 / 内存（**差集 D23**，预计 +1 `@Test`：`onCaseIngested_firstStepTriggerTime`） | ⬜ |
| **①-S3** | ⑥ 快照冻结 | 同 S1，快照非空 | `CASE_INGESTED` | snapshot 在创建时冻结写入 `context_snapshot` | `savePlan` 计划 `getContextSnapshot()` 非空且为快照 JSON（与 `getContextSnapshot` 内容一致） | L0 | ⬜ 主架构 / 内存（**差集 D23**，与 S2 合并 1 例可一并断言） | ⬜ |
| **①-S4** | ③ 单活跃计划幂等 | 同 case+stage 已有活跃计划 | `CASE_INGESTED`（stage=S1） | 幂等跳过：`create` 与 `savePlan` 均不调 | `planFactory.create` 从未调用；`savePlan` 从未调用 | L0 | `PlanLifecycleManagerTest#onCaseIngested_idempotentSkip`（#21） | ✅ |
| **①-S5** | ⑤ 工厂返回 null | 案件存活但工厂 `create` 返回 null（不 satisfy 建计划） | `CASE_INGESTED` | 不建计划：`savePlan` 不调 | `planFactory.create` 调用且返回 null；`savePlan` 从未调用 | L0 | ⬜ 主架构 / 内存（**差集 D21**，预计 +1 `@Test`：`onCaseIngested_factoryNull_noPlan`） | ⬜ |
| **①-S6** | ③ 引擎 CEASED 前置（caseStatus） | `caseInfo.caseStatus="CEASED"` | `CASE_INGESTED` | 跳过：**不调工厂**、不 savePlan | `planFactory.create` 从未调用；`savePlan` 从未调用 | L0 | ⬜ 主架构 / 内存（**差集 D22**，预计 +1 `@Test`：`onCaseIngested_ceasedCaseStatus_skip`） | ⬜ |
| **①-S7** | ③ 引擎 CEASED 前置（snapshot） | `snapshot.caseContext.collectionStatus="CEASED"`（如 DPD91 入 CEASED） | `CASE_INGESTED` | 跳过：不调工厂、不 savePlan | `planFactory.create` 从未调用；`savePlan` 从未调用 | L0 | ⬜ 主架构 / 内存（**差集 D22**，预计 +1 `@Test`：`onCaseIngested_ceasedSnapshot_skip`） | ⬜ |
| **①-S8** | ⑤ 渠道侧工厂拒建逻辑 | CEASED / DPD91 / null caseInfo / 正常 | `shouldRejectPlan(info, snapshot)` | CEASED/DPD91/null→reject(true)；正常→false | `MockPlanFactory.shouldRejectPlan` 返回值断言 | L0c | `MockPlanFactoryGuardTest#rejectCeasedCaseStatus`/`rejectDpd91`/`rejectNullCaseInfo`/`allowNormalCase`（channel） | ✅ |
| **①-S9** | ③ stage 为空 | 事件无 stage 且 `getCaseInfo().getStage()` 为 null | `CASE_INGESTED`（无 stage） | 跳过建计划（warn） | `planFactory.create` 从未调用；`savePlan` 从未调用 | L0 | ⬜ 主架构 / 内存（**差集 D21** 同例可附断言，边界） | ⬜ |
| **①-S10** | ②'③⑥⑦ STAGE_CHANGED 建新阶段 | 案件存活，存在旧阶段(S2)活跃计划 | `STAGE_CHANGED`（stage=S3） | 旧计划 `PLAN_CANCELLED(STAGE_UPGRADE)` + 新 S3 计划 savePlan | `updatePlanStatus(_, PLAN_CANCELLED, STAGE_UPGRADE)`；`savePlan` 调用（新阶段） | L0 | `PlanLifecycleManagerTest#onStageChanged_cancelsOldAndCreatesNew`（#26；交叉链路⑤） | ✅ |
| **①-S11** | ④ 建计划阶段读失败（边界） | `CaseService.getCaseInfo` 抛异常 | `CASE_INGESTED` | **异常上抛→事务回滚→NACK 重消费**（建计划阶段**无** fail-close；区别执行阶段 §3.1② PreFlight fail-close→skip） | `onCaseIngested(...)` 抛异常；`savePlan` 从未调用 | L0 | ⬜ 主架构 / 内存（**差集 D25**，预计 +1 `@Test`：`onCaseIngested_caseServiceReadFailure_propagates`；澄清 §11-10） | ⬜ |
| **①-S12** | ⑥ 快照字段缺失（边界） | snapshot=null 或字段缺失 | `CASE_INGESTED` | null-safe：CEASED 快照检查跳过、`toJson(null)`，仍建计划；取号缺失留待执行阶段 Guard（方案A） | `savePlan` 调用；不因 snapshot 字段缺失抛异常 | L0 | ⬜ 主架构 / 内存（边界，可并入 D23/D21 断言；本链路非强制） | ⬜ |
| **①-S13** | ①→⑦ L1 入案建三步计划 | StubCaseService + ThreeChannelPlanFactory | `CASE_INGESTED`（stage=S1）→驱动执行 | 建 SMS→PUSH→EMAIL 三步计划并执行至 PLAN_COMPLETED | 计划存在、三步、闭环（见链路②） | L1 | `FullChainIntegrationTest#caseIngested_runsThreeChannels_toCompleted` | ✅ |
| **①-S14** | ①→⑦ L2 入案建单步计划 | SingleStepPlanFactory（替身） | `CASE_INGESTED`→`ingestAndRunDue` | 建单步计划（C1–C7 前置） | 单步计划落库（替身） | L2 | `ChannelContractL2Test#ingestAndRunDue`（C1–C7 前置） | 🟡 |
| **①-S15** | ②'③⑥⑦ STAGE_CHANGED 建新阶段（内存集成） | 入案建 S1 计划（不驱动到期）后阶段变更 | `CASE_INGESTED(S1)`→`STAGE_CHANGED(S2)`→`drainAll` | 旧 S1 计划 `PLAN_CANCELLED(STAGE_UPGRADE)` + 新 S2 计划 PENDING | 旧 plan `status==PLAN_CANCELLED && cancelReason==STAGE_UPGRADE`；新 plan `stage==S2 && status==PENDING` | L1 | ⬜ 主架构 / 内存（**差集 D24**，预计 +1 `@Test`：`stageChanged_cancelsOldAndBuildsNewStagePlan`，见 §3.2.6 骨架） | ⬜ |
| **①-L3** | ⑦ 真实落库 | 真实 Mapper + MySQL | 应用/集成 | `ContactPlanMapper` 落库 PENDING + `context_snapshot` JSON 序列化往返 | 计划/步骤/snapshot 往返一致 | L3 | ⬜ 服务同事 / MySQL·Testcontainers（**差集 D14**） | ⬜ |
| **①-L4** | ⑤⑥ 真实计划结构 | 真实 `DefaultPlanFactory` + Nacos | 应用 | step 序列/模板/语气/观察期符合规格 | 计划结构断言 | L4 | 外链：`TC-PLAN-STRUCT-S1`/`TC-PLAN-S0`/`TC-PLAN-TONE-02`/`TC-PLAN-STRUCT-COMMON`；归属：编排同事（**差集 D15**） | ⬜ |

#### 3.2.2 主架构 vs 编排分工 + 协作契约校验点

| 环节 | 主架构（engine） | 编排同事（channel，PlanFactory SPI） | 协作契约校验点 |
|---|---|---|---|
| 事件路由/分发 | ✅ 全责（Dispatcher / onCaseIngested / onStageChanged） | — | — |
| 建计划守卫链 | ✅ stage 空跳过 / 单活跃计划幂等 / CEASED 前置（caseStatus + snapshot collectionStatus） | — | 单活跃计划约束（`caseId+stage` 唯一） |
| 读案件+快照 | 调用 + 读失败异常上抛（NACK） | — | `CaseService` 契约：getCaseInfo/getContextSnapshot 字段（取号口径见 ContextSnapshot 契约） |
| `PlanFactory.create`（SPI） | 声明接口 + 调用 + null→不建 + 异常/超时→NACK | ✅ 实现**计划结构**：步数/渠道序列/语气(scriptSlot)/观察期/delay；拒建逻辑(`shouldRejectPlan`：CEASED/DPD91/null) | **计划结构契约**：`ContactPlan.steps`（stepOrder/channelType/delayMinutes/observationMinutes/templateId）；返回 null=不建计划 |
| 快照冻结 | ✅ 创建时 `contextSnapshot=toJson(snapshot)` 冻结 | 读冻结快照取号（执行阶段） | `context_snapshot` JSON 结构（ContextSnapshot 契约/sample.json） |
| 首步 trigger_time | ✅ stepOrder=1、`now+max(0,delay)`、PENDING | delayMinutes 由计划结构决定 | 首步触发时机 |
| idempotency_key | ✅ 计划级 `caseId:stage:millis`（注：步骤级幂等 key=`planId:stepOrder:retryCount` 见链路②/§11-7） | — | `t_contact_plan.idempotency_key` 口径 |
| 落库 | 调用 `savePlan` | — | `t_contact_plan`(+step) DDL 映射（L3 服务同事） |

> **计划结构 = 编排侧验收基线**：步数/渠道序列/语气/观察期/delay 的真值由编排 `DefaultPlanFactory` 决定，真值校验归 L4（`TC-PLAN-STRUCT-*`/`TC-PLAN-TONE-02`）；引擎侧只断言「PENDING+首步 trigger_time+snapshot 冻结+幂等/CEASED 守卫」。

#### 3.2.3 映射 §10.1 状态迁移（沿用旧编号，不新造）

| 场景ID | §10.1 落点 | 旧#编号 / TC-ID |
|---|---|---|
| ①-S1/S2/S3 | M1（∅→PENDING，CASE_INGESTED） | #20 |
| ①-S4 | M1 幂等（单活跃计划约束） | #21 |
| ①-S5/S9 | M1 否决分支（工厂 null / stage 空→不建，差集 D21） | —（应补） |
| ①-S6/S7 | M21 相关（CASE_CEASED 不建/停催）；引擎 CEASED 前置（差集 D22） | —（应补，见 §11-1） |
| ①-S8 | M21（CEASED）/ DPD91 拒建逻辑（channel） | `MockPlanFactoryGuardTest` |
| ①-S10/S15 | M16（任意非终态→PLAN_CANCELLED(STAGE_UPGRADE)+新建） | #26（交叉链路⑤） |
| ①-S11 | §5.1 fail-close 边界（建计划阶段 NACK，差集 D25/§11-10） | —（应补） |
| ①-S13/S14 | §9 链路①×L1/L2 | FullChain / C1–C7 前置 |
| ①-L3/L4 | §9 链路①×L3/L4 | D14 / D15·外链 |

#### 3.2.4 覆盖结论（应覆盖 N / 已覆盖 M / 差集）

- **应覆盖 N = 15**（①-S1～S15，不含 L3/L4 真实环境行）：L0 分支 11（S1–S7/S9/S11/S12 等可内存断言项）+ L0c 1（S8 channel）+ L1 2（S13 入案、S15 阶段变更）+ L2 1（S14 前置）。
- **已覆盖 M = 6**：L0 `#20`（S1）/`#21`（S4）/`#26`（S10）+ L0c `MockPlanFactoryGuardTest` 4 例（S8）+ L1 `FullChain` 入案（S13）+ L2 前置 🟡（S14，C1–C7 装配）。（按场景计 6 行 ✅/🟡。）
- **差集 = 9 行（S2/S3/S5/S6/S7/S9/S11/S12/S15），归 5 项（D21–D25）**：
  - **D21**（工厂 null / stage 空→不建计划，引擎 L0 无断言：S5/S9）——**内存可行**，预计 L0 +1 `@Test`。
  - **D22**（引擎 CEASED 前置守卫 caseStatus/snapshot→跳过工厂，L0 无断言：S6/S7）——**内存可行**，预计 L0 +2 `@Test`。
  - **D23**（首步 trigger_time(delay=0/>0) + snapshot 冻结 JSON 断言：S2/S3）——**内存可行**，预计 L0 +1 `@Test`。
  - **D24**（STAGE_CHANGED 建新阶段 L1 集成无载体：S15）——**内存可行**，预计 L1 +1 `@Test`（见 §3.2.6 骨架）。
  - **D25**（建计划阶段 CaseService 读失败→NACK 边界，L0 无断言：S11）——**内存可行**，预计 L0 +1 `@Test`（澄清 §11-10）。
  - **D14**（L3 ContactPlanMapper 落库 + snapshot 往返：①-L3）——服务同事 / MySQL，**待环境，不出骨架**。
  - **D15**（L4 真实 `DefaultPlanFactory` 计划结构：①-L4）——编排 / 应用+Nacos，**待环境，不出骨架**（外链 `TC-PLAN-STRUCT-*`）。
  - 预计可内存补 **L0 +5（D21/D22/D23/D25）+ L1 +1（D24）= +6 `@Test`**；**补码前不计入 §1 计数表**。

#### 3.2.5 待澄清（本链路）

- **CASE_CEASED 建计划 null 路径（核对 §11-1，已部分修正）**：链路① 的「CEASED→不建计划」由 `createPlanForStage` 引擎前置守卫（`caseStatus=CEASED` / `snapshot.caseContext.collectionStatus=CEASED`）+ 渠道工厂 `shouldRejectPlan` 返回 null **共同**实现，**不依赖** `CancelReason.CEASED`（后者用于链路⑤停催取消）。代码现状：`CancelReason.CEASED`（`CancelReason.java` L12）、`EventType.CASE_CEASED`、`PlanLifecycleManager#onCaseCeased` **均已存在**——故 §11-1「CancelReason 无 CEASED」前提已过时，已更新该条（见 §11-1）；其残留待办收窄为渠道 `TC-CEASED-*` 口径与链路⑤ `onCaseCeased` 的 L0 回补（非本链路）。
- **建计划阶段 vs 执行阶段守卫边界（新登 §11-10，已澄清）**：§3.1②「系统守卫 PreFlightChecker fail-close」是**执行阶段**（`executeStep`）守卫——读失败→`check=false`→静默退出（不 NACK）；而**建计划阶段**（`onCaseIngested→createPlanForStage`）的 `CaseService.getCaseInfo/getContextSnapshot` 读失败**无 fail-close**，异常上抛→`@Transactional` 回滚→NACK 重消费（计划丢失=案件完全无触达，必须重试）。两者语义不同，差集 D25 据此断言。

#### 3.2.6 补测骨架（草案：需本地 `mvn test` 编译运行确认，未保证一次通过）

> 仅对「内存可行」且当前无载体的 **D24**（STAGE_CHANGED 建新阶段 L1）出骨架。D21/D22/D23/D25（L0）为对既有 `PlanLifecycleManagerTest` 范式的同构补例（mock SPI/Repo + `SpiInvoker.direct()`），按差集登记预计 `@Test` 数，补码时照 #20/#21 范式书写；D14/D15 待 L3/L4 环境，只登差集不出骨架。

```java
// 追加到 collection-engine/.../integration/FullChainIntegrationTest
// 草案：复用其 wire()（ThreeChannelPlanFactory + StubCaseService + 内存仓储/总线）
@Test
@DisplayName("①-S15 入案建 S1 计划 → STAGE_CHANGED(S2) → 旧 S1 计划 PLAN_CANCELLED(STAGE_UPGRADE) + 新 S2 计划 PENDING")
void stageChanged_cancelsOldAndBuildsNewStagePlan() {
    // 入案建 S1 计划（不驱动到期步骤，保持 S1 计划活跃）
    bus.publish(
            CollectionEvent.of(EventType.CASE_INGESTED)
                    .with(CollectionEvent.CASE_ID, CASE_ID)
                    .with(CollectionEvent.USER_ID, USER_ID)
                    .with(CollectionEvent.STAGE, Stage.S1.name()));
    bus.drainAll();

    // 阶段变更 → S2
    bus.publish(
            CollectionEvent.of(EventType.STAGE_CHANGED)
                    .with(CollectionEvent.CASE_ID, CASE_ID)
                    .with(CollectionEvent.STAGE, Stage.S2.name()));
    bus.drainAll();

    ContactPlan s1 = findPlanByStage(Stage.S1);
    ContactPlan s2 = findPlanByStage(Stage.S2);

    // 旧 S1 计划取消（STAGE_UPGRADE）
    assertThat(s1).isNotNull();
    assertThat(s1.getStatus()).isEqualTo(PlanStatus.PLAN_CANCELLED);
    assertThat(s1.getCancelReason()).isEqualTo(CancelReason.STAGE_UPGRADE);
    // 新 S2 计划建立（PENDING）
    assertThat(s2).isNotNull();
    assertThat(s2.getStatus()).isEqualTo(PlanStatus.PENDING);
}

/** 从内存仓储按 stage 取计划（测试辅助）。 */
private ContactPlan findPlanByStage(Stage stage) {
    for (ContactPlan p : planRepo.plans.values()) {
        if (p.getStage() == stage) {
            return p;
        }
    }
    return null;
}
```

> **草案标注**：上述 1 例需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过；通过后回填附录 A 与 §1 计数表。

---

## §4 链路② 调度执行链

### 4.1 链路定义卡

| 项 | 内容 |
|---|---|
| **触发事件** | `PLAN_STEP_DUE`（XXL-Job 扫到期步骤发布） |
| **经过组件（模块/负责人）** | Dispatcher → `PlanLifecycleManager.prepareStepDue`（分流器，事务内锁→状态前置）→ COMMIT → `StepExecutionOrchestrator.executeStep`（七步管线，事务外）→ `PreFlightChecker`（②）+ `ExecutionGuard`（③ SPI）+ `StepResolver`（④ SPI）+ `ChannelGateway`（⑤）+ `ContextAssembler` |
| **关键分支** | 分流：PENDING/SCHEDULED/EXECUTING→`STEP_EXECUTING` 执行；STEP_WAITING→结转（见链路③）；终态→noop。七步：①幂等锁 ②系统守卫 ③业务守卫 ④解析 ⑤渠道 ⑤½取消复检 ⑥降级 ⑦分流 |
| **终态/输出** | 消息类无观察期→`STEP_COMPLETED` 事件；消息类有观察期→`STEP_WAITING`（链路③）；电话类→保持 `STEP_EXECUTING` + 注册超时哨兵（链路④） |
| **数据落点** | `t_contact_plan_step`（status/result/retry_count/trigger_time/timeout_time）、`t_contact_plan`（status）、`t_contact_timeline`（发出/SKIPPED/FAILED 均写） |
| **验收断言** | 守卫拦截→SKIPPED+COMPLIANCE_BLOCKED+推进；解析异常→FAILED+推进、返回 null→SKIPPED+推进；dispatch retryable→退避重试（保持 EXECUTING）、不可重试/超上限→FAILED+推进；幂等 key=`planId:stepOrder:retryCount`；⑤½ 计划已取消/不存在→仅记 timeline 不推进 |

### 4.2 单场景级可执行用例表（就地展开，可直接替换旧分层覆盖表）

> 本节把链路②从「定义卡」就地展开为**步骤分解 + 单场景用例 + 分工/契约 + §10 映射 + 覆盖结论 + 待澄清/骨架**六部分。引用方法名与附录 A 已核对清单一致；未实现用例标 ⬜ 写清归属/环境，**不计入 §1 计数表**（补码后再同步）。定义卡 §4.1 保留。

#### 4.2.0 步骤分解（分流 + 七步，有序）

| # | 步骤 | 组件 / 模块 / 负责人 | 输入 | 输出 | 关键分支 | 代码位置（类#方法） |
|---|---|---|---|---|---|---|
| ⓪ | 到期分流（事务内：锁→校验终态→状态前置→COMMIT） | `PlanLifecycleManager` / engine / 主架构 | `PLAN_STEP_DUE`（planId/stepId） | `StepDuePreparation`（toExecute(plan,step) / noop+结转事件） | PENDING/SCHEDULED/EXECUTING→`STEP_EXECUTING`+toExecute；STEP_WAITING→结转（链路③）；终态/null→noop | `PlanLifecycleManager#prepareStepDue`（L120–156）；路由 `EventConsumerDispatcher#onPlanStepDue`（L48–57） |
| ① | 幂等锁（事务外起） | `StepExecutionOrchestrator`+`IdempotencyService` / engine / 主架构 | `plan`+`step` | key=`planId:stepOrder:retryCount` | acquire 成功→继续；未获取→静默退出 | `StepExecutionOrchestrator#executeStep`（L53–58）、`#buildIdempotencyKey`（L249–251） |
| ② | 系统级守卫（实时查 DB） | `PreFlightChecker` / engine / 主架构 | caseId | true/false | 通过(#5d)；不存在/已还款/冻结→false(#5a–#5c)；读失败 fail-close→false(#5e) | `StepExecutionOrchestrator#executeStep`（L60–63）、`PreFlightChecker#check` |
| ③ | 业务级守卫（合规 SPI，硬超时 20ms） | `ExecutionGuard`（SPI，channel） + `SpiInvoker` / 主架构调用·编排实现 | `ExecutionContext` | `GuardVerdict`(allow/block) | allow→继续；block→SKIPPED(COMPLIANCE_BLOCKED)+推进；异常/超时 fail-close→SKIPPED+推进 | `StepExecutionOrchestrator#executeStep`（L68–87）、`#markSkipped`（L192–197） |
| ④ | 步骤解析（SPI，硬超时 50ms） | `StepResolver`（SPI，channel） + `SpiInvoker` / 主架构调用·编排实现 | `ExecutionContext` | `StepCommand` / null | 正常→继续；异常/超时→FAILED+推进；返回 null→SKIPPED+推进 | `StepExecutionOrchestrator#executeStep`（L89–106）、`#markFailed`（L199–203） |
| ⑤ | 渠道调度 | `ChannelGateway`（channel） / 主架构调用·编排实现 | `StepCommand` | `StepResult`（3 情形） | success；抛异常→视为 retryable；success=false+retryable；success=false+不可重试 | `StepExecutionOrchestrator#executeStep`（L108–121） |
| ⑤½ | 回写前取消复检 | `StepExecutionOrchestrator`+`ContactPlanRepository#findById` / 主架构 | planId 重载 | 继续 / 仅记录 | 非终态→继续；终态→记 timeline 不推进；null→记 timeline 不推进 | `StepExecutionOrchestrator#executeStep`（L123–134） |
| ⑥ | 故障降级（退避/封顶） | `StepExecutionOrchestrator` / 主架构 | `StepResult`(fail) | 退避重试 / FAILED | retryable 且 retryCount<max→退避(保持 EXECUTING)；否则 FAILED+推进；退避=base×factor^attempt 封顶 max | `StepExecutionOrchestrator#executeStep`（L136–155）、`#computeBackoffSeconds`（L234–239） |
| ⑦ | 渠道分流 + 写 timeline | `StepExecutionOrchestrator` / 主架构 | `StepResult`(success) | COMPLETED / WAITING / 保持 EXECUTING | 先 `writeTimeline`；消息类无观察期→COMPLETED+发 STEP_COMPLETED；有观察期→STEP_WAITING；电话类→保持 EXECUTING+`updateStepTimeoutTime`（链路④） | `StepExecutionOrchestrator#executeStep`（L157–189）、`#writeTimeline`（L214–232） |

#### 4.2.1 单场景用例表（每分支一行）

> 前置统一：caseId=1002 / userId=9001 / stage=S2 / plan `STEP_EXECUTING` / step 默认 SMS（除注明）。「层级」L0=纯逻辑、L1=内存集成、L2=引擎↔渠道契约。触发列「`executeStep`」指 mock 装配后直接调用七步管线。

| 场景ID | 链路步骤 | 前置 | 触发（事件+载荷） | 期望结果 | 数据断言（step.status/result/retry/trigger/timeout、plan.status、timeline、事件） | 层级 | 已覆盖载体（真实#方法 / TC-ID / ⬜归属·环境） | 状态 |
|---|---|---|---|---|---|---|---|---|
| **②-S1** | ⓪ 分流·首执行 | plan PENDING/SCHEDULED/EXECUTING，step PENDING+trigger 到期 | `PLAN_STEP_DUE` | 置 `STEP_EXECUTING`+markStarted+清 trigger，返回 toExecute | `updatePlanStatus(_, STEP_EXECUTING, null)`、`markStarted`、`prep.isExecute()==true` | L0 | `PlanLifecycleManagerTest#prepareStepDue_toExecute`（#17） | ✅ |
| **②-S2** | ⓪ 分流·终态 noop | plan 终态（PLAN_CANCELLED 等） | `PLAN_STEP_DUE` | noop，不改状态、不执行 | `prep.isExecute()==false`；`updatePlanStatus` 从未调用 | L0 | `PlanLifecycleManagerTest#prepareStepDue_terminalNoop`（#19） | ✅ |
| **②-S3** | ① 幂等锁·获取成功 | happy 装配 | `executeStep` | 继续七步至 COMPLETED+发布 | `idempotencyService.acquire` 调用；`channelGateway.dispatch` 调用；发 STEP_COMPLETED | L0 | `MessageChannelHappyPathTest#messageChannel_noObservation_completesAndPublishes`（#1） | ✅ |
| **②-S4** | ① 幂等锁·未获取静默 | `acquire` 返回 false | `executeStep` | 直接静默退出，不触达 | `preFlightChecker.check` 从未调用；`dispatch` 从未调用；`publish` 从未调用 | L0 | `MessageChannelHappyPathTest#duplicateEvent_skips`（#3） | ✅ |
| **②-S5** | ② 系统守卫·通过 | 案件存活（未还款/未冻结） | `check(caseId)` | true，可触达 | `check(...)==true` | L0 | `PreFlightCheckerTest#alive_returnsTrue`（#5d） | ✅ |
| **②-S6** | ② 系统守卫·拦截 | 案件不存在/已还款/冻结 | `check(caseId)` | false | `#5a` null→false / `#5b` 已还款→false / `#5c` 冻结→false | L0 | `PreFlightCheckerTest#caseNotFound_returnsFalse`/`repaid_returnsFalse`/`frozen_returnsFalse`（#5a–#5c） | ✅ |
| **②-S7** | ② 系统守卫·读失败 fail-close | `getCaseInfo` 抛异常 | `check(caseId)` | fail-close→false | `#5e` 读失败→false | L0 | `PreFlightCheckerTest#readFailure_failCloseReturnsFalse`（#5e） | ✅ |
| **②-S8** | ② 系统守卫·拦截后静默退出 | `preFlightChecker.check` 返回 false | `executeStep` | 静默退出，不 dispatch、不发布、不置 EXECUTING | `dispatch` 从未调用；`publish` 从未调用；`updateStepStatus(_, EXECUTING, _)` 从未调用 | L0 | `StepExecutionOrchestratorTest#preflightFail_silentExit`（#5） | ✅ |
| **②-S9** | ③ 业务守卫·放行 | guard allow | `executeStep` | 继续解析/渠道 | 进入 dispatch（happy 链） | L0 | `MessageChannelHappyPathTest#…noObservation…`（happy） | ✅ |
| **②-S10** | ③ 业务守卫·block | guard `block("freq","FREQUENCY_LIMIT")` | `executeStep` | SKIPPED(COMPLIANCE_BLOCKED)+推进，不 dispatch | `updateStepStatus(stepId, SKIPPED, COMPLIANCE_BLOCKED)`；`publish` 调用；`dispatch` 从未调用 | L0 | `StepExecutionOrchestratorTest#guardBlocked_skipped`（#6） | ✅ |
| **②-S11** | ③ 业务守卫·异常 fail-close | guard 抛异常 | `executeStep` | fail-close→SKIPPED+推进 | `updateStepStatus(stepId, SKIPPED, COMPLIANCE_BLOCKED)`；`publish` 调用；`dispatch` 从未调用 | L0 | `StepExecutionOrchestratorTest#guardException_failCloseSkipped`（#7） | ✅ |
| **②-S12** | ④ 解析·正常 | resolver 返回 `StepCommand` | `executeStep` | 继续渠道调度 | 进入 dispatch | L0 | `MessageChannelHappyPathTest`（happy） | ✅ |
| **②-S13** | ④ 解析·异常 | resolver 抛异常 | `executeStep` | FAILED+推进 | `updateStepStatus(stepId, FAILED, FAILED)`；`publish` 调用；`dispatch` 从未调用 | L0 | `StepExecutionOrchestratorTest#resolverException_failed`（#8a） | ✅ |
| **②-S14** | ④ 解析·null | resolver 返回 null | `executeStep` | SKIPPED(SKIPPED)+推进（主动跳过，非失败） | `updateStepStatus(stepId, SKIPPED, SKIPPED)`；`publish` 调用；`dispatch` 从未调用 | L0 | `StepExecutionOrchestratorTest#resolverNull_skipped`（#8b） | ✅ |
| **②-S15** | ⑤ 渠道·success | gateway 受理成功 | `executeStep` | 进⑦分流 | `dispatch` 调用；timeline 落 providerMsgId | L0/L2 | `StepExecutionOrchestratorTest#push_noObservation_completes`（#4）/ `ChannelContractL2Test#c1_dispatchSuccess…`（C1） | ✅ |
| **②-S16** | ⑤ 渠道·抛异常→retryable | gateway 抛 RuntimeException | `executeStep` | 视为 retryable→退避重试 | `incrementRetryCount`；`updateStepTriggerTime(_, future, PENDING)`；未置 FAILED | L0 | `StepExecutionOrchestratorTest#channelException_retryable`（#9） | ✅ |
| **②-S17** | ⑤ 渠道·失败 retryable 未超上限 | `StepResult(success=false,retryable=true)`，retryCount<max | `executeStep` | 退避重试，保持 EXECUTING | `incrementRetryCount`；`updateStepTriggerTime(_, future, PENDING)`；未置 FAILED；plan 保持 STEP_EXECUTING | L0/L2 | `StepExecutionOrchestratorTest#retryableUnderLimit_reschedule`（#10）/ `ChannelContractL2Test#c4_networkTimeout_retryable…`（C4） | ✅ |
| **②-S18** | ⑤/⑥ 渠道·失败不可重试 | `StepResult(success=false,retryable=false)`，retryCount=0 | `executeStep` | FAILED+推进，**不**退避 | `updateStepStatus(stepId, FAILED, FAILED)`；`publish` 调用；`incrementRetryCount` 从未；plan 推进至 PLAN_COMPLETED | L0/L2 | L2 ✅ `ChannelContractL2Test#c5_invalidAddress_notRetryable…`（C5）；L0 ⬜ 主架构/内存（**差集 D20**，预计 +1 `@Test`：`notRetryable_failedNoBackoff`，见 §4.2.6 骨架） | 🟡（L2✅/L0应补） |
| **②-S19** | ⑤½ 取消复检·终态 | dispatch 后 `findById` 返回终态 plan | `executeStep` | 仅记 timeline，不推进 | `timelineRepository.writeTimeline` 调用；`updateStepStatus(_, COMPLETED, _)` 从未；`publish` 从未 | L0 | `StepExecutionOrchestratorTest#cancelledDuringDispatch_recordOnly`（#12） | ✅ |
| **②-S20** | ⑤½ 取消复检·null | dispatch 后 `findById` 返回 null | `executeStep` | 仅记 timeline，不推进 | `writeTimeline` 调用；`updateStepStatus(_, COMPLETED, _)` 从未；`publish` 从未 | L0 | `StepExecutionOrchestratorTest#reloadNull_recordOnly`（#32） | ✅ |
| **②-S21** | ⑥ 降级·超上限 FAILED | `success=false,retryable=true`，retryCount==max(3) | `executeStep` | FAILED+推进，不再重试 | `updateStepStatus(stepId, FAILED, FAILED)`；`publish` 调用；`incrementRetryCount` 从未 | L0 | `StepExecutionOrchestratorTest#retryExceeded_failed`（#11） | ✅ |
| **②-S22** | ⑥ 降级·退避递增 | retryCount=2→newCount=3，base30×factor2^3 | `executeStep` | 退避≈240s | 捕获 `updateStepTriggerTime` 时间差∈[240,250]s | L0 | `StepExecutionOrchestratorTest#backoff_growsByAttempt`（#28） | ✅ |
| **②-S23** | ⑥ 降级·退避封顶 | `retryMaxIntervalSeconds=100`，应得 240→封顶 | `executeStep` | 退避≈100s（取上限） | 捕获时间差∈[100,110]s | L0 | `StepExecutionOrchestratorTest#backoff_cappedAtMax`（#29） | ✅ |
| **②-S24** | ⑦ 分流·消息无观察期 | message 渠道，observation=0，dispatch success | `executeStep` | COMPLETED+发 STEP_COMPLETED | `updateStepStatus(stepId, COMPLETED, DELIVERED)`；`writeTimeline`；发 STEP_COMPLETED；未置 WAITING | L0 | `MessageChannelHappyPathTest#…noObservation…`（#1）/`StepExecutionOrchestratorTest#push_noObservation_completes`（#4） | ✅ |
| **②-S25** | ⑦ 分流·消息有观察期 | message 渠道，observation=30，dispatch success | `executeStep` | STEP_WAITING（不立即完成） | `updateStepTriggerTime(_, future, EXECUTING)`；`updatePlanStatus(_, STEP_WAITING, null)`；`publish` 从未 | L0 | `MessageChannelHappyPathTest#messageChannel_withObservation_entersWaiting`（#2） | ✅ |
| **②-S26** | ⑦ 分流·电话类 | AI_CALL，dispatch success | `executeStep` | 保持 EXECUTING+注册超时哨兵（进链路④） | `updateStepTimeoutTime(stepId, any)`；`publish` 从未；未置 WAITING | L0 | `StepExecutionOrchestratorTest#asyncChannel_registersTimeout`（#13；详见 §6.2 链路④） | ✅ |
| **②-S27** | ① 幂等 key 构成 | step.stepOrder=1，retryCount=2 | `executeStep` | key=`planId:1:2`（含 retryCount 防自锁） | `acquire(eq("100:1:2"), anyInt())` | L0 | `StepExecutionOrchestratorTest#idempotencyKey_includesRetryCount`（#31） | ✅ |
| **②-S28** | L1 三渠道取址执行 | 入案建 SMS→PUSH→EMAIL 三步计划 | `CASE_INGESTED`→循环 `PLAN_STEP_DUE` | 三步全 COMPLETED，按 channelType 取址（SMS=phone/PUSH=jpushToken/EMAIL=email） | `plan.status==PLAN_COMPLETED`；三步 COMPLETED；`timeline` 3 条；targetByChannel 正确 | L1 | `FullChainIntegrationTest#caseIngested_runsThreeChannels_toCompleted` | ✅ |
| **②-S29** | C7 幂等·重复 DUE | 单步计划，连发两次 `PLAN_STEP_DUE` | 同 (plan,step,retryCount=0) ×2 | 仅一次实际 dispatch | `gateway.count==1` | L2 | `ChannelContractL2Test#c7_idempotency_duplicateEventNoDoubleDispatch`（C7） | ✅ |
| **②-S30** | ③ 业务守卫·空 phone SKIP（方案A） | SMS，快照 phone 为空，guard block `NO_PHONE` | `executeStep` | block→SKIPPED+推进，不 dispatch | `updateStepStatus(stepId, SKIPPED, COMPLIANCE_BLOCKED)`；`gateway.count==0`；plan 推进 | L2 | ⬜ 主架构(替身)+编排 / 内存（**差集 D5**，预计 +1 `@Test`：`c9_emptyPhone_guardSkips_noDispatch`，见 §4.2.6 骨架） | ⬜ |
| **②-S31** | ③ 业务守卫·空 token+空 SMS SKIP（方案A） | PUSH，快照 token 与 phone 均空，guard block `NO_TOKEN` | `executeStep` | block→SKIPPED+推进，不 dispatch（无 fallback） | `updateStepStatus(stepId, SKIPPED, COMPLIANCE_BLOCKED)`；`gateway.count==0`；plan 推进 | L2 | ⬜ 主架构(替身)+编排 / 内存（**差集 D5**，预计 +1 `@Test`：`c10_emptyTokenNoFallback_guardSkips`，见 §4.2.6 骨架） | ⬜ |
| **②-S32** | ③ 守卫规则类型取值 | guard block `TIME_WINDOW`/`CONNECT_AND_STOP`/`ABANDONMENT_RATE` | 真实合规规则触发 | block→SKIPPED（规则真值由编排侧产生） | 引擎只读 `verdict.isAllowed()` 布尔；规则类型语义不在引擎断言 | L4 | ⬜ 编排 / 应用+Nacos（**差集 D6**，外链 `TC-GUARD-02/04`；引擎侧无意义，**不出骨架**） | ⬜ |
| **②-S33** | ⑥⑦ 写失败 NACK | `register_job`/`updateStepStatus`/DB 写失败 | `executeStep`（注入写异常） | 异常上抛→事务回滚/NACK→延迟重消费 | 需连库 + NACK 注入；内存总线无 NACK 语义 | L3 | ⬜ 主架构+服务同事 / MySQL（**差集 D10**，**不出骨架**，待 L3 环境） | ⬜ |
| **②-L4** | 单渠道真实发送冒烟 | 真实供应商 | 应用触发 | SMS/PUSH/EMAIL/合规/重试/幂等真实回归 | 真实落库+timeline | L4 | 外链：`TC-SMS-01`/`TC-PUSH-01`/`TC-PUSH-02`/`TC-EMAIL-01`/`TC-EMAIL-02`、`TC-GUARD-01~04`、`TC-RETRY-01`/`TC-IDEM-01`；归属：编排同事 | ⬜ |

#### 4.2.2 主架构 vs 编排分工 + 协作契约校验点

| 环节 | 主架构（engine） | 编排同事（channel，SPI 实现） | 协作契约校验点 |
|---|---|---|---|
| 分流/锁/状态前置 | ✅ 全责（`prepareStepDue` 短事务） | — | — |
| ① 幂等锁 | ✅ key=`planId:stepOrder:retryCount`（含 retryCount 防自锁） | — | 重试不被自身幂等拦截（#31/C7） |
| ② 系统守卫 | ✅ 全责（`PreFlightChecker` 实时查 DB + fail-close） | — | — |
| ③ 业务守卫 `ExecutionGuard` | 声明接口 + 调用 + 异常/超时 fail-close→SKIPPED | ✅ 实现合规逻辑（频率/时段/放弃率/**空地址 NO_PHONE/NO_TOKEN/NO_EMAIL**） | **GuardVerdict 语义**：`block→SKIPPED(COMPLIANCE_BLOCKED)`；`blockedRuleType` 取值（C3/#6） |
| ④ 步骤解析 `StepResolver` | 声明接口 + 调用 + null→SKIPPED / 异常→FAILED | ✅ 实现取址/模板/metadata | **StepCommand 字段**：`channelType`/`targetAddress`（取号口径）/`templateId`/`idempotencyKey`/`metadata`（`fallback_sms`/`timeoutMinutes`/`scriptSlot`…） |
| ⑤ 渠道调度 `ChannelGateway` | 声明接口 + 调用 + 抛异常视为 retryable | ✅ 实现发送/熔断/fallback/对账 | **StepResult 3 情形**：①受理 `success=true`（DELIVERED）；②网络超时 `success=false,retryable=true`；③其他异常 `success=false,retryable=false`；+ `providerMsgId` |
| ⑥ 降级 | ✅ 退避 `base×factor^attempt` 封顶（#28/#29）；超上限→FAILED | — | `maxRetryCount`/`retryBaseIntervalSeconds`/`retryBackoffFactor`/`retryMaxIntervalSeconds` |
| ⑦ 分流 + timeline | ✅ 消息无观察期→COMPLETED / 有观察期→WAITING / 电话类→保持 EXECUTING+超时；统一写 timeline（发出/SKIPPED/FAILED） | ✅ observationMinutes 由计划结构/Resolver 决定 | timeline `providerMsgId` ↔ 发送对账锚点；`observationMinutes` 语义 |

> **StepResult 3 情形 = 双方对接验收基线**：L2 `ChannelContractL2Test` C1（受理）/C4（超时退避）/C5（不可重试 FAILED）即对应三情形，编排真实化 Gateway 后语义一致则「对接即绿」。

#### 4.2.3 映射 §10 场景全集（沿用旧编号，不新造）

| 场景ID | §10 落点 | 旧#编号 / TC-ID |
|---|---|---|
| ②-S1 | §10.1 M2/M3（PENDING/SCHEDULED→STEP_EXECUTING） | #17 |
| ②-S2 | §10.1 M17（终态 noop） | #19 |
| ②-S3/S4 | §10.2 ①幂等锁（成功/未获取静默） | #1 / #3 |
| ②-S5～S7 | §10.3 `PreFlightChecker.check` true/false；§10.2 ②系统守卫 | #5d / #5a–#5c / #5e |
| ②-S8 | §10.2 ②系统守卫拦截（静默退出） | #5 |
| ②-S9～S11 | §10.2 ③业务守卫 allow/block/异常；§10.3 `GuardVerdict` | happy / #6 / #7 |
| ②-S12～S14 | §10.2 ④解析 正常/异常/null | happy / #8a / #8b |
| ②-S15～S18 | §10.4 StepResult 3 情形；§10.2 ⑤渠道 | C1#4 / #9 / #10·C4 / #11·C5 |
| ②-S19/S20 | §10.2 ⑤½ 取消复检（终态/null） | #12 / #32 |
| ②-S21～S23 | §10.2 ⑥降级（超上限/退避递增/封顶） | #11 / #28 / #29 |
| ②-S24～S26 | §10.2 ⑦分流；§10.5 渠道×观察期/异步 | #1/#4 / #2 / #13 |
| ②-S27 | §8 [幂等] key 含 retryCount | #31 |
| ②-S28 | §9 链路②×L1；§10.5 SMS/PUSH/EMAIL happy | FullChain |
| ②-S29 | §8 [幂等]；§10.5 幂等 | C7 |
| ②-S30/S31 | §10.3 守卫空地址 `NO_PHONE/NO_TOKEN`；§10.5 SMS/PUSH 地址缺失 | 差集 **D5**（应补） |
| ②-S32 | §10.3 `GuardVerdict.blockedRuleType` TIME_WINDOW/CONNECT_AND_STOP/ABANDONMENT_RATE | 差集 **D6**（L4 `TC-GUARD-02/04`） |
| ②-S33 | §10.6 L1 register_job/MySQL 写失败 NACK | 差集 **D10**（L3） |

#### 4.2.4 覆盖结论（应覆盖 N / 已覆盖 M / 差集）

- **应覆盖 N = 33**（②-S1～S33；不含 L4 真实回归行）：L0 分支 26（含分流 ⓪、七步全分支、退避、幂等 key）+ L1 1（S28）+ L2 2（S29 幂等、S15/S17/S18 已并入 L0/L2）+ 差集 5（S18 的 L0 不可重试、S30/S31/S32/S33）。
- **已覆盖 M = 29**：L0 25（#1–#13/#28/#29/#31/#32/#5a–#5e/#17/#19）+ L1 1（S28 FullChain）+ L2 3（C1/C4/C5/C7，并入 S15/S17/S18/S29）。
- **差集 = 5 行，归 4 项**：
  - **D20**（⑤渠道失败不可重试 retryable=false→FAILED 的引擎 L0 断言）：②-S18——L2(C5) 已覆盖，**引擎 L0 缺独立断言**，**内存可行**，预计 +1 `@Test`（见 §4.2.6 骨架 L0）。
  - **D5**（空地址 SKIP）：②-S30 `NO_PHONE` / ②-S31 `NO_TOKEN`——**内存可行**，可在 L2 替身断言「block→SKIPPED」语义，预计 +2 `@Test`（见 §4.2.6 骨架）。
  - **D6**（守卫规则类型 TIME_WINDOW/CONNECT_AND_STOP/ABANDONMENT_RATE）：②-S32——引擎只读 `allowed` 布尔，规则真值在编排侧，**归 L4**（`TC-GUARD-02/04`），**不出骨架**。
  - **D10**（⑥⑦ DB/register_job 写失败 NACK）：②-S33——需连库 + NACK 注入，内存总线无 NACK 语义，**归 L3**，**不出骨架**。
  - 预计可内存补 **L0 +1（D20）+ L2 +2（D5）= +3 `@Test`**；**补码前不计入 §1 计数表**，补码后同步 §1/附录 A。

#### 4.2.5 待澄清（本链路）

- **空地址 SKIP 断言归属（方案A，已澄清，见 §11-9）**：依契约定稿「方案A」，空地址（`NO_PHONE`/`NO_TOKEN`/`NO_EMAIL`）由**编排侧 `ExecutionGuard` 返回 `block`**，引擎统一走 `markSkipped`（SKIPPED+COMPLIANCE_BLOCKED+推进）。故：
  - **引擎侧断言**「block→SKIPPED」的通用语义（与具体 rule 码无关）——`NO_EMAIL` 已由 C3 覆盖；`NO_PHONE`/`NO_TOKEN` **可用 L2 替身补断言**（②-S30/S31，差集 D5）。
  - **rule 码真值**（哪种地址缺失映射哪个码）属编排侧 Guard 实现，**真值校验归 L4**（`TC-PUSH-02`/`TC-EMAIL-02`）。
  - PUSH 空 token 但有 SMS 号 → 编排侧 `fallback SMS`（C2，对引擎透明，一次 dispatch 即完成），**不**进 SKIP 分支；仅 token 与 fallback 均空才 `NO_TOKEN` SKIP（②-S31）。

#### 4.2.6 补测骨架（草案：需本地 `mvn test` 编译运行确认，未保证一次通过）

> 对「内存可行」的差集 **D20**（L0）与 **D5**（L2）出骨架。D6（L4）、D10（L3）按约定**只登差集不出骨架**。

**L0 草案 —— 引擎侧不可重试失败（追加到 `StepExecutionOrchestratorTest`，复用其 `stubResolver`/`stubDispatch`/`fail(boolean)` 辅助；对应 ②-S18/D20）**

```java
// 追加到 collection-engine/.../lifecycle/StepExecutionOrchestratorTest
@Test
@DisplayName("#11b 发送失败不可重试(retryable=false) → FAILED + 推进（不退避，区别于 #10/#11）")
void notRetryable_failedNoBackoff() {
    stubResolver(ChannelType.SMS);
    stubDispatch(fail(false)); // success=false, retryable=false
    step.setRetryCount(0);     // 未达上限，但因不可重试，直接 FAILED

    orchestrator.executeStep(plan, step);

    verify(planRepository).updateStepStatus(STEP_ID, StepStatus.FAILED, ContactResult.FAILED);
    verify(eventBus).publish(any());                       // 失败也推进
    verify(planRepository, never()).incrementRetryCount(STEP_ID); // 不退避
    verify(planRepository, never())
            .updateStepTriggerTime(eq(STEP_ID), any(), eq(StepStatus.PENDING));
}
```

**L2 草案 —— 空地址 SKIP（D5）**
> 追加到 `ChannelContractL2Test`，复用其 `ConfigurableGuard`/`SingleStepPlanFactory`/`MutableSnapshotCaseService`/`ConfigurableGateway` 替身与 `ingestAndRunDue()`/`onlyStep()`/`onlyPlan()`。

```java
// 追加到 collection-engine/.../integration/ChannelContractL2Test
// 草案：验证方案A——空地址由 Guard block→引擎 markSkipped(SKIPPED+COMPLIANCE_BLOCKED)+推进，不 dispatch
@Test
@DisplayName("C9 SMS phone 为空 → Guard block NO_PHONE → 步骤 SKIPPED + 推进，不 dispatch（方案A/D5）")
void c9_emptyPhone_guardSkips_noDispatch() {
    caseService.phone = null;                 // SMS 取号为空
    planFactory.channel = ChannelType.SMS;
    planFactory.observationMinutes = 0;
    guard.behavior =
            ctx -> {
                UserProfile.BasicInfo b = ctx.getContextSnapshot().getUserProfile().getBasic();
                if (b == null || b.getPrimaryPhone() == null || b.getPrimaryPhone().isEmpty()) {
                    return GuardVerdict.block("NO_PHONE", "ADDRESS_MISSING");
                }
                return GuardVerdict.allow();
            };

    ingestAndRunDue();

    assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.SKIPPED);
    assertThat(gateway.count.get()).isZero();                       // 未触达
    assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED); // 推进至穷尽 COMPLETE
}

@Test
@DisplayName("C10 PUSH token 与 SMS 号均空 → Guard block NO_TOKEN → SKIPPED + 推进，不 dispatch（方案A/D5）")
void c10_emptyTokenNoFallback_guardSkips() {
    caseService.jpushToken = null;            // 无 token
    caseService.phone = null;                 // 且无 fallback SMS 号
    planFactory.channel = ChannelType.PUSH;
    planFactory.observationMinutes = 0;
    guard.behavior =
            ctx -> {
                UserProfile.BasicInfo b = ctx.getContextSnapshot().getUserProfile().getBasic();
                UserProfile.DeviceInfo d = ctx.getContextSnapshot().getUserProfile().getDevice();
                boolean noToken = d == null || d.getJpushToken() == null || d.getJpushToken().isEmpty();
                boolean noFallback = b == null || b.getPrimaryPhone() == null || b.getPrimaryPhone().isEmpty();
                if (noToken && noFallback) {
                    return GuardVerdict.block("NO_TOKEN", "ADDRESS_MISSING");
                }
                return GuardVerdict.allow();
            };

    ingestAndRunDue();

    assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.SKIPPED);
    assertThat(gateway.count.get()).isZero();
    assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
}
```

> **草案标注**：上述 2 例需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过；通过后回填附录 A（`ChannelContractL2Test` C1–C7 → C1–C10）与 §1 计数表（引擎 L2 7→9、模块 55→57、全仓 82→84）。

---

## §5 链路③ 结果回收链

### 5.1 链路定义卡

| 项 | 内容 |
|---|---|
| **触发事件** | `STEP_COMPLETED`（步骤完成，引擎内部链式发布）；观察期到期 `PLAN_STEP_DUE`（STEP_WAITING 分支结转） |
| **经过组件（模块/负责人）** | Dispatcher → `PlanLifecycleManager.onStepCompleted`（engine/主架构）→ `AdvancementPolicy.decide`（**SPI**，channel/编排同事）；观察期结转走 `prepareStepDue`（STEP_WAITING 分支） |
| **关键分支** | `AdvancementDecision`：`ADVANCE_NEXT`（有下一步：delay>0→注册 Job+STEP_SCHEDULED / delay=0→立即 execute_step）/ `PLAN_COMPLETED`（终态）/ `PLAN_EXHAUSTED`（发布事件→链路⑤）；无下一步→发布 `PLAN_EXHAUSTED`；STEP_WAITING 到期：步骤 COMPLETED（无 best_result→SENT_NO_RESPONSE）+ 结转 `STEP_COMPLETED` |
| **终态/输出** | 推进至下一步 / 计划完成（PLAN_COMPLETED）/ 发布穷尽事件 |
| **数据落点** | `t_contact_plan`（status、current_step；**completed_at 归 admin/L3 落库写，引擎不负责**，见 §11-11）、`t_contact_plan_step`（next step trigger_time/status；waiting step result=COMPLETED） |
| **验收断言** | ADVANCE+有下一步→注册 trigger+STEP_SCHEDULED；ADVANCE+无下一步→PLAN_EXHAUSTED；PLAN_COMPLETED→终态；观察期到期→步骤 COMPLETED + 结转事件 |

### 5.2 单场景级可执行用例表（就地展开，可直接替换旧分层覆盖表）

> 本节把链路③从「定义卡」就地展开为**步骤分解 + 单场景用例 + 分工/契约 + §10 映射 + 覆盖结论 + 待澄清/骨架**六部分。引用方法名与附录 A 已核对清单一致；未实现用例标 ⬜ 写清归属/环境，**不计入 §1 计数表**。定义卡 §5.1 保留。

#### 5.2.0 步骤分解（推进 + 观察期结转，有序）

| # | 步骤 | 组件 / 模块 / 负责人 | 输入 | 输出 | 关键分支 | 代码位置（类#方法） |
|---|---|---|---|---|---|---|
| ① | 事件路由 | `EventConsumerDispatcher` / engine / 主架构 | `STEP_COMPLETED` | 路由到 `onStepCompleted` | 已注册 | `EventConsumerDispatcher#registerHandlers`（L39） |
| ② | 锁 + 终态拦截 | `PlanLifecycleManager#onStepCompleted` / engine / 主架构 | planId/stepId | 继续 / noop | `plan==null \|\| isTerminal()`→noop | `PlanLifecycleManager#onStepCompleted`（L165–168） |
| ③ | 推进决策（SPI，硬超时 10ms） | `AdvancementPolicy.decide` / channel / 编排同事 | liteCtx + `StepResult`（由完成步骤折算） | `AdvancementDecision`（ADVANCE_NEXT/PLAN_COMPLETED/PLAN_EXHAUSTED） | 异常/超时→上抛 NACK | `onStepCompleted`（L170–177）、`#toStepResult`（L399–404） |
| ④ | ADVANCE_NEXT 推进 | `PlanLifecycleManager` / engine / 主架构 | decision=ADVANCE_NEXT | 注册下一步 trigger + STEP_SCHEDULED / 无下一步→PLAN_EXHAUSTED | next==null→`PLAN_EXHAUSTED` 事件；否则 `trigger=now+max(0,delay)`、`updateCurrentStep`、`STEP_SCHEDULED` | `onStepCompleted`（L180–196） |
| ⑤ | PLAN_COMPLETED | `PlanLifecycleManager` / engine / 主架构 | decision=PLAN_COMPLETED | 计划终态 | `updatePlanStatus(PLAN_COMPLETED)`（**注：未调 markCompleted，completed_at 未写**，见 §11-11） | `onStepCompleted`（L198–201） |
| ⑥ | PLAN_EXHAUSTED | `PlanLifecycleManager` / engine / 主架构 | decision=PLAN_EXHAUSTED / 无下一步 | 发布 `PLAN_EXHAUSTED`（→链路⑤） | default 分支 | `onStepCompleted`（L203–205） |
| ⑦ | 观察期到期结转 | `PlanLifecycleManager#prepareStepDue`（STEP_WAITING 分支） / engine / 主架构 | `PLAN_STEP_DUE`（plan STEP_WAITING） | 步骤 COMPLETED + 结转 `STEP_COMPLETED` | `result=step.result?:SENT_NO_RESPONSE`；`updateStepStatus(COMPLETED, result)` + 结转事件 | `prepareStepDue`（L146–154） |

#### 5.2.1 单场景用例表（每分支一行）

> 前置统一：caseId=1002 / userId=9001 / plan `STEP_EXECUTING`（除注明）/ 完成步 stepOrder=1。「层级」L0=纯逻辑、L1=内存集成、L2=引擎↔渠道契约。

| 场景ID | 链路步骤 | 前置 | 触发（事件+载荷） | 期望结果 | 数据断言（plan.status/current_step/completed_at、next step trigger_time/status、result、事件） | 层级 | 已覆盖载体（真实#方法 / TC-ID / ⬜归属·环境） | 状态 |
|---|---|---|---|---|---|---|---|---|
| **③-S1** | ④ ADVANCE_NEXT·有下一步·delay>0 | decide=ADVANCE_NEXT，next.delayMinutes=60 | `STEP_COMPLETED` | 注册下一步 trigger(now+60)+STEP_SCHEDULED+current_step | `updateStepTriggerTime(nextId, ≈now+60min, PENDING)`；`updateCurrentStep(planId, 2)`；`updatePlanStatus(_, STEP_SCHEDULED, null)`；返回事件 isEmpty | L0 | `PlanLifecycleManagerTest#onStepCompleted_advanceNext_withNext`（#15） | ✅ |
| **③-S2** | ④ ADVANCE_NEXT·有下一步·**delay=0** | decide=ADVANCE_NEXT，next.delayMinutes=0 | `STEP_COMPLETED` | **注册 trigger≈now + STEP_SCHEDULED**（由扫描器即刻发 PLAN_STEP_DUE 驱动；**非同步递归 execute_step**——见 §11-3 修正） | `updateStepTriggerTime(nextId, ≈now, PENDING)`（trigger≈now，区别于 #15 的 now+60）；`updatePlanStatus(_, STEP_SCHEDULED, null)`；返回事件 isEmpty | L0 | ⬜ 主架构 / 内存（**差集 D1**，预计 +1 `@Test`：`onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger`，见 §5.2.6 骨架） | ⬜ |
| **③-S3** | ④ ADVANCE_NEXT·无下一步 | decide=ADVANCE_NEXT，getNextStep=null | `STEP_COMPLETED` | 发布 `PLAN_EXHAUSTED`（→链路⑤），不置 STEP_SCHEDULED | 返回事件 size=1 且 type=PLAN_EXHAUSTED；`updatePlanStatus(_, STEP_SCHEDULED, _)` 从未 | L0 | `PlanLifecycleManagerTest#onStepCompleted_advanceNext_noNext_exhausted`（#16） | ✅ |
| **③-S4** | ⑤ PLAN_COMPLETED | decide=PLAN_COMPLETED | `STEP_COMPLETED` | 计划终态 PLAN_COMPLETED | `updatePlanStatus(planId, PLAN_COMPLETED, null)`；**completed_at 未写（drift，§11-11）**；返回事件 isEmpty | L0 | `MessageChannelHappyPathTest#onStepCompleted_planCompleted`（#14；completed_at 断言缺，D27） | ✅（completed_at ⬜） |
| **③-S5** | ⑥ PLAN_EXHAUSTED（显式） | decide=PLAN_EXHAUSTED | `STEP_COMPLETED` | 发布 `PLAN_EXHAUSTED` | 返回事件 size=1 且 type=PLAN_EXHAUSTED | L0 | 🟡 语义经 #16（无下一步同分支）覆盖；显式 decision=PLAN_EXHAUSTED 无独立断言（低优，可并入 D1 例补） | 🟡 |
| **③-S6** | ⑦ 观察期到期·有 best_result | plan STEP_WAITING，step.result=DELIVERED | `PLAN_STEP_DUE` | 步骤 COMPLETED(DELIVERED) + 结转 `STEP_COMPLETED` | `updateStepStatus(stepId, COMPLETED, DELIVERED)`；prep 事件 size=1 且 type=STEP_COMPLETED；`prep.isExecute()==false` | L0 | `PlanLifecycleManagerTest#prepareStepDue_waitingCarryOver`（#18） | ✅ |
| **③-S7** | ⑦ 观察期到期·**无 best_result** | plan STEP_WAITING，step.result=null | `PLAN_STEP_DUE` | 步骤 COMPLETED(**SENT_NO_RESPONSE**) + 结转 | `updateStepStatus(stepId, COMPLETED, SENT_NO_RESPONSE)`；结转 STEP_COMPLETED | L0 | ⬜ 主架构 / 内存（**差集 D26**，预计 +1 `@Test`：`prepareStepDue_waitingCarryOver_noResult_sentNoResponse`） | ⬜ |
| **③-S8** | ② 终态拦截 noop | plan 终态 | `STEP_COMPLETED` | noop，不推进 | 返回事件 isEmpty；`updatePlanStatus` 从未（终态拦截，语义同链路② #19） | L0 | `PlanLifecycleManagerTest#prepareStepDue_terminalNoop`（#19，prepareStepDue 侧）；onStepCompleted 终态拦截🟡间接 | 🟡 |
| **③-S9** | ④→⑤ 多步推进闭环（内存集成） | 入案三步计划 | `CASE_INGESTED`→循环 DUE→末步穷尽 COMPLETE | 多步推进→末步 PLAN_EXHAUSTED→COMPLETE→PLAN_COMPLETED | `plan.status==PLAN_COMPLETED`；三步 COMPLETED | L1 | `FullChainIntegrationTest#caseIngested_runsThreeChannels_toCompleted` | ✅ |
| **③-S10** | ⑦/④ 观察期结转 + 推进（契约替身） | C6 SMS 观察期 / C3·C5 推进穷尽 | 见链路② | C6 STEP_WAITING→到期结转 COMPLETED；C3/C5 推进至穷尽 COMPLETE | C6：到期后 step COMPLETED；C3/C5：plan PLAN_COMPLETED | L2 | `ChannelContractL2Test#c6_smsObservation_waitsThenSettles`/`c3`/`c5` | 🟡 |
| **③-L3** | ④⑤⑦ 真实落库 | 真实 Mapper + MySQL | 应用/集成 | 状态推进真实落库、`current_step`/`completed_at`/next trigger_time | 落库一致（**completed_at 由 admin 落库写，§11-11 选 b**） | L3 | ⬜ 服务同事 / MySQL（**差集 D14/D27**） | ⬜ |
| **③-L4** | ④⑤ 真实回归 | 真实组件 | 应用 | 推进与三步闭环真实回归 | 端到端断言 | L4 | 外链：`TC-REG-01`（Mock 三步闭环）/`TC-VOICE-02`（NO_ANSWER 推进）；归属：编排同事（**差集 D15**） | ⬜ |

#### 5.2.2 主架构 vs 编排分工 + 协作契约校验点

| 环节 | 主架构（engine） | 编排同事（channel，AdvancementPolicy SPI） | 协作契约校验点 |
|---|---|---|---|
| 事件路由/锁/终态拦截 | ✅ 全责 | — | — |
| 推进决策 `AdvancementPolicy.decide` | 声明接口 + 调用 + 异常/超时→NACK | ✅ 实现决策规则（依 `ExecutionContext`+`StepResult`） | **AdvancementDecision 三值**：`ADVANCE_NEXT`/`PLAN_COMPLETED`/`PLAN_EXHAUSTED`（#15/#14/#16） |
| 下一步 delay | ✅ `trigger=now+max(0,delay)`；delay=0→trigger=now（扫描器即刻驱动） | ✅ **delay 来源**：计划结构 `next.delayMinutes`（PlanFactory 产出，编排定义） | **delay 语义**：delay>0=定时后到期；delay=0=即刻到期（非同步递归，§11-3）；负值被 `max(0,delay)` 钳为 0 |
| StepResult 折算 | ✅ `toStepResult`：result≠FAILED→success=true；result=null→SENT_NO_RESPONSE | — | 决策入参 `StepResult` 的 success/contactResult 折算口径 |
| 状态落地 | ✅ `current_step`/`STEP_SCHEDULED`/`PLAN_COMPLETED`/穷尽事件 | — | `t_contact_plan`(current_step/status)；**completed_at 当前未写**（§11-11） |
| 观察期结转 | ✅ STEP_WAITING 到期→COMPLETED(best_result?:SENT_NO_RESPONSE)+结转 | observationMinutes 由计划结构决定 | best_result 缺省回落 `SENT_NO_RESPONSE`（D26） |

#### 5.2.3 映射 §10.1（M7–M14）/ §10.3（沿用旧编号，不新造）

| 场景ID | §10.1 / §10.3 落点 | 旧#编号 / TC-ID |
|---|---|---|
| ③-S1 | M8（推进→STEP_SCHEDULED，delay>0）；§10.3 ADVANCE_NEXT | #15 |
| ③-S2 | M9（推进→delay=0 即刻）；**差集 D1** | —（应补，§5.2.6 骨架） |
| ③-S3/S5 | M11（推进→PLAN_EXHAUSTED 事件）；§10.3 PLAN_EXHAUSTED | #16 |
| ③-S4 | M10（推进→PLAN_COMPLETED）；§10.3 PLAN_COMPLETED | #14 |
| ③-S6/S7 | M7（STEP_WAITING→COMPLETED+结转） | #18（S7 差集 D26） |
| ③-S8 | M17（终态 noop，链路② #19 跨用） | #19 |
| ③-S9/S10 | §9 链路③×L1/L2 | FullChain / C6·C3·C5 |
| （M12/M13/M14） | PLAN_EXHAUSTED→REBUILD/ESCALATE/COMPLETE | **属链路⑤** `onPlanExhausted`（#25 三分支，见 §7） |

#### 5.2.4 覆盖结论（应覆盖 N / 已覆盖 M / 差集）

- **应覆盖 N = 10**（③-S1～S10，不含 L3/L4 真实环境行）：L0 分支 8（S1–S8）+ L1 1（S9）+ L2 1（S10）。
- **已覆盖 M = 7**：L0 `#15`（S1）/`#16`（S3/S5）/`#14`（S4，状态）/`#18`（S6）/`#19`（S8🟡）+ L1 `FullChain`（S9）+ L2 `C6/C3/C5`🟡（S10）。
- **差集 = 3 项**：
  - **D1**（M9：ADVANCE_NEXT+delay=0，③-S2）——**对齐既有 D1**；经核对**代码无同步递归 execute_step**，骨架改为断言「STEP_SCHEDULED + trigger≈now」，**内存可行**，预计 L0 +1 `@Test`（见 §5.2.6）。
  - **D26**（观察期到期 best_result 缺省→SENT_NO_RESPONSE，③-S7）——**内存可行**，预计 L0 +1 `@Test`。
  - **D27**（PLAN_COMPLETED 未写 completed_at，③-S4）——代码 drift（`markCompleted` 生产侧未调用），见 §11-11；**已拍板（选 b）：completed_at 归 L3 admin 落库写，引擎不补码**，归 D14 同环境。
  - 预计可内存补 **L0 +2（D1/D26）`@Test`**；D27 归 L3 admin，不出引擎 `@Test`；**补码前不计入 §1 计数表**。

#### 5.2.5 待澄清（本链路）

- **§11-3 delay=0「立即执行」规格↔代码↔测试（已核对修正）**：**代码现状**——`onStepCompleted` 的 `ADVANCE_NEXT` 分支对**任意 delay**（含 0）均走 `updateStepTriggerTime(next, now+max(0,delay), PENDING)` + `updateCurrentStep` + `updatePlanStatus(STEP_SCHEDULED)`，**不存在**「同步递归 `execute_step`」代码路径；delay=0 时 `trigger_time=now`，由 `TriggerScanner`/`findDueSteps` 在下一扫描周期即刻发 `PLAN_STEP_DUE` 驱动执行。**测试现状**——L0 仅测 delay>0（#15）；L1 经 `findDueSteps` 循环驱动（即覆盖了 delay=0 的"即刻"语义，但无独立 L0 断言）。**结论**：规格 §2.3.2「delay=0 立即 execute_step」是**语义**描述（无人为延时），其工程实现 = `STEP_SCHEDULED + trigger=now + 扫描器驱动`，**非同步递归**；D1 骨架据实断言 trigger≈now（见 §5.2.6），并建议据此校正规格表述（见 §11-3）。
- **§11-11 completed_at 未写（drift，已拍板选 b）**：`onStepCompleted` 的 `PLAN_COMPLETED` 分支仅 `updatePlanStatus(PLAN_COMPLETED)`，**未调用** `markCompleted`（该方法仅在内存测试仓储中定义，生产 `PlanLifecycleManager` 无调用），故引擎侧 `t_contact_plan.completed_at` 当前**不写**。**已拍板（2026-06-21，选 b）：completed_at 由 L3 admin/落库侧统一补写，引擎不负责、不补码**（同 §11-8 模式）；定义卡 §5.1 落点已标注归属，D27 转 L3 落库项（归 D14 同环境）。

#### 5.2.6 补测骨架（草案：需本地 `mvn test` 编译运行确认，未保证一次通过）

> 仅对「内存可行」的差集 **D1**（M9）与 **D26** 出骨架（追加到 `PlanLifecycleManagerTest`，复用其 `newStep`/`stepEvent`/`@Mock` 装配 + `SpiInvoker.direct()`）。D27（completed_at）已拍板归 L3 admin 落库（选 b，§11-11），D14/D15 待 L3/L4 环境，均只登差集不出骨架。

```java
// 追加到 collection-engine/.../lifecycle/PlanLifecycleManagerTest
// 需补 import：java.time.Duration、java.time.LocalDateTime（ArgumentCaptor 已在用）
@Test
@DisplayName("M9/D1 ADVANCE_NEXT 且 next.delayMinutes=0 → trigger_time≈now + STEP_SCHEDULED（扫描器即刻驱动，非同步递归 execute_step；核对 §11-3）")
void onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger() {
    when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
    when(planRepository.findStepById(STEP_ID)).thenReturn(step);
    when(advancementPolicy.decide(any(), any())).thenReturn(AdvancementDecision.ADVANCE_NEXT);
    ContactPlanStep next = newStep(NEXT_STEP_ID, 2, ChannelType.PUSH, StepStatus.PENDING);
    next.setDelayMinutes(0); // delay=0：即刻到期（非同步执行）
    when(planRepository.getNextStep(PLAN_ID, 1)).thenReturn(next);

    LocalDateTime before = LocalDateTime.now();
    List<CollectionEvent> out = manager.onStepCompleted(stepEvent(EventType.STEP_COMPLETED));

    ArgumentCaptor<LocalDateTime> at = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(planRepository)
            .updateStepTriggerTime(eq(NEXT_STEP_ID), at.capture(), eq(StepStatus.PENDING));
    // delay=0 → trigger_time≈now（与 #15 的 now+60min 区别）
    assertThat(Duration.between(before, at.getValue()).toMinutes()).isLessThan(1);
    verify(planRepository).updateCurrentStep(PLAN_ID, 2);
    verify(planRepository).updatePlanStatus(PLAN_ID, PlanStatus.STEP_SCHEDULED, null);
    // 不直接发 STEP_COMPLETED；由扫描器发 PLAN_STEP_DUE 驱动执行（非同步递归）
    assertThat(out).isEmpty();
}

@Test
@DisplayName("D26 观察期到期且 best_result 为空 → 步骤 COMPLETED(SENT_NO_RESPONSE) + 结转 STEP_COMPLETED")
void prepareStepDue_waitingCarryOver_noResult_sentNoResponse() {
    plan.setStatus(PlanStatus.STEP_WAITING);
    step.setResult(null); // 无 best_result
    when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);
    when(planRepository.findStepById(STEP_ID)).thenReturn(step);

    StepDuePreparation prep = manager.prepareStepDue(stepEvent(EventType.PLAN_STEP_DUE));

    verify(planRepository)
            .updateStepStatus(STEP_ID, StepStatus.COMPLETED, ContactResult.SENT_NO_RESPONSE);
    assertThat(prep.isExecute()).isFalse();
    assertThat(prep.getEvents()).hasSize(1);
    assertThat(prep.getEvents().get(0).getEventType()).isEqualTo(EventType.STEP_COMPLETED);
}
```

> **草案标注**：2 例需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过；通过后回填附录 A 与 §1。**D1 闭合说明**：经核对代码，M9 不是「缺同步执行测试」而是「规格用词"立即执行"对应工程实现为 trigger=now + 扫描器驱动」，本例据实断言，闭合 D1/M9。

---

## §6 链路④ 异步回调链

### 6.1 链路定义卡

| 项 | 内容 |
|---|---|
| **触发事件** | `CHANNEL_CALLBACK`（供应商 Webhook 经 admin 鉴权发布）/ `CALLBACK_TIMEOUT`（引擎超时哨兵） |
| **经过组件（模块/负责人）** | 电话/人工类（AI_CALL/HUMAN_CALL）发送后保持 `STEP_EXECUTING` + 注册超时 Job（七步⑦）→ admin 收 Webhook（admin/服务同事）→ `PlanLifecycleManager.onChannelCallback`/`onCallbackTimeout`（engine/主架构）→ 发 `STEP_COMPLETED`（链路③） |
| **关键分支** | 回调：plan 非 STEP_EXECUTING→静默吸收；否则映射 result + 步骤 COMPLETED + 写 timeline + 发 STEP_COMPLETED。超时：plan 非 STEP_EXECUTING→忽略（已正常处理）；否则步骤 FAILED + 写 `CALLBACK_TIMEOUT` timeline + 发 STEP_COMPLETED。超时时长：默认 60min，metadata.timeoutMinutes 覆盖 |
| **终态/输出** | 步骤 COMPLETED/FAILED → 进入链路③ 推进 |
| **数据落点** | `t_contact_plan_step`（result/status/timeout_time）、`t_contact_timeline`（回调结果 / 超时记录） |
| **验收断言** | 回调→步骤 COMPLETED+映射 ContactResult（如 ANSWERED）+发 STEP_COMPLETED；超时→步骤 FAILED+发 STEP_COMPLETED；异步发送→保持 EXECUTING+写 timeout_time（不发 STEP_COMPLETED、不进 STEP_WAITING）；metadata 覆盖默认 60min |

### 6.2 单场景级可执行用例表（就地展开，可直接替换旧分层覆盖表）

> 本节把链路④从「定义卡」就地展开为**步骤分解 + 单场景用例 + 分工/契约 + §10 映射 + 覆盖结论 + 漂移修正**六部分。所有引用方法名均与附录 A 已核对清单一致，未实现用例标 ⬜ 并写清归属/环境，**不计入 §1 计数表**（补码后再同步 §1/附录 A）。

#### 6.2.0 步骤分解（有序）

| # | 步骤 | 组件 / 模块 / 负责人 | 输入 | 输出 | 关键分支 | 代码位置（类#方法） |
|---|---|---|---|---|---|---|
| ① | 异步发送收尾（七步⑦电话/人工分支） | `StepExecutionOrchestrator` / engine / 主架构 | `StepCommand`（channelType=AI_CALL/HUMAN_CALL，可带 `metadata.timeoutMinutes`）+ dispatch 受理成功 `StepResult` | 保持 plan `STEP_EXECUTING`、step `EXECUTING`；写 `timeout_time`；**不**发 STEP_COMPLETED、**不**进 STEP_WAITING | `isMessageChannel()==false` → `resolveTimeoutMinutes`（metadata 覆盖 / 默认 `props.step.callbackTimeoutMinutes`=60） | `StepExecutionOrchestrator#executeStep`（L180–189）、`#resolveTimeoutMinutes`（L241–247） |
| ② | Webhook 接收 + 鉴权 | admin Webhook / admin / 服务同事 | 供应商回调 HTTP（`result`/`providerMsgId`/签名） | 鉴权通过 → 发布 `CHANNEL_CALLBACK`（携 planId/stepId/`result`） | 鉴权失败→拒收；重复回调→由引擎态拦截兜底 | admin Webhook 控制器（**L3 待建**，外链渠道指南 `TC-VOICE-*`） |
| ③ | 超时哨兵触发 | `TriggerScanner`（扫 `findTimeoutSteps`）/ admin / 服务同事+主架构 | `timeout_time<=NOW` 且仍 `STEP_EXECUTING` 的步骤 | 发布 `CALLBACK_TIMEOUT`（携 planId/stepId） | 步骤已 COMPLETED（回调先到）→ 不在扫描集 | `ContactPlanRepository#findTimeoutSteps`（接口已声明；扫描器 **L3 待建**） |
| ④ | 事件路由 | `EventConsumerDispatcher` / engine / 主架构 | `CHANNEL_CALLBACK` / `CALLBACK_TIMEOUT` | 路由到 `onChannelCallback` / `onCallbackTimeout` | 已注册（含 `CASE_CEASED`） | `EventConsumerDispatcher#registerHandlers`（L40–41 已注册） |
| ⑤ | 回调处理 | `PlanLifecycleManager` / engine / 主架构 | `CHANNEL_CALLBACK` 事件 | plan EXECUTING：映射 `ContactResult`+step `COMPLETED`+发 `STEP_COMPLETED`；非 EXECUTING：返回空（静默吸收） | `plan==null || status!=STEP_EXECUTING` → noop | `PlanLifecycleManager#onChannelCallback`（L211–225）、`#mapCallbackToResult`（L420–429） |
| ⑥ | 超时处理 | `PlanLifecycleManager` / engine / 主架构 | `CALLBACK_TIMEOUT` 事件 | plan EXECUTING：step `FAILED`(`ContactResult.FAILED`)+发 `STEP_COMPLETED`；非 EXECUTING：返回空（已处理，忽略） | `plan==null || status!=STEP_EXECUTING` → noop | `PlanLifecycleManager#onCallbackTimeout`（L229–242） |
| ⑦ | 进入链路③推进 | `PlanLifecycleManager` / engine / 主架构 | `STEP_COMPLETED` | `AdvancementPolicy` 决策推进/穷尽 | 见链路③ | `PlanLifecycleManager#onStepCompleted`（L160–207） |

#### 6.2.1 单场景用例表（每分支一行）

> 前置统一：caseId=1002 / userId=9001 / stage=S2 / 画像含 primaryPhone / 渠道=AI_CALL（除注明）。「层级」列 L0=纯逻辑单测、L1=内存集成、L2=引擎↔渠道契约。⬜ 行注明归属/环境。

| 场景ID | 链路步骤 | 前置（caseId/stage/画像/渠道） | 触发（事件+载荷） | 期望结果 | 数据断言（step.status/result/timeout_time、timeline、事件） | 层级 | 已覆盖载体（真实#方法 / TC-ID / ⬜归属·环境） | 状态 |
|---|---|---|---|---|---|---|---|---|
| **④-S1** | ① 异步发送收尾（默认超时） | plan `STEP_EXECUTING`，step AI_CALL，无 metadata.timeoutMinutes | dispatch 受理成功 `StepResult(success=true, ANSWERED)` | 保持 EXECUTING，注册回调超时（默认 60min），不发 STEP_COMPLETED、不进 WAITING | `updateStepTimeoutTime(stepId, any)` 调用；`eventBus.publish` 从未调用；`updatePlanStatus(_, STEP_WAITING, _)` 从未调用；timeout_time≈now+60min（隐式，未显式断言） | L0 | `StepExecutionOrchestratorTest#asyncChannel_registersTimeout`（#13） | ✅ |
| **④-S2** | ① 异步发送收尾（metadata 覆盖） | 同上，`StepCommand.metadata[timeoutMinutes]=15` | dispatch 受理成功 | 保持 EXECUTING，超时时长=15min（覆盖默认 60） | `updateStepTimeoutTime` 捕获时间≈now+15min（明显区别于 60） | L0 | `StepExecutionOrchestratorTest#asyncTimeout_metadataOverridesDefault`（#30） | ✅ |
| **④-S3** | ⑤ 回调·EXECUTING | plan `STEP_EXECUTING`，step AI_CALL EXECUTING | `CHANNEL_CALLBACK`，`result="ANSWERED"` | 映射 `ContactResult.ANSWERED`，step `COMPLETED`，发 1 条 `STEP_COMPLETED`（进链路③） | `updateStepStatus(stepId, COMPLETED, ANSWERED)`；返回事件 size=1 且 type=STEP_COMPLETED | L0 | `PlanLifecycleManagerTest#onChannelCallback_completesStep`（#22） | ✅ |
| **④-S4** | ⑤ 回调·result 变体映射 | 同 S3 | `CHANNEL_CALLBACK`，`result="NO_ANSWER"` / `"BUSY"` / 缺省/非法（→默认 ANSWERED） | 按 `mapCallbackToResult` 映射为对应 `ContactResult`（非法/空→ANSWERED 兜底），step COMPLETED+推进 | `updateStepStatus(stepId, COMPLETED, NO_ANSWER/BUSY)`；非法值断言落 ANSWERED | L0 | ⬜ 主架构 / 内存（**新差集 D18**，预计 +1 `@Test`：`onChannelCallback_mapsResultVariants`） | ⬜ |
| **④-S5** | ⑤ 回调·非 EXECUTING 静默吸收 | plan 已终态/已 COMPLETED（status≠STEP_EXECUTING，如 PLAN_CANCELLED） | `CHANNEL_CALLBACK`，`result="ANSWERED"` | 静默吸收：不改 step、不发事件，返回空列表 | 返回事件 isEmpty；`updateStepStatus` 从未调用 | L0 | ⬜ 主架构 / 内存（**新差集 D16**，预计 +1 `@Test`：`onChannelCallback_nonExecuting_silentlyAbsorbs`） | ⬜ |
| **④-S6** | ⑥ 超时·EXECUTING | plan `STEP_EXECUTING`，step AI_CALL EXECUTING | `CALLBACK_TIMEOUT`（planId/stepId） | step `FAILED`(`ContactResult.FAILED`)，发 1 条 `STEP_COMPLETED`（失败也推进） | `updateStepStatus(stepId, FAILED, FAILED)`；返回事件 size=1 且 type=STEP_COMPLETED | L0 | `PlanLifecycleManagerTest#onCallbackTimeout_failsStep`（#23） | ✅ |
| **④-S7** | ⑥ 超时·非 EXECUTING 忽略 | plan status≠STEP_EXECUTING（回调已先处理→COMPLETED/终态） | `CALLBACK_TIMEOUT` | 忽略（已正常处理）：不改 step、不发事件，返回空 | 返回事件 isEmpty；`updateStepStatus` 从未调用 | L0 | ⬜ 主架构 / 内存（**新差集 D17**，预计 +1 `@Test`：`onCallbackTimeout_nonExecuting_noop`） | ⬜ |
| **④-S8** | ①→⑤ 端到端回调（内存集成） | 入案→建单步 AI_CALL 计划→执行 | `CASE_INGESTED`→`PLAN_STEP_DUE`→（保持 EXECUTING）→手动 `bus.publish(CHANNEL_CALLBACK, result=ANSWERED)`→`drainAll` | 执行后 plan EXECUTING+step.timeout_time 非空；回调后 step COMPLETED+plan 推进至 PLAN_COMPLETED | 发送后：`plan.status==STEP_EXECUTING`、`step.timeoutTime!=null`、timeline 1 条（发送记录）；回调后：`step.status==COMPLETED`、`result==ANSWERED`、`plan.status==PLAN_COMPLETED` | L1 | ⬜ 主架构 / 内存（**差集 D3**，预计 +1 `@Test`：`asyncCallback_keepsExecuting_thenCompletes`，见 §6.2.5 骨架 L1） | ⬜ |
| **④-S9** | ①→⑥ 端到端超时（内存集成） | 同 S8 入案执行 | 执行（保持 EXECUTING）→手动 `bus.publish(CALLBACK_TIMEOUT)`→`drainAll` | step FAILED+plan 推进 | 回调超时后：`step.status==FAILED`、`plan.status∈{PLAN_COMPLETED}`（穷尽 COMPLETE） | L1 | ⬜ 主架构 / 内存（**差集 D3**，预计 +1 `@Test`：`asyncTimeout_failsAndAdvances`，见 §6.2.5 骨架 L1） | ⬜ |
| **④-S10** | ①→⑤ 回调契约（引擎↔渠道替身） | `SingleStepPlanFactory.channel=AI_CALL`，替身 Gateway 受理成功 | 入案执行→保持 EXECUTING→投 `CHANNEL_CALLBACK(result=ANSWERED)`→`drainAll` | AI_CALL 受理→EXECUTING→回调→COMPLETED（验证回调 payload→ContactResult 映射契约） | 执行后 `plan.status==STEP_EXECUTING`+`step.timeoutTime!=null`；回调后 `step.status==COMPLETED`、`result==ANSWERED`、`plan.status==PLAN_COMPLETED` | L2 | ⬜ 主架构+编排 / 内存（**差集 D4**，预计 +1 `@Test`：`c8_aiCallCallback_completesStep`，见 §6.2.5 骨架 L2） | ⬜ |
| **④-S11** | ⑤⑥ 回调/超时 timeline 落库 | plan EXECUTING | `CHANNEL_CALLBACK` / `CALLBACK_TIMEOUT` | 回调结果 / 超时记录写 `t_contact_timeline` | **已拍板（§11-8）：由 L3 admin 落库统一补写**——引擎仅更新 step 状态+发 STEP_COMPLETED，admin Webhook/超时扫描落库时写 timeline；引擎侧不补码 | L3 | ⬜ 服务同事（admin）/ MySQL（归 D14/D19 同环境） | ⬜ |
| **④-L4** | 真实 Webhook 回调闭环 | 真实供应商 | AI Call 供应商回调 | ANSWERED/NO_ANSWER 闭环 | 真实落库 + timeline | L4 | 外链：`TC-VOICE-01`/`TC-VOICE-02`/`TC-VOICE-03`；归属：编排同事 | ⬜ |

#### 6.2.2 主架构 vs 编排分工 + 协作契约校验点

| 环节 | 主架构（engine/admin） | 编排同事（channel） | 协作契约校验点 |
|---|---|---|---|
| 异步分流（七步⑦） | ✅ 全责：`isMessageChannel()==false`→保持 EXECUTING+注册超时 | — | — |
| 超时时长 | ✅ 默认 60min（`props.step.callbackTimeoutMinutes`）+ 读 `metadata.timeoutMinutes` 覆盖 | ✅ Resolver 写 `StepCommand.metadata[timeoutMinutes]`（如外呼 30min） | **`META_TIMEOUT_MINUTES` 取号口径**：Number 类型，越界/缺省回落默认 60 |
| Webhook 接收/鉴权 | ✅ admin 收回调→鉴权→发 `CHANNEL_CALLBACK` | ✅ 供应商对接 + 回调载荷构造 | **CHANNEL_CALLBACK payload**：`result`（字符串）+ `providerMsgId`；planId/stepId 关联 |
| 回调 payload→ContactResult 映射 | ✅ `mapCallbackToResult`：`ContactResult.valueOf(result.toUpperCase())`，非法/空→`ANSWERED` 兜底 | ✅ 供应商状态码→`result` 字符串（ANSWERED/NO_ANSWER/BUSY/...） | **映射约定**：编排回填的 `result` 须为 `ContactResult` 合法名（否则被兜底为 ANSWERED，可能掩盖真实结果） |
| providerMsgId 对账 | 发送 timeline 落 `providerMsgId`（orchestrator ⑤½/⑦） | ✅ dispatch 返回 `StepResult.providerMsgId` | **对账锚点**：回调 providerMsgId ↔ 发送 timeline providerMsgId（L3 对账，Phase 2） |
| 超时哨兵扫描 | ✅ `findTimeoutSteps` 接口 + 扫描器（L3 待建）发 `CALLBACK_TIMEOUT` | — | `timeout_time` 字段语义（`t_contact_plan_step.timeout_time`） |

> **协作契约校验点小结**：L2 ④-S10 即为「回调 payload→ContactResult 映射 + 异步态拦截」的双方验收基线——编排同事真实化 Voice Adapter/Webhook 后，`result` 字符串与 `ContactResult` 名一致则「对接即绿」。

#### 6.2.3 映射 §10 场景全集（沿用旧编号，不新造）

| 场景ID | §10 落点 | 旧#编号 |
|---|---|---|
| ④-S1 | §10.2 七步⑦「电话类→保持 EXECUTING+超时哨兵」；§10.5 AI_CALL「异步保持 EXECUTING+超时」 | #13 |
| ④-S2 | §10.5 AI_CALL（metadata 覆盖超时） | #30 |
| ④-S3 | §10.1 M6（STEP_EXECUTING 保持）回收侧；§10.5 AI_CALL「回调/超时」 | #22 |
| ④-S4 | §10.5 AI_CALL（result 变体映射，新登差集 D18） | —（应补） |
| ④-S5 | §8 [幂等]/[终态]（回调态拦截·静默吸收，新登差集 D16） | —（应补） |
| ④-S6 | §8 [异常]「#23 回调超时」；§10.5 AI_CALL | #23 |
| ④-S7 | §8 [终态]（超时态拦截，新登差集 D17） | —（应补） |
| ④-S8/S9 | §9 链路④×L1（差集 D3） | —（应补） |
| ④-S10 | §9 链路④×L2、§10.4 StepResult 受理（差集 D4） | —（应补） |
| ④-S11 | §6.1 数据落点 timeline（已拍板 §11-8：L3 admin 落库补写；差集 D19→L3） | —（L3 落库） |

#### 6.2.4 覆盖结论（应覆盖 N / 已覆盖 M / 差集）

- **应覆盖 N = 11**：L0 分支 7（④-S1～S7）+ L1 集成 2（④-S8/S9）+ L2 契约 1（④-S10）+ L3 timeline 1（④-S11）。
- **已覆盖 M = 4**：全部为 L0——`#13`（S1）/`#30`（S2）/`#22`（S3）/`#23`（S6）。
- **差集 = 7 个待补 @Test，归 6 个差集项**：
  - 对齐既有：**D3**（L1 异步集成，2 例：S8/S9）、**D4**（L2 回调契约，1 例：S10）。
  - 续编新差集：**D16**（S5 回调非 EXECUTING 静默吸收，L0 +1）、**D17**（S7 超时非 EXECUTING 忽略，L0 +1）、**D18**（S4 result 变体映射，L0 +1）、**D19**（S11 回调/超时 timeline，**已拍板 §11-8 由 L3 admin 落库统一补写**，引擎侧不补码）。
  - 预计新增引擎 `@Test` 合计：L0 +3、L1 +2、L2 +1 = **6 个**（D19 归 L3 admin 落库，不计入引擎 @Test）。**补码前不计入 §1 计数表**。

#### 6.2.5 待澄清/漂移修正（本链路）

- **§11-2 漂移已核对修正**：代码 `EventType` 枚举（`collection-common/.../enums/EventType.java` L19）**已含** `CALLBACK_TIMEOUT`（L21 亦含 `CASE_CEASED`），`EventConsumerDispatcher` L41 已注册 `CALLBACK_TIMEOUT→onCallbackTimeout`。故「枚举缺失」不成立，真实漂移**仅为领域模型 §6.6 文档表未列**该哨兵事件。§11-2 状态已由「枚举缺失」修正为「**文档表待补登**」（详见 §11-2）。
- **§11-8 已拍板（2026-06-21）**：§6.1 定义卡「数据落点 = `t_contact_timeline`（回调结果 / 超时记录）」——`onChannelCallback`/`onCallbackTimeout` 引擎侧**不写 timeline**（仅 `StepExecutionOrchestrator` 发送路径写）。**结论：回调/超时结果 timeline 由 L3 admin 落库统一补写，引擎侧不补码**；引擎职责保持「更新 step 状态 + 发 STEP_COMPLETED」。差集 D19 转 L3 admin 落库项（归 D14 同环境）。

#### 6.2.6 补测骨架（草案：需本地 `mvn test` 编译运行确认，未保证一次通过）

> 沿用现有装配范式（`SyncEventBus` / `InMemory*Repository` / 反射 `inject` / `SpiInvoker.direct()`），不引入新框架。

**L1 草案 —— 异步回调链内存集成（新增 `AsyncCallbackPlanFactory` + 2 例，对应 ④-S8/S9）**

```java
// collection-engine/src/test/java/com/collection/engine/integration/AsyncCallbackChainL1Test.java
// 草案：复用 FullChainIntegrationTest 同包 package-private 静态替身
// （SyncEventBus / InMemoryPlanRepository / InMemoryTimelineRepository / StubCaseService）；
// 若可见性不足则将这些替身抽取为独立 top-level 测试夹具后复用。
package com.collection.engine.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.collection.common.channel.ChannelGateway;
import com.collection.common.dto.ExhaustionResult;
import com.collection.common.dto.GuardVerdict;
import com.collection.common.dto.StepCommand;
import com.collection.common.dto.StepResult;
import com.collection.common.enums.*;
import com.collection.common.event.CollectionEvent;
import com.collection.common.model.*;
import com.collection.common.service.CaseService;
import com.collection.common.service.PredictiveDialerService;
import com.collection.common.spi.*;
import com.collection.engine.bus.InMemoryIdempotencyService;
import com.collection.engine.config.EngineProperties;
import com.collection.engine.lifecycle.*;
import com.collection.engine.spi.SpiInvoker;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncCallbackChainL1Test {

    private static final long CASE_ID = 1002L;
    private static final long USER_ID = 9001L;

    private FullChainIntegrationTest.SyncEventBus bus;
    private FullChainIntegrationTest.InMemoryPlanRepository planRepo;
    private FullChainIntegrationTest.InMemoryTimelineRepository timelineRepo;

    @BeforeEach
    void wire() {
        bus = new FullChainIntegrationTest.SyncEventBus();
        planRepo = new FullChainIntegrationTest.InMemoryPlanRepository();
        timelineRepo = new FullChainIntegrationTest.InMemoryTimelineRepository();

        EngineProperties props = new EngineProperties();
        CaseService caseService = new FullChainIntegrationTest.StubCaseService();

        ContextAssembler ctxAsm = new ContextAssembler();
        inject(ctxAsm, "timelineRepository", timelineRepo);
        inject(ctxAsm, "props", props);

        PreFlightChecker preFlight = new PreFlightChecker();
        inject(preFlight, "caseService", caseService);

        StepExecutionOrchestrator orch = new StepExecutionOrchestrator();
        inject(orch, "idempotencyService", new InMemoryIdempotencyService());
        inject(orch, "preFlightChecker", preFlight);
        inject(orch, "executionGuard", (ExecutionGuard) c -> GuardVerdict.allow());
        // AI_CALL：Resolver 取手机号；无 metadata → 走默认 60min 超时
        inject(orch, "stepResolver", (StepResolver) c ->
                StepCommand.builder()
                        .channelType(c.getCurrentStep().getChannelType())
                        .targetAddress("+639170000001")
                        .templateId("T").idempotencyKey("k").build());
        inject(orch, "channelGateway", (ChannelGateway) cmd ->
                StepResult.builder().success(true)
                        .contactResult(ContactResult.ANSWERED) // 受理成功，等异步回调
                        .retryable(false).providerMsgId("voice-1").build());
        inject(orch, "contextAssembler", ctxAsm);
        inject(orch, "planRepository", planRepo);
        inject(orch, "timelineRepository", timelineRepo);
        inject(orch, "eventBus", bus);
        inject(orch, "spiInvoker", SpiInvoker.direct());
        inject(orch, "props", props);

        PlanLifecycleManager mgr = new PlanLifecycleManager();
        inject(mgr, "planRepository", planRepo);
        inject(mgr, "caseService", caseService);
        inject(mgr, "planFactory", new AsyncCallbackPlanFactory());
        inject(mgr, "advancementPolicy", (AdvancementPolicy) (c, r) -> AdvancementDecision.ADVANCE_NEXT);
        inject(mgr, "exhaustionPolicy", (ExhaustionPolicy) (p, i, s) -> ExhaustionResult.complete("done"));
        inject(mgr, "predictiveDialerService", (PredictiveDialerService) uid -> {});
        inject(mgr, "spiInvoker", SpiInvoker.direct());

        EventConsumerDispatcher dispatcher = new EventConsumerDispatcher();
        inject(dispatcher, "eventBus", bus);
        inject(dispatcher, "manager", mgr);
        inject(dispatcher, "orchestrator", orch);
        dispatcher.registerHandlers();
    }

    @Test
    @DisplayName("④-S8 入案→执行→保持 EXECUTING+timeout_time→手动 CHANNEL_CALLBACK→COMPLETED+推进")
    void asyncCallback_keepsExecuting_thenCompletes() {
        ingestAndRunDue();
        ContactPlan plan = planRepo.plans.values().iterator().next();
        ContactPlanStep step = planRepo.stepsOf(plan.getId()).get(0);

        // 异步发送收尾：保持 EXECUTING + 写 timeout_time，未推进
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);
        assertThat(step.getTimeoutTime()).isNotNull();
        assertThat(step.getStatus()).isEqualTo(StepStatus.EXECUTING);

        // 模拟 admin 鉴权后投递回调
        bus.publish(CollectionEvent.of(EventType.CHANNEL_CALLBACK)
                .with(CollectionEvent.PLAN_ID, plan.getId())
                .with(CollectionEvent.STEP_ID, step.getId())
                .with("result", "ANSWERED"));
        bus.drainAll();

        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getResult()).isEqualTo(ContactResult.ANSWERED);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
    }

    @Test
    @DisplayName("④-S9 入案→执行→保持 EXECUTING→手动 CALLBACK_TIMEOUT→FAILED+推进")
    void asyncTimeout_failsAndAdvances() {
        ingestAndRunDue();
        ContactPlan plan = planRepo.plans.values().iterator().next();
        ContactPlanStep step = planRepo.stepsOf(plan.getId()).get(0);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);

        bus.publish(CollectionEvent.of(EventType.CALLBACK_TIMEOUT)
                .with(CollectionEvent.PLAN_ID, plan.getId())
                .with(CollectionEvent.STEP_ID, step.getId()));
        bus.drainAll();

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED);
    }

    private void ingestAndRunDue() {
        bus.publish(CollectionEvent.of(EventType.CASE_INGESTED)
                .with(CollectionEvent.CASE_ID, CASE_ID)
                .with(CollectionEvent.USER_ID, USER_ID)
                .with(CollectionEvent.STAGE, Stage.S1.name()));
        bus.drainAll();
        for (int i = 0; i < 10; i++) {
            List<ContactPlanStep> due = planRepo.findDueSteps(LocalDateTime.now(), 100);
            if (due.isEmpty()) break;
            for (ContactPlanStep s : due) {
                bus.publish(CollectionEvent.of(EventType.PLAN_STEP_DUE)
                        .with(CollectionEvent.PLAN_ID, s.getPlanId())
                        .with(CollectionEvent.STEP_ID, s.getId()));
            }
            bus.drainAll();
        }
    }

    /** 单步 AI_CALL 计划（delay=0，无观察期，电话类→异步保持 EXECUTING）。 */
    static class AsyncCallbackPlanFactory implements PlanFactory {
        @Override
        public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
            ContactPlan plan = new ContactPlan();
            plan.setCaseId(caseInfo.getCaseId());
            plan.setUserId(caseInfo.getUserId());
            plan.setStage(stage);
            ContactPlanStep s = new ContactPlanStep();
            s.setStepOrder(1);
            s.setChannelType(ChannelType.AI_CALL);
            s.setDelayMinutes(0);
            s.setObservationMinutes(0);
            s.setStatus(StepStatus.PENDING);
            List<ContactPlanStep> steps = new ArrayList<>();
            steps.add(s);
            plan.setSteps(steps);
            return plan;
        }
    }

    private static void inject(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("no field " + field + " on " + target.getClass());
    }
}
```

**L2 草案 —— 在 `ChannelContractL2Test` 范式上新增 1 例（对应 ④-S10，复用其替身 SPI）**

```java
// 追加到 ChannelContractL2Test，复用 SingleStepPlanFactory / SnapshotAddressResolver /
// ConfigurableGateway / MutableSnapshotCaseService / ingestAndRunDue()/onlyPlan()/onlyStep()/stepDue()
@Test
@DisplayName("C8 AI_CALL 受理 → 保持 EXECUTING → 投 CHANNEL_CALLBACK(ANSWERED) → COMPLETED+推进")
void c8_aiCallCallback_completesStep() {
    planFactory.channel = ChannelType.AI_CALL;     // 电话类：异步保持 EXECUTING
    planFactory.observationMinutes = 0;
    gateway.behavior =
            cmd ->
                    StepResult.builder()
                            .success(true)
                            .contactResult(ContactResult.ANSWERED) // 受理成功，等回调
                            .retryable(false)
                            .providerMsgId("voice-prov-1")
                            .build();

    // 入案 → 执行：AI_CALL 受理后保持 EXECUTING（findDueSteps 不再命中 EXECUTING 步骤，循环自然结束）
    ingestAndRunDue();

    ContactPlanStep step = onlyStep();
    assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.STEP_EXECUTING);
    assertThat(step.getTimeoutTime()).isNotNull();         // 注册了回调超时哨兵
    assertThat(step.getStatus()).isEqualTo(StepStatus.EXECUTING);

    // 模拟编排回调：payload result=ANSWERED（验证回调 payload→ContactResult 映射契约）
    bus.publish(
            CollectionEvent.of(com.collection.common.enums.EventType.CHANNEL_CALLBACK)
                    .with(CollectionEvent.PLAN_ID, onlyPlan().getId())
                    .with(CollectionEvent.STEP_ID, step.getId())
                    .with("result", "ANSWERED"));
    bus.drainAll();

    assertThat(onlyStep().getStatus()).isEqualTo(StepStatus.COMPLETED);
    assertThat(onlyStep().getResult()).isEqualTo(ContactResult.ANSWERED);
    assertThat(onlyPlan().getStatus()).isEqualTo(PlanStatus.PLAN_COMPLETED); // 推进至穷尽 COMPLETE
}
```

---

## §7 链路⑤ 中断与重建链

### 7.1 链路定义卡

| 项 | 内容 |
|---|---|
| **触发事件** | `REPAYMENT_RECEIVED`（还款）/ `STAGE_CHANGED`（阶段变更）/ `PLAN_EXHAUSTED`（穷尽）/ `PTP_EXPIRED`（PTP 到期）/ `CASE_CEASED`（D+91 停催，**代码已就绪**：枚举+handler+CancelReason.CEASED 均在，仅测试待补 D29，见 §11-1） |
| **经过组件（模块/负责人）** | Dispatcher → `PlanLifecycleManager.onRepaymentReceived/onStageChanged/onPlanExhausted/onPtpExpired`（engine/主架构）→ `ExhaustionPolicy.handle`（**SPI**，channel/编排同事）+ `PredictiveDialerService.filterRepaidUser`（外呼名单过滤） |
| **关键分支** | 还款：取消该用户所有活跃计划（REPAID）+ 过滤外呼名单（失败→告警+继续）。阶段变更：取消旧阶段计划（STAGE_UPGRADE）+ 建新阶段计划。穷尽：`REBUILD`（旧完成+同阶段新建）/`ESCALATE`（旧完成+发 STAGE_CHANGED）/`COMPLETE`（终态）。PTP：已还款→补偿取消；有活跃计划→不干预；无活跃计划→续建（REBUILD）。并发安全：行锁串行 + 终态单调 + ⑤½ 复检 |
| **终态/输出** | `PLAN_CANCELLED`（cancel_reason）/ `PLAN_COMPLETED` + 新计划 |
| **数据落点** | `t_contact_plan`（status、cancel_reason、completed_at）、新 `t_contact_plan(+step)` |
| **验收断言** | 还款→PLAN_CANCELLED(REPAID)+filterRepaidUser；阶段变更→旧 PLAN_CANCELLED(STAGE_UPGRADE)+savePlan 新计划；穷尽三分支正确；PTP 已还款→补偿取消 / 活跃→noop；终态事件→noop（终态拦截） |

### 7.2 单场景级可执行用例表（就地展开，可直接替换旧分层覆盖表）

> 本节把链路⑤从「定义卡」就地展开为六部分。引用方法名与附录 A 已核对清单一致；未实现用例标 ⬜ 写清归属/环境，**不计入 §1 计数表**。定义卡 §7.1 保留。**代码核对（2026-06-21）**：5 个 handler 均已注册（`EventConsumerDispatcher` L36–43：`STAGE_CHANGED/REPAYMENT_RECEIVED/CASE_CEASED/PLAN_EXHAUSTED/PTP_EXPIRED`），`CancelReason.CEASED` 已存在（`CancelReason.java` L12，engineManaged=true）。

#### 7.2.0 步骤分解（中断/穷尽/重建，含 onCaseCeased 路径）

| # | 步骤 | 组件 / 模块 / 负责人 | 输入 | 输出 | 关键分支 | 代码位置（类#方法） |
|---|---|---|---|---|---|---|
| ① | 事件路由 | `EventConsumerDispatcher` / engine / 主架构 | 5 类事件 | 路由到对应 onXxx | **均已注册**（含 `CASE_CEASED→onCaseCeased` L38） | `EventConsumerDispatcher#registerHandlers`（L36–43） |
| ② | 还款中断 | `PlanLifecycleManager#onRepaymentReceived` / engine / 主架构 | userId | 取消该用户所有活跃计划 | 行锁串行（按 id 升序）；`PLAN_CANCELLED(REPAID)`；`filterRepaidUser` **try/catch→告警+继续** | `onRepaymentReceived`（L99–116） |
| ③ | 阶段变更 | `PlanLifecycleManager#onStageChanged` / engine / 主架构 | caseId/newStage | 取消旧阶段计划 + 建新 | 仅 `p.stage != newStage` 取消（`STAGE_UPGRADE`）；后 `createPlanForStage(newStage)` | `onStageChanged`（L59–80） |
| ④ | 停催 | `PlanLifecycleManager#onCaseCeased` / engine / 主架构 | caseId | 取消活跃计划且**不再建** | `PLAN_CANCELLED(CEASED)`；**无 createPlanForStage**；后续建计划由 `createPlanForStage` CEASED 前置守卫双重拦截 | `onCaseCeased`（L84–96） |
| ⑤ | 穷尽决策（SPI，硬超时 50ms） | `onPlanExhausted` → `ExhaustionPolicy.handle` / engine→channel | plan+caseInfo+snapshot | `ExhaustionResult`（REBUILD/ESCALATE/COMPLETE） | 终态拦截 noop；异常/超时→上抛 NACK | `onPlanExhausted`（L247–280） |
| ⑥ | 穷尽落地 | `PlanLifecycleManager` / engine / 主架构 | ExhaustionResult | 旧 PLAN_COMPLETED + 续建/升档/停止 | REBUILD→同阶段 `createPlanForStage`；ESCALATE→发 `STAGE_CHANGED(targetStage)`；COMPLETE→终态 | `onPlanExhausted`（L261–279） |
| ⑦ | PTP 到期 | `onPtpExpired` / engine / 主架构 | caseId（**实时查 DB 非快照**） | 补偿取消 / noop / 续建 | `isRepaid`→补偿取消(REPAID)；有活跃→noop；无活跃→取末次完成计划+SPI REBUILD→续建 | `onPtpExpired`（L284–318） |
| ⑧ | ⑤½ 取消/null 复检 | `StepExecutionOrchestrator`（写回前复检） / engine / 主架构 | dispatch 期间 plan 取消 / reload null | record-only，不写回推进 | 终态单调 + 复检 | `cancelledDuringDispatch`/`reloadNull`（#12/#32） |

#### 7.2.1 单场景用例表（每分支一行）

> 前置统一：caseId=1002 / userId=9001。「层级」L0=纯逻辑、L1=内存集成。`PLAN_CANCELLED` 均带 `cancel_reason`。

| 场景ID | 链路步骤 | 前置 | 触发（事件+载荷） | 期望结果 | 数据断言（plan.status/cancel_reason、新 plan(+step)、事件） | 层级 | 已覆盖载体（真实#方法 / TC-ID / ⬜归属·环境） | 状态 |
|---|---|---|---|---|---|---|---|---|
| **⑤-S1** | ② 还款·取消+过滤成功 | 用户有活跃计划 | `REPAYMENT_RECEIVED`(userId) | 取消所有活跃计划(REPAID) + `filterRepaidUser` | `updatePlanStatus(_, PLAN_CANCELLED, REPAID)`（逐计划）；`predictiveDialerService.filterRepaidUser(userId)` 被调 | L0 | `PlanLifecycleManagerTest#onRepaymentReceived_cancelsAndFilters`（#24） | ✅ |
| **⑤-S2** | ② 还款·过滤失败 | 同上，`filterRepaidUser` 抛异常 | `REPAYMENT_RECEIVED`(userId) | **告警+继续**：计划仍取消(REPAID)，不上抛 | 计划 `PLAN_CANCELLED(REPAID)` 仍成立；方法正常返回（log.warn，不抛） | L0 | ⬜ 主架构 / 内存（**差集 D28**，预计 +1 `@Test`：`onRepaymentReceived_filterFails_stillCancels`） | ⬜ |
| **⑤-S3** | ③ 阶段变更 | 旧阶段 S2 活跃，新 S3 | `STAGE_CHANGED`(caseId,S3) | 取消旧阶段(STAGE_UPGRADE) + 建 S3 新计划 | `updatePlanStatus(_, PLAN_CANCELLED, STAGE_UPGRADE)`；`savePlan(新S3)`；交叉引用链路① ①-S15 | L0 | `PlanLifecycleManagerTest#onStageChanged_cancelsOldAndCreatesNew`（#26） | ✅ |
| **⑤-S4a** | ⑤⑥ 穷尽 REBUILD | decide=REBUILD | `PLAN_EXHAUSTED`(planId) | 旧 PLAN_COMPLETED + 同阶段新建 | `updatePlanStatus(planId, PLAN_COMPLETED, null)`；`savePlan(任意)` | L0 | `PlanLifecycleManagerTest#onPlanExhausted_rebuild`（#25-R） | ✅ |
| **⑤-S4b** | ⑤⑥ 穷尽 ESCALATE | decide=ESCALATE(target S3) | `PLAN_EXHAUSTED`(planId) | 旧 PLAN_COMPLETED + 发 `STAGE_CHANGED(S3)` | `updatePlanStatus(_, PLAN_COMPLETED, null)`；返回事件 size=1 type=STAGE_CHANGED 且 stage="S3" | L0 | `PlanLifecycleManagerTest#onPlanExhausted_escalate`（#25-E） | ✅ |
| **⑤-S4c** | ⑤⑥ 穷尽 COMPLETE | decide=COMPLETE | `PLAN_EXHAUSTED`(planId) | 旧 PLAN_COMPLETED 终态，无后续事件 | `updatePlanStatus(_, PLAN_COMPLETED, null)`；返回事件 isEmpty | L0 | `PlanLifecycleManagerTest#onPlanExhausted_complete`（#25-C） | ✅ |
| **⑤-S5** | ⑤ 穷尽·终态拦截/SPI异常 | plan 终态 / SPI 超时 50ms | `PLAN_EXHAUSTED`(planId) | 终态→noop；SPI 异常/超时→上抛 NACK | 终态：无 updatePlanStatus；SPI 超时：异常上抛（SpiInvoker 通用） | L0 | 🟡 `onPlanExhausted` 终态拦截无独立断言（语义同 #19）；SPI 超时由 `SpiInvokerTest` 通用覆盖（低优，可并入 D2 例补） | 🟡 |
| **⑤-S6** | ⑦ PTP·已还款补偿取消 | `isRepaid=true`，有活跃 | `PTP_EXPIRED`(caseId) | 补偿取消活跃计划 | `updatePlanStatus(_, PLAN_CANCELLED, REPAID)`（**注：补偿取消用 REPAID 非 PTP_EXPIRED**） | L0 | `PlanLifecycleManagerTest#onPtpExpired_repaidCancels`（#27-R） | ✅ |
| **⑤-S7** | ⑦ PTP·有活跃 noop | `isRepaid=false`，有活跃 | `PTP_EXPIRED`(caseId) | 不干预（正常流程继续） | `updatePlanStatus` 从未；`savePlan` 从未 | L0 | `PlanLifecycleManagerTest#onPtpExpired_activePlanNoop`（#27-A） | ✅ |
| **⑤-S8** | ⑦ PTP·无活跃续建 | `isRepaid=false`，无活跃，末次完成计划存在 | `PTP_EXPIRED`(caseId) | 取末次完成计划 + SPI；REBUILD→同阶段续建 | `getLastCompletedPlan` 命中；`exhaustionPolicy.handle`→REBUILD→`savePlan(续建)`；last==null 或非 REBUILD→不建 | L0 | ⏭ **Phase 2 延后**（PTP 非 Phase 1 焦点，**差集 D2 / M20**）；骨架 §7.2.6 ① 预留 | ⏭ |
| **⑤-S9** | ④ CASE_CEASED 取消+不再建 | 有活跃计划 | `CASE_CEASED`(caseId) | 取消活跃计划(CEASED) 且**不再建** | `updatePlanStatus(_, PLAN_CANCELLED, CEASED)`；`savePlan` 从未；返回事件 isEmpty | L0 | ⬜ 主架构 / 内存（**差集 D29-L0**，预计 +1 `@Test`：`onCaseCeased_cancelsActivePlanAndNoRebuild`，§7.2.6 骨架；§11-1 已确认枚举/handler 就绪） | ⬜ |
| **⑤-S10** | ⑧/终态 拦截 noop | plan 终态 | 任意命中终态事件 | 终态拦截 noop | `prepareStepDue` 终态 noop（#19）；onXxx 终态拦截🟡间接 | L0 | `PlanLifecycleManagerTest#prepareStepDue_terminalNoop`（#19） | ✅ |
| **⑤-S11** | ⑧ ⑤½ 复检 | dispatch 期间取消 / reload null | 渠道返回后写回前 | record-only，不推进 | `cancelledDuringDispatch`：仅记录不推进；`reloadNull`：record-only | L0 | `StepExecutionOrchestratorTest#cancelledDuringDispatch_recordOnly`（#12）/`reloadNull_recordOnly`（#32） | ✅ |
| **⑤-S12** | ② 还款取消（内存集成） | 入案 S1 计划 | `CASE_INGESTED`→`REPAYMENT_RECEIVED` | 活跃计划 PLAN_CANCELLED(REPAID) | `plan.status==PLAN_CANCELLED && cancelReason==REPAID` | L1 | `FullChainIntegrationTest#repaymentCancelsActivePlan` | ✅ |
| **⑤-S13** | ④ CASE_CEASED 取消（内存集成） | 入案 S1 计划 | `CASE_INGESTED`→`CASE_CEASED` | 活跃计划 PLAN_CANCELLED(CEASED) | `plan.status==PLAN_CANCELLED && cancelReason==CEASED` | L1 | ⬜ 主架构 / 内存（**差集 D29-L1**，预计 +1 `@Test`：`caseCeasedCancelsActivePlan`，§7.2.6 骨架） | ⬜ |
| **⑤-L3** | ②③④⑥⑦ 真实落库/并发 | 真实 Mapper + MySQL | 应用/集成 | 取消/续建真实落库、单活跃计划 UNIQUE、并发 `SELECT FOR UPDATE` 串行化 | 落库一致；并发同计划事件串行 | L3 | ⬜ 服务同事+主架构 / MySQL（**差集 D11/D14**） | ⬜ |
| **⑤-L4** | ②④ 真实回归 | 真实组件 | 应用 | 还款取消 / 停催回归 | 端到端断言 | L4 | 外链：`TC-CANCEL-01`(REPAID)/`TC-CEASED-01`(停催取消)/`TC-CEASED-02`(停催不建计划)；归属：编排同事（**差集 D15**） | ⬜ |

#### 7.2.2 主架构 vs 编排分工 + 协作契约校验点

| 环节 | 主架构（engine） | 编排同事（channel，ExhaustionPolicy SPI） | 协作契约校验点 |
|---|---|---|---|
| 还款/停催/阶段/PTP 中断 | ✅ 全责（行锁串行+终态单调+cancel_reason） | — | `cancel_reason` 口径：REPAID/STAGE_UPGRADE/CEASED（均 engineManaged）；PTP 补偿取消用 **REPAID** |
| 穷尽决策 `ExhaustionPolicy.handle` | 声明接口 + 调用（硬超时 50ms）+ 异常/超时→NACK | ✅ 实现规则（依 plan/caseInfo/snapshot） | **ExhaustionResult 三值**：`REBUILD`(同阶段续建)/`ESCALATE`(targetStage 升档)/`COMPLETE`(停止)（#25 三例） |
| 穷尽落地 | ✅ 旧 PLAN_COMPLETED + 续建/发 STAGE_CHANGED/终态 | targetStage 由 ESCALATE 结果给出 | ESCALATE 的 `result.getTargetStage()` → `STAGE_CHANGED.stage`（#25-E 断言 "S3"） |
| PTP 续建 | ✅ 无活跃→取末次完成计划+复用穷尽策略 | 同 ExhaustionPolicy（REBUILD 才续建） | `getLastCompletedPlan` + REBUILD 才建（D2/M20） |
| 外呼名单过滤 | ✅ 调 `filterRepaidUser`，失败→告警+继续 | `PredictiveDialerService`（外呼侧） | **fail-soft**：过滤失败不回滚取消（核心目标=计划已取消，规格 §5） |

#### 7.2.3 映射 §10.1（M15–M21）/ §10.3（沿用旧编号，不新造）

| 场景ID | §10.1 / §10.3 落点 | 旧#编号 / TC-ID |
|---|---|---|
| ⑤-S1/S2/S12 | M15（→PLAN_CANCELLED(REPAID)） | #24 / FullChain（S2 差集 D28） |
| ⑤-S3 | M16（→PLAN_CANCELLED(STAGE_UPGRADE)+新建） | #26 |
| ⑤-S4a/b/c | §10.3 ExhaustionAction REBUILD/ESCALATE/COMPLETE | #25-R/E/C |
| ⑤-S6 | M18（PTP 已还款→补偿取消） | #27-R |
| ⑤-S7 | M19（PTP 有活跃→noop） | #27-A |
| ⑤-S8 | M20（PTP 无活跃→续建 REBUILD）；**差集 D2，⏭ Phase 2 延后** | —（骨架 §7.2.6 ① 预留） |
| ⑤-S9/S13 | M21（→PLAN_CANCELLED(CEASED)）；**差集 D29** | —（应补，§7.2.6 骨架；§11-1） |
| ⑤-S10 | M17（终态→noop） | #19 |
| ⑤-S11 | ⑤½ 复检（链路②跨用） | #12/#32 |

#### 7.2.4 覆盖结论（应覆盖 N / 已覆盖 M / 差集）

- **应覆盖 N = 15**（⑤-S1～S13 + S4 拆三分支，不含 L3/L4 真实环境行）。
- **已覆盖 M = 10**：L0 `#24`（S1）/`#26`（S3）/`#25`×3（S4a/b/c）/`#27-R`（S6）/`#27-A`（S7）/`#19`（S10）/`#12·#32`（S11）+ L1 `repaymentCancelsActivePlan`（S12）。
- **差集 = 4 项（Phase 1 可补 3 + Phase 2 延后 1）**：
  - **D28**（还款 `filterRepaidUser` 失败→告警+继续仍取消，⑤-S2；#24 仅测成功路径）——**内存可行**，预计 L0 +1 `@Test`。
  - **D29**（CASE_CEASED 取消+不再建，⑤-S9/S13；枚举/handler 已就绪，§11-1）——**内存可行**，预计 **L0 +1 + L1 +1**（§7.2.6 双骨架）。
  - **D2**（M20：PTP 无活跃→续建 REBUILD，⑤-S8）——**⏭ Phase 2 延后（Phase 1 不考虑 PTP，已拍板，§7.2.5）**；骨架 §7.2.6 ① 预留，不计入 Phase 1 `@Test`。
  - **D11**（真实并发竞态：同计划并发事件 `SELECT FOR UPDATE` 串行化，⑤-L3）——主架构+服务同事 / MySQL，**待连库，不出骨架**。
  - 预计**可内存补（Phase 1）L0 +2（D28/D29-L0）+ L1 +1（D29-L1）= +3 `@Test`**；D2 归 Phase 2；**补码前不计入 §1 计数表**。

#### 7.2.5 待澄清（本链路）

- **§11-1 CASE_CEASED/CancelReason.CEASED（已核对，状态细化）**：**代码现状已就绪**——`EventType.CASE_CEASED`（`EventType.java` L21）、`EventConsumerDispatcher` L38 `CASE_CEASED→onCaseCeased` 已注册、`PlanLifecycleManager#onCaseCeased`（L84–96，取消活跃计划 `CancelReason.CEASED`）、`CancelReason.CEASED`（`CancelReason.java` L12，engineManaged=true）均在代码。**结论**：§11-1 **不是「枚举缺失」**，残留仅 (a) 领域模型 §6.6/§6.7 文档表待补登；(b) 引擎 `onCaseCeased` 的 **L0/L1 测试待回补**（D29，本节出骨架）；(c) 渠道 `TC-CEASED-01/02` 口径（L4）。「停催不再建」由 `onCaseCeased` 不建 + `createPlanForStage` CEASED 双重前置守卫（L335–345）保证。
- **PTP 补偿取消的 cancel_reason 口径（已拍板 2026-06-21）**：**Phase 1 不考虑 PTP 的差异化处理**——`onPtpExpired` 已还款补偿取消统一写 `CancelReason.REPAID`（与还款链一致），**不引入** `CancelReason.PTP_EXPIRED`（该枚举值 engineManaged=false，Phase 1 不写入）。既有 #27-R/#27-A 维持现状；PTP **无活跃→续建（M20/D2）** 因 PTP 非 Phase 1 焦点，**改判 Phase 2 延后**（§12.3），骨架（§7.2.6 ①）预留待 Phase 2 启用，不计入 Phase 1 `@Test`。

#### 7.2.6 补测骨架（草案：需本地 `mvn test` 编译运行确认，未保证一次通过）

> Phase 1 实补 **D29**（L0+L1）；**D28** 同范式（stub `filterRepaidUser` 抛异常→验证仍取消）。**骨架 ① D2/M20 为 ⏭ Phase 2 预留**（Phase 1 不考虑 PTP，§7.2.5/§12.3），列出供 Phase 2 启用，不计入 Phase 1。D11（真实并发）待 L3 连库，只登差集不出骨架。L0 追加到 `PlanLifecycleManagerTest`（复用 `@Mock`/`newPlan`/`newStep`/`caseInfoWithUser`）；L1 追加到 `FullChainIntegrationTest`（复用 `bus.publish`/`drainAll`/`planRepo`）。

```java
// ① 【⏭ Phase 2 预留，Phase 1 不考虑 PTP】追加到 PlanLifecycleManagerTest —— 闭合 M20/D2
@Test
@DisplayName("M20/D2 PTP 到期·未还款·无活跃计划 → 取末次完成计划 + SPI REBUILD → 同阶段续建")
void onPtpExpired_noActivePlan_rebuilds() {
    when(caseService.isRepaid(CASE_ID)).thenReturn(false);
    when(planRepository.findActivePlansByCase(CASE_ID)).thenReturn(new ArrayList<>());
    ContactPlan last = newPlan(PLAN_ID, PlanStatus.PLAN_COMPLETED, Stage.S2);
    when(planRepository.getLastCompletedPlan(CASE_ID)).thenReturn(last);
    when(caseService.getCaseInfo(CASE_ID)).thenReturn(caseInfoWithUser());
    when(caseService.getContextSnapshot(CASE_ID)).thenReturn(new ContextSnapshot());
    when(exhaustionPolicy.handle(any(), any(), any()))
            .thenReturn(ExhaustionResult.rebuild("T_REBUILD", "ptp broken"));
    when(planRepository.findActivePlanByCaseAndStage(CASE_ID, Stage.S2)).thenReturn(null);
    ContactPlan created = newPlan(0L, null, Stage.S2);
    created.getSteps().add(newStep(0L, 1, ChannelType.SMS, null));
    when(planFactory.create(any(), eq(Stage.S2), any())).thenReturn(created);

    manager.onPtpExpired(
            CollectionEvent.of(EventType.PTP_EXPIRED).with(CollectionEvent.CASE_ID, CASE_ID));

    verify(planRepository).savePlan(any()); // 无活跃→续建落库
}

// ② 追加到 PlanLifecycleManagerTest —— 闭合 D29-L0（CancelReason.CEASED 已就绪）
@Test
@DisplayName("D29/§11-1 CASE_CEASED → 取消活跃计划(CEASED) 且不再建")
void onCaseCeased_cancelsActivePlanAndNoRebuild() {
    when(planRepository.findActivePlansByCase(CASE_ID))
            .thenReturn(new ArrayList<>(Arrays.asList(plan)));
    when(planRepository.findPlanWithLock(PLAN_ID)).thenReturn(plan);

    List<CollectionEvent> out =
            manager.onCaseCeased(
                    CollectionEvent.of(EventType.CASE_CEASED).with(CollectionEvent.CASE_ID, CASE_ID));

    verify(planRepository)
            .updatePlanStatus(PLAN_ID, PlanStatus.PLAN_CANCELLED, CancelReason.CEASED);
    verify(planRepository, never()).savePlan(any()); // 不再建
    assertThat(out).isEmpty();
}
```

```java
// ③ 追加到 FullChainIntegrationTest —— 闭合 D29-L1（仿 repaymentCancelsActivePlan 范式）
@Test
@DisplayName("入案后停催(D+91) → CASE_CEASED 取消活跃计划(CEASED)")
void caseCeasedCancelsActivePlan() {
    bus.publish(
            CollectionEvent.of(EventType.CASE_INGESTED)
                    .with(CollectionEvent.CASE_ID, CASE_ID)
                    .with(CollectionEvent.USER_ID, USER_ID)
                    .with(CollectionEvent.STAGE, Stage.S1.name()));
    bus.drainAll();

    bus.publish(
            CollectionEvent.of(EventType.CASE_CEASED).with(CollectionEvent.CASE_ID, CASE_ID));
    bus.drainAll();

    ContactPlan plan = planRepo.plans.values().iterator().next();
    assertThat(plan.getStatus()).isEqualTo(PlanStatus.PLAN_CANCELLED);
    assertThat(plan.getCancelReason()).isEqualTo(CancelReason.CEASED);
}
```

> **草案标注**：3 例需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过；通过后回填附录 A 与 §1。**D29 同时闭合 §11-1 的引擎测试残留**（枚举/handler 已在代码，仅缺测试）。

---

## §8 横切维度总表

横切维度不单设章，在各层打标。下表给出每个维度的「主要落点」（沿用旧编号）。

| 维度 | 含义 | 主要落点（层 · TC-ID） | 现状 |
|---|---|---|---|
| **[异常]** | 守卫异常 / 解析异常 / 渠道异常 / DB 不可达 fail-close / 回调超时 | L0 #7（Guard 抛异常）/#8a（Resolver 抛异常）/#9（Gateway 抛异常）/#5e（DB 不可达 fail-close）/#23（回调超时）；L2 C4/C5 | ✅ |
| **[幂等]** | 重复事件、重试 key 不自锁、渠道去重 | L0 #3（幂等锁未获取）/#31（key 含 retryCount）；L2 C7（重复 DUE 不二次 dispatch）；L4 `TC-IDEM-01`；渠道 `TC-IDEM-02`（建议 L1 渠道单测） | ✅（引擎）/ ⬜（渠道幂等） |
| **[终态]** | 终态拦截、回写前取消复检 | L0 #19（终态 noop）/#12（⑤½ 已取消）/#32（⑤½ 不存在） | ✅ |
| **[退避]** | 重试递增与封顶 | L0 #28（base×factor³=240s）/#29（封顶取上限）；L2 C4 | ✅ |
| **[SPI 硬超时]** | `Future.get(timeoutMs)` 截断 + 异常透传 + MDC 传递 | L0 `SpiInvokerTest`（超时/透传/正常/MDC/直连，5 例） | ✅ |
| **[并发]** | 同计划并发事件串行化（行锁）、竞态时序 | ⬜ 无（内存总线同步 drain，无真实并发）；规格称「完整竞态由集成测试保证」 | ⬜ Phase 1 应补（L3 连库并发） |
| **[跨存储一致性]** | 外部已发未记录 / 内部已写事件未发 / 事件已消费业务未执行 → 对账修复；**续建崩溃搁浅（G1/D30）**→终态写最后+原子；**毒消息无限重投（G2/D31）**→max_delivery_count→DLQ；**step=EXECUTING 滞留（G3/D32）**→reaper 扫描兜底 | ⬜ 无（§10.6 G1/G2/G3 + 原 3 模式均未验证） | ⏭ Phase 2 / L5（混沌/故障注入） |

---

## §9 链路 × 层级覆盖矩阵

> 单元格：`✅/🟡/⬜` + 测试类/TC-ID（⬜ 注明待办归属与环境）。行=5 链路，列=L0–L4（L4 拆 a/b，见 §1）。

| 链路＼层级 | L0（纯逻辑） | L1（内存集成） | L2（引擎↔渠道契约） | L3（落库） | L4a（mock 源+真实渠道） | L4b（真实源+真实渠道） |
|---|---|---|---|---|---|---|
| **① 入案建计划** | ✅ #20/#21（`PlanLifecycleManagerTest`）+ 渠道 `MockPlanFactoryGuardTest` | ✅ `FullChainIntegrationTest`（入案建计划） | 🟡 C1–C7 前置（`SingleStepPlanFactory`） | ⬜ ContactPlanMapper 落库+snapshot 往返（admin/MySQL，服务同事） | 🟡 薄：**§L4a-1/7**（`/mock/ingest` 真发+幂等）；全待 A1：`TC-PLAN-STRUCT-S1`/`S0`/`TONE-02`/`COMMON` | ⬜ **§L4b-1/5**（PubSub `case_push`→真实建计划+旧库映射落库；结构待 A1） |
| **② 调度执行** | ✅ #1–#13/#28–#32/#5a–#5e/#17（`StepExecutionOrchestratorTest`+`MessageChannelHappyPathTest`+`PreFlightCheckerTest`） | ✅ `FullChainIntegrationTest`（三渠道取址执行） | 🟡 C1/C3/C4/C5/C7（`ChannelContractL2Test`） | ⬜ TriggerScanner 扫描+步骤/timeline 落库（admin/MySQL） | 🟡 薄：**§L4a-1/2/6/8**（三渠道/fallback/观察期/scriptSlot 真发）；合规 rule 真值待 A2：`TC-GUARD-*` | ⬜ **§L4b-6**（TriggerScanner 连库扫描→执行→timeline 落库） |
| **③ 结果回收** | ✅ #14/#15/#16/#18/#25（`PlanLifecycleManagerTest`+`MessageChannelHappyPathTest`） | ✅ `FullChainIntegrationTest`（推进→COMPLETE 闭环） | 🟡 C6/C3/C5（`ChannelContractL2Test`） | ⬜ 状态推进落库（admin/MySQL） | 🟡 薄：**§L4a-6**（观察期结转）；回调闭环 `TC-REG-01`/`TC-VOICE-02` | ⬜ **§L4b-1/6**（推进+timeline 连库回收） |
| **④ 异步回调** | ✅ #22/#23/#13/#30 + D16/D17/D18（`PlanLifecycleManagerTest`+`StepExecutionOrchestratorTest`） | ✅ `AsyncCallbackChainL1Test`（AI_CALL 回调完成 + 超时兜底，D3） | ⬜ **应补** AI_CALL 回调契约 1 例（主架构+编排） | ⬜ 回调落库+timeout_time 扫描（服务同事） | ⬜ 不在 L4a-薄（无真实电话号源）；`TC-VOICE-01/02/03`（真实供应商 Webhook） | ⬜ 真实源同链路回归（Voice 回调落库） |
| **⑤ 中断与重建** | ✅ #24/#26/#25/#27/#19/#12/#32（`PlanLifecycleManagerTest`+`StepExecutionOrchestratorTest`） | ✅ `FullChainIntegrationTest`（还款取消 REPAID） | ⬜ 可选（语义已由 L0 #12 覆盖） | ⬜ 取消/续建落库+单活跃 UNIQUE+并发串行化（admin/MySQL） | 🟡 薄：**§L4a-3/4/5**（REPAID/STAGE_UPGRADE/CEASED）；`TC-CANCEL-01`/`TC-CEASED-01/02` | ⬜ **§L4b-2/3/4/8**（真实 PubSub/DPD 日切触发取消·升档·停催·幂等落库） |

**矩阵小结**：
- L0/L1 = 引擎语义闭环已绿（L0 59 例 + L1 6 例，状态机 + 七步管线 + 系统守卫 + SPI 超时 + 异步回调链；引擎模块合计 72、全仓 99，见 §1 计数表）。
- L2 = 7 例替身骨架绿，待编排同事真实化 SPI/Gateway「对接即绿」。
- **L0/L1 差集已于 2026-06-22 回补闭合**（链路①②③⑤ 守卫/推进/结转 + 链路④ 异步回调 L0/L1）；剩余缺口 = **L2 异步回调契约（④）1 例**（待编排同事 AI_CALL 真实化）。
- L3 = 待环境（MySQL/Testcontainers），归服务同事+主架构。
- **L4a（mock 源 + 真实渠道）= L4b 前置门禁**：用 `MockTriggerController`/`/mock/*` + `*CaseRegistry` 合成案件驱动真实供应商，验证整条触达链路 + 真实投递。**当前 A3/A6 已真实 → L4a-薄 可跑（用例清单见 §L4a，8 条 L4a-1…8）**；真实计划结构/合规拦截/升档（A1/A2/A4/A5）待切 `Default*` 后转 **L4a-全**。
- **L4b（真实源 + 真实渠道）**：仅替换数据源接真实 PubSub（B1）/旧库（`RealCaseService`）/DPD 日切（B2）+ L3 落库，渠道侧不动；**用例清单见 §L4b（L4b-1…8）**；待 B1/B2 真实化 + L3 环境（MySQL）。`RealCaseService` 已实现（`collection.case-service=real`），B1/B2 仍骨架。

---

## §L4a 端到端用例清单（mock 数据源 + 真实渠道）

> **定位**：L4a = 固定数据源为合成案件（`MockTriggerController` + `*CaseRegistry`，内存事件总线、不连库），只放开真实渠道（A3 `DefaultStepResolver` + A6 `ChannelGatewayImpl`@Primary → 真实 SendGrid / 通知中心 adapter），跑通整条触达链路并验证**真实投递**。是 L4b（真实数据源）的前置门禁，避免双变量叠加。
> **范围**：本节只补**测试文档 + curl/脚本**，**不改引擎/渠道生产代码**；不连真实旧库/PubSub（那是 §1 L4b）。真发优先 `testSend` / `126` 邮箱；Gmail 单独标注 DMARC 风险。
> **被 §1 L4a 行 / §9 矩阵 L4a 列引用**（见各处 `见 §L4a-n`）。

### L4a.0 运行时装配确认（2026-06-25 更新：主架构临时 `Default*`）

| SPI / 组件 | bean | 真实/Mock | L4a 行为 | 备注 |
|---|---|---|---|---|
| A1 `PlanFactory` | **`DefaultPlanFactory`**@Primary | **临时真实** | `channel.plan-templates` 配置驱动；`legacy-three-step`=`SMS→PUSH→EMAIL`；`94102`/`94801`/`94804` 专用步序 | 编排同事回来后 review 替换 |
| A2 `ExecutionGuard` | **`ConfigurableExecutionGuard`**@Primary | **临时真实** | PHT 静默时段 + 空地址 + 内存频率计数 | 生产需 Redis Lua |
| A3 `StepResolver` | `DefaultStepResolver` | **真实** | 取址/scriptSlot/fallback | ✅ |
| A4 `AdvancementPolicy` | **`DefaultAdvancementPolicy`**@Primary | **临时真实** | 非末步→推进；末步成功→COMPLETED；末步失败→EXHAUSTED | 简化版 |
| A5 `ExhaustionPolicy` | **`DefaultExhaustionPolicy`**@Primary | **临时真实** | 内存续建计数 + Stage 升档 | `engine.plan.max-rebuild-count=2` |
| A6 `ChannelGateway` | `ChannelGatewayImpl`@Primary | **真实** | SendGrid + 通知中心 adapter | ✅ |

> **入口**：`MockTriggerController` base=`/mock`；`/ingest` 支持 `legacyThreeStep=true` 查询参数（单次请求，不污染全局配置）。**断言 API**：`GET /plans/by-case/{caseId}/history`（含 `cancelReason`）、`GET /plans/{planId}/steps`。

### L4a.1 入场 checklist（逐项打钩再发）

- [ ] **App 起 + Nacos ok**：启动 `collection-admin`（含全模块装配），`/actuator/health` UP，Nacos 配置中心/注册中心连通、无 fail-fast。
- [ ] **供应商 key 就绪**：SendGrid API key、通知中心 SMS/PUSH 凭证已配置；**默认走 `testSend` / sync 测试模式**（`NotificationSmsAdapter` testMode 命中 testSend 且不签名、`NotificationPushAdapter` sync 模式）；切正式发送前确认额度与签名。
- [ ] **确认 A1–A6 装配**：`/actuator/beans` 或启动日志确认 `DefaultPlanFactory` / `ConfigurableExecutionGuard` / `DefaultAdvancementPolicy` / `DefaultExhaustionPolicy`（@Primary）+ `DefaultStepResolver` + `ChannelGatewayImpl`。
- [ ] **L4a 专用 case 就绪**：`94999`（三渠道）、`94801`（Guard）、`94804`（REBUILD）；`channel.l4a.*` 与 `application-local.yml` 对齐。
- [ ] **限频 ≥1s**：相邻 curl 间隔 ≥1s，规避通知中心/SendGrid 限频与去重导致的误判；多用例串跑加 `sleep 1`。
- [ ] **计划模式按用例设置**：L4a-1 用 `POST /ingest?...&legacyThreeStep=true`（或 `channel.debug.legacy-three-step=true`）；L4a-6 依赖 `94102` + `channel.l4a.observation-minutes>0`。
- [ ] **CaseRegistry 真址核对**：被测 caseId 在 registry 的渠道地址为预期真号/邮箱（见 L4a.2），`123456`/虚拟号不真发。

### L4a.2 CaseRegistry 速查（真实触达地址）

| 渠道 | caseId | 真实地址 / token | 备注 |
|---|---|---|---|
| **合成** | **94999** | SMS=`639451374358` + JPush=`1a0018970bf0c19de04` + EMAIL=`wzynju@126.com` | **L4a-1 三渠道**（`L4aCaseRegistry`） |
| **Guard** | **94801** | 无 phone/email | L4a-全 NO_PHONE→SKIPPED |
| **Guard** | **94805** | 虚拟号 + 两步 SMS | L4a-全 FREQUENCY 第二步 SKIPPED |
| **Exhaustion** | **94804** | EMAIL=126 + 无效 `emailScriptSlot` | L4a-全 REBUILD/ESCALATE |
| SMS | 94101 | `639451374358` | 真号 |
| SMS | 94102 | `9451373897` | 真号 |
| SMS | 94103 | `639153239069` | 真号 |
| SMS | 94100 | `123456` | **虚拟号，不真发**（仅链路/日志验证） |
| PUSH | 94200 | 真 JPush token `1a0018970bf0c19de04` | 真推送 |
| PUSH | 94201 | 无 token | → SMS fallback `9451373897` |
| EMAIL | 92001 / 93xxx | `wzynju@126.com` | 真发优先（126） |
| EMAIL | 95xxx | `plemonsjayson723@gmail.com` | **Gmail，DMARC 风险**（可能被拒/进垃圾箱，优先用 126 验证） |

### L4a.3 断言口径（每条用例三段证据）

- **(a) 真实终端收到**：手机收 SMS / 设备收 JPush 通知 / 邮箱（126 或 Gmail）收信；虚拟号（94100）/`testSend` 模式只验链路不验真投。
- **(b) 返回体 / 日志锚点**：`providerMsgId` + `result`（DELIVERED/FAILED…）+ `scriptSlot`（A3 真实解析）；fallback 场景另看 `metadata.fallback_sms`。
- **(c) plan / step 终态**：`GET /plans/by-case/{caseId}/history` 查 `cancelReason`；`GET /plans/{planId}/steps` 查 `SKIPPED`/`STEP_WAITING`；或日志 `[execStep]`/`[advance]`/`[exhausted]`。

### L4a.4 薄/全 边界标注（2026-06-25：已切临时 `Default*`，本层按 **L4a-全** 验收）

> 主架构代写 `@Primary` 实现（见 [`L4a 编排同事补全清单`](./MOCASA催收系统升级_Phase1_L4a全量前置_编排同事补全清单.md) §8）。历史 channel 对齐纪要见 [`_archive/L4a_对齐纪要_20260622.md`](./_archive/L4a_对齐纪要_20260622.md)。**已知简化**：A2 无 Redis/放弃率；A4 不细分 contactResult；A5 续建计数内存版。编排同事 review 后替换为生产实现。
>
> **L4a-全 补充用例**（官方 8 条之外，脚本一并覆盖）：
> - **Guard block**：case `94801` → `NO_PHONE`→SKIPPED；case `94805` → 同 plan 第二步 SMS → `FREQUENCY_LIMIT`→SKIPPED
> - **REBUILD/ESCALATE**：case `94804` → 无效 scriptSlot → 末步 FAILED → 穷尽链（日志 `[exhausted] REBUILD/ESCALATE`）

### L4a.4.1 自动化 vs 人工（2026-06-25）

| 类别 | 项 | 脚本/代码 | 状态 |
|------|-----|-----------|------|
| 官方 8 条 | L4a-1…8 | `l4a-official-test.sh` | 可自动化（需 App+Nacos+08–21 PHT） |
| L4a-全 | NO_PHONE / FREQUENCY / REBUILD | 同上 `guard`/`rebuild` 段 | 可自动化 |
| 人工 (a) | 手机/126/Gmail 真收到 | — | **必人工** 查终端 |
| 外链 | `TC-PLAN-STRUCT-*` / 部分 `TC-GUARD-*` | 渠道指南 | 与 L4a 重叠，非阻塞 |
| 范围外 | Voice/AI_CALL `TC-VOICE-*` | — | L4b/L4 链路④，L4a 不测 |
| 环境 | `TIME_WINDOW` 静默时段 | — | **21:00–08:00 PHT 勿跑** |

补跑单条：`L4A_ONLY=6 ./scripts/test/l4a-official-test.sh`；全量重启：`./scripts/test/restart-and-l4a.sh`。

### L4a.5 用例清单

> caseId 见 L4a.2。「依赖」列标注临时 `Default*` 简化盲区。终态依 `DefaultAdvancementPolicy`。

| 编号 | 场景 | 触发端点 & 参数 | caseId | 期望真实触达 | 引擎终态 | 断言点 | 依赖（Mock限制） |
|---|---|---|---|---|---|---|---|
| **L4a-1** | 三渠道/计划顺序完成（`legacy-three-step`：SMS→PUSH→EMAIL） | `POST /ingest?caseId=94999&legacyThreeStep=true&stage=S1` | **94999**（三渠道合成） | 手机收 SMS + 设备收 JPush + 126 收信，**依序** | 三步 COMPLETED → **PLAN_COMPLETED** | (a) 三终端；(b) 三步 `providerMsgId`；(c) timeline 含 SMS/PUSH/EMAIL | **全**：A1 配置步序 + legacy 参数 |
| **L4a-2** | PUSH 无 token → SMS fallback | `POST /ingest`{caseId:94201, stage:S1}（计划含 PUSH 步；single-step 或默认） | 94201（无 token，fallback SMS=`9451373897`） | `9451373897` 收到 SMS（PUSH 降级） | 该步 STEP_COMPLETED → PLAN_COMPLETED | (a) fallback 手机收 SMS；(b) `providerMsgId` 为 SMS 通道 + `metadata.fallback_sms`；(c) `[execStep]` 标 fallback、step COMPLETED | **偏全**：fallback 由 A3 真实 resolver(`fallback_sms`)+A6 真实 adapter(`noTokenFallbackToSms`) 实现；A4 mock 末步COMPLETED |
| **L4a-3** | 还款中途 → 计划取消 | `POST /ingest`{caseId, S1} 建计划 →（步间）`POST /repayment`{caseId} | 94101 | 还款前已发步骤真实触达；还款后剩余步骤**不再发** | **PLAN_CANCELLED(REPAID)** | (a) 还款后无新触达；(c) plan `PLAN_CANCELLED` + `cancelReason=REPAID`（PlanQueryController / `[advance]` 日志） | **偏全**：`onRepaymentReceived` 引擎真实，不依赖 A4/A5 |
| **L4a-4** | STAGE_CHANGED → 旧取消 + 新建 | `POST /ingest`{caseId, S1} → `POST /stage-changed`{caseId, stage:S2} | 94101 | 新阶段计划首步真实触达（视计划 SMS/126） | 旧 **PLAN_CANCELLED(STAGE_UPGRADE)** + 新阶段计划 PENDING→执行 | (c) 旧 plan `PLAN_CANCELLED/STAGE_UPGRADE`、新 plan `stage=S2`；(a) 新计划首步触达 | **半薄**：取消引擎真实；新计划结构由 A1 mock（非真实升档步序）→ 待 A1/A4 转「全」 |
| **L4a-5** | CASE_CEASED → 取消不重建 | `POST /ingest`{caseId, S1} → `POST /case-ceased`{caseId} | 94101 | ceased 后**无新触达** | **PLAN_CANCELLED(CEASED)**，不再建计划 | (c) plan `PLAN_CANCELLED/CEASED`，后续无新 plan；(a) 无新触达 | **偏全**：`onCaseCeased` 引擎真实（`CancelReason.CEASED`），不依赖 A4/A5 |
| **L4a-6** | 消息类观察期结转（observation>0） | `POST /ingest?caseId=94102&stage=S1` | 94102 | SMS 真发 → **STEP_WAITING** → 观察期到期 | step COMPLETED → PLAN_COMPLETED | (c) `/history` 见 `STEP_WAITING` 再 COMPLETED；(a) SMS 真发 | **全**：A1 对 94102 单步 SMS + `channel.l4a.observation-minutes=1` |
| **L4a-7** | 重复 ingest 同 case+stage 幂等 | `POST /ingest`{caseId:92001, S1} ×2（间隔≥1s） | 92001（EMAIL=126） | **仅一轮**真实触达（不重复发） | 仅一个活跃计划；第二次幂等跳过 | (c) PlanQueryController 仅 1 plan；第二次日志「单活跃计划幂等跳过」，`planFactory.create`/`savePlan` 不再调；(a) 邮箱仅收一封 | **偏全**：单活跃计划约束（`caseId+stage` 唯一）引擎真实，不依赖 A2/A4/A5 |
| **L4a-8** | Email scriptSlot×stage 跨服务商（126 vs Gmail） | `POST /ingest`{caseId, stage∈{S0,S1,S2}} 多次 | 92001/93xxx(→126) **vs** 95xxx(→Gmail) | 126 与 Gmail 各收信；不同 stage 内容/scriptSlot 不同 | 各 PLAN_COMPLETED | (b) `scriptSlot` 随 stage 变化（`deriveSlot`：S0 byDpd / S2+ firmOnly）+ `providerMsgId` 双服务商；(a) 两邮箱收信内容差异 | **薄**：scriptSlot/取址/templateId 由 A3 真实；A1 mock 仅决定 EMAIL 步与 stage（不验完整模板合规结构）；**Gmail DMARC 风险**（126 优先，Gmail 可能被拒/进垃圾）→ 待 A1/A2 转「全」 |

> **链路覆盖映射**：L4a-1/7 → 链路①（入案建计划）；L4a-1/2/6/8 → 链路②（调度执行）；L4a-6 → 链路③（结果回收·观察期结转）；L4a-3/4/5 → 链路⑤（中断与重建）。链路④（异步回调 AI_CALL/Voice）不在 L4a-薄 范围（无真实电话供应商号源），见 §6 外链 `TC-VOICE-*`。

### L4a.6 curl / 脚本（参数字段以 `MockTriggerController` 契约为准）

**一键官方对齐（推荐）**：

```bash
# 重启 App + 跑完全部 L4a（停服→编译→后台起→测试）
./scripts/test/restart-and-l4a.sh

# App 已在跑时只测
./scripts/test/l4a-official-test.sh

# 跳过 mvn 编译（代码未改、jar 已存在）
./scripts/test/restart-and-l4a.sh --no-build
```

启动/停止单步命令见 `docs/操作说明_Nacos本地启动.md` §4.3 / §5.1。

**单条 curl（query 参数版，与控制器一致）**：

```bash
PORT=8888
BASE="http://localhost:${PORT}/mock"
PLANS="http://localhost:${PORT}/plans"

# L4a-1：三渠道（94999 + legacyThreeStep）
curl -s -X POST "$BASE/ingest?caseId=94999&userId=94999&stage=S1&legacyThreeStep=true"; sleep 1

# L4a-2：PUSH fallback
curl -s -X POST "$BASE/ingest?caseId=94201&userId=94201&stage=S1"; sleep 1

# L4a-3：还款 REPAID — 断言 cancelReason
curl -s -X POST "$BASE/ingest?caseId=94101&userId=94101&stage=S1"; sleep 15
curl -s -X POST "$BASE/repayment?userId=94101&caseId=94101"
curl -s "$PLANS/by-case/94101/history?limit=3" | jq '.[] | {status, cancelReason}'

# L4a-4：STAGE_UPGRADE
curl -s -X POST "$BASE/ingest?caseId=94101&userId=94101&stage=S1"; sleep 12
curl -s -X POST "$BASE/stage-changed?caseId=94101&stage=S2"

# L4a-5：CEASED
curl -s -X POST "$BASE/ingest?caseId=94101&userId=94101&stage=S1"; sleep 8
curl -s -X POST "$BASE/case-ceased?caseId=94101"

# L4a-6：观察期（94102，默认 observation-minutes=1，需等待 ~90s）
curl -s -X POST "$BASE/ingest?caseId=94102&userId=94102&stage=S1"

# L4a-7：幂等 92001
curl -s -X POST "$BASE/ingest?caseId=92001&userId=92001&stage=S1"; sleep 2
curl -s -X POST "$BASE/ingest?caseId=92001&userId=92001&stage=S1"

# L4a-8：126 vs Gmail
curl -s -X POST "$BASE/ingest?caseId=92001&userId=92001&stage=S0"; sleep 1
curl -s -X POST "$BASE/ingest?caseId=95001&userId=95001&stage=S0"

# L4a-全 Guard SKIPPED
curl -s -X POST "$BASE/ingest?caseId=94801&userId=94801&stage=S1"
curl -s "$PLANS/by-case/94801/history?limit=1" | jq '.[0].id' | xargs -I{} curl -s "$PLANS/{}/steps"

# L4a-全 REBUILD/ESCALATE
curl -s -X POST "$BASE/ingest?caseId=94804&userId=94804&stage=S1"
# 等待 ~90s，查 /plans/by-case/94804/history 与日志 [exhausted]
```

**旧版 JSON body 示例（若控制器扩展 POST body 时使用）** — 当前以 query 参数为准：

```bash
PORT=8080
BASE="http://localhost:${PORT}/mock"
H='Content-Type: application/json'

# L4a-1（旧示例 caseId=94101，已改为 94999 + legacyThreeStep 查询参数）
curl -s -XPOST "$BASE/ingest" -H "$H" -d '{"caseId":94101,"userId":94101,"stage":"S1"}'; sleep 1

# L4a-2：PUSH 无 token → SMS fallback
curl -s -XPOST "$BASE/ingest" -H "$H" -d '{"caseId":94201,"userId":94201,"stage":"S1"}'; sleep 1

# L4a-3：建计划 → 还款取消（REPAID）
curl -s -XPOST "$BASE/ingest"     -H "$H" -d '{"caseId":94101,"userId":94101,"stage":"S1"}'; sleep 1
curl -s -XPOST "$BASE/repayment"  -H "$H" -d '{"caseId":94101}'

# L4a-4：建计划 → 阶段升档（STAGE_UPGRADE + 新建）
curl -s -XPOST "$BASE/ingest"        -H "$H" -d '{"caseId":94101,"userId":94101,"stage":"S1"}'; sleep 1
curl -s -XPOST "$BASE/stage-changed" -H "$H" -d '{"caseId":94101,"stage":"S2"}'

# L4a-5：建计划 → 案件终止（CEASED，不重建）
curl -s -XPOST "$BASE/ingest"      -H "$H" -d '{"caseId":94101,"userId":94101,"stage":"S1"}'; sleep 1
curl -s -XPOST "$BASE/case-ceased" -H "$H" -d '{"caseId":94101}'

# L4a-6：消息类观察期结转（SMS observation>0）
curl -s -XPOST "$BASE/ingest" -H "$H" -d '{"caseId":94102,"userId":94102,"stage":"S1"}'

# L4a-7：重复 ingest 同 case+stage 幂等（两次，间隔≥1s）
curl -s -XPOST "$BASE/ingest" -H "$H" -d '{"caseId":92001,"userId":92001,"stage":"S1"}'; sleep 1
curl -s -XPOST "$BASE/ingest" -H "$H" -d '{"caseId":92001,"userId":92001,"stage":"S1"}'

# L4a-8：Email scriptSlot×stage 跨服务商（126 vs Gmail，多 stage）
for s in S0 S1 S2; do
  curl -s -XPOST "$BASE/ingest" -H "$H" -d "{\"caseId\":92001,\"userId\":92001,\"stage\":\"$s\"}"; sleep 1   # →126
  curl -s -XPOST "$BASE/ingest" -H "$H" -d "{\"caseId\":95001,\"userId\":95001,\"stage\":\"$s\"}"; sleep 1   # →Gmail（DMARC 风险）
done

# PTP（⏭ Phase 2，仅入口存在，L4a 不验）
# curl -s -XPOST "$BASE/ptp-expired" -H "$H" -d '{"caseId":94101}'
```

> **注**：以上 JSON 字段（`caseId`/`userId`/`stage`）按 `MockTriggerController` 入参契约为准；到期步骤由 admin 调度扫描器/`delay=0` 即刻驱动，无需手动触发扫描。终态查询用 `PlanQueryController`（或日志 `[execStep]`/`[advance]`/`[callback]`）。

---

## §L4b 端到端用例清单（真实数据源 + 真实渠道）

> **定位**：L4b = 在 §L4a 基础上**只换一个变量——数据源**：把 `MockTriggerController`+`*CaseRegistry` 合成案件，换成 **真实 GCP PubSub 消费（B1 `IngestionService`）+ 真实旧库映射（`RealCaseService`/`CaseService` 读 `t_collection` 等）+ DPD 日切（B2 `DpdStageRollHandler.dailyRoll`，XXL-Job）**，并叠加 **L3 真实落库**（MyBatis Mapper + MySQL）。**渠道侧（A1–A6 装配）原样继承 L4a，不动。**
> **门禁**：**L4a 是 L4b 的前置门禁**（L4a 已验证「真实渠道触达 + 引擎语义」）。L4b 只新增「数据源真实性 + 落库」断言，**渠道断言直接复用 L4a 口径**（§L4a.3）。承「薄/全」：A1/A2/A4/A5 仍 Mock 时为 **L4b-薄**（数据源真、计划结构/合规/升档仍 mock）；切 `Default*` 后为 **L4b-全**（见 §L4b.4）。
> **范围**：本节只定义**验收用例 + 落库断言 + SQL 脚本**，**不改引擎语义代码**；`ingestion`/`admin`/`service` 真实化由对应模块负责。**数据库连接信息走环境变量、不入仓**；DDL 缺失项（模板表/规则表）需先补 `db/schema.sql`。被 §1 L4b 行 / §9 矩阵 L4b 列引用。

### L4b.0 运行时装配确认（与 L4a 的差量，已核对代码 2026-06-22）

| 维度 | L4a（数据源 mock） | L4b（数据源真实） | 现状（代码） |
|---|---|---|---|
| **B1 入案数据源** | `MockTriggerController`/`/mock/*` 手动发事件 | 真实 PubSub 消费（**单订阅 `collection-cases` + `dataType` 路由**）→ `IngestionService.ingestCase(.., snapshotFields)` 校验清洗 + 发领域事件（**决策 B：快照字段随 payload 带出，引擎据 payload 组装，运行时不读旧库**；`RealCaseService` 仅兜底/对账） | 🟡 **骨架**：`IngestionService` 已支持 payload 带快照字段重载，**无真实 PubSub Consumer**（待数据接入真实化） |
| **B2 DPD 日切** | 不涉及 | `DpdStageRollHandler.dailyRoll`（XXL-Job，每日 0:05 PHT）：重算 Max DPD → `STAGE_CHANGED`（1–90 阶段变）/ `CASE_CEASED`（≥91 写 `collection_status=CEASED`） | 🟡 **骨架/占位**：`dailyRoll()` 仅 log，**无重算逻辑 + 未接 XXL-Job**（待真实化） |
| **CaseService（旧库映射）** | `MockCaseService`（合成画像） | `RealCaseService` 读 `t_collection`(+设备表，名称待核实) → `CaseInfo`/`CaseContext`/`ContextSnapshot` | ✅ 已实现；**决策 B 降级**：非主快照来源，仅 payload 缺失时兜底 / 对账（`getContextSnapshot`），`isRepaid` 仍为实时还款守卫 |
| **L3 落库** | 内存仓储 | `ContactPlanRepository` MyBatis 实现 + MySQL `t_contact_plan`/`_step`/`t_contact_timeline` | ⬜ **待建**（差集 D14；DDL `db/schema.sql` 已含三表，缺模板/规则表） |
| **A1–A6 渠道装配** | A3/A6 真实；A1/A2/A4/A5 Mock | **继承 L4a**（不变） | 同 §L4a.0（薄/全同步） |
| **Nacos / 供应商** | 必需 | 同 L4a（不变） | — |

> **关键**：L4b 唯一新增变量是「数据源」。为避免「mock 策略 + 真实数据源」双变量叠加，**推荐 L4a-全（A1/A2/A4/A5→`Default*`）后再做 L4b-全**；若先在 L4a-薄基础上跑 L4b-薄，则 **L4b-1/3 的「真实建计划」实为「mock 计划结构 + 真实落库」**，须在用例「前置依赖」列标注。

### L4b.1 入场 checklist（跑前必读）

#### 联调配置（真实入站 + 沙箱触达）

| 层 | 键 | L4b 取值 |
|---|---|---|
| PubSub | `GCP_PUBSUB_SUBSCRIPTION` | **`collection-cases-ai-v1-sub`**（禁止 `collection-cases-sub`） |
| 接入 | `collection.ingestion.enabled` | **`true`** |
| 接入（可选） | `collection.ingestion.loan-id-whitelist` | 仅处理名单内 `loan_id`，其余 ack 跳过 |
| 渠道 | `channel.notification.push-test-token` | Push 强制投测试 app |
| 渠道 | `channel.notification.sms-test-mode` | **`true`**（testSend） |
| 渠道 | SendGrid 测试收件人 | 内部邮箱（见 [功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)） |
| 运维 | `loan_id` 白名单 | 案件级隔离；**不入仓** |

生产接入参数 SSOT → [接入 §2.1](../MOCASA催收系统升级_Phase1_数据接入规格.md#21-订阅与并发消费)

#### 自动检查

```bash
./scripts/test/l4b-preflight.sh          # 环境 / 服务 / 新库表 / PubSub 凭证
./scripts/test/l4b-preflight.sh --strict # 同上；缺 GCP 订阅或 ingestion.enabled 未 true 时非 0 退出
```

| 检查项 | 自动（preflight） | 人工 |
|---|---|---|
| 服务可达 + L4a 门禁 | 提示查 `logs/run/l4a.last.log` | ✓ 确认 L4a 已通过 |
| `GCP_PUBSUB_*` / 凭证文件 | ✓ | 运维建订阅 `collection-cases-ai-v1-sub` |
| `collection.ingestion.enabled=true` | 读 Nacos YAML（`--strict`） | L4b 必须 true |
| 渠道沙箱（push-test-token / sms-test-mode） | 读 Nacos YAML | SendGrid 测试收件人 |
| 新库 DDL（plan/step/timeline/device_token） | ✓ `SHOW TABLES` | — |
| 旧库 `t_collection` 只读 | — | ✓ 可选对账账号 |
| XXL-Job `dailyRoll` | — | ✓ B2 注册 |
| `loan_id` 白名单 | 环境变量提示 | ✓ 运维清单（不入仓） |
| A1–A6 薄/全 | — | ✓ `/actuator/beans` |

#### 手动清单

- [ ] **L4a 前置门禁**：§L4a 8 条已通过。
- [ ] **PubSub**：`GCP_PUBSUB_PROJECT` / 凭证已注入；B1 Consumer 已接入；运维已建订阅 `collection-cases-ai-v1-sub`。
- [ ] **联调配置**：上表各项已设（`enabled=true` + 渠道沙箱）。
- [ ] **新库**：`t_contact_plan` / `_step` / `t_contact_timeline` / `t_user_device_token` 已建；MyBatis L3 已装配。
- [ ] **日切**：XXL-Job `dailyRoll` 已注册（B2）。
- [ ] **旧库只读**（可选对账）：`t_collection`；主链路不读旧库（决策 B）。
- [ ] **A1–A6**：与 L4a 一致；Mock 时按 §L4b.4 标「薄」。
- [ ] **数据安全**：白名单、脱敏、触发间隔 ≥1s。
- [ ] **方案 A**：全额结清 `repayment_push_and_load` → `DEL ingestion:ingested:{loan_id}`（接入 §2.2.2 / §3.3）。

#### 测试数据落点（无专门「测试表」）

| 数据 | 落点 | 说明 |
|---|---|---|
| 案件画像 phone/email | PubSub payload → `t_contact_plan.context_snapshot` JSON | 决策 B 主链路；L4b-5 断言 payload 映射 |
| 旧库造数 | **`t_collection`**（`db/seed-test-cases.sql`，`loan_id` 9900000x） | 仅 RealCaseService 兜底 / 可选对账；**非** publish 门禁 |
| Push 测试 token | **Nacos** `channel.notification.push-test-token` | 适配器强制覆盖，**不落库** |
| SMS 沙箱 | **Nacos** `sms-test-mode=true` | testSend 端点 |
| jpush enrichment（**可选降级**） | 新库 **`t_user_device_token`** | 仅 `enrich-jpush-token=true` 且消息缺 token；**主路径 = `case_push` 消息体**（2026-07） |
| 引擎执行结果 | **`t_contact_plan`** / **`_step`** / **`t_contact_timeline`** | L4b 落库断言主表 |
| L4a 合成案件 | 内存 `*CaseRegistry` | 不经 DB；L4b 换 PubSub 后不用 |
| `loan_id` 白名单 | 运维清单 / Nacos `loan-id-whitelist` | **不入仓** |

### L4b.2 真实数据源映射（事件源 + 旧库字段，以代码为准）

**(1) 事件来源映射**

| 真实触发源 | 经过 | 领域事件 |
|---|---|---|
| PubSub `case_push` | `IngestionService.ingestCase`（校验清洗入库；snapshot 由引擎建计划时组装） | `CASE_INGESTED` |
| PubSub `repayment_push_and_load` | `IngestionService.repayment` | `REPAYMENT_RECEIVED` |
| XXL-Job `dailyRoll`（DPD 1–90 阶段变） | `DpdStageRollHandler` 重算 → `IngestionService.changeStage` | `STAGE_CHANGED` |
| XXL-Job `dailyRoll`（DPD≥91） | `DpdStageRollHandler` → `caseCeased` | `CASE_CEASED`（**不写**旧库 CEASED 列；停催态由引擎计划取消 + snapshot 表达） |
| `TriggerScanner.scanDueSteps`（`trigger_time<=now`） | admin 扫表 | `PLAN_STEP_DUE` |

**(2) 旧库 `t_collection` → `ContextSnapshot` 字段映射（`RealCaseService`，L4b-5 断言依据）**

| 快照字段 | 旧库来源 / 规则 |
|---|---|
| `caseId` | `loan_id`（数字串；`t_collection.id` 为 hex 不可用） |
| `dpd` / `stage` | `overdue_days` → `Stage.fromDpd(dpd)`（S2[4,15]/S3[16,30]/S4[31+]） |
| `totalOutstanding` | `total_not_paid` |
| `penaltyAmount` | `overdue`（罚息） |
| `primaryPhone` | `phone` 归一化 E.164 `+63`（`09xx/9xx→+639xx`） |
| `email` | 脏值（空/`"0"`/无 `@`）→ `null`（EMAIL 走 Guard SKIP） |
| `device.jpushToken` | 上游 `case_push` 消息体（2026-07 确认）→ payload → 快照；null→PushAdapter fallback SMS |
| `repaid` | `full_repay_time` 非空 或 `total_not_paid<=0` |
| `collectionStatus` | `dpd>=91 ? CEASED : ACTIVE` |
| `strategyTone` | 固定 `"STANDARD"`（Phase 1；真实语气策略待 A1/决策层） |

> 金额 SSOT / 取号口径细节以 `docs/contracts/README_ContextSnapshot契约对齐.md` §12 为准，本表不重复定义。

### L4b.3 落库断言口径（每条用例三类证据）

- **(a) 数据源真实性**：事件确由真实 PubSub/日切触发（非手动 `/mock`）；`t_contact_plan.context_snapshot` 内字段值来自 `t_collection`（与旧库行比对）。
- **(b) 渠道断言（复用 L4a §L4a.3）**：真实终端收到 + `providerMsgId`/`result`/`scriptSlot`。
- **(c) 落库断言**：
  - `t_contact_plan`：`status`（PENDING→…→PLAN_COMPLETED/PLAN_CANCELLED）、`stage`、`cancel_reason`（REPAID/STAGE_UPGRADE/CEASED）、`context_snapshot` JSON 往返一致、`idempotency_key`、`completed_at`（终态写入，§11-11 归 admin/L3 落库）。
  - `t_contact_plan_step`：`status`、`trigger_time`/`timeout_time`、`result`、`retry_count`、`executed_at`/`completed_at`。
  - `t_contact_timeline`：每次发送/回调一行，`channel`/`result`/`provider_msg_id`/`source`（`SYSTEM`/`PUBSUB_SYNC`）。

### L4b.4 薄/全 边界标注（承 §L4a.4 + Q1 门禁）

> L4b 的「薄/全」**完全由渠道装配（A1/A2/A4/A5）决定**，与数据源真实性正交：
> - **不受 Mock 影响、L4b-薄即可全验**：L4b-2（取消落库）、L4b-4（CEASED 停催落库）、L4b-5（旧库映射快照）、L4b-6（扫描器到期落库）、L4b-7（PubSub NACK 重投）、L4b-8（日切幂等）。
> - **受 A1 `MockPlanFactory` 影响、需 `DefaultPlanFactory` 才转全**：L4b-1（真实建计划结构落库）、L4b-3（升档新阶段计划结构）——薄阶段落库的是 mock 计划结构（`PUSH→EMAIL`），**结构真实性待 A1→Default**。
> - 合规拦截（A2）/升档穷尽决策（A4/A5）的真实验证，随 L4a-全 一并解锁，不单列 L4b 用例。

### L4b.5 用例清单

> 「前置依赖」列标注本条所需真实化组件与薄/全归属。⏭ PTP（`PTP_EXPIRED`）/ 跨存储一致性对账（§10.6/§5.2 三模式）= Phase 2，不在 L4b。

| 编号 | 场景 | 真实触发源 | 期望事件 | 期望真实触达 | 落库断言 | 引擎终态 | 前置依赖 |
|---|---|---|---|---|---|---|---|
| **L4b-1** | 入案→建计划→真投递→timeline 落库 | PubSub `case_push`（白名单 caseId） | `CASE_INGESTED` | 计划首步真实触达（SMS/PUSH/EMAIL，视计划） | `t_contact_plan` 落 PENDING→执行、`context_snapshot` JSON 往返；`t_contact_plan_step` 首步 `trigger_time`；`t_contact_timeline` 发送行 `provider_msg_id`/`result` | PLAN_COMPLETED（A4 mock 末步） | B1 真实 + L3 落库 + A3/A6；**计划结构受 A1 Mock → 薄**（待 `DefaultPlanFactory` 转全） |
| **L4b-2** | 还款→取消活跃计划落库 | PubSub `repayment_push_and_load`（白名单 userId） | `REPAYMENT_RECEIVED` | 还款后剩余步骤不再发 | `t_contact_plan.status=PLAN_CANCELLED`、`cancel_reason=REPAID`、`completed_at` 写入 | PLAN_CANCELLED(REPAID) | B1 真实 + L3；**全**（引擎语义，不依赖 A1/A4/A5） |
| **L4b-3** | DPD 日切 1–90 阶段变→升档建新阶段落库 | XXL-Job `dailyRoll`（DPD 跨 stage 边界） | `STAGE_CHANGED` | 新阶段计划首步真实触达 | 旧 plan `PLAN_CANCELLED/STAGE_UPGRADE`；新 plan `stage`=新阶段、PENDING；两行 `context_snapshot` 对应不同 dpd | 旧 CANCELLED + 新阶段执行 | B2 真实 + 旧库 dpd 重算 + L3；**新计划结构受 A1 Mock → 薄**（待 Default 转全） |
| **L4b-4** | DPD≥91→CEASED 停催落库 | XXL-Job `dailyRoll`（DPD≥91） | `CASE_CEASED` | 停催后无新触达 | 活跃 plan `PLAN_CANCELLED/CEASED`；**不**断言旧库 `colleciton_status` 写入 | PLAN_CANCELLED(CEASED)，不重建 | B2 + L3；**全** |
| **L4b-5** | 快照字段溯源（**决策 B：payload → 快照**） | PubSub `case_push`（payload 带快照字段） | `CASE_INGESTED` | （可选触达） | `t_contact_plan.context_snapshot` 内 `dpd`/`stage`/`totalOutstanding`/`penaltyAmount`/`primaryPhone(E.164)`/`email(清洗)`/`jpushToken`/`repaid`/`collectionStatus`/`strategyTone` **逐字段 == `CASE_INGESTED` payload 映射**（引擎 `buildSnapshotFromEvent`，**不读旧库**）；旧库 `t_collection` 行映射仅作**可选对账**（`RealCaseService` 兜底路径，payload 缺失时才走） | 同 L4b-1 | B1 真实(payload) + L3；旧库只读降级为对账；**全**（引擎组装，不经渠道 SPI） |
| **L4b-6** | TriggerScanner 扫描到期→执行落库 | `TriggerScanner.scanDueSteps`（`trigger_time<=now`） | `PLAN_STEP_DUE` | 到期步骤真实触达 | 步 `status` PENDING→EXECUTING→COMPLETED/WAITING、`executed_at`；`t_contact_timeline` 落发送行 | 步推进 / PLAN_COMPLETED | L3（`findDueSteps` 连库）+ admin 调度 + A3/A6；**全**（扫描/执行语义） |
| **L4b-7** | PubSub 消费失败→NACK 重投（可靠性，L4b 新增变量） | PubSub `case_push`（注入下游异常，如 CaseService 读失败） | 消费 NACK | 重投后成功一次触达（不重复） | 仅一个活跃计划（重投幂等）；失败时 `t_contact_plan` 无脏数据 | 重投成功后 PLAN_COMPLETED | B1 真实 PubSub ack/nack + L3 + 计划幂等键；**全** |
| **L4b-8** | DPD 日切幂等（重复 `dailyRoll` 不重复建/升档） | XXL-Job `dailyRoll` 同日重复执行 | `STAGE_CHANGED` 仅一次有效 | 不重复触达 | 同 case+stage 仅一个活跃计划（单活跃约束）；重复日切不产生重复 `PLAN_CANCELLED/STAGE_UPGRADE` | 幂等：第二次 noop | B2 真实 + 单活跃计划约束 + L3；**全** |

### L4b.6 落库断言 SQL（示例，连真实 MySQL；连接走环境变量）

```sql
-- 前置：连接信息走环境变量，不入仓
--   mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p "$DB_NAME"
SET @caseId = 80010001;   -- 替换为白名单测试 caseId

-- L4b-1/6：计划 + 步骤 + timeline 落库
SELECT id, stage, status, cancel_reason, completed_at, idempotency_key
  FROM t_contact_plan WHERE case_id = @caseId ORDER BY id DESC;
SELECT step_order, channel_type, status, trigger_time, result, retry_count, executed_at, completed_at
  FROM t_contact_plan_step
 WHERE plan_id = (SELECT id FROM t_contact_plan WHERE case_id=@caseId ORDER BY id DESC LIMIT 1)
 ORDER BY step_order;
SELECT channel, direction, result, provider_msg_id, source, created_at
  FROM t_contact_timeline WHERE case_id = @caseId ORDER BY created_at;

-- L4b-2：还款取消
SELECT status, cancel_reason, completed_at FROM t_contact_plan
 WHERE case_id=@caseId ORDER BY id DESC LIMIT 1;   -- 期望 PLAN_CANCELLED / REPAID

-- L4b-3：升档（旧取消 + 新阶段）
SELECT id, stage, status, cancel_reason FROM t_contact_plan
 WHERE case_id=@caseId ORDER BY id;   -- 旧:PLAN_CANCELLED/STAGE_UPGRADE，新:stage=新阶段/PENDING|执行

-- L4b-4：CEASED 停催（活跃计划取消 + 不再建）
SELECT status, cancel_reason FROM t_contact_plan WHERE case_id=@caseId;   -- PLAN_CANCELLED/CEASED

-- L4b-5：快照字段溯源（与 t_collection 行比对）
SELECT JSON_EXTRACT(context_snapshot,'$.caseContext.dpd')              AS dpd,
       JSON_EXTRACT(context_snapshot,'$.caseContext.stage')            AS stage,
       JSON_EXTRACT(context_snapshot,'$.caseContext.totalOutstanding') AS total_out,
       JSON_EXTRACT(context_snapshot,'$.caseContext.penaltyAmount')    AS penalty,
       JSON_EXTRACT(context_snapshot,'$.caseContext.collectionStatus') AS coll_status,
       JSON_EXTRACT(context_snapshot,'$.userProfile.basic.primaryPhone') AS phone,
       JSON_EXTRACT(context_snapshot,'$.userProfile.basic.email')      AS email
  FROM t_contact_plan WHERE case_id=@caseId ORDER BY id DESC LIMIT 1;
-- 对照：SELECT loan_id, overdue_days, total_not_paid, overdue, colleciton_status, phone, email
--         FROM t_collection WHERE loan_id = @caseId;
```

### L4b.数据安全（强制）

- **旧库只读**：`RealCaseService` 兜底路径仅 `SELECT t_collection`（L4b 对账）；**jpushToken 主路径随 `case_push` 消息体**（2026-07 确认），enrichment 读新库仅降级路径。
- **测试用户白名单**：L4b 仅对约定的白名单 caseId/loan_id 子集触达（避免真实催收用户被打扰）；白名单来源 = 旧库内部测试账户或 L4a `*CaseRegistry` 对应的真号画像，**单独清单维护、不入仓**。
- **脱敏**：日志/SQL 输出对真实手机号/邮箱脱敏（中段掩码）；`provider_callback`/`content_summary` 不落敏感明文。
- **限频**：相邻真实触发 ≥1s；批量回归对供应商额度限速。
- **连接信息不入仓**：DB/PubSub/供应商凭证一律环境变量 + Nacos，仓库只留 `.env.example` 占位。

### L4b 与 L4a 的回归对齐

| 维度 | L4a 已验（前置门禁） | L4b 新增断言 |
|---|---|---|
| 真实渠道触达 + providerMsgId/scriptSlot | ✅ §L4a.3 | 复用，不重测 |
| 引擎语义（取消/升档/停催/幂等/观察期） | ✅ §L4a-3/4/5/6/7 | 复用 + 叠加落库断言 |
| **数据源真实性** | ❌（mock 入口） | ✅ PubSub/日切/旧库映射（L4b-1/3/4/5/7/8） |
| **L3 落库** | ❌（内存仓储） | ✅ `t_contact_plan`/`_step`/`t_contact_timeline`（全用例 c 断言） |

> **结论**：L4a-薄 → L4a-全 → L4b 三步推进；**L4b 通过即视为「真实数据源 + 真实渠道」端到端门禁达成**（三渠道完整测试的最后一环，配套环节见 §12 / 下方 §9 矩阵）。

---

## §10 场景全集反推与差集清单

> 方法：对**每个状态迁移 / 七步分支 / 守卫返回值 / StepResult 情形 / 渠道×异常组合**列「期望全集」，标注覆盖。`✅`=已覆盖，`⬜`=未覆盖（差集），`❓`=待澄清。**差集 = 所有 ⬜/❓ 行**，集中列于 §10.7。

### 10.1 状态机迁移全集（PlanStatus，规格 §2.7）

| # | 迁移 | 触发 | 覆盖 | 落点 |
|---|---|---|---|---|
| M1 | ∅ → PENDING | CASE_INGESTED/STAGE_CHANGED | ✅ | #20 |
| M2 | PENDING → STEP_EXECUTING | PLAN_STEP_DUE | ✅ | #17 |
| M3 | STEP_SCHEDULED → STEP_EXECUTING | PLAN_STEP_DUE | ✅ | #17（同分支）/L1 |
| M4 | STEP_EXECUTING → STEP_WAITING | 消息类有观察期 | ✅ | #2/C6 |
| M5 | STEP_EXECUTING → STEP_COMPLETED 事件 | 消息类无观察期 | ✅ | #1/#4/L1 |
| M6 | STEP_EXECUTING 保持 | 异步渠道等回调 | ✅ | #13 |
| M7 | STEP_WAITING → 步骤 COMPLETED+结转 | 观察期到期 PLAN_STEP_DUE | ✅ | #18/C6 |
| M8 | 推进 → STEP_SCHEDULED | ADVANCE_NEXT 且 delay>0 | ✅ | #15 |
| M9 | 推进 → STEP_SCHEDULED（trigger=now，扫描器即刻驱动；**非同步递归**，§11-3 已修正） | ADVANCE_NEXT 且 delay=0 | ⬜ | L0 无独立断言（D1，§5.2.6 骨架闭合）；L1 经 findDueSteps 覆盖 |
| M10 | 推进 → PLAN_COMPLETED | decision=PLAN_COMPLETED | ✅ | #14 |
| M11 | 推进 → PLAN_EXHAUSTED 事件 | 无下一步 / EXHAUSTED | ✅ | #16 |
| M12 | PLAN_EXHAUSTED → PLAN_COMPLETED+新建 | REBUILD | ✅ | #25-REBUILD |
| M13 | PLAN_EXHAUSTED → PLAN_COMPLETED+STAGE_CHANGED | ESCALATE | ✅ | #25-ESCALATE |
| M14 | PLAN_EXHAUSTED → PLAN_COMPLETED | COMPLETE | ✅ | #25-COMPLETE |
| M15 | 任意非终态 → PLAN_CANCELLED(REPAID) | REPAYMENT_RECEIVED | ✅ | #24/L1 |
| M16 | 任意非终态 → PLAN_CANCELLED(STAGE_UPGRADE)+新建 | STAGE_CHANGED | ✅ | #26 |
| M17 | 终态 → noop | 任意事件命中终态拦截 | ✅ | #19 |
| M18 | PTP 已还款 → 补偿取消 | PTP_EXPIRED | ✅ | #27-repaid |
| M19 | PTP 有活跃计划 → 不干预 | PTP_EXPIRED | ✅ | #27-active |
| M20 | PTP 无活跃计划 → 续建(REBUILD) | PTP_EXPIRED | ⏭ | **Phase 2 延后**（Phase 1 不考虑 PTP，§7.2.5）；D2 骨架预留 |
| M21 | 任意非终态 → PLAN_CANCELLED(CEASED) | CASE_CEASED | ⬜ | **枚举/handler 已就绪**（`CancelReason.CEASED`+`onCaseCeased`）；仅缺测试（D29，§7.2.6 骨架）；§11-1 修正 |

### 10.2 七步管线分支全集（规格 §3.1）

| 步 | 分支 | 覆盖 | 落点 |
|---|---|---|---|
| ① 幂等锁 | 获取成功 / 未获取静默退出 | ✅ / ✅ | happy / #3/C7 |
| ② 系统守卫 | 通过 / 拦截（null、已还款、冻结、读失败 fail-close） | ✅ / ✅ | #5d / #5a–#5c/#5e + #5 |
| ③ 业务守卫 | 放行 / block→SKIPPED / 抛异常 fail-close→SKIPPED | ✅ / ✅ / ✅ | happy / #6/C3 / #7 |
| ④ 步骤解析 | 正常 / 抛异常→FAILED / 返回 null→SKIPPED | ✅ / ✅ / ✅ | happy / #8a / #8b |
| ⑤ 渠道调度 | success / 抛异常→retryable / success=false+retryable / success=false+不可重试 | ✅ / ✅ / ✅ / ✅ | C1 / #9 / #10/C4 / #11/C5 |
| ⑤½ 取消复检 | 非终态继续 / 终态→记录不推进 / null→记录不推进 | ✅ / ✅ / ✅ | happy / #12 / #32 |
| ⑥ 故障降级 | retryable 未超上限→退避 / 超上限→FAILED / 退避递增 / 封顶 | ✅ / ✅ / ✅ / ✅ | #10 / #11 / #28 / #29 |
| ⑦ 渠道分流 | 消息类有观察期→WAITING / 无观察期→COMPLETED / 电话类→保持 EXECUTING+超时哨兵 | ✅ / ✅ / ✅ | #2/C6 / #1/#4 / #13/#30 |

### 10.3 守卫/决策返回值全集

| 接口 | 取值 | 覆盖 | 落点 |
|---|---|---|---|
| `PreFlightChecker.check` | true / false | ✅ / ✅ | #5d / #5a–#5c/#5e |
| `GuardVerdict` | allow / block | ✅ / ✅ | happy / #6/#7/C3 |
| `GuardVerdict.blockedRuleType` | FREQUENCY_LIMIT | ✅ | #6 |
| ↑ | TIME_WINDOW / CONNECT_AND_STOP / ABANDONMENT_RATE | ⬜（L0；引擎只读 allowed 布尔，规则值由渠道侧） | L4 `TC-GUARD-02/04`（编排同事） |
| ↑ | 空地址 NO_EMAIL/NO_PHONE/NO_TOKEN | ✅（NO_EMAIL）/ ⬜（NO_PHONE/NO_TOKEN 仅替身逻辑无断言） | C3 / L4 `TC-EMAIL-02`、`TC-PUSH-02` |
| `AdvancementDecision` | ADVANCE_NEXT / PLAN_COMPLETED / PLAN_EXHAUSTED | ✅ / ✅ / ✅ | #15/#16 / #14 / #16 |
| `ExhaustionAction` | REBUILD / ESCALATE / COMPLETE | ✅ / ✅ / ✅ | #25 三分支 |

### 10.4 StepResult 情形全集（契约定稿 3 情形）

| 情形 | success/contactResult/retryable | 引擎行为 | 覆盖 | 落点 |
|---|---|---|---|---|
| ① 发送受理 | true / DELIVERED / false | STEP_COMPLETED | ✅ | C1/#1 |
| ② 网络超时（含 5xx/限流/熔断） | false / FAILED / true | 退避重试（未超上限） | ✅ | C4/#10 |
| ③ 其他异常（地址无效/退订） | false / FAILED / false | FAILED + 推进 | ✅ | C5/#11 |
| SMS 两段：受理→DLR 观察期→结转 | true 受理 + 观察期 | STEP_WAITING→结转 | ✅ | C6 |

### 10.5 渠道类型 × 异常组合全集

| 渠道 | happy | 地址缺失 | 失败/重试 | 幂等 | 观察期/异步 | 覆盖小结 |
|---|---|---|---|---|---|---|
| **SMS** | ✅ #1/L1 | ⬜ NO_PHONE→Guard block→SKIPPED（L2 可补，②-S30/§4.2.6 C9；D5） | ✅ #10/C4 | ✅ #3/#31/C7 | ✅ 观察期 C6 | 地址缺失差集 D5（L2 内存可补） |
| **PUSH** | ✅ #4/L1 | ✅ 空 token→fallback SMS C2；⬜ 空 token+空 SMS→NO_TOKEN SKIPPED（L2 可补，②-S31/§4.2.6 C10；D5） | ✅（同管线） | ✅ | 无观察期 ✅ | NO_TOKEN SKIP 差集 D5（L2 内存可补） |
| **EMAIL** | ✅ C1/L1 | ✅ 空 email→NO_EMAIL SKIPPED C3 | ✅ C5 | ✅ | 无观察期 ✅ | 已覆盖 |
| **VIBER** | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | **全差集**（规格列入消息类，Phase 1 无实现，❓§11-4） |
| **WHATSAPP** | ⬜ | ⬜ | ⬜ | ⬜ | ⬜ | **全差集**（同上） |
| **AI_CALL** | ✅ #13 | n/a | ✅（回调/超时 EXECUTING）#22/#23 | ✅ | ✅ 异步保持 EXECUTING+超时（#13/#30） | L0 态拦截/映射差集 D16/D17/D18；L1/L2 集成差集 D3/D4（§6.2、§9 链路④） |
| **HUMAN_CALL** | ⬜ | n/a | — | — | ⬜ | Phase 1 编排禁用（E4：不进 plan），引擎分流入「电话类」；❓§11-5 |

### 10.6 异常恢复全集（规格 §5）

| 故障点 | 期望行为 | 覆盖 | 落点 |
|---|---|---|---|
| L1 幂等锁 Redis 不可达 | fail-close 静默退出 | 🟡 间接（#3 锁未获取语义） | #3 |
| L1 PreFlight MySQL 不可达 | fail-close→check=false | ✅ | #5e |
| L1 合规计数器 Redis 不可达 | SKIPPED+告警+推进 | 🟡 间接（#7 Guard 抛异常 fail-close） | #7 |
| L1 register_job/MySQL 写失败（⑥⑦） | NACK→重消费 | ⬜ | 无（无连库/无 NACK 注入） |
| L2 SPI 抛错/硬超时 | 按接口应对（NACK/fail-close） | ✅（超时机制） | `SpiInvokerTest` |
| L3 外部已发未记录 | 对账回查 providerMsgId 补写 | ⬜ | ⏭ Phase 2 |
| L3 内部已写事件未发 | 扫描重发 STEP_COMPLETED | ⬜ | ⏭ Phase 2 |
| L3 事件已消费业务未执行 | 处理中标记+超时重发 | ⬜ | ⏭ Phase 2 |
| 并发竞态（还款 vs 执行） | 行锁串行+终态单调+⑤½ 复检 | 🟡 单线程语义（#12/#32） | 真实并发 ⬜（§8 [并发]） |
| **G1** 续建/升档崩溃搁浅（§4.5 `on_plan_exhausted` REBUILD/ESCALATE 中途崩溃） | 终态写最后+原子事务保证可重入：旧计划 `PLAN_COMPLETED` 置于后继（新计划持久化 / `STAGE_CHANGED` 发布）之后；崩溃→旧计划仍非终态→重投重跑（[核心引擎规格 §4.5](./MOCASA催收系统升级_Phase1_核心引擎规格.md#45-穷尽续建)） | ⬜ | **D30**——需注入崩溃点（mock repository 第二次写入抛异常）验证重投幂等不重复建计划。L3 连库（Phase 2 混沌/L5） |
| **G2** 毒消息无限重投（retryable 但持续失败的事件占满 Consumer 线程池） | 投递次数达 `max_delivery_count`→DLQ+告警（[核心引擎规格 §7.3](./MOCASA催收系统升级_Phase1_核心引擎规格.md#73-l1-基础设施异常)、[基础设施交互规范 §2](./MOCASA催收系统升级_Phase1_基础设施交互规范.md#2-事件总线redis-stream)） | ⬜ | **D31**——需真实 Redis Stream（`XPENDING` delivery_count）或集成替身。内存总线无 delivery_count 语义，Phase 1 不可测（Phase 2 / L5） |
| **G3** step=EXECUTING 滞留无恢复（⑤ 后 ⑥⑦ 写库/登记 Job 失败或进程崩溃；NACK 重投因幂等锁无效；无 `timeout_time` 哨兵） | reaper 扫描 step=EXECUTING 超 `executing_reaper_minutes` 且 `timeout_time` 为空→复检+回查供应商→补 timeline 或重置重试（[§7.3 Orchestrator ⑥⑦](./MOCASA催收系统升级_Phase1_核心引擎规格.md#orchestrator-层51-七步管线)、[§7.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md#74-跨存储一致性修复)） | ⬜ | **D32**——需真实定时扫描器+Redis 幂等锁 TTL 交互。L3 连库（Phase 2 / L5） |

### 10.7 差集清单（汇总——所有 ⬜/❓）

| 编号 | 差集项 | 类型 | 归属 / 环境 | 分流 |
|---|---|---|---|---|
| D1 | M9：ADVANCE_NEXT+delay=0 无独立 L0 断言（**经核对：代码非同步递归，实为 STEP_SCHEDULED+trigger=now，§11-3 修正**） | 状态迁移 | 主架构 / L0 | ✅ 已补 2026-06-22（`onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger`，断言 trigger≈now + STEP_SCHEDULED） |
| D2 | M20：PTP 违约+无活跃计划→续建(REBUILD) 无断言（⑤-S8） | 状态迁移 | 主架构 / L0 | **⏭ Phase 2 延后**（Phase 1 不考虑 PTP，已拍板 §7.2.5）；骨架 §7.2.6 ① 预留 |
| D3 | 链路④ 异步回调 L1 内存集成缺失 | 集成 | 主架构 / L1 | ✅ 已补 2026-06-22（`AsyncCallbackChainL1Test`：AI_CALL 回调完成 + 超时兜底 2 例） |
| D4 | 链路④ 异步回调 L2 契约用例缺失（回调 payload→ContactResult 映射、回调态拦截） | 契约 | 主架构+编排 / L2 | Phase 1 应补 |
| D5 | NO_PHONE / NO_TOKEN 空地址 SKIP 无 L0/L2 断言（②-S30/S31） | 守卫 | 主架构(替身)+编排 / L2（内存可行）；rule 真值 L4 | Phase 1 应补：L2 替身断言「block→SKIPPED」预计 +2 `@Test`（§4.2.6 骨架 C9/C10）；rule 真值 L4 `TC-PUSH-02`/`TC-EMAIL-02`（已澄清 §11-9） |
| D6 | GuardVerdict 规则类型 TIME_WINDOW/CONNECT_AND_STOP/ABANDONMENT_RATE | 守卫 | 编排 / L4 | L4（`TC-GUARD-*`，引擎侧只读 allowed） |
| D7 | VIBER / WHATSAPP 全链路无测试 | 渠道 | 编排 / — | ❓ Phase 1 范围待澄清（§11-4） |
| D8 | ~~TTS 独立用例~~ | — | — | **取消**（TTS 由 LTH 域外独立编排，本系统不测） |
| D9 | HUMAN_CALL 引擎分流路径 | 渠道 | 主架构+编排 / — | ❓ E4 禁用，待澄清（§11-5） |
| D10 | ⑥⑦ DB/register_job 写失败 NACK 路径 | 异常 | 主架构 / L3 | Phase 1 应补（连库注入） |
| D11 | 真实并发竞态（同计划并发事件 `SELECT FOR UPDATE` 串行化，⑤-L3） | 并发 | 主架构 + 服务同事 / L3 | Phase 1 应补（连库并发，不出骨架） |
| D12 | 渠道幂等 TC-IDEM-02（相同 idempotencyKey 去重） | 幂等 | 编排 / L1 渠道单测 | Phase 1 应补 |
| D13 | 跨存储一致性对账修复（§5.2 三模式） | 一致性 | 运维/编排 / L3 | ⏭ Phase 2 |
| D14 | L3 落库映射（Mapper/snapshot 往返/TriggerScanner） | 落库 | 服务同事 / L3 | Phase 1 应补（待环境） |
| D15 | L4 端到端真实供应商回归 | 端到端 | 编排/全员 / L4 | Phase 1 应补（待环境，按渠道指南） |
| D16 | 链路④ `onChannelCallback` plan 非 STEP_EXECUTING → 静默吸收 无 L0 断言（④-S5） | 回调态拦截 | 主架构 / L0 | ✅ 已补 2026-06-22（`onChannelCallback_nonExecuting_silentlyAbsorbs`） |
| D17 | 链路④ `onCallbackTimeout` plan 非 STEP_EXECUTING → 忽略 无 L0 断言（④-S7） | 超时态拦截 | 主架构 / L0 | ✅ 已补 2026-06-22（`onCallbackTimeout_nonExecuting_noop`） |
| D18 | 链路④ `mapCallbackToResult` 非 ANSWERED 取值（NO_ANSWER/BUSY/非法兜底）映射无断言（④-S4） | 回调映射 | 主架构 / L0 | ✅ 已补 2026-06-22（`onChannelCallback_mapsResultVariants`） |
| D19 | 链路④ 回调/超时结果 timeline 由 L3 admin 落库统一补写（④-S11；已拍板 §11-8） | 落库 | 服务同事（admin）/ L3 MySQL | Phase 1 应补（L3 落库，归 D14 同环境；引擎侧不补码） |
| D20 | 链路② ⑤渠道失败不可重试(retryable=false)→FAILED 引擎 L0 无独立断言（②-S18） | 渠道/降级 | 主架构 / L0 | ✅ 已补 2026-06-22（`notRetryable_failedNoBackoff`） |
| D21 | 链路① `PlanFactory.create` 返回 null / stage 空→不建计划，引擎 L0 无断言（①-S5/S9） | 建计划 | 主架构 / L0 | ✅ 已补 2026-06-22（`onCaseIngested_factoryNull_noPlan`） |
| D22 | 链路① 引擎 CEASED 前置守卫（caseStatus / snapshot collectionStatus）→跳过工厂，L0 无断言（①-S6/S7） | 建计划 | 主架构 / L0 | ✅ 已补 2026-06-22（`onCaseIngested_ceasedCaseStatus_skip` + `onCaseIngested_ceasedSnapshot_skip`） |
| D23 | 链路① 首步 trigger_time(delay=0/>0) + snapshot 冻结 JSON 断言（①-S2/S3） | 建计划 | 主架构 / L0 | ✅ 已补 2026-06-22（`onCaseIngested_firstStepTriggerTime_andSnapshotFrozen`） |
| D24 | 链路① STAGE_CHANGED 建新阶段 L1 集成无载体（①-S15） | 集成 | 主架构 / L1 | ✅ 已补 2026-06-22（`FullChainIntegrationTest#stageChanged_cancelsOldAndBuildsNewStagePlan`） |
| D25 | 链路① 建计划阶段 CaseService 读失败→NACK 边界（无 fail-close）L0 无断言（①-S11） | 异常/边界 | 主架构 / L0 | ✅ 已补 2026-06-22（`onCaseIngested_caseServiceReadFailure_propagates`，澄清 §11-10） |
| D26 | 链路③ 观察期到期且 best_result 缺省→`SENT_NO_RESPONSE` 结转，L0 无断言（③-S7；#18 仅测 DELIVERED） | 状态迁移 | 主架构 / L0 | ✅ 已补 2026-06-22（`prepareStepDue_waitingCarryOver_defaultSentNoResponse`） |
| D27 | 链路③ `PLAN_COMPLETED` 未写 `completed_at`（`markCompleted` 生产侧未调用，drift；③-S4） | 落库/状态 | 服务同事（admin）/ L3 | **已拍板（选 b，§11-11）：completed_at 归 L3 admin 落库写，引擎不补码**；归 D14 同环境 |
| D28 | 链路⑤ 还款 `filterRepaidUser` 失败→告警+继续仍取消，L0 无断言（⑤-S2；#24 仅测成功路径） | 异常/降级 | 主架构 / L0 | ✅ 已补 2026-06-22（`onRepaymentReceived_filterFails_stillCancels`） |
| D29 | 链路⑤ CASE_CEASED→取消活跃(CEASED)且不再建，L0/L1 无断言（⑤-S9/S13；枚举/handler 已就绪，§11-1） | 状态迁移 | 主架构 / L0+L1 | ✅ 已补 2026-06-22（L0 `onCaseCeased_cancelsActivePlanAndNoRebuild` + L1 `FullChainIntegrationTest#caseCeasedCancelsActivePlan`） |
| D30 | **G1** 续建/升档崩溃搁浅：`on_plan_exhausted` REBUILD/ESCALATE 中途崩溃→旧计划仍非终态→重投重跑幂等不重复建计划（§10.6 G1） | 崩溃恢复/原子性 | 主架构 / L3 连库（注入崩溃点） | ⏭ Phase 2 / L5 混沌（内存版无法模拟事务半提交；规格 §4.5 已改写为终态写最后+原子事务） |
| D31 | **G2** 毒消息无限重投：retryable 但持续失败→`max_delivery_count`→DLQ+告警（§10.6 G2） | 基础设施防护 | 主架构 / Redis Stream 真实实现 | ⏭ Phase 2 / L5（内存总线无 delivery_count 语义；规格 §7.3 + 基础设施 §2 已补规范） |
| D32 | **G3** step=EXECUTING 滞留无恢复：消息类 ⑤ 后崩溃无 `timeout_time` 哨兵→reaper 扫描兜底（§10.6 G3） | 崩溃恢复/定时扫描 | 主架构 / L3 连库+Redis 幂等锁 TTL | ⏭ Phase 2 / L5（需真实扫描器+Redis TTL 交互；规格 §5 已补 step→EXECUTING 标记 + §7.4 reaper 行） |

---

## §11 待澄清清单（以代码与规格为准，不擅自编造）

| # | 不一致点 | 文档/代码现状 | 影响 | 建议 |
|---|---|---|---|---|
| **§11-1** | `CASE_CEASED` 事件 + `CancelReason.CEASED`（代码已落地，焦点收窄） | **代码已含**：`EventType.CASE_CEASED`（L21）、`CancelReason.CEASED`（`CancelReason.java` L12，engineManaged=true）、`PlanLifecycleManager#onCaseCeased`（L84–96，取消活跃计划 `CancelReason.CEASED`，不再建）、Dispatcher L38 已注册 `CASE_CEASED→onCaseCeased`；仅领域模型 §6.6/§6.7 文档表滞后未列；渠道指南 `TC-CEASED-01` 期望 `cancel_reason=CEASED`，对齐 **E1** | **非「枚举缺失」**；残留：(a) 文档表待补登；(b) `onCaseCeased` 的 L0/L1 测试待回补（**D29**，§7.2.6 已出骨架）；(c) 渠道 `TC-CEASED-*`（L4） | 在领域模型 §6.6 补 `CASE_CEASED`、§6.7 补 `CancelReason.CEASED`（标注 engineManaged）；补链路⑤ `onCaseCeased` L0+L1 断言（D29，骨架见 §7.2.6）+ 渠道 `TC-CEASED-01/02`。**链路① 的「CEASED→不建计划」由 `createPlanForStage` 前置守卫 + 工厂 null 实现，不依赖本条**（见 §3.2.5） |
| **§11-2** | `CALLBACK_TIMEOUT` 文档表待补登（已核对修正） | **代码已含**：`collection-common/.../enums/EventType.java` L19 含 `CALLBACK_TIMEOUT`（且 L21 含 `CASE_CEASED`，共 10 值），`EventConsumerDispatcher` L41 已注册 `CALLBACK_TIMEOUT→onCallbackTimeout`；规格 §1.1 称其为「内部超时哨兵」；仅领域模型 §6.6 文档表未列出 | **非「枚举缺失」**，真实漂移仅为文档表滞后 | 状态由「枚举缺失」修正为「**文档表待补登**」：在领域模型 §6.6 表补 `CALLBACK_TIMEOUT`（标注「引擎内部哨兵，不跨模块」）；§11-1 的 `CASE_CEASED` 同理已在 `EventType` 落地（`onCaseCeased`+`CancelReason.CEASED` 均在代码），其待澄清焦点收窄为渠道 `TC-CEASED-*` 口径与 L0 回补 |
| **§11-8** | 回调/超时路径 timeline 由 L3 admin 落库统一补（已拍板） | §6.1 定义卡「数据落点 = `t_contact_timeline`（回调结果/超时记录）」；`PlanLifecycleManager#onChannelCallback`（L211–225）/`#onCallbackTimeout`（L229–242）**仅更新 step.status + 发 STEP_COMPLETED，引擎侧不写 timeline**；timeline 仅在 `StepExecutionOrchestrator` 发送路径写 | 回调结果/超时记录的 timeline 落点归属明确 | **已拍板（2026-06-21）：回调/超时结果 timeline 由 L3 admin 落库统一补写，引擎侧不补码**。引擎职责保持「更新 step 状态 + 发 STEP_COMPLETED」；admin Webhook/超时扫描落库时同步写 `t_contact_timeline`（回调结果/超时记录）。§6.1 定义卡「数据落点」语义不变（落点真实但落库在 admin/L3），引擎 L0/L1 不再就此补 `@Test`，差集 D19 转 L3 admin 落库项 |
| **§11-3** | ADVANCE_NEXT 且 delay=0「立即执行」（已核对修正：规格用词↔代码实现） | **代码现状**：`onStepCompleted` 的 ADVANCE_NEXT 分支对**任意 delay（含 0）** 均走 `updateStepTriggerTime(next, now+max(0,delay), PENDING)` + `updateCurrentStep` + `updatePlanStatus(STEP_SCHEDULED)`，**不存在「同步递归 execute_step」代码路径**；delay=0 时 `trigger_time=now`，由 `TriggerScanner`/`findDueSteps` 下一扫描周期即刻发 `PLAN_STEP_DUE` 驱动。**测试现状**：L0 仅测 delay>0（#15）；L1 经 `findDueSteps` 循环驱动覆盖 delay=0 即刻语义，无独立 L0 断言 | M9 无独立断言（D1）；**规格 §2.3.2「立即 execute_step」用词与代码（STEP_SCHEDULED+扫描器驱动）有歧义** | **不臆造同步递归测试**：规格「立即执行」=语义上无人为延时，工程实现 = `STEP_SCHEDULED + trigger=now + 扫描器驱动`。补 L0 据实断言「delay=0 → trigger≈now + STEP_SCHEDULED、返回事件 isEmpty」（`onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger`，§5.2.6），**闭合 D1/M9**；并建议校正规格 §2.3.2 表述为「trigger=now 即刻调度」 |
| **§11-4** | VIBER / WHATSAPP Phase 1 范围 | 规格 §3.1⑦ 分流将 VIBER/WHATSAPP 列入「消息类」；领域模型 §6.1 标 VIBER=接入（待签约）、WHATSAPP=升级；当前无任何测试与渠道实现 | 渠道全集 D7 是否计入 Phase 1 测全 | 确认 Phase 1 实测渠道集合（疑似仅 SMS/PUSH/EMAIL + AI_CALL），VIBER/WHATSAPP 标 ⏭ 或注「接入即补」 |
| **§11-5** | HUMAN_CALL 引擎分流 | 规格七步⑦把 HUMAN_CALL 归「电话/人工类」保持 EXECUTING；但对齐清单 **E4** 与渠道 `TC-PLAN-STRUCT-COMMON` 约定「Phase 1 不进 plan / StepResolver 永不输出 HUMAN_CALL」 | D9：引擎是否需测 HUMAN_CALL 分流 | 以 E4 为准：Phase 1 不构造 HUMAN_CALL 步骤；引擎分流逻辑保留但标「Phase 1 不触发」，不强制 L0 |
| **§11-6** | PUSH 取号口径 `jpushToken` vs `fcmToken` | 契约定稿与领域模型 §3.2 DeviceInfo 以 **`jpushToken`** 为准；但 `FullChainIntegrationTest` 变量名 `FCM` 实际 set 进 `device.jpushToken`（命名误导，语义正确）；`UserProfile.DeviceInfo` 暂留 `fcmToken` 兼容字段 | 测试可读性/收口动作 | 收口：编排切 `jpushToken` 后由主架构在 common 移除 `fcmToken`；同步把 `FullChainIntegrationTest` 变量名 `FCM`→`JPUSH` |
| **§11-7** | `idempotency_key` 维度 | 领域模型字段注释为 `plan_id:step_order:attempt`；引擎实测 key=`planId:stepOrder:retryCount`（#31）；契约定稿口径 `plan:stepOrder:retryCount` | 术语 attempt vs retryCount | 统一表述为 `retryCount`（与代码一致），更新领域模型注释 |
| **§11-10** | 建计划阶段 vs 执行阶段守卫边界（已澄清） | §3.1② `PreFlightChecker` 是**执行阶段**（`executeStep`）守卫，读失败→`check=false`→静默退出（fail-close，不 NACK）；**建计划阶段**（`onCaseIngested→createPlanForStage`）的 `CaseService.getCaseInfo/getContextSnapshot` 读失败**无 fail-close**，异常上抛→`@Transactional` 回滚→NACK 重消费 | 链路① 读失败行为与链路②执行守卫不同，易混淆 | **已澄清**：建计划阶段读失败 = NACK 重消费（计划不可丢）；执行阶段守卫 = fail-close 静默跳过。差集 D25 据此补 L0（建计划阶段读失败异常上抛断言） |
| **§11-9** | 空地址 SKIP 断言归属（方案A，已澄清） | 契约定稿「方案A」：空地址（`NO_PHONE`/`NO_TOKEN`/`NO_EMAIL`）由编排侧 `ExecutionGuard` 返回 `block`，引擎统一 `markSkipped`（SKIPPED+COMPLIANCE_BLOCKED+推进）；`NO_EMAIL` 已由 C3 覆盖，`NO_PHONE`/`NO_TOKEN` 在引擎侧无 L0/L2 断言（D5） | 链路② 空地址 SKIP 语义的引擎↔编排断言边界 | **已澄清**：引擎侧用 L2 替身断言「block→SKIPPED」通用语义（②-S30/S31，可内存补 +2 `@Test`，见 §4.2.6）；rule 码真值（哪种缺失→哪个码）归编排 L4（`TC-PUSH-02`/`TC-EMAIL-02`）。PUSH 空 token 但有 SMS 号→编排 fallback SMS（C2，不进 SKIP） |
| **§11-11** | 链路③ `PLAN_COMPLETED` 未写 `completed_at`（已拍板） | 定义卡 §5.1「数据落点 `t_contact_plan.completed_at`」；但 `onStepCompleted` 的 `PLAN_COMPLETED` 分支仅 `updatePlanStatus(planId, PLAN_COMPLETED, null)`，**未调用 `markCompleted`**——`markCompleted(setCompletedAt(now))` 仅在内存测试仓储（`FullChainIntegrationTest`/`ChannelContractL2Test`）中定义，**生产 `PlanLifecycleManager` 无任何调用** | `completed_at` 当前不写，与定义卡落点不符；③-S4（#14）仅断言 status | **已拍板（2026-06-21，选 b）：`completed_at` 由 L3 admin/落库侧统一补写，引擎不负责、不补码**（同 §11-8 模式）。定义卡 §5.1 落点已标注「completed_at 归 admin/L3 落库」；引擎 L0/L1 不就此补 `@Test`，D27 转 L3 落库项（归 D14 同环境） |
| **§11-12** | **决策 B：快照来源 = 事件 payload（2026-06-29 已拍板）** | `PlanLifecycleManager` 建计划/续建/升档优先据 `CASE_INGESTED` payload / carry-forward 组装快照，**运行时不读旧库 `t_collection`**；payload 缺失才降级 `CaseService`。已加 L0 验证 payload 路径 | 快照主链路与旧库解耦 | 已落地 common/engine/ingestion 重载；**jpushToken**：`case_push` 消息体携带（2026-07 确认），入案零读库 |

---

## §12 现状 / Phase 1 应补 / Phase 2 延后

### 12.1 现状已覆盖（✅，CI/本地可跑）

- **L0 引擎纯逻辑**：状态机（#14–#27）、七步管线（#1–#13、#28–#32）、系统守卫（#5a–#5e）、SPI 硬超时（`SpiInvokerTest` 5 例）—— 全绿。
- **L0 渠道纯逻辑（27 例）**：`ScriptResolverLogicTest`(6，变量注入/空 name 清洗/scriptSlot 推导)、`MockPlanFactoryGuardTest`(4，CEASED/DPD91/null 拒建 + 正常放行)、3 个 Adapter 单测共 17 例（`SendGridEmailAdapterTest` 5 + `NotificationSmsAdapterTest` 6 + `NotificationPushAdapterTest` 6，覆盖请求/响应映射 + 空地址永久失败 + 无 token→SMS fallback + testSend/sync 模式 + 5xx retryable）。
- **L1 内存集成**：`FullChainIntegrationTest`（三渠道闭环 + 还款取消 + STAGE_CHANGED 升档建新 + CASE_CEASED 取消）+ `AsyncCallbackChainL1Test`（AI_CALL 回调完成 + 超时兜底）。
- **L2 契约骨架**：`ChannelContractL2Test`（C1–C7，编码契约替身，🟡 骨架绿，待真实化对接即 ✅）。

> **2026-06-22 L0/L1 差集回补闭合**：D1/D16/D17/D18/D20/D21/D22/D23/D25/D26/D28（L0）+ D24/D29/D3（L1）共 17 例已补并全绿；引擎模块 55→72、全仓 82→99。

### 12.2 Phase 1 应补（⬜，范围内、当前缺）

| 项 | 差集 | 归属 | 环境 |
|---|---|---|---|
| 异步回调链 L2 契约载体（AI_CALL 回调 payload→ContactResult 映射、回调态拦截 1 例） | D4 | 主架构 + 编排 / L2 | 内存（无需环境） |
| 空地址 NO_PHONE/NO_TOKEN SKIP 断言 | D5 | 编排（L2 替身或 L4） | 内存 / 应用 |
| L3 落库（Mapper/snapshot 往返/TriggerScanner/并发串行化/写失败 NACK/completed_at/回调 timeline） | D10/D11/D14/D19/D27 | 服务同事 + 主架构 | MySQL/Testcontainers |
| 渠道幂等 TC-IDEM-02 | D12 | 编排 | Redis（L1 渠道单测） |
| L4a/L4b 端到端真实供应商回归（L4a-薄用例清单见 §L4a，L4a-1…8） | D6/D8/D15 | 编排 / 全员 | 应用 + Nacos + `*CaseRegistry` 测试号 / 真实 ingestion |
| 待澄清回补（§11 文档表补登） | §11-2/4/5 | 主架构 | 内存 |

### 12.3 Phase 2 延后（⏭）

- 跨存储一致性对账修复（§5.2 三模式：对账清理器 / 重发事件 / 处理中标记）—— D13，运维兜底层。
- **崩溃恢复与基础设施防护**（2026-06-25 §7 审计补登）：续建崩溃搁浅 **D30**（注入崩溃点验证重投幂等）、毒消息 DLQ **D31**（需 Redis Stream `delivery_count`）、step=EXECUTING reaper **D32**（需真实扫描器+Redis TTL）。三项均需真实基础设施，内存版不可测。
- 真实并发/混沌/性能层（用户提及的「L5」）—— 压测、故障注入（D30/D31/D32 为 L5 首批用例）。
- LLM 决策替换 SPI（规则引擎→LLM）后的决策回归。
- VIBER/WHATSAPP（若 §11-4 确认非 Phase 1）。
- **PTP 相关（已拍板 2026-06-21）：Phase 1 不考虑 PTP 差异化**——PTP 无活跃→续建（M20/D2）、PTP 路径独立 `cancel_reason`（`PTP_EXPIRED`）均延后；Phase 1 维持现状（#27-R/#27-A 已覆盖，补偿取消统一写 `REPAID`）。D2 骨架（§7.2.6 ①）预留待 Phase 2 启用。

---

### 12.4 Phase 1 收口路线 / Definition of Done（可勾选门禁，2026-06-22 起点）

> **读法**：9 个收口项，前 7 项是**测试覆盖 DoD**，第 8 项是**门禁依赖项**（不完成则 L4 链路④阻塞），第 9 项说明**生产就绪项不计入测试 DoD**。勾选全部前 8 项 = Phase 1 端到端测全（**三渠道 SMS/PUSH/EMAIL + 电话类**）。

#### 测试覆盖 DoD（7 项）

- [ ] **① L2 异步回调契约补齐**（差集 D4）
  - `ChannelContractL2Test` 补 `c8_aiCallCallback_completesStep`（AI_CALL 回调 payload→ContactResult 映射 + 回调态拦截 1 例）。
  - 归属：主架构 + 编排同事（待 AI_CALL 真实化对接即绿）。内存，无需环境。

- [ ] **② L2 空地址 SKIP 契约补齐**（差集 D5 引擎侧）
  - `ChannelContractL2Test` 补 `c9_emptyPhone_guardSkips`（NO_PHONE→SKIPPED）、`c10_emptyTokenNoFallback_guardSkips`（NO_TOKEN→SKIPPED）共 2 例。
  - rule 真值（`TC-PUSH-02`/`TC-EMAIL-02`/`TC-GUARD-*`）待 A2→`DefaultExecutionGuard` + L4a-全。

- [ ] **③ L3 落库单测**（差集 D10/D11/D14/D19/D27）
  - `ContactPlanMapper`/snapshot 往返/`TriggerScanner` 连库/写失败 NACK/`completed_at`/回调 timeline 落库。
  - 归属：服务同事 + 主架构（admin）。环境：MySQL/Testcontainers。

- [ ] **④ 渠道幂等 TC-IDEM-02**（差集 D12）
  - 相同 `idempotencyKey` 去重（渠道侧 L1 单测）。归属：编排同事。环境：Redis。

- [x] **⑤ L4a-全（临时 `Default*`@Primary）→ `./scripts/test/l4a-official-test.sh` 跑通 §L4a 8 条 + Guard/REBUILD**（2026-06-25；编排同事仍须 review 替换生产实现）
  - 主架构代写见 `docs/testing/MOCASA催收系统升级_Phase1_L4a全量前置_编排同事补全清单.md` §8；专用 case：`94999`/`94801`/`94804`。

- [ ] **⑥ L4b-全（B1/B2 真实化 + L3 连库）→ 跑通 §L4b 8 条用例**
  - B1 `IngestionService` 接真实 PubSub Consumer、B2 `DpdStageRollHandler` 日切重算逻辑、`RealCaseService`(`case-service=real`) 连旧库、L3 落库。
  - 归属：数据接入 + 服务同事 + 主架构（admin）。环境：GCP PubSub + MySQL + XXL-Job。

- [ ] **⑦ 链路④ 异步回调（AI_CALL）L4 真实供应商**
  - 不在 L4a-薄范围（无真实电话号源）；需 AI Call 供应商 Webhook，走 `TC-VOICE-01/02/03`。
  - 归属：编排同事（渠道指南，外链见附录 B）。环境：LTH Voice API + Webhook 回调。

#### 门禁依赖项（1 项，不完成则 L4 链路④ 阻塞）

- [ ] **⑧ `WebhookController` 加鉴权**（HANDOFF.md §E2）
  - 当前 `admin/web/WebhookController.java` 骨架已实现事件发布，**缺供应商签名校验层**。
  - L4 链路④（AI_CALL）真实 Webhook 回调依赖此；L4a/L4b 消息类（SMS/PUSH/EMAIL，无回调）不阻塞。
  - 归属：admin（主架构 + 服务同事）。

#### 生产就绪项（不计入测试 DoD，单列）

> 以下项影响**生产上线**但不阻塞 Phase 1 测试覆盖（当前内存版对测试完全等效）：

- 🏭 **Redis Stream 事件总线** 替换内存版（HANDOFF §D1）—— 生产一致性/崩溃恢复；测试用内存版语义等效。
- 🏭 **Redis 幂等锁** 替换内存版（HANDOFF §D2）—— 分布式幂等；测试用内存版等效。
- 🏭 **TriggerScanner → XXL-Job**（HANDOFF §E1）—— 分布式调度；测试用 `@Scheduled` 等效。
- 🏭 **可观测性**（Micrometer 指标 + MDC 跨线程，HANDOFF §D4）—— 运维，不影响测试断言。
- 🏭 **`fcmToken` 收口**（§11-6）—— common 移除 `fcmToken`、测试变量名 `FCM`→`JPUSH`；小改，可最后收。
- 🏭 **`idempotency_key` 术语统一**（§11-7）—— 领域模型注释 `attempt`→`retryCount`；文档改，不影响运行。

#### §11 待澄清收口（2 项，影响范围认定，尽早拍板）

- [ ] **§11-4/§11-5 范围拍板**：VIBER/WHATSAPP 与 HUMAN_CALL 是否计入 Phase 1 测全（若否→ ⏭ Phase 2，D7/D9 即关闭）。当前暂按「Phase 1 仅 SMS/PUSH/EMAIL + AI_CALL」处理，待正式确认后更新 §10.5 与 §12.3。
- [ ] **§11-2 文档补登**：领域模型 §6.6 补 `CALLBACK_TIMEOUT`（内部哨兵）/ `CASE_CEASED`，§6.7 补 `CancelReason.CEASED`（engineManaged）。不阻塞测试，但影响「文档↔代码一致性」门禁。

#### 全量收口顺序（建议）

```
L0/L1 全绿（✅ 已达）
  → ① L2 c8（D4）+ ② L2 c9/c10（D5）      ← 内存，立即可补
  → ⑧ WebhookController 鉴权               ← admin，解锁链路④ L4
  → ⑤ L4a-全（临时 Default* 已就位，跑 official 脚本）  ← 2026-06-25
  → ③ L3 落库单测（D10/D11/D14/D19/D27）   ← 连库环境
  → ④ 渠道幂等 TC-IDEM-02（D12）            ← Redis 环境
  → ⑥ L4b-全（B1/B2 + 旧库 + L3）          ← 数据接入 + 旧库
  → ⑦ 链路④ Voice L4（TC-VOICE-*）  ← AI Call 供应商
  → §11-4/5 范围拍板 + §11-2 文档补登       ← 文档一致性
  ────────────────────────────────────────
  ✅ Phase 1 端到端测全（SMS/PUSH/EMAIL + AI_CALL）
```

---

## 附录 A 测试资产索引（可执行性落点）

> 真实测试类与方法清单（方法名已与代码逐一核对，2026-06-21；非示例）。运行见 §0.3。

### A.1 collection-engine（L0/L1/L2，主架构 · 共 72 例，含 2026-06-22 差集回补 +17）

| 文件 | 覆盖（旧编号） | 方法（全量，已核对） |
|---|---|---|
| `lifecycle/MessageChannelHappyPathTest` | #1–#3、#14 | `messageChannel_noObservation_completesAndPublishes`、`messageChannel_withObservation_entersWaiting`、`duplicateEvent_skips`、`onStepCompleted_planCompleted` |
| `lifecycle/StepExecutionOrchestratorTest`（17） | #4–#13、#28–#32 + D20 | …（原 16 例）+ `notRetryable_failedNoBackoff`（D20，retryable=false→FAILED 不退避） |
| `lifecycle/PreFlightCheckerTest` | #5a–#5e | `caseNotFound_returnsFalse`、`repaid_returnsFalse`、`frozen_returnsFalse`、`alive_returnsTrue`、`readFailure_failCloseReturnsFalse` |
| `lifecycle/PlanLifecycleManagerTest`（28） | #15–#27 + D1/D16/D17/D18/D21/D22×2/D23/D25/D26/D28/D29-L0 | …（原 16 例）+ `onCaseIngested_factoryNull_noPlan`（D21）、`onCaseIngested_ceasedCaseStatus_skip`/`onCaseIngested_ceasedSnapshot_skip`（D22）、`onCaseIngested_firstStepTriggerTime_andSnapshotFrozen`（D23）、`onCaseIngested_caseServiceReadFailure_propagates`（D25）、`onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger`（D1）、`prepareStepDue_waitingCarryOver_defaultSentNoResponse`（D26）、`onChannelCallback_nonExecuting_silentlyAbsorbs`（D16）、`onCallbackTimeout_nonExecuting_noop`（D17）、`onChannelCallback_mapsResultVariants`（D18）、`onRepaymentReceived_filterFails_stillCancels`（D28）、`onCaseCeased_cancelsActivePlanAndNoRebuild`（D29-L0） |
| `spi/SpiInvokerTest` | SPI 硬超时 | `returnsResultWithinTimeout`、`throwsOnTimeout`、`propagatesRuntimeException`、`propagatesMdcToWorker`、`directModeDoesNotEnforceTimeout` |
| `integration/FullChainIntegrationTest`（4） | L1 + D24/D29-L1 | `caseIngested_runsThreeChannels_toCompleted`、`repaymentCancelsActivePlan`、`stageChanged_cancelsOldAndBuildsNewStagePlan`（D24）、`caseCeasedCancelsActivePlan`（D29-L1） |
| `integration/AsyncCallbackChainL1Test`（2，新建） | L1 链路④ / D3 | `asyncCallback_keepsExecuting_thenCompletes`、`asyncTimeout_failsAndAdvances` |
| `integration/ChannelContractL2Test` | C1–C7 | `c1_dispatchSuccess…`、`c2_pushEmptyToken_gatewayFallbackSms…`、`c3_emptyEmail_guardSkips…`、`c4_networkTimeout_retryable…`、`c5_invalidAddress_notRetryable…`、`c6_smsObservation_waitsThenSettles`、`c7_idempotency_duplicateEventNoDoubleDispatch` |

> **A.1-④ 链路④异步回调（差集回补，2026-06-22 已补并全绿）**：
> - ✅ `PlanLifecycleManagerTest` **+3**：`onChannelCallback_nonExecuting_silentlyAbsorbs`（D16/④-S5）、`onCallbackTimeout_nonExecuting_noop`（D17/④-S7）、`onChannelCallback_mapsResultVariants`（D18/④-S4）。
> - ✅ `AsyncCallbackChainL1Test`（新建，L1）**+2**：`asyncCallback_keepsExecuting_thenCompletes`（D3/④-S8）、`asyncTimeout_failsAndAdvances`（D3/④-S9）。
> - ⬜ **仍待**：`ChannelContractL2Test` **+1** `c8_aiCallCallback_completesStep`（D4/④-S10，L2 契约，待编排 AI_CALL 真实化对接）。
> - D19（timeline）已拍板归 L3 admin 落库，不计引擎 `@Test`。

> **A.1-② 链路②调度执行空地址 SKIP 应补清单（差集 D5，方案A/§11-9；补码前不计入 §1）**：
> - `ChannelContractL2Test` **+2**：`c9_emptyPhone_guardSkips_noDispatch`（②-S30，NO_PHONE）、`c10_emptyTokenNoFallback_guardSkips`（②-S31，NO_TOKEN）。骨架草案见 §4.2.6。
> - 补码后 `ChannelContractL2Test` C1–C7→C1–C10，引擎 L2 7→9，届时同步 §1 与附录 A（与下方 A.1-① 合并计入）。
> - D6（守卫规则类型，L4）/ D10（⑥⑦ 写失败 NACK，L3）按约定**只登差集不出骨架**。
> - **D20** 引擎 L0 不可重试 **+1**：`StepExecutionOrchestratorTest#notRetryable_failedNoBackoff`（②-S18）。骨架草案见 §4.2.6。
> - **草案标注**：需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过。

> **A.1-① 链路①入案建计划应补清单（差集 D21–D25；补码前不计入 §1）**：
> - `PlanLifecycleManagerTest` **+5**：`onCaseIngested_factoryNull_noPlan`（D21/①-S5；含 stage 空边界 ①-S9）、`onCaseIngested_ceasedCaseStatus_skip`（D22/①-S6）、`onCaseIngested_ceasedSnapshot_skip`（D22/①-S7）、`onCaseIngested_firstStepTriggerTime`（D23/①-S2，一并断言 snapshot 冻结 ①-S3）、`onCaseIngested_caseServiceReadFailure_propagates`（D25/①-S11）。照 #20/#21 mock 范式书写。
> - `FullChainIntegrationTest` **+1**：`stageChanged_cancelsOldAndBuildsNewStagePlan`（D24/①-S15）。骨架草案见 §3.2.6。
> - D14（L3 Mapper/snapshot 往返）/ D15（L4 真实计划结构）**待环境，只登差集不出骨架**。
> - **合计本轮（链路①②）可内存补：引擎 L0 +6（D20 1 + D21 1 + D22 2 + D23 1 + D25 1）、L1 +1（D24）、L2 +2（D5）= +9 `@Test`**。

> **A.1-③ 链路③结果回收应补清单（差集 D1/D26/D27；补码前不计入 §1）**：
> - `PlanLifecycleManagerTest` **+2**：`onStepCompleted_advanceNext_delayZero_schedulesWithNowTrigger`（D1/M9/③-S2，据实断言 trigger≈now + STEP_SCHEDULED；**非同步递归**，§11-3 修正）、`prepareStepDue_waitingCarryOver_noResult_sentNoResponse`（D26/③-S7）。骨架草案见 §5.2.6。
> - **D27**（PLAN_COMPLETED 未写 completed_at，③-S4/§11-11）：**已拍板（选 b）：completed_at 归 L3 admin 落库写，引擎不补码**，归 D14 同环境，本累计不含 D27。
> - **草案标注**：2 例需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过。

> **A.1-⑤ 链路⑤中断与重建应补清单（Phase 1 差集 D28/D29；D2 ⏭ Phase 2、D11 待 L3 连库；补码前不计入 §1）**：
> - `PlanLifecycleManagerTest` **+2**：`onRepaymentReceived_filterFails_stillCancels`（D28/⑤-S2，stub `filterRepaidUser` 抛异常→验证计划仍取消）、`onCaseCeased_cancelsActivePlanAndNoRebuild`（D29-L0/⑤-S9）。照 #24/#27 mock 范式书写。
> - `FullChainIntegrationTest` **+1**：`caseCeasedCancelsActivePlan`（D29-L1/⑤-S13）。骨架草案见 §7.2.6。
> - **D2/M20**（PTP 无活跃续建）**⏭ Phase 2 延后**（Phase 1 不考虑 PTP，已拍板 §7.2.5/§12.3）；骨架 §7.2.6 ① 预留，不计入 Phase 1。**D11**（真实并发 `SELECT FOR UPDATE` 串行化，⑤-L3）**待环境，只登差集不出骨架**。
> - 合计本链路 Phase 1 可内存补：引擎 **L0 +2、L1 +1 = +3 `@Test`**；同时闭合 §11-1 引擎测试残留（D29）。
> - **草案标注**：3 例（含 D2 预留）需本地 `mvn -pl collection-engine -am test` 编译运行确认，未保证一次通过。

> - **跨链路累计实况（2026-06-22）**：计划 +20 中**已补 +17 并全绿**（引擎 L0 +14：D1/D16/D17/D18/D20/D21/D22×2/D23/D25/D26/D28/D29-L0；L1 +3：D24/D29-L1/D3×2），引擎模块 **55→72**、全仓 **82→99**（已同步 §1）。**仍待 +3 均为 L2 契约**：D4（`c8_aiCallCallback`）+ D5（`c9_emptyPhone`/`c10_emptyTokenNoFallback`），归编排同事 AI_CALL/空地址真实化「对接即绿」。**注：D2（PTP 续建）⏭ Phase 2、D27（completed_at）/D19（回调 timeline）归 L3 admin 落库、D11（并发）待 L3 连库，均不计入本累计**。

### A.2 collection-channel（L0c，编排同事 · 共 27 例）

| 文件 | 例数 | 方法（全量，已核对） |
|---|---|---|
| `strategy/ScriptResolverLogicTest` | 6 | `inject_replacesAllPlaceholders`、`inject_emptyName_cleansPunctuation`、`inject_emptyName_stripsLeadingComma`、`deriveSlot_s0_byDpd`、`deriveSlot_firmOnlyForSmsAtS2Plus`、`deriveSlot_nullStage_fallsBackToS1` |
| `strategy/MockPlanFactoryGuardTest` | 4 | `rejectCeasedCaseStatus`、`rejectDpd91`、`rejectNullCaseInfo`、`allowNormalCase`（被测生产方法为 `shouldRejectPlan`） |
| `adapter/SendGridEmailAdapterTest` | 5 | `accepted202`、`noEmailPermanentFailure`、`resolvesTemplateIdFromScriptSlotMap`、`noTemplateMappingFails`、`notConfiguredWhenMissingApiKey` |
| `adapter/NotificationSmsAdapterTest` | 6 | `sendSuccess`、`rejectedNoAccount`、`requestSuccessFalseRejected`、`transient5xxRetryable`、`notConfigured`、`testModeHitsTestSendWithoutSign` |
| `adapter/NotificationPushAdapterTest` | 6 | `enqueueSuccessNoProviderMsgId`、`noTokenFallbackToSms`、`invalidProviderFailed`、`transient5xxRetryable`、`syncModeHitsSyncEndpointAndReturnsRequestId`、`syncModeRejectedWhenRequestSuccessFalse` |

## 附录 B 外链引用清单（不复制全文，单一信息源边界）

| 文档 | 用途 | 本文引用方式 |
|---|---|---|
| `docs/channel/...collection-channel功能测试指南.md` | L2/L3/L4 curl + SQL 用例（`TC-*`）、测试数据约定、阶段门禁、通过标准 | 各链路 L4 单元格按 TC-ID 索引 |
| `docs/channel/email-templates/email-e2e-test-cases.md` | 5 封 Email E2E 案例表 | `TC-EMAIL-E2E` 索引 |
| `docs/channel/...Notification对接说明.md`、`...SendGrid_Email对接说明.md`、`...LTH_Voice对接说明.md` | 供应商接口/签名/回调 | L4 执行细节，不复制 |
| `docs/contracts/引擎渠道执行契约对齐_待编排确认.md` | StepResult 3 情形 / metadata / 观察期 / 空地址方案 A | §2.2、§10.4 契约基线 |
| `docs/contracts/README_ContextSnapshot契约对齐.md` + `ContextSnapshot.sample.json` | 快照字段/取号口径 | §2.2、§11-6 |
| `docs/contracts/README_编排同事对齐清单.md` | SPI 实现约束 + E1–E8 | §2.2、§11 待澄清 |
| `docs/MOCASA催收系统升级_Phase1_核心引擎规格.md` / `领域模型与数据定义.md` | 状态机/七步/SPI/DTO/枚举/DDL 基准 | 全文场景反推依据 |

---

> MOCASA Collection System Upgrade — Phase 1 测试主文档（链路 × 层级）v2.0 — 维护人：主架构
