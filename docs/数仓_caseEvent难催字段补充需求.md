# caseEvent 难催字段补充需求

> **版本**：2026-09-01 · **读者**：数仓 / Publisher  
> **规格**：[§6.3.1](./channel/MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径ingestion-层) · **测试**：[FIRM 用例表](./testing/FIRM渠道测试用例表.md)

在每日 `caseEvent`（03:00 PHT 前、与 `dpd` 同批）的 `data` 顶层新增 4 个字段。数仓只加工**事实**，不算 `strategyTone`（催收 ingestion 合并）。


| 字段                       | 类型      |
| ------------------------ | ------- |
| `userEverReachedS2`      | boolean |
| `loanHadS2PlusCure`      | boolean |
| `everReachedS2Overdue`   | boolean |
| `concurrentOverdueBills` | int     |


数据源：`detail.t_loan` + `detail.t_loan_repayment_plan`。观察日 `as_of_date` 与现有 `dpd` 一致。新字段纳入 `caseVersion` 指纹。

---

## 1. 共用计算：max_dpd

所有指标基于同一套 bill 逾期判定：

```text
bill 计入条件：
  due_date <= as_of_date
  AND (clear_time IS NULL OR DATE(clear_time) > due_date)

bill_dpd = DATE_DIFF(as_of_date, due_date, DAY)
max_dpd  = MAX(bill_dpd)   -- 整笔 loan；S2+ = max_dpd >= 4
```

---



## 2. 字段加工逻辑



### `userEverReachedS2` — 用户历史曾 S2+

该 `userId` 下，**除当前** `caseId` **外**任一历史 loan，曾 peak `max_dpd >= 4`。

```sql
SELECT LOGICAL_OR(hist_ever_s2) AS userEverReachedS2
FROM (
  SELECT l.loan_id,
    MAX(GREATEST(0, DATE_DIFF(
      LEAST(COALESCE(DATE(p.clear_time), @as_of_date), @as_of_date),
      p.due_date, DAY))) >= 4 AS hist_ever_s2
  FROM detail.t_loan l
  JOIN detail.t_loan_repayment_plan p ON p.loan_id = l.loan_id
  WHERE l.user_id = @user_id
    AND l.loan_id <> @current_loan_id
    AND p.due_date <= @as_of_date
  GROUP BY l.loan_id
);
```

- 仅 S1 逾期（从未 D+4）→ `false`
- 无其他 loan → `false`

---



### `loanHadS2PlusCure` — 本笔复发

当前 loan 从首笔 `due_date` 到 `as_of_date`，**逐日**算 `max_dpd`，存在某天同时满足：

```text
此前曾 max_dpd >= 4  →  当日 max_dpd < 4  →  此后再次 max_dpd >= 4
```


| 场景                          | 结果      |
| --------------------------- | ------- |
| 首次逾期波次，一路滚到 D+30 从未回落       | `false` |
| D+20 还一期后 max_dpd→2，再逾到 D+5 | `true`  |
| 三期：还掉最早逾期期后暂降，下一期再逾         | `true`  |


---



### `everReachedS2Overdue` — 合并

```text
everReachedS2Overdue = userEverReachedS2 OR loanHadS2PlusCure
```



---



### `concurrentOverdueBills` — 并发逾期期数

观察日已到期且未结清的 bill 数（与 `max_dpd` 无关）：

```sql
SELECT COUNT(DISTINCT plan_id)
FROM detail.t_loan_repayment_plan
WHERE loan_id = @current_loan_id
  AND due_date <= @as_of_date
  AND (clear_time IS NULL OR DATE(clear_time) > due_date);
```

Phase 1 催收暂不消费；数仓须加工，供后续「三期并发 ≥2」启用。

---



## 3. 催收侧怎么用（对齐用，数仓不算）

```text
hardToCollect = everReachedS2Overdue
             OR (product IN ('3','4') AND concurrentOverdueBills >= 2)

strategyTone = hardToCollect AND stage IN (S2,S3,S4) ? FIRM : STANDARD
```

S0/S1 即使 `everReachedS2Overdue=true` 仍为 STANDARD。FIRM 只硬化不回退。

---



## 4. 消息样例

```json
{
  "caseId": "530684",
  "dpd": 7,
  "stage": "S2",
  "userEverReachedS2": true,
  "loanHadS2PlusCure": false,
  "everReachedS2Overdue": true,
  "concurrentOverdueBills": 1
}
```

负样本（本笔首次 S2）：`505611` → 三个 boolean 均为 `false`。

---



## 5. 验收（e2e200 · 观察日 2026-09-01）


| 检查项                         | 预期                |
| --------------------------- | ----------------- |
| `everReachedS2Overdue=true` | 16 户              |
| 其中 `dpd>=4`                 | 14 户              |
| S2 档且 ever=true             | `530684`、`531245` |


金样本：


| loan_id | userEverReachedS2 | loanHadS2PlusCure | everReachedS2Overdue |
| ------- | ----------------- | ----------------- | -------------------- |
| 530684  | ✓                 | ✗                 | ✓                    |
| 531245  | ✓                 | ✗                 | ✓                    |
| 505611  | ✗                 | ✗                 | ✗                    |
| 506516  | ✗                 | ✓                 | ✓                    |


类型须为 boolean/int，非字符串；缺字段按 `false`/`0` 兜底。`repaymentEvent` 不携带这些字段。

---

**不做**：`strategyTone` 直出、PTP/无互动、定向补样（另开任务）。