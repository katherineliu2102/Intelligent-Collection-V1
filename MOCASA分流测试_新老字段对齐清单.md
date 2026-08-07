# MOCASA 催收系统升级 Phase1 — 分流测试 · 新老字段对齐清单

> **用途**：与数据 / 接入同事对齐，确保"老系统分流一部分数据给新系统"的测试可推进。
> **日期**：2026-08-07
> **范围**：老系统 4 类 Pub/Sub 推送 → 新系统 `ContextSnapshot` / 事件 / 4 渠道（SMS·PUSH·EMAIL·AI_CALL）
> **状态**：草案，待数据 / 运维同事确认第 §7 项

---

## 0. 结论速览（先给结论）

1. **4 个渠道字段基本完整**：唯一硬缺口是 `repaymentUrl`（配置常量，由 ingestion 注入，**老系统无需提供**）。
2. **复杂度主要来自"三消息关联"**：`case_push` / `case_info_push` / `case_user_info_push` 三类推送字段碎片化，须按 `loanId` 关联缓冲后才能组装完整 `CASE_INGESTED`。测试期可走"薄 payload + 新系统自补全旧库"的轻量路线（见 §4）。
3. **老推送存在字段名大小写不一致、金额/邮箱口径异常**，必须在 ingestion 做兼容与防御（见 §5）。
4. **老系统有大量字段新系统 Phase1 不消费**（用户画像、借款申请、还款明细冗余），见 §2。

---

## 1. 老系统 4 类推送 · 字段概览

| dataType | 承载内容 | 关键字段（节选） |
|---|---|---|
| `case_push` | 案件主信息 | `loanId` / `userId` / `appName` / `realName` / `phone` / `repaymentDate` / `overdueDays` / `fullRepayTime` / `principal` / `interest` / `overdue` / `STATUS` / `payCount` / `email`(多空) |
| `case_info_push` | 还款计划 / 案件明细 | `loanID` / `userID` / `appName` / `planJson[]`(含 `principal`/`interest`/`penaltyInterest`/`dueDate`/`status`) / `overdue_days` / `phone` / `realName` / `email` |
| `case_user_info_push` | 用户画像 / 设备 | `userID` / `loanID` / `appName` / `realName` / `phone` / `jpushToken` / `email` / `contactInfo[]`(紧急联系人) |
| `repayment_push_and_load` | 还款事件 | `referenceId` / `userId` / `loanId` / `appName` / `fullRepayTime` / `repaymentTime` / `STATUS` / `RepaymentType` / `currentAmmout` |

> ⚠️ **字段名大小写不一致**（见 §5）：`loanId`(case_push) vs `loanID`(另两类)；`userId` vs `userID`；`STATUS` vs `status`。

---

## 2. 老系统有、新系统 Phase1 不消费的字段（冗余字段）

> 下列字段老系统推送里有，但新系统 Phase1 不消费（契约 §76：用户画像扩展字段 Phase 2 预留、返回 null；申请/还款明细类渠道执行不需要）。列此清单避免数据同事误以为"缺字段"。

**A. 用户画像类（Phase 2 预留，Phase 1 返回 null）**
`dateBirth` / `sex` / `idCard`(+`idCardFrontPicUrl`/`idCardBackPicUrl`) / `panNumber` / `panPicUrl` / `selfieUrl` / `education` / `maritalStatus` / `liveCity` / `liveDetailAddress` / `workTelephone` / `companyName` / `occupation` / `imcomePicUrl` / `monthIncomeRange` / `personalEmail` / `frequentlyUsedMobile` / `facebookAccount` / `equipmentBrand` / `equipmentMode` / `contactInfo`(紧急联系人) / `appPackageName`

**B. 借款申请类（入案执行不需要）**
`applyAmount` / `applyTime` / `deadline` / `disburseAmount` / `disburseDate` / `reconciliationAmount`

**C. 还款明细类（全额结清走 `REPAYMENT_RECEIVED`；部分还款从 `currentAmmout` 刷新余额）**
`principal` / `interest` / `overdue` / `realityPrincipal`/`realityInterest`/`realityOverdue` / `currentRealityPrincipal`/`currentRealityInterest`/`currentRealityOverdue` / `remark` / `RepaymentType`

---

## 3. 新系统所需字段总表（按来源分类）

