# L4b Pub/Sub v3 样例

> L4b 与 Pilot 仅使用数仓新契约的单案消息：`caseEvent`、`repaymentEvent`。禁止投递旧批量 envelope、`calibrationEvent` 或外部阶段事件。

## `caseEvent` — 首次入催或每日投影刷新

Attributes：`dataType=caseEvent`

```json
{
  "eventId": "l4b-case-001",
  "eventType": "CASE_INGESTED",
  "occurredAt": "2026-08-12T03:00:00+08:00",
  "caseId": "99000001",
  "userId": "9901",
  "caseVersion": 1,
  "product": "3",
  "stage": "S1",
  "dpd": 2,
  "collectionStatus": "IN_COLLECTION",
  "totalOutstanding": 3000.00,
  "penaltyAmount": 0.00,
  "remainingAmount": 9000.00,
  "dueDate": "2026-08-11",
  "borrower": {
    "name": "L4B USER",
    "phone": "+639563093217",
    "email": "l4b@example.com",
    "language": "en"
  },
  "device": {
    "pushToken": "l4b-push-token"
  }
}
```

对同一 `caseId` 再投一个更高 `caseVersion` 的完整 `caseEvent`：投影应更新；已有催收周期时不得重复建计划或触达。

## `repaymentEvent` — 部分或整笔还款

Attributes：`dataType=repaymentEvent`

```json
{
  "eventId": "l4b-repay-001",
  "eventType": "REPAYMENT",
  "occurredAt": "2026-08-12T10:06:00+08:00",
  "caseId": "99000001",
  "userId": "9901",
  "caseVersion": 2,
  "repayTime": "2026-08-12T10:00:00+08:00",
  "paidAmount": 1000.00,
  "isFullCleared": false,
  "product": "3",
  "stage": "S1",
  "dpd": 2,
  "collectionStatus": "IN_COLLECTION",
  "totalOutstanding": 2000.00,
  "penaltyAmount": 0.00,
  "remainingAmount": 8000.00,
  "dueDate": "2026-08-11",
  "borrower": {
    "name": "L4B USER",
    "phone": "+639563093217",
    "email": "l4b@example.com",
    "language": "en"
  },
  "device": {
    "pushToken": "l4b-push-token"
  }
}
```

整笔结清时设 `isFullCleared=true`、`collectionStatus=SETTLED` 且所有余额为零；该消息须在账务结清状态落库 360 秒后发布。

## 契约越权样例

把 `caseEvent` 的 `eventType` 改为 `CASE_STAGE_CHANGED` 或 `CASE_CEASED` 后投递，接入层必须 poison ack 并告警；投影与计划均不得变化。阶段变化与停催只由 `dailyRoll` 内部产生。
