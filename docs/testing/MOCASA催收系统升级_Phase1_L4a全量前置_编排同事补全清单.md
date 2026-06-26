# L4a 全量化前置条件 & 编排同事补全清单（通知）

> **发起**：主架构（engine）  **接收**：编排同事（collection-channel）
> **日期**：2026-06-22
> **关联**：测试主文档 [`§L4a 端到端用例清单`](./MOCASA催收系统升级_Phase1_测试文档.md)（L4a-1…8）；契约见 `docs/contracts/`、渠道指南见 `docs/channel/`。
> **一句话**：L4a-**薄**（mock 数据源 + 真实渠道）已具备运行条件（A3/A6 已真实）；要把 L4a 推到 **全量（L4a-全）**，需把 **A1/A2/A4/A5 四个 SPI 从 `Mock*` 切到 `Default*`**——这四个归你（channel）负责。本文列清「前置条件」与「你需要补全的部分」。

---

## 1. 当前运行时装配（已核对代码，2026-06-22）

| 编号 | SPI | 实际生效 bean（`collection-channel`） | 状态 | 备注 |
|---|---|---|---|---|
| A1 | `PlanFactory` | `strategy/MockPlanFactory` | **Mock** | 默认 `PUSH→EMAIL`；`channel.debug.single-step` / `legacy-three-step` 可切 |
| A2 | `ExecutionGuard` | `strategy/MockExecutionGuard` | **Mock** | 恒 `allow()` |
| A3 | `StepResolver` | `strategy/DefaultStepResolver` | ✅ **真实** | 取址/模板/scriptSlot/metadata（含 `fallback_sms`）已就绪 |
| A4 | `AdvancementPolicy` | `strategy/MockAdvancementPolicy` | **Mock** | 末步→`PLAN_COMPLETED`，否则 `ADVANCE_NEXT` |
| A5 | `ExhaustionPolicy` | `strategy/MockExhaustionPolicy` | **Mock** | 恒 `COMPLETE`（不续建） |
| A6 | `ChannelGateway` | `gateway/ChannelGatewayImpl`@Primary | ✅ **真实** | 真实 SendGrid Email + 通知中心 SMS/PUSH adapter；无 token→SMS fallback；未注册渠道委托 `MockChannelGateway` |

> **结论**：链路骨架 + 真实投递（A3/A6）已通；**L4a-薄能跑**。**待补 = A1/A2/A4/A5**（都在 `collection-channel/src/main/java/com/collection/channel/strategy/`）。

---

## 2. L4a-薄（现在可验） vs L4a-全（待你解锁）

| 维度 | L4a-薄（A3/A6 真实，A1/A2/A4/A5 Mock） | L4a-全（A1–A6 全真实） | 解锁所需 SPI |
|---|---|---|---|
| 真实投递（SMS/PUSH/EMAIL 落终端） | ✅ 可验 | ✅ | A3/A6（已就绪） |
| `providerMsgId` / `result` / `scriptSlot` | ✅ 可验 | ✅ | A3/A6（已就绪） |
| 无 token→SMS fallback | ✅ 可验（L4a-2） | ✅ | A3/A6（已就绪） |
| 还款/升档/终止 取消语义 | ✅ 可验（L4a-3/4/5，引擎真实） | ✅ | 引擎（已就绪） |
| **真实计划结构**（步序/模板/语气/观察期） | ❌ 仅 mock 编排 | ✅ | **A1 `DefaultPlanFactory`** |
| **合规拦截**（频率/时段/放弃率/空地址 rule 真值，block→SKIPPED） | ❌ 恒放行 | ✅ | **A2 `DefaultExecutionGuard`** |
| **真实推进/升档决策**（按响应/模板顺序） | ❌ 末步即 COMPLETED | ✅ | **A4 `DefaultAdvancementPolicy`** |
| **穷尽续建/升档**（REBUILD/ESCALATE） | ❌ 恒 COMPLETE | ✅ | **A5 `DefaultExhaustionPolicy`** |

