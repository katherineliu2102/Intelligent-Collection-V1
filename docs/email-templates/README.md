# MOCASA Email 模板设计系统（Phase 1）

> **版本**: v1.5 · **日期**: 2026-06-10 · **文案**: v3 催收心理学优化  
> **状态**: ✅ Email HTML 17/17 草稿齐全；SendGrid **Phase 1 启用 5 封**  
> **适用范围**: Phase 1 **实际发信 5 封** + 里程碑 HTML 8 封备用 + 条件 Email 4 封（Phase 2）  
> **上游**: [渠道模板清单与配置 §3](../MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#3-emailsendgrid) · [渠道编排规格 §7.9](../MOCASA催收系统升级_Phase1_渠道编排规格.md#79-email-发送原则s0s4)

---

## 目录

- [1. 目录结构](#1-目录结构)
- [2. 催收叙事原则](#2-催收叙事原则)
- [3. Phase 1 决策](#3-phase-1-决策)
- [4. 设计规范](#4-设计规范)
- [5. milestone 文件头规范](#5-milestone-文件头规范)
- [6. SendGrid 建站 SOP](#6-sendgrid-建站-sop)
- [7. 落地状态](#7-落地状态)
- [8. 配置三层](#8-配置三层)

---

## 1. 目录结构

```
email-templates/
├── README.md                    # 本文件
├── subjects.md                  # Subject / Preheader SSOT
├── _layouts/                    # friendly / standard / formal / legal-letter / conditional
├── conditionals/                # 4 个条件 Email HTML ✅（Phase 2）
└── email-templates-test/        # SendGrid Test Data JSON
    ├── README.md
    ├── test-data-index.json
    └── test-data*.json
```

> **HTML 源码 SSOT**：13 个里程碑模板已统一移至 `collection-admin/src/main/resources/catalog/email-templates/`（代码仓 SSOT）。本目录不再保留 `milestones/` 副本，避免漂移。

**关联文档**：[渠道模板清单与配置](../MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) · [E2E 联调案例](./email-e2e-test-cases.md) · [策略迭代手册 §5.2](../MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md#52-改话术--邮件正文--深链)

---

## 2. 催收心理学矩阵

> **核心**：词汇与视觉必须**分阶段释放**，避免「狼来了」脱敏；S4 建立绝对压迫感，禁止「讨论/选项/结清方案」等软化措辞。

### 2.1 借款人心理阶段

| Stage | DPD | 心理 | 对外叙事 |
|-------|-----|------|----------|
| S0–S1 | D0–D+3 | 侥幸期 | 到期、**late fees**、**account escalation** |
| S2 | D+4–D+15 | 试探/讨价还价 | 账户升级、**直接解决窗口收窄**（陈述事实，不虚构减免政策） |
| S3 | D+16–D+30 | 脱敏前夜 | **Legal review**、**credit reporting warning**；**禁止**提外部移交 |
| S4 早期 | D+31–D+60 | 恐慌前夜 | **MOCASA 正式催收**（内部继续跟催）；禁 third-party |
| S4 末期 | D+75 | 最后施压 | `{{assignment_date}}` **final delinquency review**；征信/未来借贷受限；**禁 third-party / 停催** |

### 2.2 词汇阶段限制

| 可用 | S1/S2 | S3 | S4 (D+31+) |
|------|-------|-----|------------|
| Account escalation / Late fees | ✅ | ✅ | ✅ |
| Legal review | ❌ | ✅ | ✅ |
| Credit reporting / bureau | ❌ | ✅ | ✅ |
| Intensified contact（SMS / Call / Email） | S2 末 | ✅ | ✅ |
| Third-party / external agency | ❌ | **❌** | **❌ 全程禁止** |
| Discuss / Options / Settlement | S2 Offer 区 | 可保留直接窗口 | **❌ 全文禁止** |

### 2.4 后果表述原则

**只写 MOCASA 实际会执行的后果**（合规 + 威慑可信）：

| ✅ 可写 | ❌ 勿写（若无此能力） |
|--------|---------------------|
| Late fees、account escalation | Hardship Program / 虚构减免 |
| SMS / Call / Email 多渠道跟进 | Field visit / 上门外访 |
| Legal review、credit bureau reporting | 停催、collection will cease |
| S4 D+31–60 MOCASA 正式催收 | third-party / external agency |
| S4 D+75 final delinquency review（`assignment_date`） | 停催、collection will cease / no further contact |

---

### 2.3 内部事实 vs 对外叙事

| 内部事实（不对客说） | 对外叙事（Email 应说） |
|--------------------|------------------------|
| D+91 停止 in-house **主动触达** | **Final delinquency review**；余额**仍全额法定欠款**；征信/未来借贷受限 |
| Write-off / Debt sale | **征信上报**、未来借贷/就业受限 |
| 实际无委外 | **Never** 写 third-party / external agency |
| 「我们不催了」 | **Never** — 禁止 `collection will cease` / `no further contact` |

**禁用表述**：`collection will cease` / `we will stop contacting you` / `no further action` / `third-party` / `external agency` / `field visit`

---

## 3. Phase 1 决策

| 项 | 决策 | 状态 |
|----|------|------|
| **发信频率** | Phase 1 **仅 5 封**（D0 / D+1 / D+4 / D+31 / D+75）；其余 HTML 保留不发，控频降封号风险 | ✅ |
| **发件人** | `collections@mocasa.com` | ✅ |
| **语言** | 仅英文；Tagalog 走 Phase 2 独立 `*_TL` scriptSlot | ✅ |
| **Offer（S2+）** | Phase 1 **无**减免/分期政策；S2 底部仅陈述「直接还款窗口收窄」，不虚构 Hardship Program；`offer_amount` Phase 2 | ✅ |
| **scriptSlot** | 13 独立 slot = SendGrid Template Name；**Nacos 仅映射 5 个活跃** | ✅ |
| **Subject** | SendGrid Settings；SSOT 见 `subjects.md` | ✅ |
| **S4_PRE_CLOSE 变量** | `assignment_date` = 对外 **final review 截止日**（= 内部 D+91）；**非委外** | ✅ |

---

## 4. 设计规范

### 4.1 版式与 Stage

| Layout | Stage | 视觉 | 语气 |
|--------|-------|------|------|
| `friendly` | S0 | 蓝顶条 `#3B82F6` | 友好到期 |
| `standard` | S1–S2 | 橙顶条 `#F97316` | 账户升级 / late fees |
| `formal` | S3 | 红顶条 `#DC2626` | 法务审核 / 征信警告 |
| `legal-letter` | S4 D+31–D+60 | **纯黑白**，无彩色 UI | 律师函；禁止软化模块 |
| `formal` + 深红区块 | S4 D+75 | 黑白底 + `#7F1D1D` 倒计时 | final delinquency review |
| `conditional` | Phase 2 | 灰左边条 | 短跟进 |

### 4.2 变量契约

| 变量 | 说明 |
|------|------|
| `borrower_name`, `amount_due`, `overdue_days`, `payment_link` | 所有里程碑 |
| `assignment_date` | 仅 `S4_EMAIL_PRE_CLOSE`（final review 截止日，= 内部 D+91） |

---

## 5. milestone 文件头规范

每个里程碑 HTML（位于 `collection-admin/src/main/resources/catalog/email-templates/`）顶部 HTML 注释含：`scriptSlot`、`stage`、`dpd`、`subject`、`variables`、`sendgrid`。

---

## 6. SendGrid 建站 SOP

1. Template Name = `scriptSlot`
2. Code 粘贴 `collection-admin/src/main/resources/catalog/email-templates/`（里程碑）或本目录 `conditionals/`（Phase 2）HTML
3. **Settings** 填 Subject / Preheader（`subjects.md`）
4. **Test Data** 粘贴 [`email-templates-test/`](./email-templates-test/README.md) 对应 JSON
5. Activate → Nacos `channel.sendgrid.templates.{scriptSlot}`

| 场景 | 要求 |
|------|------|
| 联调收件箱 | **优先 `wzynju@126.com`**（92002 / 93101 / 93201 / 93401 / 93404） |
| Gmail | 易 DMARC 拦截，非默认测试邮箱 |

---

## 7. 落地状态

| 批次 | HTML | SendGrid 建站 |
|------|------|---------------|
| S0 | ✅ | ✅ 已联调 |
| S1–S4 里程碑 | ✅ | 待运营 |
| 条件 Email ×4 | ✅ | Phase 2 |
| S4_PRE_CLOSE | ✅ · 法务审 | 待运营 |

---

## 8. 配置三层

| 层 | 位置 |
|----|------|
| HTML 源码（里程碑） | `collection-admin/src/main/resources/catalog/email-templates/`（SSOT） |
| HTML 源码（布局/条件） | `docs/email-templates/_layouts/` · `docs/email-templates/conditionals/` |
| 线上模板 | SendGrid `d-xxx` |
| 映射 | Nacos `channel.sendgrid.templates` |

`.env` 仅放 `NACOS_*`；SendGrid 密钥在 Nacos `channel.sendgrid.api-key` / `from-email`。
