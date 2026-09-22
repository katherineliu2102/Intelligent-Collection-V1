# Phase 1 · FIRM 渠道测试用例表

> **版本**：2026-09-01  
> **前置条件**：[数仓难催字段需求](../数仓_caseEvent难催字段补充需求.md) 上线 + 催收 ingestion 接入 `strategyTone` + tone 硬化时 cancel/rebuild plan  
> **关联**：e2e200 抽样说明 · [渠道编排规格 §6/§7](../channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)

---

## 1. 测试目标

验证 **FIRM 用户**在各渠道的派发链路：

| 渠道 | Phase 1 FIRM 预期 | 测试重点 |
|---|---|---|
| **SMS** | S2+ 使用 `*_SMS_FIRM` 槽位 | **主验** |
| **Push** | 仍 `*_PUSH_STANDARD`（无 FIRM 变体） | 验「不变」 |
| **Email** | 仍里程碑模板（`S2_EMAIL_ENTRY` 等） | 验「不变」 |
| **AI Call** | 同 STANDARD 时间表；FIRM 脚本 Phase 2 | 验「能打通」 |

**Plan 骨架**：FIRM 与 STANDARD **同 Stage 共用 dayBlocks**（时间表、槽位、AI 次数相同）；差异仅在 SMS `script_slot`。

---

## 2. 测试户分层（BQ 2026-09-01 核验）

### 2.1 P0 · S2 金样本（必测，08:00 SMS FIRM）

| loan_id | user_id | 桶 | 9/1 dpd | Stage | 触发路径 | 说明 |
|---------|---------|-----|---------|-------|----------|------|
| **530684** | 3319542 | S2_D+4 | 7 | S2 | A（历史 loan 513680） | **S2 FIRM 主样本** |
| **531245** | 3260468 | S1_D+2 | 5 | S2 | A（历史 loan 365986） | **S2 FIRM 主样本** |

> 两户须保持在 Pilot 投影内，**不得 MANUAL_CLEANUP**。

### 2.2 P1 · S3/S4 FIRM 样本（扩测 SMS FIRM）

| loan_id | 桶 | 9/1 dpd | Stage | 触发路径 |
|---------|-----|---------|-------|----------|
| 500583 | S3_D+16 | 80 | S4 | B 复发 |
| 504728 | S2_D+7 | 71 | S4 | B 复发 |
| 505644 | S2_D+4 | 38 | S4 | B 复发 |
| 506516 | S1_D+2 | 66 | S4 | B 复发 |
| 506532 | S1_D+2 | 66 | S4 | B 复发 |
| 507001 | S4_D+31 | 65 | S4 | B 复发 |
| 518005 | S2_D+7 | 41 | S4 | B 复发 |
| 519633 | S1_D+2 | 36 | S4 | B 复发 |

### 2.3 P2 · 停催区（仅字段对账，不测触达）

| loan_id | 说明 |
|---------|------|
| 463054 | D91+，`ever=true`，已停催，无活跃 plan |
| 470966 | D91+ |
| 487237 | D91+，路径 A |
| 493332 | D91+，路径 B |

### 2.4 N0 · 负样本（必须仍为 STANDARD）

| loan_id | 桶 | 场景 | 预期 ever | 预期 tone |
|---------|-----|------|-----------|-----------|
| 505611 | S2_D+4 | 本笔首次进 S2 | false | STANDARD |
| 530569 | S2_D+4 | 本笔首次进 S2 | false | STANDARD |
| 507300 | S0_D-2 | S0 段（有历史但非 S2+） | true* | **STANDARD**（S0 禁止 FIRM） |
| 531858 | S0_D0 | 同上 | true* | **STANDARD** |

\* `everReachedS2Overdue=true` 但 Stage 非 S2+，验证「硬化条件不生效」。

---

## 3. 用例明细

### TC-FIRM-01 · 入案写入 strategyTone（P0）

| 项 | 内容 |
|---|---|
| 前置 | 数仓 `caseEvent` 含 `everReachedS2Overdue=true` |
| 操作 | 投递 / 等待日切 `530684` |
| 断言 | `t_contact_plan.context_snapshot` → `$.strategyTone = "FIRM"` |
| 断言 | `t_ai_collection` 投影存在且 `stage=S2` |

### TC-FIRM-02 · S2 08:00 SMS 使用 FIRM 槽位（P0）

| 项 | 内容 |
|---|---|
| 样本 | `530684` 或 `531245` |
| 操作 | 等待或触发 08:00 SMS 槽 |
| 断言 | `t_contact_timeline.script_slot = 'S2_SMS_FIRM'` |
| 断言 | `template_version` 对应 DB `t_script_template` id=103 |
| 断言 | **非** `S2_SMS_STANDARD` |
| 备注 | timeline 不存正文；可用 L2 mock 或通知中心回执侧验证 delinquent 句 |

### TC-FIRM-03 · S2 12:00 Push 仍为 STANDARD（P0）

| 项 | 内容 |
|---|---|
| 样本 | `530684` |
| 操作 | 12:00 Push 槽 |
| 断言 | `script_slot = 'S2_PUSH_STANDARD'` |
| 断言 | **非** `*_FIRM` |

### TC-FIRM-04 · S2 14:00 Email 仍为里程碑模板（P0）

| 项 | 内容 |
|---|---|
| 样本 | `530684`（dpd=4 当日或已过重置后下一 D+4 里程碑） |
| 断言 | `script_slot = 'S2_EMAIL_ENTRY'` |
| 断言 | SendGrid `d-86ed8faae3b24489ad7db8a11067b8c4` |

### TC-FIRM-05 · S2 AI Call 打通（P1）