---

## 3. 你（编排同事）需要补全的部分

> 统一做法：新增 `Default*` 实现，用 `@Primary` 接管（参考 A6 `ChannelGatewayImpl` 已有的 `@Primary @Component` 范式），保留 `Mock*` 作为回退/测试桩。**改这些 SPI 涉及执行运行时契约，需回看 `ic-v1-channel-contract` 与 `引擎渠道执行契约对齐_待编排确认.md`。**

### A1 → `DefaultPlanFactory`（最高优先，解锁最多用例）
- **现状**：`MockPlanFactory` 固定三种编排（默认 `PUSH→EMAIL`、`single-step`、`legacy-three-step`），模板 ID 硬编码（101/102/201…）。
- **需补**：按 **stage / DPD / 渠道签约** 构造真实计划结构——步序、`channelType`、`delayMinutes`、`observationMinutes`、`templateId`、语气(scriptSlot 由 A3 推导)。返回 `null`=不建计划（CEASED/D+91 拒建逻辑 `shouldRejectPlan` 已在 mock，迁移到 Default 时保留）。
- **解锁**：L4a-1（真实三渠道步序）、L4a-4（真实升档计划）、L4a-6（真实观察期时长）、L4a-8（stage×模板）。
- **验收/契约**：计划结构契约 `ContactPlan.steps`；渠道指南 `TC-PLAN-STRUCT-S1`/`S0`/`TONE-02`/`COMMON`。

### A2 → `DefaultExecutionGuard`（合规）
- **现状**：`MockExecutionGuard` 恒 `GuardVerdict.allow()`。
- **需补**：基于 Redis 合规计数器做频率/时段/放弃率校验（硬超时 20ms，单次 Redis 交互）；空地址 → `block`（方案A：引擎统一 `markSkipped`）。`blockedRuleType` 取值（FREQUENCY_LIMIT/TIME_WINDOW/CONNECT_AND_STOP/ABANDONMENT_RATE/NO_PHONE/NO_TOKEN/NO_EMAIL）。
- **解锁**：合规拦截 L4a 用例（block→SKIPPED）、`TC-GUARD-*`、`TC-EMAIL-02`/`TC-PUSH-02`（差集 D5/D6）。
- **验收/契约**：`GuardVerdict` 语义 `block→SKIPPED`（C3/TC-EMAIL-02）；引擎只读 `allowed` 布尔，rule 真值归你。

### A4 → `DefaultAdvancementPolicy`（推进/升档）
- **现状**：`MockAdvancementPolicy` 末步→`PLAN_COMPLETED`，否则 `ADVANCE_NEXT`（不看用户响应）。
- **需补**：按模板顺序 + 用户响应（`StepResult.contactResult`）做 `ADVANCE_NEXT / PLAN_COMPLETED / PLAN_EXHAUSTED` 决策。
- **解锁**：L4a-4 真实升档、推进决策真值。
- **验收/契约**：三值枚举 `AdvancementDecision`；引擎只读 `success`，决策据 `contactResult`。

### A5 → `DefaultExhaustionPolicy`（穷尽）
- **现状**：`MockExhaustionPolicy` 恒 `ExhaustionResult.complete()`。
- **需补**：按续建次数上限（`engine.plan.max_rebuild_count`）+ 阶段规则判 `REBUILD / ESCALATE / COMPLETE`。
- **解锁**：续建/升档用例（M12/M13）转「全」。
- **验收/契约**：`ExhaustionResult` 字段约束（REBUILD/ESCALATE/COMPLETE）。

---

## 4. 前置条件 checklist（双方）

