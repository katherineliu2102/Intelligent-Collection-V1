# MOCASA Phase 1 — 策略迭代与测试操作手册

> **版本**: v1.1  
> **日期**: 2026-06-05  
> **范围**: 仅覆盖菲律宾市场  
> **模块**: `collection-channel`  
> **关联文档**: [渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围)、[渠道模板清单](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md)、[功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)

---

## 目录

- [1. Phase 1 策略配置在哪里](#1-phase-1-策略配置在哪里)
- [2. 数据库表职责](#2-数据库表职责)
- [3. 供应商模板与密钥](#3-供应商模板与密钥)
- [4. 分渠道测试怎么跑](#4-分渠道测试怎么跑)
- [5. 策略迭代工作流](#5-策略迭代工作流)
- [6. 哑管道与策略子层分工](#6-哑管道与策略子层分工)
- [7. 文档维护](#7-文档维护)

---

## 1. Phase 1 策略配置在哪里

| 配置类型 | Phase 1 实际来源 | 数据库表（Phase 1） |
|----------|------------------|---------------------|
| **计划骨架**（Stage×Tone、日块、槽位） | Java 常量 / Nacos `channel.plan-templates`（待 `DefaultPlanFactory`） | ❌ `t_contact_plan_template` **未建** |
| **话术 scriptSlot → 模板** | [渠道模板清单 SSOT](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md)；Email 见 `email-templates/` | ❌ 无独立话术表 |
| **合规限额/触达窗** | Nacos `channel.compliance.*`（待 Guard） | ❌ `t_compliance_rule` **未建** |
| **渠道密钥** | Nacos `channel.*`（`intelligent-collection-local.yml`） | ❌ `t_channel_config` **未建** |
| **运行态计划/步骤** | MySQL | ✅ 见 [§2](#2-数据库表职责) |

**结论**：Phase 1 策略迭代主路径是 **Git 文档 + Nacos + 代码发布**；DB 仅存运行实例。

---

## 2. 数据库表职责

### 2.1 已存在（运行观测）

| 表 | 用途 | 策略配置？ |
|----|------|-----------|
| `t_contact_plan` | 计划实例 | 否 |
| `t_contact_plan_step` | 步骤实例 | 否 |
| `t_contact_timeline` | 触达时间线 | 否（审计） |

### 2.2 Phase 2 规划（未 DDL）

| 表 | 规划用途 |
|----|----------|
| `t_contact_plan_template` | 计划模板 JSON |
| `t_compliance_rule` / `t_channel_config` | 合规与渠道后台 |

### 2.3 运行态 SQL 速查

```sql
SELECT p.id, p.case_id, p.stage, p.status, p.total_steps
FROM t_contact_plan p WHERE p.case_id = ? ORDER BY p.id DESC LIMIT 1;

SELECT step_order, channel_type, template_id, trigger_time, status, result
FROM t_contact_plan_step WHERE plan_id = ? ORDER BY step_order;

SELECT channel, result, provider_msg_id, created_at
FROM t_contact_timeline WHERE user_id = ? ORDER BY id DESC LIMIT 10;
```

---

## 3. 供应商模板与密钥

### 3.1 Nacos 渠道密钥（Data ID: `intelligent-collection-local.yml`）

| Nacos 路径 | 用途 | 测试 TC |
|------------|------|---------|
| `channel.notification.app-key` 等 | 通知中心 SMS + Push | TC-SMS-01、TC-PUSH-01/02 |
| `channel.sendgrid.api-key` + `templates` 映射 | SendGrid Email | TC-EMAIL-01、TC-EMAIL-D0-01 |
| `channel.lth.voice.url` | LTH 外呼 | TC-VOICE-01 |
| `channel.callback.base-url` | Voice 回调 | TC-VOICE-03 |
| `channel.compliance.*` | 合规 Guard | TC-GUARD-* |
| `channel.debug.single-step` | 单渠道冒烟 | 各单渠道 TC |

> 密钥不进 `.env`；发布见 `scripts/dev/publish-channel-secrets-to-nacos.ps1`。Email **不要**每个模板一条配置项；`d-xxx` 写在 `channel.sendgrid.templates`（见 [渠道模板清单 §3.1](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#31-配置映射)）。

### 3.2 scriptSlot 与素材位置

**全表 SSOT**：[渠道模板清单与配置 §2](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#2-全渠道-scriptslot-总表)（SMS / Push / Email / Voice）。

| 渠道 | 素材位置 | template_id |
|------|----------|-------------|
| **EMAIL** | `docs/email-templates/milestones/` + SendGrid 控制台 | 每 milestone 一个 `d-xxx` |
| **EMAIL Test Data** | `docs/email-templates/email-templates-test/` | — |
| **SMS** | Resolver → `sms_body` | 无 LTH template_id |
| **PUSH** | 通知中心 `title`/`body`/`data`（JPush） | 无独立 template_id |
| **Voice** | LTH 脚本参数 | 待 LTH 确认 |

**Phase 1 Email 状态摘要**

| scriptSlot | SendGrid | HTML |
|------------|----------|------|
| `S0_DUE_TODAY_EMAIL` | ✅ `d-39cb3cd…` 已联调 | ✅ |
| S1~S4 里程碑 ×12 | _待运营建站_ | ✅ |
| `S*_EMAIL_CONDITIONAL` ×4 | Phase 2 | ✅ |

---

## 4. 分渠道测试怎么跑

> 详见 [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)。

### 4.1 单渠道冒烟

```yaml
channel:
  debug:
    single-step: EMAIL   # SMS / PUSH / AI_CALL / TTS
collection:
  scan:
    interval-ms: 3000
```

| 渠道 | single-step | 推荐 userId | 备注 |
|------|-------------|-------------|------|
| SMS | `SMS` | 90001 | `channel.notification.*` |
| PUSH | `PUSH` | 90002 | 有 jpushToken |
| EMAIL | `EMAIL` | 90004 | SendGrid |
| **D0 Email** | `EMAIL` | **92002** | D0 模板 + **`wzynju@126.com`**（优先 126，勿默认 Gmail） |

### 4.2 多通道编排（当前 Mock）

默认 **PUSH → EMAIL**（`MockPlanFactory`）；三步步回归：`legacy-three-step: true`。

### 4.3 引擎 / 生命周期

| 场景 | 操作 |
|------|------|
| 还款 | `POST /mock/repayment?userId=&caseId=` |
| D+91 停催（内部） | `POST /mock/case-ceased?caseId=&maxDpd=91` |
| 阶段变更 | `POST /mock/stage-changed?caseId=&stage=S2` |

> **注意**：D+91「停催」是**内部**生命周期；对外 Email **不得**写停催或委外，D+75 用 `assignment_date` 包装为 **final delinquency review**（见 [email-templates §2](./email-templates/README.md#2-催收心理学矩阵)）。

### 4.4 重复测试

Email 联调递增 caseId（92002、92003…），避免 plan 冲突。

---

## 5. 策略迭代工作流

### 5.1 改「发什么渠道、什么顺序、什么时间」

| 目标 | Phase 1 | Phase 2+ |
|------|---------|----------|
| 调整日块/槽位 | `DefaultPlanFactory` 或 Nacos `channel.plan-templates` | `t_contact_plan_template` |
| S0 D0 14:00 Email | 同上（当前 Mock 为 PUSH→EMAIL 占位） | 后台 |

**发布**：改代码/Nacos → `mvn package` → 重启 → SQL + timeline 验证。

### 5.2 改「话术 / 邮件正文 / 深链」

#### Email（SendGrid）

| 步骤 | 操作 | 文档 |
|------|------|------|
| 1 | 改 HTML 源码 | `email-templates/milestones/` 或 `conditionals/` |
| 2 | 同步 Subject / Preheader | `email-templates/subjects.md` |
| 3 | SendGrid 控制台粘贴 HTML + Settings | [建站 SOP](./email-templates/README.md#6-sendgrid-建站-sop) |
| 4 | Test Data 预览 | `email-templates/email-templates-test/` + `test-data-index.json` |
| 5 | 填 Nacos `channel.sendgrid.templates.{scriptSlot}` | [§3.1](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#31-配置映射) |
| 6 | 联调 | TC-EMAIL-D0-01；92002 → 126 邮箱 |

**叙事合规**：改文案前阅读 [email-templates §2](./email-templates/README.md#2-催收心理学矩阵)——**无委外**；D+75 用 final delinquency review；禁 `collection will cease` / third-party。

#### SMS / Push

| 渠道 | 改哪里 |
|------|--------|
| SMS | Resolver 文案 + LTH URL；无 SendGrid 式 template_id |
| PUSH | FCM payload + App 深链约定 |

### 5.3 改「合规频率 / 触达窗」

Nacos `channel.compliance.*`（需 `ComplianceExecutionGuard` 上线后生效）。

### 5.4 改「Offer/减免」

Phase 1 静态引导文案；F10 动态注入 Phase 2。

---

## 6. 哑管道与策略子层分工

```
ingest → PlanFactory（策略：几步、什么渠道）
       → TriggerScanner → StepResolver（策略：渲染命令）
       → ExecutionGuard（策略：能不能发）
       → ChannelGateway（哑管道）
       → Adapter（供应商 API）
```

进度见 [开发进度](./MOCASA催收系统升级_Phase1_collection-channel开发进度.md)。

---

## 7. 文档维护

| 变更 | 更新 |
|------|------|
| 新增/改 Email HTML | `email-templates/` + `subjects.md` + [渠道模板清单 §2](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#2-全渠道-scriptslot-总表) |
| 新增 SendGrid `d-xxx` | Nacos 映射 + `subjects.md` SendGrid ID 列 |
| 新增 Test Data JSON | `email-templates/email-templates-test/` + `test-data-index.json` |
| 新增测试 caseId | 本手册 §4 + [功能测试指南 §1.3](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) |
| DefaultPlanFactory 上线 | §5.1 Phase 2 列、TC-PLAN-* |
| 策略 DDL 上线 | 重写 §2，增加后台章节 |

**文档导航**：[README_渠道文档索引](./README_渠道文档索引.md)
