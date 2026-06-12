# MOCASA Phase 1 — 渠道模板清单与配置（SSOT）

> **版本**: v2.3 · **日期**: 2026-06-12  
> **状态**: ✅ Email Phase 1 **启用 5 封**（`collections@mocasa.com`）；SMS/Push **英文初稿已出**（全 S0–S4，见 §4.1/§5.1，待母语润色与法务/业务终审）；Voice ⏳ 待 LTH  
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
- [4. SMS（通知中心）](#4-sms通知中心)
- [5. App Push（通知中心 / JPush）](#5-app-push通知中心--jpush)
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

> **Phase 1 Email**：**仅发 5 封**（D0 / D+1 / D+4 / D+31 / D+75），发件人 `collections@mocasa.com`；其余里程碑 HTML 保留备用、**无 SendGrid 映射**。  
> **Phase 1 SMS/Push**：**英文初稿已出**（全 S0–S4），正文见 [§4.1 SMS 文案](#41-sms-文案英文初稿) / [§5.1 Push 文案](#51-push-文案英文初稿)；下表「素材」列保留供应商映射视角，文案以 §4.1/§5.1 为准。  
> **Phase 1 Voice** ⏳ 待 LTH。  
> **Phase 2**：`*_EMAIL_CONDITIONAL` Plan 启用 + ExecutionGuard；SMS/Push Tagalog 本地化。

### 2.1 S0（D-3 ~ D0）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S0_REMINDER` | SMS / Push | LTH / FCM | D-3、D-2 · 08:00 | Resolver → `sms_body` / FCM data | 待填 · 含防诈骗 | ⏳ |
| `S0_REMINDER_URGENT` | SMS / Push | LTH / FCM | D-1 · 08:00 | 同上 | 待填 | ⏳ |
| `S0_DUE_TODAY` | SMS / Push | LTH / FCM | D0 · 08:00 | 同上 | 待填 | ⏳ |
| `S0_DUE_TODAY_EMAIL` | EMAIL | SendGrid | D0 · 14:00 | `d-9b485bfd24e14950a7811faf33c2b22f` | [HTML](./email-templates/milestones/S0_DUE_TODAY_EMAIL.html) · [Test Data](./email-templates/email-templates-test/test-data.sample.json) | ✅ **启用** |

### 2.2 S1（D+1 ~ D+3）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S1_SMS_STANDARD` | SMS | LTH | 每日 08:00 | `sms_body` | 待填 | ⏳ |
| `S1_PUSH_STANDARD` | PUSH | FCM | 12:00 | FCM data + 深链 | 待 App | ⏳ |
| `S1_EMAIL_OVERDUE_NOTICE` | EMAIL | SendGrid | D+1 · 14:00 | `d-bc7f5aee7e304caf93ca4d435a73a1d7` | [HTML](./email-templates/milestones/S1_EMAIL_OVERDUE_NOTICE.html) · [Test Data](./email-templates/email-templates-test/test-data-s1-d1.json) | ✅ **启用** |
| `S1_EMAIL_STAGE_WARNING` | EMAIL | SendGrid | D+3 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S1_EMAIL_STAGE_WARNING.html) · [Test Data](./email-templates/email-templates-test/test-data-s1-d3.json) | 📦 HTML 备用 |
| `S1_EMAIL_CONDITIONAL` | EMAIL | SendGrid | D+1 · 16:00 | _待填_ | [HTML](./email-templates/conditionals/S1_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S1_VOICE_PRIMARY` / `RETRY` | AI_CALL | LTH | Wave-1/2 | voice 参数 | 待 LTH | Mock |

### 2.3 S2（D+4 ~ D+15）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S2_SMS_STANDARD` / `S2_SMS_FIRM` | SMS | LTH | 08:00 | `sms_body` + offer 占位 | 待填 | ⏳ |
| `S2_PUSH_STANDARD` | PUSH | FCM | 12:00 | data payload | 待填 | ⏳ |
| `S2_EMAIL_ENTRY` | EMAIL | SendGrid | D+4 · 14:00 | `d-86ed8faae3b24489ad7db8a11067b8c4` | [HTML](./email-templates/milestones/S2_EMAIL_ENTRY.html) · [Test Data](./email-templates/email-templates-test/test-data-s2-d4.json) | ✅ **启用** |
| `S2_EMAIL_MID` | EMAIL | SendGrid | D+7 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S2_EMAIL_MID.html) · [Test Data](./email-templates/email-templates-test/test-data-s2-d7.json) | 📦 HTML 备用 |
| `S2_EMAIL_PRE_S3` | EMAIL | SendGrid | D+12 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S2_EMAIL_PRE_S3.html) · [Test Data](./email-templates/email-templates-test/test-data-s2-d12.json) | 📦 HTML 备用 |
| `S2_EMAIL_CONDITIONAL` | EMAIL | SendGrid | 16:00 | _待填_ | [conditionals/](./email-templates/conditionals/S2_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S2_VOICE_*` | AI_CALL | LTH | Wave-1/2 | voice | 待填 | Mock |

### 2.4 S3（D+16 ~ D+30）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S3_SMS_*` | SMS | LTH | 08:00 | Pay Now 加重 | 待填 | ⏳ |
| `S3_PUSH_STANDARD` | PUSH | FCM | 12:00 | data payload | 待填 | ⏳ |
| `S3_EMAIL_ENTRY` | EMAIL | SendGrid | D+16 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S3_EMAIL_ENTRY.html) · [Test Data](./email-templates/email-templates-test/test-data-s3-d16.json) | 📦 HTML 备用 |
| `S3_EMAIL_MID` | EMAIL | SendGrid | D+23 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S3_EMAIL_MID.html) · [Test Data](./email-templates/email-templates-test/test-data-s3-d23.json) | 📦 HTML 备用 |
| `S3_EMAIL_PRE_S4` | EMAIL | SendGrid | D+30 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S3_EMAIL_PRE_S4.html) · [Test Data](./email-templates/email-templates-test/test-data-s3-d30.json) | 📦 HTML 备用 |
| `S3_EMAIL_CONDITIONAL` | EMAIL | SendGrid | 16:00 | _待填_ | [conditionals/](./email-templates/conditionals/S3_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S3_VOICE_*` | AI_CALL | LTH | Wave-1/2 | voice | 待填 | Mock |

### 2.5 S4（D+31 ~ D+90）

| scriptSlot | 渠道 | 供应商 | 触发 | 渲染 / template_id | 素材 | Phase 1 |
|------------|------|--------|------|-------------------|------|---------|
| `S4_SMS_STANDARD` | SMS | LTH | 08:00 · Remedial | `sms_body` | 待填 | ⏳ |
| `S4_PUSH_STANDARD` | PUSH | FCM | 12:00 | data payload | 待填 | ⏳ |
| `S4_EMAIL_ENTRY` | EMAIL | SendGrid | D+31 · 14:00 | `d-658d5be184ab4710a19c8419ed66bca9` | [HTML](./email-templates/milestones/S4_EMAIL_ENTRY.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d31.json) | ✅ **启用** |
| `S4_EMAIL_FINAL_REMINDER` | EMAIL | SendGrid | D+45 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S4_EMAIL_FINAL_REMINDER.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d45.json) | 📦 HTML 备用 |
| `S4_EMAIL_MID` | EMAIL | SendGrid | D+60 · 14:00 | _无映射_ | [HTML](./email-templates/milestones/S4_EMAIL_MID.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d60.json) | 📦 HTML 备用 |
| `S4_EMAIL_PRE_CLOSE` | EMAIL | SendGrid | D+75 · 14:00 | `d-881ce23667cc4df2abf82097b890cae1` | [HTML](./email-templates/milestones/S4_EMAIL_PRE_CLOSE.html) · [Test Data](./email-templates/email-templates-test/test-data-s4-d75.json) · ❓ 法务审 | ✅ **启用** |
| `S4_EMAIL_CONDITIONAL` | EMAIL | SendGrid | 16:00 | _待填_ | [conditionals/](./email-templates/conditionals/S4_EMAIL_CONDITIONAL.html) | Phase 2 |
| `S4_VOICE_*` | AI_CALL | LTH | Wave-1/2 | Remedial；Wave-2 仅 D+31~60 | 待填 | Mock |

---

## 3. Email（SendGrid）

### 3.1 配置映射

**密钥**（`.env`，勿提交 Git）：

```dotenv
SENDGRID_API_KEY=SG.xxxx
SENDGRID_FROM_EMAIL=collections@mocasa.com
```

**scriptSlot → d-xxx**（Nacos / `application-local.yml`，**Phase 1 仅 5 项**）：

```yaml
channel:
  sendgrid:
    api-key: ${SENDGRID_API_KEY:}
    from-email: ${SENDGRID_FROM_EMAIL:collections@mocasa.com}
    from-name: MOCASA Collections
    templates:
      S0_DUE_TODAY_EMAIL: d-9b485bfd24e14950a7811faf33c2b22f
      S1_EMAIL_OVERDUE_NOTICE: d-bc7f5aee7e304caf93ca4d435a73a1d7
      S2_EMAIL_ENTRY: d-86ed8faae3b24489ad7db8a11067b8c4
      S4_EMAIL_ENTRY: d-658d5be184ab4710a19c8419ed66bca9
      S4_EMAIL_PRE_CLOSE: d-881ce23667cc4df2abf82097b890cae1
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

| # | scriptSlot | DPD | SendGrid ID | Phase 1 |
|---|------------|-----|-------------|---------|
| 1 | `S0_DUE_TODAY_EMAIL` | D0 | `d-9b485bfd…` | ✅ 发信 |
| 2 | `S1_EMAIL_OVERDUE_NOTICE` | D+1 | `d-bc7f5ae…` | ✅ 发信 |
| 3 | `S2_EMAIL_ENTRY` | D+4 | `d-86ed8fa…` | ✅ 发信 |
| 4 | `S4_EMAIL_ENTRY` | D+31 | `d-658d5be…` | ✅ 发信 |
| 5 | `S4_EMAIL_PRE_CLOSE` | D+75 | `d-881ce23…` | ✅ 发信 |
| — | 其余 8 里程碑 | 见 §2 | _无映射_ | 📦 HTML 备用 |
| — | `S*_EMAIL_CONDITIONAL` ×4 | Phase 2 | _待填_ | Phase 2 |

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
| **文案存放** | Phase 1：Nacos `channel.scripts.sms`（见 §7，待 `DefaultStepResolver` 读取）；长期：策略后台 |
| **变量** | `{name}`=borrower_name、`{amount}`=amount_due(PHP)、`{dpd}`=overdue_days；**SMS 不放裸链接**（短链风险，引导走 SKYPAYLOANS App） |

### 4.1 SMS 文案（英文初稿）

> **品牌**：正文 MOCASA；还款入口 **SKYPAYLOANS** App。CTA `in the SKYPAYLOANS app only` 同时承担 **防诈骗**（只在官方 App 还款）与引导。  
> **Tone**：S0 友好（禁 Collections）；S1+ 允许 Collections；S2+ 加入 **Offer 诱饵**（引导进 App 看专属方案，不写固定折扣）；施压一律用**客观账户后果**（reported as delinquent / limit your account / future loan eligibility），**不用**主观威胁词（avoid further action/escalation）。  
> **排版（保送达）**：**禁用全大写词**（URGENT/PAY NOW/FINAL NOTICE 一律首字母大写），降低运营商 Spam 拦截。  
> **长度（控成本）**：英文 GSM-7，160 字/段；去寒暄（无 Hi），为 `{name}`/`{amount}` 预留余量；下表 `段` 为典型值预估，>1 段请终审裁剪。  
> **占位符**：`{name}` `{amount}` `{dpd}` 由 Resolver 注入；`{name}` 缺失时 Resolver 自动省略并清理标点空格。

| scriptSlot | Stage/日块 | Tone | 正文（EN · 专家修订版） | 段 |
|------------|-----------|------|----------------|----|
| `S0_REMINDER` | S0 · D-3/D-2 | 友好 | `MOCASA: {name}, your PHP {amount} payment is due soon. Pay in the SKYPAYLOANS app only.` | 1 |
| `S0_REMINDER_URGENT` | S0 · D-1 | 友好+提醒 | `MOCASA: {name}, your PHP {amount} payment is due tomorrow. Pay today in the SKYPAYLOANS app only to keep your account current.` | 1 |
| `S0_DUE_TODAY` | S0 · D0 | 友好 | `MOCASA: {name}, your PHP {amount} payment is due today. Pay in the SKYPAYLOANS app only to stay current.` | 1 |
| `S1_SMS_STANDARD` | S1 · D+1~3 08:00 | 标准 | `MOCASA Collections: {name}, your account is {dpd} day(s) overdue. Settle PHP {amount} in the SKYPAYLOANS app only.` | 1 |
| `S2_SMS_STANDARD` | S2 · 08:00 | 标准+Offer | `MOCASA Collections: {name}, {dpd} days overdue. See your personalized payment options for PHP {amount} in the SKYPAYLOANS app only.` | 1 |
| `S2_SMS_FIRM` | S2 · 08:00 难催 | FIRM+Offer | `MOCASA Collections: {name}, {dpd} days overdue; your account may be reported as delinquent. See your options for PHP {amount} in the SKYPAYLOANS app only.` | 1~2 |
| `S3_SMS_STANDARD` | S3 · 08:00 | Pay+Offer | `MOCASA Collections: {name}, {dpd} days overdue. Delay may limit your account and future loan eligibility. View your options for PHP {amount} in the SKYPAYLOANS app only.` | 1~2 |
| `S3_SMS_FIRM` | S3 · 08:00 难催 | FIRM+Offer | `MOCASA Collections: {name}, {dpd} days overdue and may be recorded as delinquent. Settle PHP {amount} or view your options in the SKYPAYLOANS app only.` | 1~2 |
| `S4_SMS_STANDARD` | S4 · 08:00 Remedial | Final+Offer | `MOCASA Collections: {name}, final notice. {dpd} days overdue and at risk of a delinquency record. View your resolution options for PHP {amount} in the SKYPAYLOANS app only.` | 1~2 |
| `S4_SMS_FIRM` | S4 · D+31~60 难催 | Final+Offer | `MOCASA Collections: {name}, severely overdue ({dpd} days) and may be recorded as delinquent. Resolve PHP {amount} via your options in the SKYPAYLOANS app only.` | 1~2 |

> **合规/送达校验点**：①无委外/legal/third-party；②无裸 URL；③**无全大写词**；④后果用客观描述（delinquency record / limited account / future eligibility），不写主观威胁；⑤Sender ID 统一 `MOCASA`；⑥S0 不含 Collections。

---

## 5. App Push（通知中心 / JPush）

| 项 | 说明 |
|----|------|
| **渲染** | `title`、`body`；`data` 为 JSON object 字符串（`deep_link`、`case_id` 等，value 均为 string） |
| **token** | `userProfile.device.jpushToken`（JPush Registration ID） |
| **配置** | `channel.notification.*`（见 [Notification 对接说明](./channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) §2） |
| **fallback** | 无 token / 入队前参数错误 → 同槽 SMS（§4）；JPush 投递失败 Phase 1 不自动 fallback |

### 5.1 Push 文案（英文初稿）

> **占位符**同 §4.1；Push `body` 力求极短（通知栏一行）。**标题前置关键信息**（逾期天数/金额/Offer），强化首因效应拉点击。`data` 统一携带 `scene/case_id/script_slot/deep_link`（`deep_link`=`caseContext.repaymentUrl`，缺失时用 `channel.scripts.push-default-deep-link` 兜底；**所有 value 均为 string**）。

| scriptSlot | Stage/日块 | title（前置关键信息） | body（EN · 专家修订版） |
|------------|-----------|-------|----------------|
| `S0_REMINDER` | S0 · D-3/D-2 | `Payment due soon` | `{name}, PHP {amount} is due soon. Tap to pay in the SKYPAYLOANS app.` |
| `S0_REMINDER_URGENT` | S0 · D-1 | `Due tomorrow` | `{name}, PHP {amount} is due tomorrow. Tap to pay in the SKYPAYLOANS app.` |
| `S0_DUE_TODAY` | S0 · D0 | `Due today` | `{name}, PHP {amount} is due today. Tap to pay now.` |
| `S1_PUSH_STANDARD` | S1 · 12:00 | `Overdue: PHP {amount}` | `{name}, your payment is past due. Tap to settle in the app.` |
| `S2_PUSH_STANDARD` | S2 · 12:00 | `{dpd} days overdue` | `See your personalized payment options for PHP {amount}. Tap to view.` |
| `S3_PUSH_STANDARD` | S3 · 12:00 | `{dpd} days overdue` | `Delay may limit your account. View your options for PHP {amount}. Tap now.` |
| `S4_PUSH_STANDARD` | S4 · 12:00 | `Final notice: {dpd} days overdue` | `Resolve PHP {amount} now. View your options in the app.` |

**`data` 示例**（序列化为字符串传 `data` 字段）：

```json
{
  "scene": "collection",
  "case_id": "1001",
  "script_slot": "S1_PUSH_STANDARD",
  "deep_link": "https://app.mocasa.com/repay?bill=xxx"
}
```

> Push `data` 的 `deep_link` 解析与跳转逻辑须与 **App 团队（李辉）** 终确认（见 [Notification 对接说明](./channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) §2.3）。

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
  notification:
    base-url: https://service-test.mocasa.com/notification
    app-code: mocasa
    app-key: ${NOTIFICATION_APP_KEY}     # 生产 /v1/sms/send 必需；测试 /testSend 免签名可空
    sms-content-type: collection
    sms-test-mode: false                  # true → SMS 走 /v1/sms/testSend（免签名，联调用）
    sms-test-account-name: ""             # 可选：指定测试通道账号名
  lth:
    voice: { url }                # SMS 已废弃；lth.sms 勿配
  callback:
    base-url: https://domain/webhook
  scripts:                        # SMS/Push 文案（§4.1/§5.1），DefaultStepResolver 按 scriptSlot 读取并注入变量
    push-default-deep-link: "https://app.mocasa.com/repay"   # repaymentUrl 缺失时兜底（到 App 还款页，待 App 确认）
    sms:
      S0_REMINDER: "MOCASA: {name}, your PHP {amount} payment is due soon. Pay in the SKYPAYLOANS app only."
      S0_REMINDER_URGENT: "MOCASA: {name}, your PHP {amount} payment is due tomorrow. Pay today in the SKYPAYLOANS app only to keep your account current."
      S0_DUE_TODAY: "MOCASA: {name}, your PHP {amount} payment is due today. Pay in the SKYPAYLOANS app only to stay current."
      S1_SMS_STANDARD: "MOCASA Collections: {name}, your account is {dpd} day(s) overdue. Settle PHP {amount} in the SKYPAYLOANS app only."
      S2_SMS_STANDARD: "MOCASA Collections: {name}, {dpd} days overdue. See your personalized payment options for PHP {amount} in the SKYPAYLOANS app only."
      S2_SMS_FIRM: "MOCASA Collections: {name}, {dpd} days overdue; your account may be reported as delinquent. See your options for PHP {amount} in the SKYPAYLOANS app only."
      S3_SMS_STANDARD: "MOCASA Collections: {name}, {dpd} days overdue. Delay may limit your account and future loan eligibility. View your options for PHP {amount} in the SKYPAYLOANS app only."
      S3_SMS_FIRM: "MOCASA Collections: {name}, {dpd} days overdue and may be recorded as delinquent. Settle PHP {amount} or view your options in the SKYPAYLOANS app only."
      S4_SMS_STANDARD: "MOCASA Collections: {name}, final notice. {dpd} days overdue and at risk of a delinquency record. View your resolution options for PHP {amount} in the SKYPAYLOANS app only."
      S4_SMS_FIRM: "MOCASA Collections: {name}, severely overdue ({dpd} days) and may be recorded as delinquent. Resolve PHP {amount} via your options in the SKYPAYLOANS app only."
    push:                         # 每槽 title + body 两键
      S0_REMINDER: { title: "Payment due soon", body: "{name}, PHP {amount} is due soon. Tap to pay in the SKYPAYLOANS app." }
      S0_REMINDER_URGENT: { title: "Due tomorrow", body: "{name}, PHP {amount} is due tomorrow. Tap to pay in the SKYPAYLOANS app." }
      S0_DUE_TODAY: { title: "Due today", body: "{name}, PHP {amount} is due today. Tap to pay now." }
      S1_PUSH_STANDARD: { title: "Overdue: PHP {amount}", body: "{name}, your payment is past due. Tap to settle in the app." }
      S2_PUSH_STANDARD: { title: "{dpd} days overdue", body: "See your personalized payment options for PHP {amount}. Tap to view." }
      S3_PUSH_STANDARD: { title: "{dpd} days overdue", body: "Delay may limit your account. View your options for PHP {amount}. Tap now." }
      S4_PUSH_STANDARD: { title: "Final notice: {dpd} days overdue", body: "Resolve PHP {amount} now. View your options in the app." }
```

> **落地说明**：`DefaultStepResolver` 由 `Stage + 渠道 + strategyTone(+dpd)` 推导 `scriptSlot`，读 `channel.scripts` 注入 `{name}/{amount}/{dpd}`；S2+ 自动按 `strategyTone=FIRM` 选 `*_FIRM`。配置缺该槽时回退占位串。FIRM/STANDARD 由 ingestion 写入 `caseContext.strategyTone`。

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
