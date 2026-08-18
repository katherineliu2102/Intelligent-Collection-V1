# t_ai_collection 与 Pub/Sub 契约（索引）

> **字段、样例、GCP 资源、验收：数仓对外唯一 SSOT** → [数仓 Pub/Sub 交付契约](../数仓_PubSub交付契约.md)  
> **消费、ACK、投影、日切实现** → [数据接入规格](../MOCASA催收系统升级_Phase1_数据接入规格.md)

本文件不再维护字段表。快捷入口：

| 关切 | 章节 |
| --- | --- |
| 入站顺序 / Publisher 任务 | [契约 §1.1](../数仓_PubSub交付契约.md#11-入站顺序与-publisher-任务) |
| 两条管道 / Topic / IAM | [契约 §1.2](../数仓_PubSub交付契约.md#12-gcp-资源) |
| 两类事实事件 / 按事件字段 / 样例 | [契约 §2](../数仓_PubSub交付契约.md#2-计算口径与事件契约) |
| 独立发布样例 | [caseEvent](./caseEvent.sample.json)；[repaymentEvent](./repaymentEvent.sample.json)；每份均为单条 `{dataType, data}` message body |
| `eventId` / `caseVersion` / 360s | [契约 §3](../数仓_PubSub交付契约.md#3-发布可靠性) |
| 阶段 / 停催场景 | [契约 §4](../数仓_PubSub交付契约.md#4-场景矩阵) |
| 03:00 批次 vs 03:35 日切 | [契约 §5](../数仓_PubSub交付契约.md#5-日切窗口与批次门控) |
| 上线验收签字 | [契约 §6](../数仓_PubSub交付契约.md#6-上线验收) |
