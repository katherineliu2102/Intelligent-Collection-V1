# 与编排同事（collection-channel）对齐清单

> **版本**: Phase 1  
> **日期**: 2026-06-11  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-common` / `collection-channel`  
> **关联文档**: [核心引擎规格 §6](../MOCASA催收系统升级_Phase1_核心引擎规格.md#6-spi-接口契约)、[ContextSnapshot 契约对齐](./README_ContextSnapshot契约对齐.md)、[引擎渠道执行契约对齐](./MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md)

---

## 一、要对齐的文件

### A. 契约代码（collection-common，我维护，编排同事实现/消费）

| 文件 | 类型 | 编排同事角色 | 对齐点 |
|---|---|---|---|
| `spi/PlanFactory.java` | SPI | **实现** | 入案/升阶/续建建什么计划；null=不建计划；禁副作用；硬超时 50ms |
| `spi/StepResolver.java` | SPI | **实现** | 读快照产出 `StepCommand`（channelType/targetAddress/templateId）；`null` 仅表示策略性跳过→SKIPPED，异常→FAILED；零 DB I/O；硬超时 50ms；**Phase 1 永不输出 HUMAN_CALL**（E4） |
| `spi/ExecutionGuard.java` | SPI | **实现** | 合规放行/拦截；抛异常→fail-close(SKIPPED)；硬超时 20ms |
| `spi/AdvancementPolicy.java` | SPI | **实现** | 推进/完成/穷尽；不许 null；硬超时 10ms |
| `spi/ExhaustionPolicy.java` | SPI | **实现** | 续建/升档/完成；REBUILD 填 templateId、ESCALATE 填 targetStage；硬超时 50ms |
| `channel/ChannelGateway.java` | 技术管道 | **实现** | `dispatch(StepCommand)→StepResult`；熔断/fallback 对引擎透明；抛异常一律 retryable |
| `dto/ExecutionContext.java` | 输入 | **只读** | SPI 统一入参；**禁止调 setter**（只读约束） |
| `dto/StepCommand.java` | 输出 | 产出 | metadata key：stage/language/callbackUrl/timeoutMinutes |
| `dto/StepResult.java` | 输出 | 产出 | success 由 contactResult 推导；retryable 语义（网络超时 true / 号码无效 false） |
| `model/ContextSnapshot.java`（+ CaseContext/UserProfile/ContactHistory） | 快照 | **只读** | StepResolver 决策的唯一数据源；字段见下表 |
| `enums/ChannelType.java` | 枚举 | 共用 | Phase 1 SMS/PUSH/EMAIL 同步完成且 observation=0；AI_CALL 等异步回调 |

### B. 对齐文档（docs/）

| 文件 | 对齐点 |
|---|---|
| `docs/contracts/ContextSnapshot.sample.json` | **冻结的快照样例**——编排同事拿它即可开发 StepResolver |
| `docs/contracts/README_ContextSnapshot契约对齐.md` | message/push 最小必填字段 + 谁填 + 开放问题 |
| `docs/channel/MOCASA催收系统升级_Phase1_渠道编排与引擎对齐待办.md` | **E1–E8** 引擎侧需拍板项（见下） |
| `docs/MOCASA催收系统升级_Phase1_核心引擎规格.md` | 状态机/七步管线语义基准 |

## 二、对齐点汇总

### 契约级（本轮快照相关，需编排同事确认）

1. **`StepCommand.targetAddress`**：✅ StepResolver 从快照取值；SMS=`basic.primaryPhone`、PUSH=`device.jpushToken`、EMAIL=`basic.email`。Gateway/Adapter 不得查库取号。
2. **PUSH token**：✅ 仅 `jpushToken`（JPush Registration ID），不使用 `fcmToken`；来源为 `case_push`，缺失可走约定 fallback。
3. **手机号格式**：✅ 快照统一 E.164（`+63…`）。
4. **快照字段范围**：✅ `work.*`、`risk.*`、offer、投诉冻结均非 Phase 1 StepResolver 输入；可留 null 或不消费。
5. **SPI 副作用与超时**：✅ 实现须禁写 DB/发事件/调外部；Guard 可读合规计数器；引擎强制 `Future.get(timeoutMs)`。

### 生命周期级（E1–E8，详见对齐待办文档，需会议拍板）

| 编号 | 对齐点 | 渠道编排倾向 |
|---|---|---|
| E1 | D+91 停催事件名 | `CASE_CEASED`（不增 Stage.CEASED） |
| E2 | DPD 日切 Job 归属/调度 | 日批 Job 优先 |
| E3 | Override 中断事件最小集 + 谁写标签 | **Phase 2**：Phase 1 不生产/消费 Override 或投诉冻结事件，不由 Guard 拦截 |
| E4 | 引擎是否调度 HUMAN_CALL | **不**进 plan（LTH + 标签） |
| E5 | 外呼调度是否做 VoiceQueue | 固定 trigger_time，不做 Queue |
| E6 | Offer 是否进 ContextSnapshot、谁填 | **Phase 2**：Phase 1 不定义/填充/消费 offer 字段 |
| E7 | PTP 来源 | Phase 1 仅坐席 App |
| E8 | 客户进线事件是否进引擎 | Phase 1 仅 LTH 打标 |

> E3/E6 已收敛为 Phase 2，不构成当前 common 快照或 Phase 1 引擎事件契约。
