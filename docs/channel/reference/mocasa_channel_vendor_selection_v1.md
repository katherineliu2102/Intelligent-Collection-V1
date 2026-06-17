# MOCASA 催收系统：全渠道触达服务商选型报告

> **文档状态**：✅ 选型结论已定，部分商务条款 ⏳ 待签约确认  
> **日期**：2026-05-28  
> **适用范围**：MOCASA 催收系统 Phase 1（`collection-engine` + `collection-channel`），仅菲律宾市场  
> **关联文档**：[架构设计文档](./相关资料/MOCASA催收系统升级_Phase1_架构设计文档.md)、[PRD](./相关资料/MOCASA催收系统升级_Phase1_产品需求文档_PRD.md)、[Email 专项报告](./philippines_fintech_email_vendor_selection_report.md)

---

## 目录

- [一、总述](#一总述)
- [二、分通道选型](#二分通道选型)
  - [2.1 Email](#21-email)
  - [2.2 即时消息（Viber / WhatsApp）](#22-即时消息viber--whatsapp)
  - [2.3 SMS](#23-sms)
  - [2.4 外呼](#24-外呼)
  - [2.5 Push](#25-push)
- [三、总结与落地计划](#三总结与落地计划)

---

## 一、总述

### 1.1 背景与架构

MOCASA Phase 1 将到期前通知、逾期催收、人工外呼整合为统一触达平台：

- `collection-engine`：策略编排 + 合规校验（**大脑**）
- `collection-channel`：对接供应商 API 的哑管道（**手脚**）
- `collection-admin`：接收 Webhook，回传 `CHANNEL_CALLBACK`

这意味着**所有服务商只需做好投递和状态回传**，不需要参与编排决策。

### 1.2 业务前提

| 项 | 说明 |
|----|------|
| 催收日触达 | 约 **2,000–3,000 人次/天** |
| Messaging 月量 | 10 万条+（Viber/WhatsApp，不含 SMS） |
| 现有供应商 | LTH（SMS + SIP 外呼，**已定**） |
| 尚未具备 | Meta Business 验证、Twilio 账号 |
| 架构约束 | 不引入 CEP（MoEngage/CleverTap）；编排留在自研 engine |

### 1.3 Phase 1 总选型一览

| 通道 | 选型 | 状态 | 本报告章节 |
|------|------|------|------------|
| Email | **SendGrid** 主 + SES 备 | ✅ 新接入 | [2.1](#21-email) |
| Viber | **Infobip** | ⚡ Phase 1 末上线 | [2.2](#22-即时消息viber--whatsapp) |
| WhatsApp | Meta 官方 API | ⏳ Phase 2 | [2.2](#22-即时消息viber--whatsapp) |
| SMS | **LTH** 单通道 | ✅ 保留 | [2.3](#23-sms) |
| 外呼 | **LTH SIP** | ✅ 已定 | [2.4](#24-外呼) |
| Push | **FCM/APNs** | ✅ 对齐接口 | [2.5](#25-push) |

### 1.4 合规共性

- 触达窗口：**22:00–06:00 PHT 禁止**（engine `ComplianceExecutionGuard` 拦截）
- 仅触达借款人本人；凭证用 `credentials_ref` 存储，不明文

---

## 二、分通道选型

### 2.1 Email

#### 结论

| 角色 | 选型 | 状态 |
|------|------|------|
| Phase 1 主通道 | **Twilio SendGrid** | ✅ |
| 备通道（月量 >100 万） | Amazon SES | ⏳ |
| 法务函独立子域 | Postmark（小流量可选） | ⏳ |
| 发信前清洗 | ZeroBounce / NeverBounce | ✅ 必配 |

#### 为什么选 SendGrid？

核心问题：**MOCASA 已有自研编排引擎，Email 服务商只需做「哑管道」——谁在催收场景下做哑管道做得最好？**

**第一步：排除不适合的方案类型**

| 方案类型 | 代表 | 排除理由 |
|----------|------|----------|
| CEP 全家桶 | MoEngage、CleverTap | 与自研编排重叠，形成「双大脑」；需专职运营团队。PH 市场 Maya 用 CleverTap（[案例](https://clevertap.com/wp-content/uploads/2025/05/Maya-CleverTap-Case-Study-2025.pdf)），但其体量百万级且有增长团队 |
| 营销导向 ESP | Brevo | 数据主存 EU，NPC 跨境评估成本高；催收投递非其重心 |

排除后，候选为**纯投递型 ESP**：SendGrid、Amazon SES、Postmark、Mailgun。

**第二步：行业验证——同类公司怎么做**

| 公司 | 与 MOCASA 的关系 | 方案 | 来源 |
|------|------------------|------|------|
| **FinAccel**（印尼信贷） | 最近参照：东南亚信贷、中小体量、自研编排 | **SendGrid** 做投递，编排由自有系统控制 | [Twilio 案例](https://customers.twilio.com/en-us/finaccel) |
| **Tala Philippines** | 同市场 OLA，Email + SMS + Push 并用 | ESP 厂商未公开 | [官网](https://tala.ph/tala-we-miss-you/) |
| SaaS dunning 通用模式 | 与催收 D+N 步骤驱动同构 | 后端监听逾期事件 → 调 SendGrid → Webhook 回传 | [参考](https://lifecyclearchitect.com/tool-guides/dunning-optimization-with-sendgrid/) |

结论：**自研编排 + 纯投递 ESP** 是信贷/催收场景的主流路径。

**第三步：四家 ESP 对比**

| | **SendGrid** | **Amazon SES** | **Postmark** | **Mailgun** |
|--|--------------|----------------|--------------|-------------|
| ① 催收状态回传 | ✅ 全量实时推送，且**携带业务 ID** 原样返回 | 需额外搭建 SNS 队列做桥接 | 有基础事件，**无投诉事件** | 较全，但无业务 ID 级回传 |
| ② 催收邮件隔离 | ✅ 子账号 + 独立子域 | 配置复杂 | Stream 隔离 | 域级隔离 |
| ③ 多语言模板 | ✅ 云端 Handlebars if/else | 需自建 | ✅ | ✅ |
| ④ 集成成本 | **低**——REST API 开箱即用 | **高**——需搭配 SNS/SQS/CloudWatch | 低 | 低 |
| ⑤ 月费（~10 万封） | ~$40–80 | ~$10–20 | ~$100–150 | ~$80+ |

核心差异：

- **vs SES**：SES 便宜 $30–60/月，但 DevOps 投入远大于省下的钱。适合月量 >100 万的降本场景。
- **vs Postmark**：贵 2–3 倍且无投诉事件回传。适合小量法务函子域，不适合日常催收主通道。
- **vs Mailgun**：功能相近，但缺少业务 ID 透传——催收系统需要将邮件事件关联回具体案件。

**结论**：SendGrid 是当前体量和架构下的最优选——**事件回传最完整、业务 ID 可穿透、集成成本最低**。

#### 接入要点

- **注册**：企业邮箱注册（勿用 Gmail）；Domain Authentication（SPF/DKIM/DMARC）；催收用独立域 `notice.collections.{brand}.ph`
- **架构**：`POST /v3/mail/send`（Web API，不用 SMTP）；`custom_args` 透传 `case_id`；催收与 OTP **Subuser 隔离**
- **防雷**：List Cleaning（hard bounce <2%）；IP 2–4 周 warm-up（~50 封/日起）；Link Branding 避免短链
- **POC 通过线**（≥1,000 封）：Delivery >98%；Webhook P95 <30s

---

### 2.2 即时消息（Viber / WhatsApp）

#### 结论

| 方案 | Phase 1 | 核心理由 |
|------|---------|----------|
| **Viber（Infobip）** | ⚡ 优先接入 | 成本低 4–5 倍、无 Meta 前置、PH 渗透率高 |
| **WhatsApp 官方 API** | ⏳ Phase 2 | 需 Meta 验证 + WABA，Utility 单价高 |
| **WSCRM** | ❌ 不用于自动化 | 坐席 CRM 模式，无法批量 API 发送，有封号风险 |

#### 为什么 Viber 优先于 WhatsApp？

核心问题：**月量 10 万条 + 无 Meta 账号，选哪个即时消息通道？**

**对比**

| | **Viber（Infobip）** | **WhatsApp 官方 API** | **WSCRM** |
|--|----------------------|-----------------------|-----------|
| ① 10 万条/月成本 | ~**$250** | ~**$1,130**（Utility）；若被判 Marketing 则 ~$7,000 | 不按条计费，非 API |
| ② 能否立即启动 | ✅ Infobip Partner 审核 2–14 天 | ❌ 需先完成 Meta Business 验证 + WABA | 已有，但不可用于自动化 |
| ③ PH 用户覆盖 | ~71%（金融通知常见） | ~40%+（个人/跨境更强） | 坐席辅助 |
| ④ 被改为营销计费的风险 | 低（Transactional 模板） | **高**（催收话术易被判 Marketing，费率翻 6 倍） | 封号 |
| ⑤ 与 StepCommand 集成 | ✅ Template API + callbackData 透传 | ✅ Template API + Webhook | ❌ |

**决策依据**：

- **成本**：同等量级 Viber 仅 WhatsApp 的 **1/4 到 1/5**，且无改类风险
- **门槛**：MOCASA 当前无 Meta 账号，WhatsApp 从零开始需 Meta 验证 + WABA 注册 + 号码绑定，周期不可控；Viber 经 Infobip 可直接启动
- **行业参考**：Infobip 公开案例含菲律宾 Metrobank 用 Viber Business Messages 做银行通知（⏳ 推断）

**结论**：Phase 1 **首选 Viber**；WhatsApp 放 Phase 2（届时 Meta 验证完成 + Viber 数据可做 ROI 对比）。

#### Viber 接入要点（Infobip）

1. Infobip 后台申请 Viber for Business → Qualification Form + Warranty Letter（opt-in 承诺）
2. 审核 2–14 天 → 获 Service ID → 签 MSA → 开通 API
3. `POST /viber/2/messages`（TEMPLATE）；Webhook 配 `collection-admin`
4. 模板走 **Transactional**；`callbackData` / `bulkId` 透传 `case_id`

#### WhatsApp 接入预备（Phase 2）

1. Meta Business 验证 → WABA → 号码验证 → Utility 模板审核
2. 催收文案避免促销措辞，防 Utility → Marketing 改价
3. 可与 Infobip 同 BSP，但与 Viber **分模板管理**

---

### 2.3 SMS

#### 结论

| 角色 | 选型 | 状态 |
|------|------|------|
| Phase 1 主通道 | **LTH**（现有 `sendSms`） | ✅ |
| 本地网关熔断备 | Phase 1 **不签约**，架构预留 | ❌ |
| Twilio / Infobip 国际 SMS | 不替换 | ❌ PH 单价过高 |

#### 为什么不签第二家 SMS 供应商？

核心问题：**日量 2,000–3,000 条，是否有必要签 Semaphore/PhilSMS 做 LTH 的熔断备选？**

| 维度 | 分析 |
|------|------|
| **故障影响** | LTH 宕机一天 ≈ 2–3k 条未发，engine 次日可重试 |
| **熔断能解决的问题** | 仅 LTH 平台故障；**不能解决**运营商对催收内容的拦截（换 Semaphore 同样被拦） |
| **备通道成本** | Sender Name 5 工作日审批 + 预充值 + 第二套 Adapter + 日常监控 |
| **行业惯例** | 多供应商 fallback 常见于**百万级**通知系统；当前体量优先 Adapter 化而非双签 |

**结论**：Phase 1 不签第二家。在 `LthSmsAdapter` 预留 fallback 接口；触发再签的条件：LTH **连续 SLA 违约**或 SMS 月量突破 **10 万**。

#### 备选运营商参考（供后续评估）

| 运营商 | 类型 | 单价（PH） | Delivery 回调 | Phase 1 |
|--------|------|------------|---------------|---------|
| **LTH** | 呼叫中心配套 | 依合同 | ❓ 待确认 | ✅ 主通道 |
| **Semaphore** | 本地网关 | ~₱0.50/条 | GET 查状态 | 架构预留 |
| **PhilSMS** | 本地网关 | ~₱0.35/条起 | message_id 查状态 | 架构预留 |
| **Infobip** | 国际 CPaaS | ~$0.17/条 | Webhook | ❌ 贵 |
| **Twilio** | 国际 CPaaS | ~$0.20/条 | Webhook | ❌ 贵 |

> 国际 CPaaS 1 万条 ≈ $1,700–2,000，本地网关 ≈ ₱3,500–5,000（~$60–90），差距约 **20 倍**。

---

### 2.4 外呼

#### 结论

外呼（TTS / AI Call / 预测式人工）**已确定使用 LTH SIP 线路**，Phase 1 不做供应商替换，仅将现有 API 封装为标准 Adapter。

#### 为什么不换供应商？

| 维度 | 分析 |
|------|------|
| **现状** | LTH 已集成 6 张表 + 10+ API（createTask / addNumber / filterNumber / agentLogin 等） |
| **替换成本** | 换 CommPeak 等云呼叫中心需全栈重写，工期 3–6 月 |
| **Phase 1 价值** | 统一接口（Adapter 化）+ 回调对账，而非换供应商 |

#### 需要交付什么

| Adapter | 对应 LTH API | 关键特征 |
|---------|-------------|----------|
| `LthVoiceAdapter` | `voiceNotification` | 异步调用 → 等 `CHANNEL_CALLBACK` |
| `LthHumanCallAdapter` | `createTask` / `addNumberToTask` | 长生命周期任务；超时需对账（架构 §1.3.7） |
| PreFlight 联动 | `filterNumber` | 还款/冻结时从 LTH 任务中移除号码 |

**要点**：调用后状态保持 `STEP_EXECUTING`，等回调推进；Issabel 废弃，通话记录 100% 回流 Timeline；呼损率超阈值降级渐进式外呼。

---

### 2.5 Push

#### 结论

Push **不涉及供应商选型**。MOCASA App 已接入 FCM/APNs，collection-channel 只需实现 `PushAdapter`，与 App 团队对齐接口。

#### 为什么不买 Push SaaS？

| 维度 | 分析 |
|------|------|
| **FCM 免费** | 发送零成本，App 已有 Firebase 项目 |
| **Push SaaS 额外价值** | OneSignal/MoEngage 等主要解决「运营看板 + 分群」——这在 MOCASA 由 engine 承担 |

#### 需要对齐什么

| 项 | 负责方 | 说明 |
|----|--------|------|
| Device Token 同步 | App + 信贷 | 写入 `context_snapshot`，无 token 则跳过 Push |
| 还款深链 | 产品 + App | PRD Q3 待确认 `repayment_url` |
| `PushAdapter` | collection-channel | 调 FCM v1 API；用 `data` 消息（非 notification）+ 幂等 key |
| 失败处理 | channel | 404/Unregistered → 标记无效 token |

---

## 三、总结与落地计划

### 3.1 架构总览

```
collection-engine（策略编排 + 合规）
        │
        ▼
collection-channel（哑管道）
  ├─ EmailAdapter         → SendGrid（主）/ SES（备）
  ├─ ViberAdapter         → Infobip（Phase 1 末）
  ├─ WhatsAppAdapter      → Phase 2
  ├─ LthSmsAdapter        → LTH（fallback 接口预留）
  ├─ LthVoiceAdapter      → LTH SIP
  ├─ LthHumanCallAdapter  → LTH SIP 预测式
  └─ PushAdapter          → FCM / APNs
        │
        ▼
collection-admin Webhook → CHANNEL_CALLBACK
```

### 3.2 选型总结

| 通道 | 选型 | 选它的核心理由 |
|------|------|----------------|
| Email | SendGrid | 事件回传最完整 + 业务 ID 穿透 + 集成成本最低 |
| Viber | Infobip | 成本仅 WhatsApp 的 1/4、无 Meta 前置、PH 渗透 71% |
| WhatsApp | Phase 2 | 需 Meta 验证，当前无账号 |
| SMS | LTH 单通道 | 已集成；日量 2–3k 不值得双签 |
| 外呼 | LTH SIP | 已定；替换成本 3–6 月，Phase 1 仅 Adapter 化 |
| Push | FCM/APNs | 免费且 App 已有；无需第三方 |

### 3.3 落地节奏

| 阶段 | 动作 | 周期 | 交付物 |
|------|------|------|--------|
| Phase 0 | Email DNS + ChannelGateway 骨架 | 1–2 周 | 接口定义 + 配置表 |
| Phase 1a | SendGrid + LTH SMS + Push 对齐 | 2–3 周 | EmailAdapter + LthSmsAdapter + PushAdapter |
| Phase 1b | LTH 外呼 Adapter + 对账 | 3–4 周 | LthVoiceAdapter + LthHumanCallAdapter |
| Phase 1c | D-3 接管；S0–S2 上线 | 2 周 | 全链路生产 |
| Phase 1d | Infobip Viber 签约 + POC | 3–4 周 | ViberAdapter |
| Phase 2 | Meta 验证 + WhatsApp 官方 | 4–6 周 | WhatsAppAdapter |

### 3.4 立即行动项

| 事项 | 优先级 | 状态 |
|------|--------|------|
| SendGrid 企业账号 + 测试子域 POC | P0 | ⏳ |
| Infobip Viber 商务签约 | P0 | ❓ 待签 |
| 冻结 StepCommand / StepResult 契约 | P0 | ⏳ |
| App FCM token 字段对齐 | P0 | ❓ 待 App |
| LTH SMS delivery 回调能力确认 | P0 | ❓ 待 LTH |
| 还款链接归属确认（PRD Q3） | P0 | ❓ 待产品 |