| 项 | 内容 |
|---|---|
| 样本 | `530684` |
| 操作 | 09:15 / 14:30 AI 槽 |
| 断言 | timeline 有 `AI_CALL` 步骤且非 SKIPPED（无争议/冻结） |
| 断言 | Phase 1 **不验** FIRM 脚本差异（代码未传 tone） |

### TC-FIRM-06 · S4 SMS FIRM 槽位（P1）

| 项 | 内容 |
|---|---|
| 样本 | `505644`（dpd=38, S4） |
| 操作 | 08:00 SMS |
| 断言 | `script_slot = 'S4_SMS_FIRM'` |

### TC-FIRM-07 · 负样本首次波次仍 STANDARD（N0）

| 项 | 内容 |
|---|---|
| 样本 | `505611`（本笔首次 S2, dpd=4） |
| 断言 | `context_snapshot.strategyTone = "STANDARD"` |
| 断言 | 08:00 SMS → `S2_SMS_STANDARD` |

### TC-FIRM-08 · S0 有历史仍禁止 FIRM（N0）

| 项 | 内容 |
|---|---|
| 样本 | `507300`（ever=true, stage=S0） |
| 断言 | `strategyTone = "STANDARD"` |
| 断言 | Push 槽 `S0_REMINDER`，无 `_FIRM` |

### TC-FIRM-09 · tone 硬化触发 plan 重建（P0）

| 项 | 内容 |
|---|---|
| 场景 | 户先以 STANDARD 建 plan，次日数仓标 ever=true |
| 操作 | 新 `caseEvent` 入库且 `caseVersion` 变化 |
| 断言 | 旧 plan → `PLAN_CANCELLED` |
| 断言 | 新 plan `context_snapshot.strategyTone = "FIRM"` |
| 断言 | 下一 SMS 槽为 `*_SMS_FIRM` |

### TC-FIRM-10 · tone 只硬化不回退（P1）

| 项 | 内容 |
|---|---|
| 场景 | FIRM 户还款后 max_dpd 下降、Stage 回 S1 |
| 断言 | `strategyTone` **仍为 FIRM**（规格 §6.3） |
| 断言 | S1 槽位无 FIRM，实际 SMS 为 `S1_SMS_STANDARD` |

---

## 4. 各 Stage × 渠道预期矩阵（FIRM 户）

| Stage | 时间 | SMS script_slot | Push | Email | AI |
|-------|------|-----------------|------|-------|-----|
| S0 | 08:00 | `S0_REMINDER*` | 同左 | — | — |
| S1 | 08:00 | `S1_SMS_STANDARD` | `S1_PUSH_STANDARD` | D+1 里程碑 | 2×/日 |
| **S2** | 08:00 | **`S2_SMS_FIRM`** | `S2_PUSH_STANDARD` | D+4 `S2_EMAIL_ENTRY` | 2×/日 |
| **S3** | 08:00 | **`S3_SMS_FIRM`** | `S3_PUSH_STANDARD` | 不发 | 2×/日 |
| **S4** | 08:00 | **`S4_SMS_FIRM`** | `S4_PUSH_STANDARD` | D+31/D+75 里程碑 | D+31~60: 2×；D+61~90: 1× |

\* FIRM 户在 S0/S1 段时尚未硬化或硬化不生效，均为 STANDARD 槽位。

---

## 5. 验收 SQL（Pilot 库）

```sql
-- 1) 当前 FIRM 计划快照
SELECT case_id, plan_id, status,
       JSON_UNQUOTE(JSON_EXTRACT(context_snapshot, '$.strategyTone')) AS tone,
       JSON_UNQUOTE(JSON_EXTRACT(context_snapshot, '$.stage')) AS stage
FROM t_contact_plan
WHERE case_id IN (530684, 531245, 505611)
  AND status IN ('ACTIVE', 'PLAN_EXHAUSTED')
ORDER BY case_id, created_at DESC;

-- 2) SMS 槽位是否 FIRM
SELECT case_id, channel, script_slot, status, scheduled_at
FROM t_contact_timeline
WHERE case_id IN (530684, 531245, 505644, 505611)
  AND channel = 'SMS'
  AND DATE(scheduled_at) >= CURDATE()
ORDER BY case_id, scheduled_at;

-- 3) 全量 FIRM 命中统计
SELECT COUNT(*) AS firm_plans
FROM t_contact_plan
WHERE status = 'ACTIVE'
  AND JSON_UNQUOTE(JSON_EXTRACT(context_snapshot, '$.strategyTone')) = 'FIRM';
```

---

## 6. 执行顺序建议

```text
Day 0  数仓 4 字段上线 → 金样本对账（§8.2）
Day 1  催收 ingestion 上线 → TC-FIRM-01
Day 2  盯 08:00 → TC-FIRM-02/03/04（P0 两户）
Day 3  扩测 P1 S4 户 → TC-FIRM-06
Day 4  负样本 → TC-FIRM-07/08
       硬化重建 → TC-FIRM-09/10
```

---

## 7. 通过标准

| 级别 | 条件 |
|---|---|
| **最低通过** | P0 全绿（TC-01~04、09）+ N0 负样本（TC-07） |
| **完整通过** | P0 + P1 SMS 全绿 + Push/Email「不变」断言 |
| **不阻塞上线** | AI FIRM 脚本差异（TC-05 仅验打通） |

---

## 8. 风险与规避

| 风险 | 规避 |
|---|---|
| S2 金样本仅 2 户 | 保留 `530684`/`531245`；可选数仓定向补 3 户 dpd 4–7 + userEverReachedS2 |
| 硬化当日 plan 未重建 | 必须先过 TC-FIRM-09 再测 SMS |
| 11 户 B 路径（三期复发）业务争议 | 与产品确认后改规格；测试按现行规格执行 |
| D91 户误纳入 | P2 仅做对账，不断言触达 |