分类图例（**带「计算」即新系统需重新加工/派生**）：
- **已有-直接**：老推送直接有，直接映射。
- **已有-计算**：老推送有原始值，需加工（聚合 / 格式化 / 枚举映射）。
- **新增-计算**：老推送无，由 `dpd` / 其他字段派生。
- **新增-配置**：部署配置常量，不进推送。
- **新增-系统**：运行时由系统（Redis / 频控）组装，非用户数据。
- **新增-旧库**：老推送无，需读旧库 / 数仓（测试期可用近似派生）。

### A. caseContext（案件上下文）

| 新字段 | 类型 | 分类 | 老系统来源字段 | 取值逻辑 | 示例 |
|---|---|---|---|---|---|
| `caseId` | String | 已有-直接 | `case_push.loanId` / `case_info_push.loanID` / `case_user_info_push.loanID` | 直接映射（注意大小写） | `"2004151"` |
| `userId` | String | 已有-直接 | `case_push.userId` / `case_info_push.userID` / `case_user_info_push.userID` | 直接映射 | `"435408"` |
| `stage` | String | **新增-计算** | 无 | 按 `dpd` 计算：S0(<4)/S1(4-10)/S2(11-30)/S3(31-60)/S4(61-90)/STOP(≥91) | `"S1"` |
| `dpd` | Integer | 已有-直接 | `case_push.overdueDays` / `case_info_push.overdue_days` | 取其一（建议 `case_push.overdueDays`） | `1167` |
| `product` | String | **新增-计算** | `case_push.appName` / `case_info_push.appName` | `appName→product` 映射（§8） | `"MOCASA"` |
| `overdueAmount` | BigDecimal | **已有-计算** | `case_info_push.planJson[].{principal+interest}` | 各期本金+利息之和；**planJson 为 SSOT**（case_push 顶层 principal 有 0 异常，禁用） | `400.00` |
| `penaltyAmount` | BigDecimal | **已有-计算** | `case_info_push.planJson[].penaltyInterest` | 各期罚息之和 | `0.00` |
| `totalOutstanding` | BigDecimal | **新增-计算** | 上述两字段 | `= overdueAmount + penaltyAmount` | `400.00` |
| `dueDate` | LocalDate | **已有-计算** | `case_info_push.planJson[].dueDate`（首期） | 取最早未还期；`case_push.repaymentDate` 作兜底 | `"2026-08-10"` |
| `caseStatus` | String | **已有-映射** | `case_push.STATUS` / `planJson.status` / `repayment.STATUS` | 枚举映射 `-1/0/1 → 业务状态`（§5） | `"OVERDUE"` |
| `isFirstLoan` | boolean | **新增-旧库** | 无直接字段 | 判定=同 `userId` 仅一笔贷款。测试期可用 `payCount==1` 近似 | `false` |
| `payCount` | int | 已有-直接 | `case_push.payCount` / `case_info_push.payCount` / `case_user_info_push.payCount` | 直接映射 | `4` |
| `maxDpd` | Integer | **新增-计算** | 无 | `CASE_CEASED` 用，默认 91；或旧库历史最大 dpd | `91` |
| `repaymentUrl` | String | 新增-配置 | 无（信贷结账链路） | 固定深链配置注入，可选拼 `?loanId={caseId}` | `https://mocasa.ph/repay` |

### B. userProfile.basic

| 新字段 | 类型 | 分类 | 老系统来源字段 | 取值逻辑 | 示例 |
|---|---|---|---|---|---|
| `name` | String | 已有-直接 | `realName`（三类均有） | 直接映射 | `"Norman warren garcia sison"` |
| `primaryPhone` | String | **已有-计算** | `case_push.phone` / `case_info_push.phone` / `case_user_info_push.phone` | 转 **E.164**：`9690059710 → +639690059710` | `"+639690059710"` |
| `email` | String | 已有-直接 | `case_user_info_push.email` / `case_info_push.email` | 取非空值（**case_push.email 多为空**，以另两类为准） | `"catalbasandrew@gmail.com"` |
| `language` | String | 新增-配置 | 无 | Phase1 固定 `en`（配置常量） | `"en"` |
| `alternatePhones` | List<String> | 已有-可选 | `case_user_info_push.contactInfo[].phone` | 紧急联系人；Phase1 渠道不触达，可留 | `["+639266282770"]` |
| `phoneValidity` | String/enum | 新增-计算 | 无 | 号码有效性校验（可选块），不阻塞 | `"VALID"` |

