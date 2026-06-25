# L4a 对齐纪要（channel → engine 确认）

> **发起**：编排同事（collection-channel）　**接收**：主架构（engine）
> **日期**：2026-06-22
> **关联**：[`L4a 全量前置补全清单`](./MOCASA催收系统升级_Phase1_L4a全量前置_编排同事补全清单.md)、[`测试主文档 §L4a`](./MOCASA催收系统升级_Phase1_测试文档.md)
> **一句话**：针对你发来的补全清单 + 重写后的测试文档，channel 侧已核代码、定决策、落薄层桩并校正文档。**本纪要请你确认 §2（门禁前必须确认）与 §3（转全前契约）**；确认无误后双方即可开跑 L4a-薄 门禁。§5 仅列仍待 engine 拍板的开放项。

---

## 1. 本轮已对齐 / 已落地（channel 侧已做）

### 1.1 决策已定
| # | 议题 | 结论 |
|---|---|---|
| A | 补全清单 §5.1（L4a-1 三渠道怎么解） | **采用方案 (c)**：`MockPlanFactory` 加 `channel.debug.three-channel-step`（SMS→PUSH→EMAIL）薄层桩；不动生产 `DefaultPlanFactory`，真实结构留待 A1 转「全」 |
| B | `legacy-three-step` 真实步序 | 代码实锤 = `SMS→PUSH→SMS`（**非** SMS→PUSH→EMAIL）；测试文档原口径已校正 |
| C | L4a-6 观察期薄层怎么解 | 加 `channel.debug.observation-minutes` 桩，配 `single-step=SMS` 即可验「STEP_WAITING→结转」 |
| D | 三渠道合成 case 地址 | caseId **94001** = SMS `639451374358` + JPush `1a0018970bf0c19de04` + EMAIL `zoewang532@gmail.com`（Gmail，已验证可达） |
| E | curl 契约 | `MockTriggerController` 用 `@RequestParam`（query/form），**非 JSON body**；测试文档 curl 全量改为 query string |
| F | SendGrid 动态模板 | **已配齐**（channel）；L4a EMAIL 腿按正常断言，不作为开放议题 |
| G | L4a-薄 门禁 pass 线 | **L4a-1…8 共 8 条全绿**；每条按 §L4a.3 三段证据（终端收到 + providerMsgId/result/scriptSlot + plan/step 终态）全部满足，**无例外/软失败** |

### 1.2 代码改动
| 文件 | 模块/归属 | 改动 |
|---|---|---|
| `ChannelProperties.java` | channel ✅ 我方 | `Debug` 加 `threeChannelStep` / `observationMinutes`（默认 false/0，不改原行为） |
| `MockPlanFactory.java` | channel ✅ 我方 | 新增 `buildThreeChannelFlow()`(SMS→PUSH→EMAIL) + 观察期注入；`single-step` 也支持 obs |
| `MockProfileService.java` | **collection-service（你方）** | 加合成 case 94001（三渠道真址）。**channel 代落，请你 review 并接管** |

> 已过 `mvn -o -pl collection-channel,collection-service -am test-compile`（exit 0）+ 静态检查无错。

### 1.3 文档改动
- **测试文档**：§L4a.0 装配表（legacy 口径 + 新桩开关）、§L4a.1 计划模式、§L4a.2 加 94001、L4a-1/L4a-6 用例行、§L4a.6 整段 curl（query string + `/repayment` 补 userId + `/ptp-expired` 补 ptpId + `PlanQueryController` 真实路径）。
- **补全清单**：§1/§4「待建」→「已存在（含位置）」、§5 三条决策回填、§7 动作表重排。

---

## 2. 待 engine 确认（**门禁前必须**，阻塞 L4a-薄 开跑）

| # | 待确认项 | 为什么需要你 | 期望答复 |
|---|---|---|---|
| Q1 | **合成 case 94001** 写入 `MockProfileService` 你是否接管？（也可你换更规范的 caseId 段位） | 文件在 collection-service（你方域），channel 只是代落 | 接管 / 改 id / 改放置位置 |
| Q2 | **TriggerScanner 在 L4a runtime 是否启用并扫到期步骤？** L4a-6（obs=2min 结转）、delay>0 步骤都依赖扫描器把 `STEP_WAITING`/到期步骤重新驱动 | 扫描器装配/调度归 admin（你方） | 确认启用 + 扫描周期 + 是否需手动触发 |
| Q3 | **断言出口**：以 `PlanQueryController`（`/plans/active|overview|timeline`）+ 日志 `[execStep]/[advance]/[callback]` 为准？字段是否够断言三段证据？ | 出口在 collection-admin（你方） | 确认 / 补字段 |
| Q4 | **发送模式与额度**：SMS 优先 `testSend`、PUSH 优先 `sync`、EMAIL 走 SendGrid（Gmail 已测通）。供应商 key、额度、SMS 签名是否就绪？ | 供应商 key 由 channel 配，但真投额度/签名需运维侧 | 就绪情况 + 是否可真投 |

---

## 3. 待双方对齐的契约（**转全前**，不阻塞薄层门禁）

