# MOCASA Phase 1 — collection-channel 开发进度

> **最后更新**: 2026-06-05  
> **SSOT 任务分解**: [开发执行指南 §7 Checklist](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md#7-推荐开发时间线checklist)  
> **测试手册**: [策略迭代与测试操作手册](./MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md) · [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)

> **最后更新**: 2026-06-05（现网对照 · 决策：Voice=TTS only · 策略后台=Phase 2 全量）

---

## 0. 现网对照与已确认决策

### 0.1 现网 vs 新系统

| 能力 | 现网 `collection_rebuild` | Phase 1 新系统 | 动作 |
|------|---------------------------|----------------|------|
| App Push (FCM) | ❌ 无 | `FcmPushAdapter` | 向 App 团队要 FCM；**无可抄现网代码** |
| LTH SMS | `LthFunction.sendSms`（version/account/password/…） | `LthSmsAdapter` 简化 JSON | **P0 对齐现网协议** |
| LTH TTS | `voiceNotifactionTTSAndCustomize` | 待开发 | 复用 `postLth` digest 鉴权 |
| LTH AI 对话 | 现网走 Wiz/Saiduo | — | **Phase 1 不做** |
| 策略模板 DB | `t_system_property` 等 | Java + Nacos | Phase 2 全量后台 + DDL |

### 0.2 团队决策（2026-06-05）

| 议题 | 决策 |
|------|------|
| **AI_CALL** | Phase 1 **仅 LTH TTS**；`AI_CALL` 继续 Mock / Phase 2 |
| **策略前台** | Phase 2 **完整策略后台**（模板、合规、渠道开关）；Phase 1 用 Nacos + SQL + REST |
| **策略 DDL** | Phase 1 **不建** `t_contact_plan_template`；跑通后再 DDL + 后台 |

---

```mermaid
flowchart LR
    subgraph done [已完成]
        E[核心引擎]
        C0[common 契约]
        EX[执行子层 Adapter 3/4]
        GW[ChannelGatewayImpl]
    end
    subgraph partial [部分完成 Mock 增强]
        PF[PlanFactory]
        SR[StepResolver]
        G[ExecutionGuard]
        AP[AdvancementPolicy]
    end
    subgraph todo [未开始]
        DF[DefaultPlanFactory]
        DG[ComplianceExecutionGuard]
        LV[LthVoiceAdapter]
        WH[Webhook 完整]
    end
    done --> partial
    partial --> todo
```

| 分层 | 完成度 | 说明 |
|------|--------|------|
| **核心引擎**（collection-engine） | **~95%** | 事件消费、状态机、七步管线、CASE_CEASED 已联调 |
| **执行子层 / 哑管道** | **~75%** | Gateway + SMS/Push/Email Adapter；Voice/Webhook 未做 |
| **策略子层**（5 SPI） | **~15%** | 仍为 MockPlanFactory 等；无真实 S0–S4 编排 |
| **数据接入**（ingestion） | **~20%** | Mock 进案；日切/CASE_CEASED 生产 Job 占位 |
| **策略 DB 后台** | **0%** | `t_contact_plan_template` 等未 DDL |

---

## 2. Checklist 逐项状态

| # | 任务 | 状态 | 备注 / 验证 |
|---|------|------|-------------|
| 0 | .env + Nacos 连通；Mock 基线 | ✅ 完成 | TC-REG-01；verify-env.ps1 |
| 1 | 阶段 0：common 契约 | ✅ 完成 | CASE_CEASED、StepCommand 常量、repaymentUrl |
| 2 | ChannelProperties + Nacos `channel.*` | ✅ 完成 | `@RefreshScope`；`.env` SendGrid 变量 |
| 3 | MockStepResolver 增强 | ✅ 完成 | 多渠道地址、sms_body、S0 D0 scriptSlot |
| 4 | LthSmsAdapter + ChannelGatewayImpl | ✅ 完成 | WireMock 单测；未配密钥时回退 Mock |
| 5 | FcmPushAdapter + SMS fallback | ✅ 代码完成 | **待真实 FCM 密钥联调** TC-PUSH-01/02 |
| 6 | SendGridEmailAdapter | ✅ **已联调** | TC-EMAIL-D0-01：92002 → wzynju@126.com DELIVERED |
| 7 | LthVoiceAdapter + `/webhook/lth/voice` | ❌ 未开始 | AI_CALL/TTS 仍走 MockChannelGateway |
| 8 | DefaultPlanFactory（8 套骨架） | ❌ 未开始 | 当前 Mock：PUSH→EMAIL 或 legacy 三步步 |
| 9 | DefaultStepResolver | ❌ 未开始 | |
| 10 | ComplianceExecutionGuard | ❌ 未开始 | MockExecutionGuard 恒放行；需 Redis |
| 11 | DefaultAdvancement/ExhaustionPolicy | ❌ 未开始 | Wave-2 取消、disposition 映射未做 |
| 12 | SendGrid Webhook timeline 升级 | ❌ 未开始 | |
| 13 | 删除全部 Mock 类 | ❌ 未开始 | 7 个 Mock 仍在 |
| 14 | E2E CASE_CEASED | 🟡 部分 | 引擎+MockPlanFactory 守卫已有；ingestion 日切未做 |

**图例**：✅ 完成 · 🟡 进行中/部分 · ❌ 未开始

---

## 3. 模块文件清单

### 3.1 执行子层（哑管道）— 真实实现

| 类 | 状态 |
|----|------|
| `ChannelProperties` | ✅ |
| `ChannelGatewayImpl` | ✅ @Primary |
| `LthSmsAdapter` | ✅ |
| `FcmPushAdapter` | ✅ |
| `SendGridEmailAdapter` | ✅ |
| `LthVoiceAdapter` | ❌ |
| `MockChannelGateway` | 🟡 兜底（未配密钥 / 未实现渠道） |

### 3.2 策略子层 — 仍为 Mock

| SPI | 当前类 | 目标类 | 状态 |
|-----|--------|--------|------|
| PlanFactory | MockPlanFactory | DefaultPlanFactory | 🟡 增强 Mock |
| StepResolver | MockStepResolver | DefaultStepResolver | 🟡 增强 Mock |
| ExecutionGuard | MockExecutionGuard | ComplianceExecutionGuard | ❌ |
| AdvancementPolicy | MockAdvancementPolicy | DefaultAdvancementPolicy | ❌ |
| ExhaustionPolicy | MockExhaustionPolicy | DefaultExhaustionPolicy | ❌ |

### 3.3 单测

| 套件 | 状态 |
|------|------|
| LthSmsAdapterTest（WireMock） | ✅ 3 cases |
| SendGridEmailAdapterTest | ✅ 3 cases |
| MockPlanFactoryGuardTest | ✅ 4 cases |
| FcmPushAdapterTest | ❌ |
| Gateway 幂等单测 | ❌ |

---

## 4. 已验证的端到端能力

| 能力 | 验证方式 | 日期 |
|------|----------|------|
| Mock 全链路 PLAN_COMPLETED | caseId 91000 | 2026-06-05 |
| SendGrid 真实发信 D0 Email | caseId 92001/92002 → 126 邮箱 | 2026-06-05 |
| PUSH→EMAIL 简单编排 | 91001 timeline 2 条 | 2026-06-05 |
| CASE_CEASED 不建 plan | 90091 | 文档/冒烟 |

---

## 5. 下一步计划（建议顺序，2026-06-05 修订）

| 优先级 | 工作项 | 预估 | 验收 | 阻塞 |
|--------|--------|------|------|------|
| **P0** | **LthSmsAdapter 对齐现网协议** | 0.5d | TC-SMS-01 真实 LTH | — |
| P0 | FCM 密钥联调（App 侧配置） | 1d | TC-PUSH-01/02 | 无现网代码，需 App 团队 |
| P0 | **LthVoiceAdapter（仅 TTS）** + `/webhook/lth/voice` | 2d | TC-VOICE-TTS-01 | 对齐现网 TTS action |
| P0 | **AI_CALL** | — | 保持 Mock | Phase 2 / LTH AI 文档到位后 |
| P1 | `DefaultPlanFactory` S0–S1 最小日块 | 3–5d | TC-PLAN-S0 | — |
| P1 | `ComplianceExecutionGuard` + Redis | 2d | TC-GUARD-* | parent POM 加 redis |
| P1 | `DefaultStepResolver` 附录 A | 2d | 变量完整 | — |
| P2 | S2–S4 + AdvancementPolicy + Webhook | 5d+ | 冒烟矩阵 | Voice disposition |
| P3 | 删 Mock | 1d | Checklist #13 | — |
| P3 | **策略 DDL + 全量策略后台** | TBD | 模板/合规/渠道 CRUD | 产品 Phase 2 |

---

## 6. 变更日志

| 日期 | 进展 |
|------|------|
| 2026-06-05 | 初版进度文档；阶段 0–2 子集完成；TC-EMAIL-D0-01 联调通过（92002/wzynju@126.com） |
| 2026-06-05 | 现网对照：无 FCM；LTH SMS 协议需对齐；Voice AI 供应商待决策 |
| 2026-06-05 | Email 文案 v3（催收心理学：词汇分阶段、S4 黑白律师函）；移除虚构 Hardship Program |

---

## 7. 相关文档索引

| 文档 | 用途 |
|------|------|
| [开发执行指南](./MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md) | 怎么写代码 |
| [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | TC 与 curl |
| [策略迭代与测试操作手册](./MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md) | 运营/策略怎么测、怎么改 |
| [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) | 业务规则 SSOT |
| [渠道模板清单与配置](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) | 全渠道 scriptSlot SSOT |
| [总规格 附录 A](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#附录-ascriptslot--供应商-template_id-映射表) | scriptSlot 映射 |
