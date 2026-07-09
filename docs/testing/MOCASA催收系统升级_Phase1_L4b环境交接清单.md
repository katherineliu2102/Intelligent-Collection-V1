# L4b 运维待办

> 给运维 / SRE / 信贷协调。开发项不在本文。
> 联调方案定稿：**影子并行 + 六案闭环**（2026-06-30）

---

## 联调方案（运维须知晓）

与旧系统**并行期**一致：同一 PubSub topic、**两个独立订阅**各 ack；新系统只处理 6 个测试 `loan_id`，触达走渠道沙箱。

| 维度 | 定稿 |
|---|---|
| Topic | `collection-cases`（与线上一致） |
| 新订阅 | `collection-cases-ai-v1-sub`（**禁止**复用 `collection-cases-sub`） |
| 旧系统 | 继续消费原订阅，**保持运行** |
| 白名单 | `99000000`–`99000005`（`IC_TEST_*` 测试案，非真实用户） |
| 触达地址 | phone **`+639451374358`**（L4a 94101/94999），email **`wzynju@126.com`**（L4a 126 真发） |
| Push | 沙箱 `push-test-token` = **`1a0018970bf0c19de04`**（L4a 94200，与 `application-local.yml` 一致） |
| SMS | `sms-test-mode=true`（testSend） |
| Email | **无全局沙箱**；PubSub payload / 镜像须为 **126 邮箱**（勿用 Gmail） |
| 信贷 | 对 6 案**停发 D-3~D0**；联调期**仅**对 6 案发 `case_push` / `repayment_push_and_load` |
| 日切 Job | XXL-Job `dailyRoll`，0:05 PHT（B2 代码就绪后生效） |
| 不采用 | 近 1 个月到期全量、白名单留空（全量消费） |

---

## 运维待办

| # | 待办 | 目的 / 验收 |
|---|---|---|
| O1 | 建独立 PubSub 订阅 `collection-cases-ai-v1-sub`（挂 topic `collection-cases`） | **目的**：扇出并行，新/旧系统互不抢消息。**验收**：订阅存在；`collection-cases-sub` 仍归旧系统。 |
| O2 | 配置运行环境变量：`GCP_PUBSUB_PROJECT`、`GCP_PUBSUB_SUBSCRIPTION=collection-cases-ai-v1-sub`、`GOOGLE_APPLICATION_CREDENTIALS`（JSON 路径） | **目的**：新系统 Consumer 鉴权拉取。**验收**：`gcloud pubsub subscriptions describe` 可见；应用启动无 PubSub 鉴权错误。 |
| O3 | 注册 XXL-Job **`dailyRoll`**（Cron `0 5 * * *`，时区 **Asia/Manila**）并绑定 `collection-admin` 执行器 | **目的**：DPD 日切调度（L4b-3/4/8）。**验收**：控制台可手动触发；应用日志出现 `[DpdStageRollHandler]`。 |
| O4 | Nacos 写入白名单（见下 YAML）+ **协调信贷**：6 案停发 D-3~D0 + 仅对 6 案发 PubSub（payload phone/email 须为 L4a 测试地址） | **目的**：范围隔离、防双发。**验收**：非白名单 ack 跳过；信贷确认停发；6 案 PubSub 中 email=`wzynju@126.com`、phone=`+639451374358`。 |
| O5 | Nacos 写入：`collection.ingestion.enabled=true`、`channel.notification.push-test-token`、`channel.notification.sms-test-mode=true` | **目的**：打开真实入站 + Push/SMS 沙箱。**验收**：`./scripts/test/l4b-preflight.sh` Nacos 段转绿。 |
| O6 | 签发通知中心 **`appKey`**（`appCode=mocasa`）+ 确认 SMS 额度 / 签名 | **目的**：SMS/Push fallback 真入队。**验收**：`requestSuccess=true`。**注**：O5 已开 `sms-test-mode` 时主路径不阻塞，但 fallback 仍需。 |

### O4 + O5 Nacos 片段（真值不入仓）

```yaml
collection:
  ingestion:
    enabled: true
    loan-id-whitelist: [99000000, 99000001, 99000002, 99000003, 99000004, 99000005]
channel:
  notification:
    sms-test-mode: true
    push-test-token: 1a0018970bf0c19de04   # L4a 94200；生产 Nacos 真值不入仓时可仅运维侧配置
```

### 六案与 PubSub 类型（供信贷对齐）

| loan_id | 信贷应发的消息类型 | 用途 |
|---|---|---|
| 99000000 | `case_push` | 入案建计划 |
| 99000001 | `case_push` → 再发 `repayment_push_and_load` | 还款取消 |
| 99000002 | `case_push` + 日切后 `STAGE_CHANGED` | 升档 |
| 99000003 | `case_push` | 扫描到期执行 |
| 99000004 | `case_push` | 高 dpd 触达 |
| 99000005 | `case_push` + 日切 dpd≥91 | CEASED 停催 |

### 自检

```bash
./scripts/test/l4b-preflight.sh --strict
```

DB 连接已在 Nacos `spring.datasource`（`ai_collection_db`），**无需运维额外开库权限**。