**主架构侧（我负责，L4a 入口基础设施）**
- [ ] `MockTriggerController`（base `/mock`：`/ingest /repayment /stage-changed /case-ceased /ptp-expired`）+ 内存事件总线驱动（**当前代码库尚无此 Controller，待建**）。
- [ ] `*CaseRegistry` 合成案件 + 真号/邮箱（SMS 94101–94103、PUSH 94200/94201、EMAIL 92001/93xxx→126、95xxx→Gmail；94100 虚拟不真发）。
- [ ] `PlanQueryController`（或日志 `[execStep]`/`[advance]`/`[callback]`）作为终态断言出口。

**编排同事侧（你负责）**
- [ ] A1/A2/A4/A5 → `Default*` 实现 + `@Primary` 接管（保留 `Mock*` 回退）。
- [ ] 渠道供应商 key：SendGrid / 通知中心 SMS/PUSH（**优先 `testSend` / sync 测试模式**，正式发送前确认额度与签名）。
- [ ] 真实模板映射表（`templateId` ↔ scriptSlot ↔ stage）与主引擎对齐同一张表。

**共同**
- [ ] App 起 + Nacos UP；`/actuator/beans` 确认 A1–A6 生效 bean 与预期一致。
- [ ] 相邻触发 **限频 ≥1s**，规避供应商限频/去重误判。

---

## 5. ⚠ 需先对齐的不一致（请确认）

1. **`legacy-three-step` 实际是 `SMS→PUSH→SMS`，不是 `SMS→PUSH→EMAIL`**
   - 代码事实：`MockPlanFactory#buildLegacyThreeStep()` = SMS(101)→PUSH(102)→SMS(103)（注释亦写 `SMS→PUSH→SMS（TC-REG-01）`）。
   - 影响：**L4a-1「真实三渠道（SMS+PUSH+EMAIL）顺序完成」当前没有任何 mock 编排能产出**（默认 `PUSH→EMAIL`、legacy `SMS→PUSH→SMS`）。
   - 处理建议（二选一，请你定）：
     - (a) **L4a-薄 临时方案**：用 `single-step` 分渠道各跑一遍（SMS/PUSH/EMAIL 各一），验证单渠道真投；三渠道顺序留待 A1。
     - (b) `DefaultPlanFactory` 提供真正含 EMAIL 第三步的三渠道计划（推荐，直接满足 L4a-1-全）。
   - 我会据你的选择回填测试文档 §L4a-1 的口径（目前 §L4a-1 写的是 `legacy-three-step → SMS→PUSH→EMAIL`，与代码不符，**待校正**）。

2. **CaseRegistry 三渠道合成 case**：L4a-1 需要一个 caseId 的画像同时带 SMS 号 + JPush token + Email，A3 才能逐步取址。registry 当前是「每 caseId 主配一个渠道」。需要我新增一条「三渠道合成 case」，请确认地址用 SMS=`639451374358` / JPush=`1a0018970bf0c19de04` / EMAIL=`wzynju@126.com`。

3. **Gmail DMARC 风险**：`95xxx → plemonsjayson723@gmail.com` 可能被拒/进垃圾箱。L4a-8 真投以 **126 优先**，Gmail 仅作对照、失败不阻断。

---

## 6. 契约引用（不复制，按需 @）

- 执行运行时契约（StepResult/StepCommand/观察期/空地址方案A）：`docs/contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约对齐_待编排确认.md`
- 快照字段 / 取号口径：`do../contracts/README_ContextSnapshot契约对齐.md` + `ContextSnapshot.sample.json`
- SPI 实现约束 + 生命周期 E1–E8：`docs/contracts/README_编排同事对齐清单.md`
- 渠道功能测试 `TC-*`、模板/合规/计划结构：`docs/channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md`
- L4a 用例清单（8 条）：`docs/testing/MOCASA催收系统升级_Phase1_测试文档.md` §L4a

---

## 7. 动作项（建议顺序）