| # | 契约 | 现状/问题 | 建议 |
|---|---|---|---|
| C1 | **模板映射表 `templateId ↔ scriptSlot ↔ stage`** | mock 里 templateId 硬编码（101/102/103/201/301/302）；真实 `DefaultPlanFactory` 要按 stage 构造步序+templateId，A3 `DefaultStepResolver` 再 deriveSlot。**目前没有一张双方共用的真实表** | 由 channel 出初版表（基于现有 scriptSlot：`S0_DUE_TODAY_EMAIL`/`S1_EMAIL_OVERDUE_NOTICE`…），engine review 后定稿；A1 骨架见 §4 |
| C2 | **A4/A5 的 ESCALATE 到底归谁** | 补全清单 §3 写 A4=`ADVANCE_NEXT/PLAN_COMPLETED/PLAN_EXHAUSTED`、A5=`REBUILD/ESCALATE/COMPLETE`；但测试文档 L4a.4 写「A4…(ADVANCE_NEXT/ESCALATE)」——**ESCALATE 归属不一致** | 拍定：升档(ESCALATE)是 A4 推进决策、还是 A5 穷尽后续建/升档？影响枚举与引擎分支 |
| C3 | **A2 合规计数器读写归属** | A2 `DefaultExecutionGuard` 读 Redis 频率/时段/放弃率计数器做 block；但**谁在发送成功后写/自增这些计数器**？引擎、channel adapter、还是 admin？ | 明确读写两侧 + Redis key 格式 + `blockedRuleType` 枚举落地位置 |
| C4 | **真实观察期取值** | 桩里 obs 由 debug 注入；真实各 stage×channel 的 `observationMinutes` 值是多少？ | 并入 C1 的映射表一起出 |

---

## 4. 附：`DefaultPlanFactory` 骨架（评审件，**未入库**）

> 目的：让 engine 先确认「计划结构契约」（步序/channelType/templateId/delay/obs 由 A1 产出，scriptSlot 由 A3 推导）。`@Primary` 暂不加，真实化前仍由 `MockPlanFactory` 生效。TODO 处依赖 §3-C1 的映射表定稿。

```java
package com.collection.channel.strategy;

// imports 略（同 MockPlanFactory）

/**
 * A1 真实计划工厂（骨架）。按 stage / DPD / 渠道签约构造真实计划结构。
 * 真实化后加 @Primary 接管 MockPlanFactory；返回 null = 不建计划（CEASED / D+91）。
 */
// @Primary  // ← 真实化、且映射表定稿后再开
@Component
public class DefaultPlanFactory implements PlanFactory {

    @Resource
    private PlanTemplateProperties planTemplates; // ← C1：templateId↔scriptSlot↔stage 配置化（新增）

    @Override
    public ContactPlan create(CaseInfo caseInfo, Stage stage, ContextSnapshot snapshot) {
        // 1) 入口守卫：复用 MockPlanFactory#shouldRejectPlan（CEASED / D+91 → null）
        if (MockPlanFactory.shouldRejectPlan(caseInfo, snapshot)) {
            return null;
        }

        // 2) 按 stage（+ DPD + 渠道签约）取该阶段的有序步骤模板
        //    TODO(C1): 从 planTemplates 读取 stage → List<StepTemplate>{channelType, templateId, delayMinutes, observationMinutes}
        List<StepTemplate> templates = planTemplates.stepsFor(stage, caseInfo, snapshot);

        ContactPlan plan = new ContactPlan();
        plan.setCaseId(caseInfo.getCaseId());
        plan.setUserId(caseInfo.getUserId());
        plan.setStage(stage);
        plan.setSteps(toSteps(templates));   // channelType/templateId/delay/obs/stepOrder/PENDING
        return plan;
        // 注：scriptSlot 不在此设置 —— 由 A3 DefaultStepResolver 按 stage/DPD deriveSlot
    }

    // toSteps(...)：把 StepTemplate 映射为 ContactPlanStep（语气/scriptSlot 交给 A3）
}
```

**留给 engine 的确认点**：①「计划只产 templateId、scriptSlot 归 A3」这个职责切分 OK 吗？②升档新计划结构（L4a-4 转全）由谁触发 A1 重建——engine 在 STAGE_CHANGED 调 `create(stage=S2)`，A1 只管结构，对吗？

---

## 5. 仍待 engine 拍板（开放项）

1. **`stage` 入参 vs case 自身 stage**：`/ingest?stage=S2` 与 `MockCaseService` 里 case 自带 stage 不一致时，以哪个为准？L4a-8 跨 stage 用同一 caseId 改 stage，需确认 ingest 的 stage 入参确实覆盖建计划用的 stage。
2. **L4b 切换时机与数据源**：薄层过后接真实数据源（旧库/PubSub），owner、环境、灰度范围尚未排期——不阻塞 L4a-薄，但建议先约时间点。
3. **供应商侧去重（L4a-8 实操注意）**：相邻触发限频≥1s 已约定；L4a-8 多 stage 循环时，若通知中心/SendGrid 有去重窗口，可能导致同 case 快速重发被误判重复——跑用例时注意间隔，必要时 engine 确认供应商去重策略。

---

## 6. 下一步（确认后执行）

| 顺序 | 动作 | owner | 依赖 |
|---|---|---|---|
| 1 | engine 回 §2 Q1–Q4 + §5（3 条开放项） | engine | — |
| 2 | 跑 L4a-薄 1…8（§1.1-G：8 条全绿，§L4a.3 三段证据） | 双方 | §2 全绿 |
| 3 | 出模板映射表初版（C1）+ A4/A5/A2 契约定稿（C2/C3/C4） | channel 起草、engine review | 转全前 |
| 4 | A1→A2→A4/A5 `Default*` 实现 + 逐条薄→全 | channel | C1–C4 定稿 |
| 5 | 进 L4b | 双方 | L4a-全 通过 |
