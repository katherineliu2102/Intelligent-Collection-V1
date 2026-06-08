# Email SendGrid Test Data

> **位置**：`docs/email-templates/email-templates-test/`（Email 模板子目录）  
> **用途**：SendGrid Dynamic Template 编辑器 **Test Data** 面板 Mock JSON  
> **联调收件箱**：优先 **`wzynju@126.com`**（92001/92002）

---

## 目录

- [索引](#索引)
- [用法](#用法)
- [文件说明](#文件说明)
- [关联文档](#关联文档)

---

## 索引

见 [`test-data-index.json`](./test-data-index.json)：`scriptSlot` → 文件名。

---

## 用法

1. SendGrid → 编辑模板 → **Test Data**
2. 打开本目录下对应 JSON，全选粘贴
3. 预览检查 `{{borrower_name}}`、`₱ {{amount_due}}` 等变量渲染

---

## 文件说明

| 前缀 | 含义 |
|------|------|
| `test-data.sample.json` | S0 D0 |
| `test-data-s1-d*.json` | S1 里程碑 |
| `test-data-s2-d*.json` | S2 里程碑 |
| `test-data-s3-d*.json` | S3 里程碑 |
| `test-data-s4-d*.json` | S4（`d75` 含 `assignment_date`） |
| `test-data-conditional-s*.json` | Phase 2 条件 Email |

---

## 关联文档

| 文档 | 说明 |
|------|------|
| [../README.md](../README.md) | Email 设计系统、催收叙事原则 |
| [../subjects.md](../subjects.md) | Subject / Preheader SSOT |
| [../../MOCASA催收系统升级_Phase1_渠道模板清单与配置.md](../../MOCASA催收系统升级_Phase1_渠道模板清单与配置.md) | 全渠道模板 SSOT |
