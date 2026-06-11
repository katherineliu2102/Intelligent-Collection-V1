# 引擎阶段 1 测试矩阵（轻量清单）

> 维护人：主架构。范围：`collection-engine` 纯逻辑单测（mock SPI/Repository/EventBus，不连库）。
> 里程碑：引擎语义冻结、mock 链路 CI 常绿。规格依据见各行「规格章节」。
>
> 图例：✅ 已覆盖 ｜ ⬜ 待补
>
> **当前状态：37 个场景全部覆盖，单测 43 个全绿（含 happy path 子用例 + 值级/边界补强）。**

## StepExecutionOrchestrator（七步管线，核心引擎规格 §3.1）

| # | 场景 | 期望 | 规格 | 状态 |
|---|---|---|---|---|
| 1 | 消息渠道无观察期，发送成功 | STEP_COMPLETED + 发布 STEP_COMPLETED | §3.1⑦ | ✅ |
| 2 | 消息渠道有观察期，发送成功 | STEP_WAITING，不发完成事件 | §3.1⑦ | ✅ |
| 3 | 重复事件（幂等锁未获取） | 静默退出，不触达 | §3.1① | ✅ |
| 4 | PUSH 渠道 happy path | 同消息渠道分流 | §3.1⑦ | ✅ |
| 5 | 系统守卫不通过（已还款/冻结/读失败 fail-close） | 静默退出 | §3.1②、§5.1 | ✅ |
| 6 | 业务守卫拦截（不放行） | SKIPPED + COMPLIANCE_BLOCKED + 推进 | §3.1③ | ✅ |
| 7 | 业务守卫抛异常（fail-close） | SKIPPED + 推进 | §4.1 | ✅ |
| 8 | StepResolver 抛异常 / 返回 null | FAILED + 推进 | §4.1 | ✅ |
| 9 | ChannelGateway 抛异常 | 视为 retryable | §3.2 | ✅ |
| 10 | 发送失败 retryable 且未超上限 | 退避重试、保持 STEP_EXECUTING | §3.2 | ✅ |
| 11 | 发送失败超过 maxRetry | FAILED + 推进 | §3.2 | ✅ |
| 12 | 回写前计划已取消（⑤½ 复检，终态） | 仅记录 timeline，不推进 | §3.1⑤½ | ✅ |
| 13 | 异步渠道（AI_CALL/TTS） | 保持 STEP_EXECUTING + 注册回调超时 | §3.1⑦ | ✅ |
| 28 | 退避算法递增（第 3 次=base×factor³=240s） | 触发时间 ≈ now+240s | §3.2 | ✅ |
| 29 | 退避封顶（超 retryMaxIntervalSeconds 取上限） | 触发时间 ≈ now+上限 | §3.2 | ✅ |
| 30 | 异步回调超时 metadata 覆盖默认值 | 超时时间用 metadata.timeoutMinutes 而非默认 60 | §3.1⑦ | ✅ |
| 31 | 幂等 key 含 retryCount（重试不被自身幂等拦截） | key = planId:stepOrder:retryCount | §3.1① | ✅ |
| 32 | 回写前计划已不存在(null)（⑤½ 复检，null 分支） | 仅记录 timeline，不推进 | §3.1⑤½ | ✅ |

## PlanLifecycleManager（状态机，核心引擎规格 §2）

