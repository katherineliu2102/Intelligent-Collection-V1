# ContextSnapshot 契约对齐回复（催收渠道 / 编排侧）

> **版本**: v1.0 · **日期**: 2026-06-09  
> **回复对象**: [README_ContextSnapshot契约对齐.md](../../../AI%20collection/相关资料/README_ContextSnapshot契约对齐.md)、[ContextSnapshot.sample.json](../../../AI%20collection/相关资料/ContextSnapshot.sample.json)  
> **关联**: [领域模型与数据定义 §3](../MOCASA催收系统升级_Phase1_领域模型与数据定义.md)、[Notification 对接说明](./MOCASA催收系统升级_Phase1_Notification对接说明.md)、[渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md)  
> **维护约定**: 快照契约仍由主架构维护；本文档为 **渠道/编排同事** 的审阅结论与**待合并修改清单**，定稿后请同步更新 `AI collection/相关资料/` 与 `领域模型` 正文。

---

## 1. 总体结论

| 维度 | 评价 |
|------|------|
| 结构分层 | **合理**：`caseContext` / `userProfile` / `contactHistory` + `snapshotVersion` 清晰 |
| 数据流向 | **合理**：接入组装 → 落库 → SPI 零 DB 只读快照 |
| 最小必填表 | **方向正确**，但 PUSH token、Email、还款链等需按 Phase 1 真实渠道补齐 |
| 样例 JSON | **可作骨架**，需修正 1 处字段名冲突、补 3 个缺失字段、统一金额口径 |

**已拍板（2026-06-09，与产品/渠道对齐）**

- Push token 字段：**`device.jpushToken`**（JPush Registration ID），不用 `fcmToken`
- 文案金额 SSOT：**只认 `caseContext.*`**；`userProfile.repayment.*` 仅画像辅助
- Phase 1 必补：**`repaymentUrl`、`basic.email`、`basic.language`（预留）**

---

## 2. 确认保留（无需改动）

- `snapshotVersion: "v1"` 及「字段增删先在此目录对齐」机制
- `isFirstLoan` 序列化为 JSON 字段名 `firstLoan` 的说明
- 最小必填集 + 责任人分工（服务同事映射 vs 接入组装 `contactHistory`）
- `contactHistory.todayTouchCount` / `channelTouchCounts` 用于频控 Guard
- `risk.*`、`behavior.*` 等结构**保留**（见 §5.2，不能因「消息渠道不用」而删）

---

## 3. 必须修正

### 3.1 `device.fcmToken` → `device.jpushToken`

| 项 | 说明 |
|----|------|
| **问题** | 样例与 README 开放问题 #1 使用 `fcmToken`；催收 Push 已改为经**通知中心 → JPush** |
| **决议** | 字段改名 **`jpushToken`**，语义 = JPush Registration ID |
| **来源** | App 登录/启动上报 → 信贷/App 后端 → `t_user_equipment` → `ProfileService` |
| **消费** | `StepResolver`：PUSH 步骤 `targetAddress = userProfile.device.jpushToken` |

→ **直接关闭** README 开放问题 #1。

### 3.2 金额字段 SSOT

样例中 `caseContext.penaltyAmount=80` 与 `userProfile.repayment.penaltyFee=0` **矛盾**。

| 用途 | SSOT 字段 | 非 SSOT（仅画像） |
|------|-----------|-------------------|
| SMS/Email/Push 模板 `amount_due` | `caseContext.totalOutstanding` | `repayment.remainingAmount` |
| 模板 `overdue_days` | `caseContext.dpd` | — |
| 罚息展示 | `caseContext.penaltyAmount` | `repayment.penaltyFee` / `overdueFee` |

**要求**：服务同事映射时保证 `caseContext` 金额自洽；`repayment.*` 可与案件表口径不同，但**不得**被 StepResolver 用作对外文案变量。

---

## 4. 必须补充（Phase 1）

### 4.1 `caseContext.repaymentUrl`

| 项 | 说明 |
|----|------|
| **用途** | SMS `payment_link`、Push `data.deep_link`、Email `payment_link` |
| **必填** | 有还款引导的渠道步骤均依赖；无则文案/深链失败 |
| **来源** | ingestion 从 App/信贷结账链路写入（`CaseService.buildContext`） |
| **Java** | `CaseContext.repaymentUrl` 已存在于 `collection-common`，**领域模型 §3.1 表需补一行** |

