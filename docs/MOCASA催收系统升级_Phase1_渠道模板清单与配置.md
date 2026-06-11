# MOCASA Phase 1 — 渠道模板清单与配置（SSOT）

> **版本**: v2.1 · **日期**: 2026-06-05  
> **状态**: ✅ Email HTML 17/17；SendGrid S0 已联调；SMS/Push/Voice 文案 ⏳ 待业务填充  
> **定位**: 全渠道 **scriptSlot** 模板唯一清单（SMS / Push / Email / Voice）  
> **适用范围**: Phase 1 collection-channel；Phase 2 条件 Email 与策略后台扩展  
> **上游**: [渠道编排规格 §8.4](./MOCASA催收系统升级_Phase1_渠道编排规格.md#84-各-stage-话术槽拟议) · [collection-channel 总规格 附录 A](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#附录-ascriptslot--供应商-template_id-映射表)  
> **下游**: [策略迭代手册 §5.2](./MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md#52-改话术--邮件正文--深链) · [功能测试指南 TC-EMAIL-D0-01](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)

---

## 目录

- [1. 文档分工与目录](#1-文档分工与目录)
- [2. 全渠道 scriptSlot 总表](#2-全渠道-scriptslot-总表)
  - [2.1 S0](#21-s0d-3--d0)
  - [2.2 S1](#22-s1d1--d3)
  - [2.3 S2](#23-s2d4--d15)
  - [2.4 S3](#24-s3d16--d30)
  - [2.5 S4](#25-s4d31--d90)
- [3. Email（SendGrid）](#3-emailsendgrid)
  - [3.1 配置映射](#31-配置映射)
  - [3.2 变量与叙事](#32-变量与叙事)
  - [3.3 里程碑速查](#33-里程碑速查)
  - [3.4 建站 SOP](#34-建站-sop)
- [4. SMS（LTH）](#4-smslth)
- [5. Push（FCM）](#5-pushfcm)
- [6. Voice / AI_CALL（LTH）](#6-voice--ai_calllth)
- [7. Nacos 配置汇总](#7-nacos-配置汇总)
- [8. 与代码对应](#8-与代码对应)
- [9. 关联文档](#9-关联文档)

---

## 1. 文档分工与目录

| 内容 | 位置 | 维护者 |
|------|------|--------|
| **本文件** | 全渠道 scriptSlot 总表、Nacos 约定、各渠道渲染方式 | 渠道 + 策略 |
| [`email-templates/`](./email-templates/README.md) | Email HTML、版式、**催收叙事原则**、`subjects.md` | 策略 + 运营 |
| [`email-templates/email-templates-test/`](./email-templates/email-templates-test/README.md) | SendGrid Test Data JSON | 运营/QA |
| 各 Adapter 对接说明 | Notification（SMS+Push）/ SendGrid / LTH Voice | 开发 |

```
docs/
├── MOCASA催收系统升级_Phase1_渠道模板清单与配置.md   ← 本文件（全渠道 SSOT）
└── email-templates/
    ├── README.md
    ├── subjects.md
    ├── milestones/              # 13 × HTML
    ├── conditionals/            # 4 × HTML（Phase 2）
    └── email-templates-test/    # Test Data JSON
```

> 原 `MOCASA催收系统升级_Phase1_Email模板清单与SendGrid配置.md` 已合并入本文档（v2.0）。

---

## 2. 全渠道 scriptSlot 总表

> **Phase 1**：Email 里程碑 HTML ✅（文案 v2 催收叙事修订）；SMS/Push/Voice ⏳ 待业务/LTH 填充。  
> **Phase 2**：`*_EMAIL_CONDITIONAL` Plan 启用 + ExecutionGuard。

### 2.1 S0（D-3 ~ D0）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S0_REMINDER` | SMS / Push | LTH / FCM | D-3、D-2 · 08:00 | Resolver → `sms_body` / FCM data | 待填 · 含防诈骗 | ⏳ |
| `S0_REMINDER_URGENT` | SMS / Push | LTH / FCM | D-1 · 08:00 | 同上 | 待填 | ⏳ |
| `S0_DUE_TODAY` | SMS / Push | LTH / FCM | D0 · 08:00 | 同上 | 待填 | ⏳ |
| `S0_DUE_TODAY_EMAIL` | EMAIL | SendGrid | D0 · 14:00 | `d-39cb3cd90ee44451887c5c67bd1ac073` | [HTML](./email-templates/milestones/S0_DUE_TODAY_EMAIL.html) · [Test Data](./email-templates/email-templates-test/test-data.sample.json) | ✅ 已联调 |

### 2.2 S1（D+1 ~ D+3）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S1_SMS_STANDARD` | SMS | LTH | 每日 08:00 | `sms_body` | 待填 | ⏳ |
| `S1_PUSH_STANDARD` | PUSH | FCM | 12:00 | FCM data + 深链 | 待 App | ⏳ |
| `S1_EMAIL_OVERDUE_NOTICE` | EMAIL | SendGrid | D+1 · 14:00 | _待填 d-xxx_ | [HTML](./email-templates/milestones/S1_EMAIL_OVERDUE_NOTICE.html) · [Test Data](./email-templates/email-templates-test/test-data-s1-d1.json) | ✅ HTML |
| `S1_EMAIL_STAGE_WARNING` | EMAIL | SendGrid | D+3 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S1_EMAIL_STAGE_WARNING.html) · [Test Data](./email-templates/email-templates-test/test-data-s1-d3.json) | ✅ HTML |
| `S1_EMAIL_CONDITIONAL` | EMAIL | SendGrid | D+1 · 16:00 | _待填_ | [HTML](./email-templates/conditionals/S1_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S1_VOICE_PRIMARY` / `RETRY` | AI_CALL | LTH | Wave-1/2 | voice 参数 | 待 LTH | Mock |

### 2.3 S2（D+4 ~ D+15）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S2_SMS_STANDARD` / `S2_SMS_FIRM` | SMS | LTH | 08:00 | `sms_body` + offer 占位 | 待填 | ⏳ |
| `S2_PUSH_STANDARD` | PUSH | FCM | 12:00 | data payload | 待填 | ⏳ |
| `S2_EMAIL_ENTRY` | EMAIL | SendGrid | D+4 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S2_EMAIL_ENTRY.html) · [Test Data](./email-templates/email-templates-test/test-data-s2-d4.json) | ✅ HTML |
| `S2_EMAIL_MID` | EMAIL | SendGrid | D+7 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S2_EMAIL_MID.html) · [Test Data](./email-templates/email-templates-test/test-data-s2-d7.json) | ✅ HTML |
| `S2_EMAIL_PRE_S3` | EMAIL | SendGrid | D+12 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S2_EMAIL_PRE_S3.html) · [Test Data](./email-templates/email-templates-test/test-data-s2-d12.json) | ✅ HTML |
| `S2_EMAIL_CONDITIONAL` | EMAIL | SendGrid | 16:00 | _待填_ | [conditionals/](./email-templates/conditionals/S2_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S2_VOICE_*` | AI_CALL | LTH | Wave-1/2 | voice | 待填 | Mock |

### 2.4 S3（D+16 ~ D+30）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S3_SMS_*` | SMS | LTH | 08:00 | Pay Now 加重 | 待填 | ⏳ |
| `S3_PUSH_STANDARD` | PUSH | FCM | 12:00 | data payload | 待填 | ⏳ |
| `S3_EMAIL_ENTRY` | EMAIL | SendGrid | D+16 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S3_EMAIL_ENTRY.html) · [Test Data](./email-templates/email-templates-test/test-data-s3-d16.json) | ✅ HTML |
| `S3_EMAIL_MID` | EMAIL | SendGrid | D+23 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S3_EMAIL_MID.html) · [Test Data](./email-templates/email-templates-test/test-data-s3-d23.json) | ✅ HTML |
| `S3_EMAIL_PRE_S4` | EMAIL | SendGrid | D+30 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S3_EMAIL_PRE_S4.html) · [Test Data](./email-templates/email-templates-test/test-data-s3-d30.json) | ✅ HTML |
| `S3_EMAIL_CONDITIONAL` | EMAIL | SendGrid | 16:00 | _待填_ | [conditionals/](./email-templates/conditionals/S3_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S3_VOICE_*` | AI_CALL | LTH | Wave-1/2 | voice | 待填 | Mock |

### 2.5 S4（D+31 ~ D+90）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S4_SMS_STANDARD` | SMS | LTH | 08:00 · Remedial | `sms_body` | 待填 | ⏳ |
| `S4_PUSH_STANDARD` | PUSH | FCM | 12:00 | data payload | 待填 | ⏳ |
| `S4_EMAIL_ENTRY` | EMAIL | SendGrid | D+31 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S4_EMAIL_ENTRY.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d31.json) | ✅ HTML |
| `S4_EMAIL_FINAL_REMINDER` | EMAIL | SendGrid | D+45 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S4_EMAIL_FINAL_REMINDER.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d45.json) | ✅ HTML |
| `S4_EMAIL_MID` | EMAIL | SendGrid | D+60 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S4_EMAIL_MID.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d60.json) | ✅ HTML |
| `S4_EMAIL_PRE_CLOSE` | EMAIL | SendGrid | D+75 · 14:00 | _待填_ | [HTML](./email-templates/milestones/S4_EMAIL_PRE_CLOSE.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d75.json) · ❓ 法务审 | ✅ HTML |
| `S4_EMAIL_CONDITIONAL` | EMAIL | SendGrid | 16:00 | _待填_ | [conditionals/](./email-templates/conditionals/S4_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S4_VOICE_*` | AI_CALL | LTH | Wave-1/2 | Remedial；Wave-2 仅 D+31~60 | 待填 | Mock |

---

## 3. Email（SendGrid）

### 3.1 配置映射

**密钥**（`.env`，勿提交 Git）：

```dotenv
SENDGRID_API_KEY=SG.xxxx
SENDGRID_FROM_EMAIL=notice.collections@your-domain.ph
```

**scriptSlot → d-xxx**（Nacos / `application-local.yml`，可热更）：

```yaml
channel:
  sendgrid:
    api-key: ${SENDGRID_API_KEY:}
    from-email: ${SENDGRID_FROM_EMAIL:}
    from-name: MOCASA Collections
    templates:
      S0_DUE_TODAY_EMAIL: d-39cb3cd90ee44451887c5c67bd1ac073
      S1_EMAIL_OVERDUE_NOTICE: d-xxxxxxxx
      S4_EMAIL_PRE_CLOSE: d-e28ba12db2344c8e9c0a4d9a4e26a107
    unsubscribe-group-id: 0
```

**解析顺序**（`SendGridEmailAdapter`）：`metadata.scriptSlot` → `templates` 映射 → `step.templateId`（`d-` 前缀）。**未命中则失败**（`SENDGRID_NO_TEMPLATE`），无兜底 `default-template-id`。

**E2E 联调案例**：[`email-templates/email-e2e-test-cases.md`](./email-templates/email-e2e-test-cases.md)

### 3.2 变量与叙事

| 变量 | 来源 | 说明 |
|------|------|------|
| `borrower_name` | userProfile | 建议必填 |
| `amount_due` | caseContext.totalOutstanding | 模板内加 `₱` |
| `overdue_days` | caseContext.dpd | S0 = 0 |
| `payment_link` | caseContext.repaymentUrl | Pay Now href |
| `assignment_date` | Resolver 计算 | 仅 `S4_EMAIL_PRE_CLOSE`（D+75）：对外 **final delinquency review 日**（= 内部 D+91）；**无委外** |

**叙事原则**（v3）：见 [email-templates/README §2](./email-templates/README.md#2-催收心理学矩阵)——**全程无 third-party**；D+75 用 `assignment_date` 预告 **final delinquency review**（禁写停催/委外）。
**Subject / Preheader SSOT**：[`email-templates/subjects.md`](./email-templates/subjects.md)  
**语言**：Phase 1 仅英文；S2+ 静态 Offer 引导（无 `offer_amount`）。

### 3.3 里程碑速查

| # | scriptSlot | DPD | SendGrid ID | HTML |
|---|------------|-----|-------------|------|
| 1 | `S0_DUE_TODAY_EMAIL` | D0 | `d-39cb3cd…` | ✅ 已建站 |
| 2–13 | S1~S4 里程碑 | 见 §2 | _待填_ | ✅ `email-templates/milestones/` |
| — | `S*_EMAIL_CONDITIONAL` ×4 | Phase 2 | _待填_ | ✅ `email-templates/conditionals/` |

**Test Data 索引**：[`email-templates/email-templates-test/test-data-index.json`](./email-templates/email-templates-test/test-data-index.json)  
**联调**：`single-step=EMAIL` + caseId **92002** → `wzynju@126.com`（优先 126，Gmail 易 DMARC 拦截）

### 3.4 建站 SOP

1. Template Name = `scriptSlot`（与上表一致）
2. Code 粘贴 `email-templates/milestones/` 或 `conditionals/` HTML
3. **Settings** 填 Subject / Preheader（`subjects.md`）
4. **Test Data** 粘贴 `email-templates/email-templates-test/` 对应 JSON（见 `test-data-index.json`）
5. Activate → Nacos `channel.sendgrid.templates.{scriptSlot}`

详细步骤见 [email-templates/README §6](./email-templates/README.md#6-sendgrid-建站-sop)。

---

## 4. SMS（通知中心）

| 项 | 说明 |
|----|------|
| **渲染** | `DefaultStepResolver` 按 `scriptSlot` → `sms_body` |
| **发送** | `POST /v1/sms/send`（同步），`contentType=collection`；运营商路由在通知中心后台 |
| **配置** | `channel.notification.base-url`、`app-code`、`app-key`（见 [Notification 对接说明](./channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) §1） |
| **文案存放** | Phase 1：Nacos `channel.scripts.sms`（待 Resolver）；长期：策略后台 |
| **变量** | `borrower_name`、`amount_due`、`payment_link`、`overdue_days` |

---

## 5. App Push（通知中心 / JPush）

| 项 | 说明 |
|----|------|
| **渲染** | `title`、`body`；`data` 为 JSON object 字符串（`deep_link`、`case_id` 等，value 均为 string） |
| **token** | `userProfile.device.jpushToken`（JPush Registration ID） |
| **配置** | `channel.notification.*`（见 [Notification 对接说明](./channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) §2） |
| **fallback** | 无 token / 入队前参数错误 → 同槽 SMS（§3）；JPush 投递失败 Phase 1 不自动 fallback |

---

## 6. Voice / AI_CALL（LTH）

| 项 | 说明 |
|----|------|
| **渲染** | LTH TTS / AI 外呼脚本 + 动态参数 |
| **配置** | `channel.lth.voice.url`、`channel.callback.base-url` |
| **Phase 1** | Mock；TTS Adapter 暂缓 |

---

## 7. Nacos 配置汇总

```yaml
channel:
  sendgrid:
    templates: { ... }          # §3.1
  lth:
    sms: { url, sender-id }
    voice: { url }
  fcm:
    project-id: ...
    service-account-json: |
      ...
  callback:
    base-url: https://domain/webhook
```

---

## 8. 与代码对应

| 环节 | 行为 |
|------|------|
| `DefaultPlanFactory` | Stage/DPD/时刻 → step + `scriptSlot` |
| `DefaultStepResolver` | `scriptSlot` → 渠道 payload + `dynamicTemplateData` |
| `SendGridEmailAdapter` | Email：`scriptSlot` → `channel.sendgrid.templates` |
| `NotificationSmsAdapter` / `NotificationPushAdapter` / `LthVoiceAdapter` | 各渠道发送 |

---

## 9. 关联文档

| 文档 | 用途 |
|------|------|
| [email-templates/README.md](./email-templates/README.md) | Email 设计系统、叙事原则 |
| [email-templates/subjects.md](./email-templates/subjects.md) | Subject / Preheader |
| [email-templates/email-templates-test/](./email-templates/email-templates-test/README.md) | Test Data |
| [策略迭代与测试操作手册 §5.2](./MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md#52-改话术--邮件正文--深链) | 运营改模板流程 |
| [SendGrid Email 对接说明](./MOCASA催收系统升级_Phase1_SendGrid_Email对接说明.md) | Adapter |
| [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | TC 冒烟 |
| [开发进度](./MOCASA催收系统升级_Phase1_collection-channel开发进度.md) | 里程碑状态 |
| [渠道文档索引](./README_渠道文档索引.md) | 导航 |