| # | 场景 | 期望 | 规格 | 状态 |
|---|---|---|---|---|
| 14 | onStepCompleted = PLAN_COMPLETED | 计划 → PLAN_COMPLETED | §2.3.2 | ✅ |
| 15 | onStepCompleted = ADVANCE_NEXT 有下一步 | 注册下一步 + STEP_SCHEDULED | §2.3.2 | ✅ |
| 16 | onStepCompleted = ADVANCE_NEXT 无下一步 | 发布 PLAN_EXHAUSTED | §2.3.2 | ✅ |
| 17 | prepareStepDue PENDING/SCHEDULED/EXECUTING | → STEP_EXECUTING + toExecute | §2.3.1 | ✅ |
| 18 | prepareStepDue STEP_WAITING（观察期到期） | 步骤 COMPLETED + 结转事件 | §2.3.1 | ✅ |
| 19 | prepareStepDue 计划终态 | noop | §2.3.1 | ✅ |
| 20 | onCaseIngested 建计划 | 落库 PENDING + 首步 trigger | §2.2 | ✅ |
| 21 | onCaseIngested 单活跃计划幂等 | 已有活跃计划则跳过 | §2.2 | ✅ |
| 22 | onChannelCallback | 步骤 COMPLETED + 推进 | §2.3.3 | ✅ |
| 23 | onCallbackTimeout | 步骤 FAILED + 推进 | §2.3.4 | ✅ |
| 24 | onRepaymentReceived | 取消活跃计划 + filterRepaidUser | §2.4 | ✅ |
| 25 | onPlanExhausted REBUILD/ESCALATE/COMPLETE | 三分支正确 | §2.5 | ✅ |
| 26 | onStageChanged 取消旧阶段 + 建新 | 旧 CANCELLED + 新建 | §2.2 | ✅ |
| 27 | onPtpExpired 已还款/活跃计划分支 | 补偿取消 / 不动 | §2.6 | ✅ |

## PreFlightChecker（系统级守卫，核心引擎规格 §3.1②、§5.1）

| # | 场景 | 期望 | 规格 | 状态 |
|---|---|---|---|---|
| 5a | 案件不存在(null) | check=false 静默退出 | §3.1② | ✅ |
| 5b | 已还款 | check=false | §3.1② | ✅ |
| 5c | 冻结 | check=false | §3.1② | ✅ |
| 5d | 案件存活（未还款/未冻结） | check=true 可触达 | §3.1② | ✅ |
| 5e | 读取失败 DB 不可达 | fail-close → check=false | §5.1 | ✅ |

## 测试文件

| 文件 | 覆盖 |
|---|---|
| `MessageChannelHappyPathTest` | #1-3、#14（消息渠道 happy path 切片） |
| `StepExecutionOrchestratorTest` | #4-13、#28-32（七步管线分支 + 值级/边界补强） |
| `PreFlightCheckerTest` | #5a-5e（系统守卫四类返回 + fail-close） |
| `PlanLifecycleManagerTest` | #15-27（状态机分支） |
| `integration/FullChainIntegrationTest` | 全链路集成（见下） |

## 全链路集成测试（无 DB，CI 可跑）

`FullChainIntegrationTest` 用**同步内存总线 + 内存仓储**驱动**真实引擎组件**（Dispatcher/状态机/七步管线），覆盖单测覆盖不到的"装配 + 事件链"：

- `CASE_INGESTED → SMS → PUSH → EMAIL → PLAN_COMPLETED` 端到端闭环（含多步推进 + 穷尽 COMPLETE）
- 三渠道共用同一份 `ContextSnapshot`，按 `channelType` 取地址：SMS=手机号 / PUSH=jpushToken / EMAIL=email
- 入案后还款 → 取消活跃计划（REPAID）

> 真·连库集成（MyBatis + `ai_collection_db` + TriggerScanner）需 MySQL/Testcontainers，**不在本测试**；
> 待联调环境就绪后于 `collection-admin` 补 `@SpringBootTest`（`@Tag("integration")`，默认 CI 不跑）。

## 运行方式

```bash
mvn -pl collection-common,collection-engine -am test -Dsurefire.failIfNoSpecifiedTests=false
# 全量（CI 同此）：mvn -B clean test
```

## 后续（阶段 2/3，依赖跨人对齐，不在本矩阵）

- 真实 `ContextSnapshot` → 真实 `StepResolver` → 真实发送的端到端测试，依赖与编排/服务同事字段对齐
  （见 `docs/contracts/README_编排同事对齐清单.md`）。
- 引擎语义若因 E1–E8 拍板调整（如新增 `CASE_CEASED` 事件、禁用 HUMAN_CALL），需回头补/改对应单测。