| # | 动作 | 负责人 | 阻塞解锁 |
|---|---|---|---|
| 1 | 回我「§5 第 1 条」的方案选择（a/b） | 编排同事 | L4a-1 口径定稿 |
| 2 | `DefaultPlanFactory`（A1）+ `@Primary` | 编排同事 | L4a-1/4/6/8 转全 |
| 3 | `DefaultExecutionGuard`（A2） | 编排同事 | 合规拦截用例（D5/D6） |
| 4 | `DefaultAdvancementPolicy`/`DefaultExhaustionPolicy`（A4/A5） | 编排同事 | 升档/续建（M12/M13） |
| 5 | `MockTriggerController` + `*CaseRegistry` + 三渠道合成 case | 主架构 | L4a 入口可跑 |
| 6 | 联调跑 L4a-1…8，按 §L4a.3 三段证据断言 | 双方 | L4a 门禁通过 → 进 L4b |

---

## 8. 临时实现说明（主架构代写，2026-06-25）

> 编排同事暂不参与，为推进 L4a-全测试，主架构代写以下简化版 `@Primary` 实现。
> **非生产目标**，编排同事回来后需 review 并替换为生产实现。
> 全量 99 测试通过（engine 72 + channel 27），不破坏现有链路。

### 8.1 新增/修改文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `collection-channel/.../strategy/DefaultPlanFactory.java` | **新增** | A1 @Primary，配置驱动 |
| `collection-channel/.../strategy/ConfigurableExecutionGuard.java` | **新增** | A2 @Primary，时段+空地址+内存频率 |
| `collection-channel/.../strategy/DefaultAdvancementPolicy.java` | **新增** | A4 @Primary，步序+成功/失败 |
| `collection-channel/.../strategy/DefaultExhaustionPolicy.java` | **新增** | A5 @Primary，内存续建计数+升档 |
| `collection-channel/.../config/ChannelProperties.java` | **修改** | 新增 `planTemplates` + `PlanTemplate` / `PlanStepDef` 内部类 |

### 8.2 各 SPI 简化策略 vs 生产差距

| SPI | 临时类 | 简化策略 | 与生产目标的差距 | 接管优先级 |
|---|---|---|---|---|
| A1 | `DefaultPlanFactory` | YAML `channel.plan-templates.{STAGE}` 配置驱动，不读 DB 模板表；保留 `single-step`/`legacy-three-step` 调试开关 | 生产需接 `t_contact_plan_template`（stage×product 匹配）+ 动态模板管理 | **P0** |
| A2 | `ConfigurableExecutionGuard` | (1) 时段（PHT 21:00–08:00）(2) 空地址（NO_PHONE/NO_TOKEN/NO_EMAIL）(3) 内存 `ConcurrentHashMap` 按 userId:channel:date 频率计数 | 生产需 Redis Lua 原子计数器 + 放弃率/CONNECT_AND_STOP 规则 | **P1** |
| A4 | `DefaultAdvancementPolicy` | 非末步→ADVANCE_NEXT；末步+成功→COMPLETED；末步+失败→EXHAUSTED | 生产需按 `contactResult` 细分决策（ANSWERED/NO_ANSWER/BUSY 等） | **P2** |
| A5 | `DefaultExhaustionPolicy` | 内存 `ConcurrentHashMap<caseId:stage, count>` 追踪续建次数（重启清零）；< max → REBUILD，超限 S1→S2→S3→S4 升档，S4 → COMPLETE | 生产需持久化续建计数（DB 或 Redis）+ 完整规则引擎 | **P2** |

### 8.3 配置示例（YAML，加入 application.yml 或 Nacos）