### C. userProfile.device

| 新字段 | 类型 | 分类 | 老系统来源字段 | 取值逻辑 | 示例 |
|---|---|---|---|---|---|
| `jpushToken` | String | 已有-仅一处 | `case_user_info_push.jpushToken` | ⚠️ **仅此类有**；契约称 case_push 也带（2026-07 确认），但样本 case_push **无此字段**——需运维确认（§7-1） | `"191e35f7e164f2de9ad"` |

### D. contactHistory（频控，非用户数据）

| 新字段 | 类型 | 分类 | 来源 | 取值逻辑 |
|---|---|---|---|---|
| `todayTouchCount` | Integer | 新增-系统 | 无 | ingestion 从 Redis / 运行时频控计数组装 |
| `channelTouchCounts` | Map | 新增-系统 | 无 | 同上，按渠道计数 |

### E. 事件级 & AI_CALL 系统配置

| 字段 | 分类 | 来源 | 取值逻辑 |
|---|---|---|---|
| `CASE_INGESTED` | 组合 | `caseId + stage + 快照` | 案件入案主事件 |
| `STAGE_CHANGED` | **新增-计算** | `dpd` | 引擎按 dpd 重算 stage |
| `REPAYMENT_RECEIVED` | 已有-直接 | `repayment_push_and_load.{userId, fullRepayTime, STATUS}` | `fullRepayTime` 非空或 `STATUS=4` 才发布；按 `userId` 取消活跃计划 |
| `CASE_BALANCE_UPDATED` | 已有-直接 | `repayment_push_and_load.{userId, loanId, currentAmmout, STATUS}` | 未结清且 `currentAmmout` 有效非负时发布；刷新对应案件余额快照 |
| `CASE_CEASED` | **新增-计算** | `maxDpd` | D+91 停催（mock / 日切） |
| `callbackUrl` / `timeoutMinutes` / `scriptSlot`(AI_CALL) | 新增-配置 | 无 | 系统配置，与数据无关 |

---

## 4. ingestion 适配层 · 映射伪代码（参考）

```text
# 4 类推送 → 新系统事件
# 案件三类推送按 loanId 关联；还款推送独立

# ---- 案件入案（决策 B 薄 payload 路线，测试期推荐）----
on case_push / case_info_push / case_user_info_push(msg):
    loanId = normalizeLoanId(msg.loanId ?? msg.loanID)   # 兼容大小写
    buffer[loanId].merge(msg)                            # 三消息累加
    if buffer[loanId] has all 3 types OR timeout:
        push CASE_INGESTED {
            caseId: loanId,
            stage:  calcStage(buffer.dpd),               # 由 dpd 派生
            snapshot: buildSnapshot(buffer, config)      # 见下；其余读旧库补全
        }

buildSnapshot(buf, config):
    # 直接来自推送
    caseId      = buf.loanId
    userId      = buf.userId
    dpd         = buf.overdueDays ?? buf.overdue_days
    name        = buf.realName
    primaryPhone= toE164(buf.phone)                       # +63 归一化
    email       = firstNonEmpty(buf.email)               # case_user_info_push 优先
    jpushToken  = buf.jpushToken                          # 仅 case_user_info_push
    payCount    = buf.payCount
    product     = config.appNameToProduct[buf.appName]    # mocasa→MOCASA
    # 金额以 planJson 为 SSOT（避免 case_push 顶层 principal=0 异常）
    plan        = parse(buf.planJson)
    overdueAmount   = sum(plan.principal + plan.interest)
    penaltyAmount    = sum(plan.penaltyInterest)
    totalOutstanding = overdueAmount + penaltyAmount
    dueDate     = min(plan.dueDate where not paid)
    caseStatus  = mapStatus(buf.STATUS ?? plan.status)    # -1/0/1 → 枚举
    isFirstLoan = (buf.payCount == 1)                    # 测试期近似
    repaymentUrl= config.repayUrl[product] + "?loanId=" + caseId   # 配置注入
    language    = "en"                                   # 固定
    # 测试期可选自补全（决策 B 降级口子）：读旧库 t_user_repayment_plan / t_collection 兜底缺失字段
    return snapshot

# ---- 还款事件 ----
on repayment_push_and_load(msg):
    if msg.fullRepayTime is not empty or msg.STATUS == 4:
        push REPAYMENT_RECEIVED { userId: msg.userId }
    else if msg.loanId exists and msg.currentAmmout >= 0:
        push CASE_BALANCE_UPDATED {
            caseId: msg.loanId,
            userId: msg.userId,
            totalOutstanding: msg.currentAmmout
        }

# ---- 停催（日切 / mock）----
daily_job(): push CASE_CEASED { caseId, maxDpd: 91 }
```

