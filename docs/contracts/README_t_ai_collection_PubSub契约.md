# t_ai_collection 与 Pub/Sub 契约（索引）

> **字段、样例、GCP 资源、验收：数仓对外唯一 SSOT** → [数仓 Pub/Sub 交付契约](../数仓_PubSub交付契约.md)  
> **消费、ACK、投影、日切实现** → [数据接入规格](../MOCASA催收系统升级_Phase1_数据接入规格.md)

本文件不再维护字段表。快捷入口：

| 关切 | 章节 |
| --- | --- |
| Topic / IAM / 双 Scheduler | [契约 §1](../数仓_PubSub交付契约.md#1-封面与边界) |
| DPD / 金额 / `collectionStatus` | [契约 §2](../数仓_PubSub交付契约.md#2-数仓要算什么) |
| `caseEvent` / `repaymentEvent` 样例 | [契约 §3](../数仓_PubSub交付契约.md#3-数仓要发什么) |
| `eventId` / `caseVersion` / 360s | [契约 §4](../数仓_PubSub交付契约.md#4-怎么发才可靠) |
| 谁发阶段/停催/复活 | [契约 §5](../数仓_PubSub交付契约.md#5-场景矩阵) |
| 03:00 批次 vs 03:35 日切 | [契约 §6](../数仓_PubSub交付契约.md#6-日切门控) |
| 上线验收签字 | [契约 §7](../数仓_PubSub交付契约.md#7-验收清单) |