### 4.2 `userProfile.basic.email`

| 项 | 说明 |
|----|------|
| **用途** | Email 渠道 `StepCommand.targetAddress` |
| **必填** | 有邮箱则填；`null` → `ExecutionGuard` `NO_EMAIL` → 步骤 SKIP（编排 §3.5） |
| **来源** | `t_user_basis` 或信贷用户表 |
| **Java** | `UserProfile.BasicInfo.email` 已存在，**领域模型 §3.2 BasicInfo 表需补一行** |

### 4.3 `userProfile.basic.language`

| 项 | 说明 |
|----|------|
| **用途** | `StepResolver` → `metadata.language`（`tl` / `en`） |
| **Phase 1** | 可默认 `en`；字段**预留**，避免二期改契约 |
| **建议位置** | `basic.language`（ISO 639-1） |

---

## 5. Phase 1 精简字段集（2026-06-09 定稿）

> 与 [渠道编排 §6](../../Intelligent-Collection-V1/docs/channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#6-策略标记替代-persona-分类) 一致：**L1 = Stage + 事实标记**，不做 Persona/ML 分群。落库 JSON **只传精简集**（样例已更新）。

### 5.1 保留

| 块 | 字段 | 备注 |
|----|------|------|
| **caseContext** | `caseId`, `userId`, `dpd`, `stage`, `product`, `dueDate`, `totalOutstanding`, `repaymentUrl`, `strategyTone`, `complaintFrozen`, `collectionStatus` | 必填集 |
| **basic** | `name`, `primaryPhone`, `email`, `language` | 必填集 |
| **device** | `jpushToken`, `phoneValidity` | 必填集 |
| **behavior** | `channelReachability`, `appLastActiveTime`, `lastEffectiveContactTime`, `lastEffectiveChannel`, `bestContactHour`, `preferredChannel` | **可选块**；Phase 1 SPI 可忽略；有数据即写入，供 Phase 2 条件 Email / 无互动 / FIRM |
| **contactHistory** | `todayTouchCount`, `channelTouchCounts`, `todayPhoneAnswered`, `currentPlanAiBotFailCount`, `stageEntryDate` | 必填集 |
| **元数据** | `snapshotTime`, `snapshotVersion` | |

> **修订（2026-06-09）**：`behavior` 从删除清单移回。Phase 1 不做互动/条件，原因是**担心数据未就绪**，非字段无用。

### 5.2 删除（相对原样例）

| 删除 | 理由 |
|------|------|
| **`risk` 整块**（含 score、sensitivityTag、ptpFulfillRate、complaintCount） | FIRM/冻结由 `strategyTone` + `complaintFrozen` 表达；评分分级 Phase 1 **不需要** |
| **`profileCompleteness`** | SPI 不消费 |
| **`userProfile.repayment.*`** | 金额 SSOT 已在 `caseContext`；重复且易矛盾 |
| **`work` / `contacts` 整块** | Phase 1 机器轨模板不用 |
| **`basic` 扩展字段** | 模板变量未使用 |
| **`device` 扩展**（model/os/viber/whatsapp） | 非 Phase 1 需求 |
| **`caseContext` 冗余金额拆分与运营字段** | `loanAmount`/`penaltyAmount`/`payCount` 等；模板只用 `totalOutstanding`+`dpd` |
| **`contactHistory.lastTouch*` / `ptp*` / `totalTouchCount`** | PTP 事实在接入层折算进 `strategyTone`；频控用 `today*` |

### 5.3 `strategyTone` 与删除 `risk` 的关系

编排 §6.2 难催条件（历史 S2+、PTP 履约率、并发逾期、无互动、PTP 过期）在 **接入层入案/标记变更时** 计算，结果写入 `caseContext.strategyTone`。SPI（PlanFactory）**只匹配 stage × tone**，无需在快照中重复传评分或中间事实。

```text
接入层: 读案件事实 + contactHistory 源数据 → 算 strategyTone / complaintFrozen → 写精简 snapshot
SPI:    只读 strategyTone，不读 risk.*
```

---

## 6. 回答 README 开放问题（定稿）

| # | 问题 | 定稿答复 |
|---|------|----------|
| 1 | PUSH device token 来源 | **`jpushToken`**，App → `t_user_equipment` → ProfileService；见 §3.1 |
| 2 | `targetAddress` 由谁定 | **StepResolver** 从快照填入 `StepCommand.targetAddress`；Gateway/Adapter **不再取号** |
| 3 | 手机号格式 | 快照统一 **E.164 `+63...`**；通知中心 `mobile` 可容错，Adapter 可再归一化 |
| 4 | work/risk 等是否不需要 | 消息渠道模板可不填；**结构保留**，PlanFactory/Guard 可能读取 |

---

## 7. 渠道侧最小必填（在 README 基础上扩展）

在同事原「SMS / PUSH」表之外，渠道侧建议 **README 增补** 如下：

### SMS（通知中心）

| 字段路径 | 用途 |
|----------|------|
| `userProfile.basic.primaryPhone` | `targetAddress` |
| `caseContext.dpd` / `stage` | 选 `scriptSlot`、渲染变量 |
| `caseContext.totalOutstanding` | 模板 `amount_due` |
| `caseContext.repaymentUrl` | 模板 `payment_link` |
| `userProfile.basic.name` | 模板 `borrower_name` |
| `userProfile.basic.language` | `metadata.language`（可默认 `en`） |

### App Push（通知中心 / JPush）

| 字段路径 | 用途 |
|----------|------|
| `userProfile.device.jpushToken` | `targetAddress`；空则 PushAdapter 同槽 fallback SMS |
| `caseContext.repaymentUrl` | `data.deep_link` |
| `metadata.title` / `body` | 由 Resolver 从 `scriptSlot` 渲染 |

### Email（SendGrid）

| 字段路径 | 用途 |
|----------|------|
| `userProfile.basic.email` | `targetAddress`；`null` → Guard SKIP |
| `caseContext.repaymentUrl` | 模板 `payment_link` |
| `caseContext.dpd` | 里程碑 scriptSlot / 变量 `overdue_days` |

---

## 8. 待主架构合并：README 修改清单

文件：`AI collection/相关资料/README_ContextSnapshot契约对齐.md`

| 位置 | 修改 |
|------|------|
| PUSH 最小必填表 | `fcmToken` → **`jpushToken`**；补充来源说明（§3.1） |
| SMS 最小必填表 | 增加 `caseContext.repaymentUrl`、`basic.language` |
| 新增 **Email** 最小必填小节 | `basic.email`、`repaymentUrl`（见 §7） |
| 开放问题 #1 | **删除**或改为「已决：jpushToken」 |
| 开放问题 #2–#4 | 替换为 §6 定稿答复 |
| 新增 **金额 SSOT** 小节 | 摘录 §3.2 |
| 新增 **targetAddress 分工** | StepResolver 负责，摘录 §6 #2 |

---

## 9. 待主架构合并：样例 JSON 修改清单

文件：`AI collection/相关资料/ContextSnapshot.sample.json`

| 操作 | 字段 |
|------|------|
| **改名** | `device.fcmToken` → `device.jpushToken`，示例值改为 JPush RID 形态 |
| **新增** | `caseContext.repaymentUrl`（示例 URL） |
| **新增** | `userProfile.basic.email`（示例邮箱） |
| **新增** | `userProfile.basic.language`（示例 `"en"`） |
| **建议新增** | `caseContext.strategyTone`、`complaintFrozen`（与 Java 一致，Mock 联调用） |
| **修正** | `repayment.penaltyFee` 与 `caseContext.penaltyAmount` 对齐，或 `penaltyFee` 标 `null` 并注释「非文案 SSOT」 |

**修订后样例片段（供直接替换）** 见 [附录 A](#附录-a修订后样例-json片段)。

---

## 10. 待主架构合并：领域模型文档修改清单

文件：`Intelligent-Collection-V1/docs/MOCASA催收系统升级_Phase1_领域模型与数据定义.md`

### 10.1 §3.1 CaseContext — 补字段

在 `activePlanId` 行后增加：

| 字段 | 类型 | 必填 | 说明 | 来源 |
|------|------|------|------|------|
| repaymentUrl | String | 否 | App 还款深链；SMS/Push/Email 模板变量 | ingestion / 信贷结账链路 |
| strategyTone | String | 否 | 编排强度 STANDARD / FIRM | 案件标签或数仓 |
| complaintFrozen | boolean | 否 | 争议冻结；true 时 Guard BLOCK 机器轨 | 案件事件 |
| collectionStatus | String | 否 | 催收生命周期；`CEASED` = D+91 停催 | 引擎/接入 |

并在 §3.1 末增加 **金额 SSOT 约定**（摘录 §3.2 本文）。

### 10.2 §3.2 BasicInfo — 补字段

| 字段 | 类型 | 必填 | 来源 |
|------|------|------|------|
| email | String | 否 | t_user_basis.email 或信贷用户表；Email 渠道 targetAddress |
| language | String | 否 | 用户语言偏好 ISO 639-1（`tl`/`en`）；默认 `en` |

### 10.3 §3.2 DeviceInfo — 已与渠道对齐

正文已含 `jpushToken` 与 ingestion 约定。**请删除**文中若仍出现的 `fcmToken` 表述（当前正文已无 `fcmToken`，保持即可）。

### 10.4 §3.2 RepaymentInfo — 补注释

在 RepaymentInfo 表末增加：

> **与 CaseContext 区分**：`remainingAmount` / `penaltyFee` 等为画像维度汇总，**不作为** SMS/Push/Email 对外文案变量的 SSOT。文案变量以 `caseContext.totalOutstanding`、`caseContext.penaltyAmount`、`caseContext.dpd` 为准。

### 10.5 §3.4 ContextSnapshot — 补交叉引用

在 §3.4 增加链接：渠道侧字段消费见 [本文档](./MOCASA催收系统升级_Phase1_ContextSnapshot契约对齐回复.md) §7。

---

## 11. 待主架构合并：`collection-common` Java

| 文件 | 修改 |
|------|------|
| `UserProfile.DeviceInfo` | `fcmToken` → **`jpushToken`**（与领域模型、通知中心一致） |
| `UserProfile.BasicInfo` | 增加 **`language`**（`email` 已有） |
| `CaseContext` | `repaymentUrl` 等已有，无需删改 |

改 Java 后请同步 Mock `MockProfileService` 测试 userId：**90002** 返回 `jpushToken`，**90004** 返回 `email`。

---

## 12. StepResolver 读快照约定（编排实现）

```
SMS   → targetAddress = basic.primaryPhone
        metadata.sms_body 渲染自 scriptSlot + caseContext(totalOutstanding, dpd, repaymentUrl) + basic(name, language)

PUSH  → targetAddress = device.jpushToken（空 → PushAdapter fallback SMS，仍一次 dispatch）
        metadata.title/body + data JSON 字符串（含 repaymentUrl 作 deep_link）

EMAIL → targetAddress = basic.email（null → Guard 不走到 Resolver 或 Resolver 标记 SKIP）
        dynamicTemplateData 含 repaymentUrl、borrower_name、amount_due、overdue_days
```

---

## 附录 A：Phase 1 精简样例（全量）

见 [`ContextSnapshot.sample.json`](../../../AI%20collection/相关资料/ContextSnapshot.sample.json)（`snapshotVersion: v1-phase1-slim`）。

字段数：原样例 ~50+ 业务字段 → **精简后 24 个**（不含元数据）。

---

## 附录 B：变更追踪

| 日期 | 变更 |
|------|------|
| 2026-06-09 | 渠道侧初版回复：jpushToken、repaymentUrl、email、language、金额 SSOT、README/样例/领域模型待合并清单 |
| 2026-06-09 | Phase 1 精简：删除 risk/repayment 等；`strategyTone` 预计算；样例与 README 已更新 |
| 2026-06-09 | `behavior` 改回**可选保留**（Phase 1 不消费，有数据即写入供 Phase 2） |

---

> 请将本文档结论合并进 `README_ContextSnapshot契约对齐.md` 与 `ContextSnapshot.sample.json` 后，在 `AI collection/相关资料/` 更新 `snapshotVersion` 或变更日志，并通知编排同事刷新 Mock 数据。