> 说明：若走"完全决策 B（不读旧库、全量快照进 payload）"，则 `buildSnapshot` 不允许读旧库，三消息必须齐了才发事件，且要处理乱序 / 迟到 / 缺类。测试期推荐上面"薄 payload + 旧库自补全"轻量路线，跑稳后再演进。

---

## 5. 字段名大小写不一致清单（必须兼容，否则解析失败）

| 概念 | case_push | case_info_push | case_user_info_push | 处理 |
|---|---|---|---|---|
| 案件号 | `loanId`（小 d） | `loanID`（大 D） | `loanID`（大 D） | ingestion 兼容两种 key |
| 用户号 | `userId` | `userID`（大 D） | `userID`（大 D） | 同上 |
| 状态 | `STATUS`（全大） | `planJson.status`（全小） | — | 枚举映射统一 |
| 逾期天数 | `overdueDays` | `overdue_days`（下划线） | — | 取其一 |

---

## 6. 4 渠道完整性判定

| 渠道 | 结论 | 缺口 |
|---|---|---|
| **SMS** | ✅ 完整 | 仅 `repaymentUrl`（配置注入即补） |
| **EMAIL** | ✅ 完整 | 仅 `repaymentUrl` |
| **PUSH** | ✅ 基本完整 | `repaymentUrl` + `jpushToken` 来源待确认（缺失则 fallback SMS） |
| **AI_CALL** | ✅ 完整 | `repaymentUrl` + 系统配置项（callbackUrl 等，属部署配置） |

**结论：4 渠道字段对老系统数据基本完整。唯一硬缺口 `repaymentUrl` 是配置常量（零代码、不进推送）；`jpushToken` 来源歧义是唯一需运维拍板的点。**

---

## 7. 待数据 / 运维同事确认清单（action items）

1. **jpushToken 来源**：契约写"case_push 消息体携带（2026-07 运维确认）"，但**样本里 case_push 没有该字段，只在 case_user_info_push 出现**。请运维确认上游真相，否则 PUSH 只能全 fallback SMS。
2. **字段名大小写不一致**（§5）：`loanId`/`loanID`、`userId`/`userID`、`STATUS`/`status`、`overdueDays`/`overdue_days` 需统一或 ingestion 兼容。
3. **金额口径**：`case_push` 顶层 `principal` 出现 0（疑似核销 / 异常），金额 SSOT 以 `case_info_push.planJson` 为准，并加空值防御。
4. **`email` 口径**：`case_push.email` 多为空，以 `case_user_info_push` / `case_info_push` 为准。
5. **手机号格式**：老数据无 `+63` 前缀，ingestion 需归一化为 E.164（契约 §75 已定）。
6. **`appName→product→brand` 映射**：老数据只有 `appName`（mocasa / QuickLoan），需先定映射配置（§8），否则模板品牌 / 统计会偏。

---

## 8. 附录：appName → product → brand 映射建议

| appName | product | brand（模板品牌） | 还款深链（示例，配置常量） |
|---|---|---|---|
| `mocasa` | `MOCASA` | `MOCASA` | `https://mocasa.ph/repay` |
| `QuickLoan` | `SKYPAYLOANS` | `SKYPAYLOANS` | `https://skypayloans.ph/repay` |

> 映射表为配置项，由数据 / 业务确认后写入 ingestion 配置。

---

## 9. 参考文档

- `contracts/README_ContextSnapshot契约对齐.md`（各渠道最小必填字段 SSOT）
- `MOCASA催收系统升级_Phase1_领域模型与数据定义.md`（§4 ContextSnapshot / §6.2 事件 payload）
- `channel/MOCASA催收系统升级_Phase1_渠道编排规格.md`（§1 Stage×渠道 / §5.3 模板变量 / §6 Tone）
- `MOCASA催收系统升级_Phase1_核心引擎规格.md`（§2.1 事件 / §4.2 决策 B）
- 老系统样本：`D:\AI\AI collection\数据类型.txt`
