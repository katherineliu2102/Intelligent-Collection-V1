# L4b PubSub payload 草案（联调 / 脚本引用）

> 与 L4a 触达口径一致：phone `+639451374358`、email `wzynju@126.com`、jpushToken `1a0018970bf0c19de04`。
> JSON body 使用领域模型 §9.2 语义 key；信贷若 key 不同，配 Nacos `collection.ingestion.case-push.field-map`。
> **上线前须信贷确认**真实报文 key 名（C-I-01）。

## case_push — 99000000（S0）

Attributes（可选）：`dataType=case_push`, `messageId=ic-l4b-s0-001`

```json
{
  "dataType": "case_push",
  "messageId": "ic-l4b-s0-001",
  "caseId": 99000000,
  "userId": 99000000,
  "stage": "S0",
  "dpd": 0,
  "product": "QuickLoan",
  "totalOutstanding": 5100.00,
  "penaltyAmount": 0.00,
  "dueDate": "2026-06-30",
  "name": "Test Case S0",
  "phone": "+639451374358",
  "email": "wzynju@126.com",
  "jpushToken": "1a0018970bf0c19de04"
}
```

## case_push — 99000001（S1，L4b-2 前置）

```json
{
  "dataType": "case_push",
  "messageId": "ic-l4b-s1-001",
  "caseId": 99000001,
  "userId": 99000001,
  "stage": "S1",
  "dpd": 2,
  "totalOutstanding": 5250.00,
  "penaltyAmount": 100.00,
  "phone": "+639451374358",
  "email": "wzynju@126.com",
  "jpushToken": "1a0018970bf0c19de04"
}
```

## repayment_push_and_load — 99000001

```json
{
  "dataType": "repayment_push_and_load",
  "messageId": "ic-l4b-repay-001",
  "userId": 99000001,
  "loanId": 99000001,
  "totalOutstanding": 0,
  "fullRepay": true
}
```

## case_push — 99000002（S2）

```json
{
  "dataType": "case_push",
  "messageId": "ic-l4b-s2-001",
  "caseId": 99000002,
  "userId": 99000002,
  "stage": "S2",
  "dpd": 7,
  "totalOutstanding": 8560.00,
  "penaltyAmount": 320.00,
  "phone": "+639451374358",
  "email": "wzynju@126.com"
}
```

（`jpushToken` 可省略 → 读 `t_user_device_token`。）
