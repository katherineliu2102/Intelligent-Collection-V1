# Phase 1 测试总览（分层索引）

> 维护人：主架构。定位：**全局测试地图**，索引各层测试与负责人，**不重复抄写**已有矩阵。
> 引擎纯逻辑层详见 [`测试矩阵_engine阶段1.md`](./测试矩阵_engine阶段1.md)。
>
> 图例：✅ 已就绪 ｜ 🟡 进行中 ｜ ⬜ 待联调环境/跨人对齐

## 分层模型

测试按"耦合度 / 依赖范围"分 5 层，异常等作为**横切维度**贯穿各层（见末节），避免重复设章。

| 层 | 名称 | 依赖 | 连库 | 责任人 | 状态 |
|---|---|---|---|---|---|
| L0 | 引擎纯逻辑单测 | mock SPI/Repo/EventBus | 否 | 主架构 | ✅ |
| L1 | 引擎全链路内存集成 | 内存总线 + 内存仓储 + 真实引擎组件 | 否 | 主架构 | ✅ |
| L2 | 引擎↔编排联调 | mock 渠道 → 真实渠道（SMS/PUSH/EMAIL） | 否/可选 | 主架构 + 编排同事 | 🟡 |
| L3 | 数据落库集成 | MyBatis + MySQL/Testcontainers | 是 | 服务同事 + 主架构 | ⬜ |
| L4 | 端到端回归 | 全模块装配 + 真实/准真实数据 | 是 | 全员 | ⬜ |

---

## L0 引擎纯逻辑单测（✅ 已就绪）

- 范围：状态机（§2）+ 七步管线（§3.1）+ 系统守卫（§3.1②）。
- 现状：**43 单测全绿**，场景明细见引擎矩阵（七步管线 #1-13/#28-32、状态机 #14-27、PreFlight #5a-5e）。
- 约定：全 mock 不连库；SPI 调用硬超时 + 异常兜底。
- **此处只索引，明细与新增以引擎矩阵为准（单一信息源，防漂移）。**

## L1 引擎全链路内存集成（✅ 已就绪）

- 文件：`collection-engine/.../integration/FullChainIntegrationTest`。
- 覆盖：`CASE_INGESTED → SMS → PUSH → EMAIL → PLAN_COMPLETED` 闭环、三渠道共用快照按 channelType 取址、入案后还款取消。
- 价值：补单测覆盖不到的"装配 + 事件链"。

## L2 引擎↔编排联调（🟡 进行中 — 当前推进重点）

冻结项依据 [`ic-v1-channel-contract.mdc`](../../.cursor/rules/ic-v1-channel-contract.mdc) 与 [ContextSnapshot 对齐](./contracts/MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐_re.md)。

> 骨架已落：`collection-engine/.../integration/ChannelContractL2Test`（编码定稿契约语义的可配置替身 + mock 发送，7 例全绿）。编排同事真实化 Mock 后对接即绿。

| # | 场景 | 期望 | 状态 |
|---|---|---|---|
| C1 | dispatch 成功（以 EMAIL 为例） | StepResult.success=true，timeline 落 providerMsgId | 🟡 骨架绿 |
| C2 | PUSH jpushToken 为空 → fallback SMS | 同槽 fallback，一次 dispatch | 🟡 骨架绿 |
| C3 | EMAIL email 为空 → Guard SKIP | 步骤 SKIPPED + 推进 | 🟡 骨架绿 |
| C4 | dispatch 网络超时 retryable=true | 退避重试（步骤重排 PENDING + 未来触发） | 🟡 骨架绿 |
| C5 | dispatch 地址无效 retryable=false | FAILED + 推进 | 🟡 骨架绿 |
| C6 | SMS 观察期 → STEP_WAITING → 到期结转 | 状态正确流转 | 🟡 骨架绿 |
| C7 | idempotencyKey(plan+step+retryCount) 防重发 | 重复触发不二次发送 | 🟡 骨架绿 |

> 契约已定稿（2026-06-11，见 [`引擎渠道执行契约对齐_待编排确认`](./contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)）：StepResult 3 情形、SMS 观察期 10min、空地址方案 A、token=jpushToken。
> 待编排同事真实化 `ChannelGateway`/`StepResolver`/`ExecutionGuard` 后，把替身换成真实实现即转 ✅。

## L3 数据落库集成（⬜ 待环境）

- 位置：`collection-admin` 的 `@SpringBootTest`，标 `@Tag("integration")`，默认 CI 不跑。
- 覆盖：`ContactPlanMapper`/`ContactPlanStepMapper`/`TimelineRepository` 真实读写、`context_snapshot` JSON 序列化往返、TriggerScanner 扫描到期步骤。
- 依赖：MySQL（`ai_collection_db`）或 Testcontainers；连接信息不入仓库。

## L4 端到端回归（⬜ 待环境）

- 全模块装配，从入案到计划完成/穷尽的真实/准真实链路。
- 作为发版前回归基线。

---

## 横切维度（在各层分别打标覆盖，不单设章）

| 维度 | 含义 | 主要落点 |
|---|---|---|
| [异常] | 守卫异常 / 解析异常 / 渠道异常 / DB 不可达 fail-close / 回调超时 | L0（#7/#8/#9/#5e）、L2（C4/C5） |
| [幂等] | 重复事件、重试 key 不自锁 | L0（#3/#31）、L2（C7） |
| [终态] | 终态拦截、回写前取消复检 | L0（#12/#32）、状态机 #19 |
| [退避] | 重试递增与封顶 | L0（#28/#29） |

---

## 运行入口

```bash
# L0 + L1（CI 常绿基线）
mvn -pl collection-common,collection-engine -am test -Dsurefire.failIfNoSpecifiedTests=false
# 连库集成（需环境）
mvn -pl collection-admin -am test -Dgroups=integration
```

## 待办与跨人对齐

- L2：与编排同事确认 StepResult/targetAddress/观察期口径后，将 C1-C7 落为可执行测试。
- L3/L4：待联调库与 Testcontainers 就绪。
- 引擎语义若调整（如新增 `CASE_CEASED`、禁用 HUMAN_CALL），回头同步引擎矩阵与本总览。