```yaml
channel:
  plan-templates:
    S0:
      steps:
        - { channel: SMS, delayMin: 0, observeMin: 0, templateId: 101 }
    S1:
      steps:
        - { channel: SMS, delayMin: 0, observeMin: 0, templateId: 101 }
        - { channel: PUSH, delayMin: 1, observeMin: 0, templateId: 102 }
        - { channel: EMAIL, delayMin: 2, observeMin: 0, templateId: 201 }
    S2:
      steps:
        - { channel: SMS, delayMin: 0, observeMin: 0, templateId: 101 }
        - { channel: PUSH, delayMin: 1, observeMin: 0, templateId: 102 }
        - { channel: SMS, delayMin: 2, observeMin: 0, templateId: 103 }
        - { channel: EMAIL, delayMin: 3, observeMin: 0, templateId: 202 }
    S3:
      steps:
        - { channel: SMS, delayMin: 0, observeMin: 0, templateId: 101 }
        - { channel: PUSH, delayMin: 1, observeMin: 0, templateId: 102 }
        - { channel: EMAIL, delayMin: 3, observeMin: 0, templateId: 203 }
    S4:
      steps:
        - { channel: SMS, delayMin: 0, observeMin: 0, templateId: 101 }
        - { channel: EMAIL, delayMin: 2, observeMin: 0, templateId: 204 }
  compliance:
    daily-limit:
      SMS: 3
      PUSH: 2
      EMAIL: 2
    quiet-hours-start: "21:00"
    quiet-hours-end: "08:00"
    timezone: Asia/Manila
```

### 8.4 切换/回退方式

- **回退到 Mock**：删除四个 `Default*`/`Configurable*` 类上的 `@Primary` 注解（或删除类文件），`MockPlanFactory` / `MockExecutionGuard` / `MockAdvancementPolicy` / `MockExhaustionPolicy` 自动接管。
- **编排同事接管**：新建 `Production*` 类加 `@Primary`（或直接修改 `Default*` 类内容），主架构临时实现即失效。
- **配置切换**：未配 `channel.plan-templates` 时自动 fallback 到 PUSH→EMAIL（与原 Mock 默认行为一致）。

### 8.5 已知限制（L4a-全 测试时注意）

1. **A2 频率计数器重启清零**：每次重启后计数从 0 开始，不影响 L4a 测试（单次启动内有效）。
2. **A5 续建计数器同上**：重启后无法延续之前的续建次数。
3. **A2 无放弃率/CONNECT_AND_STOP**：Phase 1 恒放行这两项规则，不影响 L4a 验收。
4. **A1 未接模板表**：计划结构来自 YAML 静态配置，非动态；L4a 测试前需确认 YAML 模板已配。
5. **`legacy-three-step` 步序已对齐文档**：SMS→PUSH→EMAIL（2026-06-25 修正，原 Mock 为 SMS→PUSH→SMS）。

### 8.6 L4a 官方用例补齐（2026-06-25）

| 组件 | 说明 |
|---|---|
| `L4aCaseRegistry` | case `94999` 三渠道 / `94801` Guard / `94804` REBUILD |
| `channel.l4a.*` | `application-local.yml` 与 `ChannelProperties.L4a` 对齐 |
| `GET /plans/by-case/{id}/history` | 断言 `cancelReason`（REPAID/STAGE_UPGRADE/CEASED） |
| `POST /mock/ingest?legacyThreeStep=true` | L4a-1 单次 legacy 模式，不污染全局配置 |
| `scripts/test/l4a-official-test.sh` | §L4a 官方 8 条 + Guard SKIPPED + REBUILD/ESCALATE |
| `scripts/test/restart-and-l4a.sh` | **一键**：停服 → 编译 → 后台起 → 健康检查 → 上脚本 |
| `scripts/dev/start-local.sh` / `stop-local.sh` | macOS/Linux 启停（同 `start-local.ps1`） |

跑法：

```bash
./scripts/test/restart-and-l4a.sh              # 全量（含 mvn）
./scripts/test/restart-and-l4a.sh --no-build   # 仅重启 + 测试
./scripts/test/l4a-official-test.sh            # App 已运行时
```

08:00–21:00 PHT；L4a-6 观察期默认额外等待 ~90s。日志：`logs/run/l4a.last.log`、`logs/run/admin.log`。
