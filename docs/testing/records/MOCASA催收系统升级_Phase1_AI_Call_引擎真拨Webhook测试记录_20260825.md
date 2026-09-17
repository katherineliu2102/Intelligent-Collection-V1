# Phase 1 AI Call 引擎真拨 + Webhook 测试记录（2026-08-25）

> **层级**：引擎闭环（调度 `planStepDue` → `ChannelGateway(AI_CALL)` → Facade `create → cases → start` → `POST /webhook/facade-callback` → 步骤终态）。  
> **对比 L1**：2026-08-19 的 L1 记录走 `/mock/send-ai-call`，**不经引擎、无 Webhook 入库**；本记录补上那两条缺口。（该 L1 记录尚未入仓，需向编排同事索取后补进 `docs/testing/records/`。）  
> **环境**：Pilot 机 `bdp01`（登录地址见 `docs/ops/生产访问凭据.local.md`，不入库）；容器 `collection-admin-aicall`；镜像 `intelligent-collection-admin:aicall-e2e`；域名 `https://collection-admin.mocasa.com`。  
> **时区**：业务时间为 **PHT（UTC+8）**。库内 `executed_at` / 回调 `received_at` 为 UTC。  
> **PII**：手机号脱敏；只写 `loan_id` / `user_id`。  
> **关联**：[Webhook 实现规格 v0.3](../../channel/MOCASA催收系统升级_Phase1_AI_Call_Facade_Webhook实现规格.md) · [发版手册](../../channel/MOCASA催收系统升级_Phase1_发版手册.md)

---

## 1. 结论

**出站 + 入站闭环 1/1 成功。** Facade 已 `start` 并回 `session.completed`；我方验签通过、写入审计与时间线，引擎将步骤结为 `NO_ANSWER`、计划 `PLAN_COMPLETED`。

| 口径 | 结果 |
|---|---|
| 调度扫到步骤并 dispatch | 是（步骤 1414，14:41:04 PHT） |
| Facade 建批 / 上传案件 / start | 是 |
| `batch_id` | `591838ae-e3b7-4510-b537-036a2eb685c6` |
| Webhook `POST /webhook/facade-callback` | 是（14:42:00 UTC = 14:42:00 PHT） |
| 验签 | `signature_valid=1` |
| 引擎 `ContactResult` | **`NO_ANSWER`**（规格：未接通，算验收） |
| 真人接通 | **否**（`was_answered=false`，`was_ai_connected=false`，`was_ringing=true`） |
| 数仓 Pub/Sub 进件 | **本窗口未测**（`COLLECTION_INGESTION_ENABLED=false`，计划由 Redis 注入 `CASE_INGESTED` 创建） |

未接通主因在线路（振铃后无人接），**不是** payload / 验签 / 路径错误。与 L1 真实案样本同向。

---

## 2. 通次明细

### #1 引擎真拨（案 520049）

| 项 | 值 |
|---|---|
| 入口 | Cloud Scheduler → Topic `intelligent-collection-schedule-v1` → 订阅 `intelligent-collection-schedule-v1-sub` → `job=planStepDue` |
| 选案 | Pilot 白名单内 `t_ai_collection` DPD25 / S3 / product=1；测试窗口补 `due_date=2026-07-31` |
| `loan_id` / `user_id` | **520049** / 4538503 |
| 计划 / 步骤 | `t_contact_plan.id=828`，`t_contact_plan_step.id=1414`（`step_order=23`，`template_id=301`） |
| 被叫 | `+639635****98`（脱敏） |
| 主叫 CLI | `6310001`（租户默认） |
| 触发 | 2026-08-25 **14:41:00** PHT（`original_trigger_time`） |
| dispatch | 2026-08-25 **14:41:05** PHT |
| Facade start | 2026-08-25 **14:41:06** PHT（`dispatched_at` 06:41:06 UTC） |
| 回调到达 | 2026-08-25 **14:42:00** PHT（约 **55s**） |
| 步骤完成 | 2026-08-25 **14:42:01** PHT |
| 出站 | `[FacadeAiCallAdapter] started batchId=591838ae-e3b7-4510-b537-036a2eb685c6` |
| 事件 | `event=session.completed` |
| `session_id` | `4b43cf2c-5d5f-42ca-b518-da12859e29f6` |
| `client_metadata` | `case_id=520049`，`plan_id=828`，`step_id=1414` |
| 线路 | `line_outcome.reason=NO_ANSWER`，`sip_code` 空，`attempt_count=1` |
| 振铃/接通 | `was_ringing=true`，`was_answered=false`，`was_ai_connected=false` |
| 审计 | `t_channel_callback_audit.id=7`；`provider_msg_id=session_id`；`result=NO_ANSWER`；`disposition` 空（符合规格） |
| 时间线 | `t_contact_timeline.id=712`，`channel=AI_CALL`，`result=NO_ANSWER` |
| 计划终态 | `PLAN_COMPLETED`（当日其余步骤此前已 SKIPPED，1414 为最后成功步） |

Webhook 路径：**`POST /webhook/facade-callback`**（不是 L1 文档里的 `/webhook/facade/voice`）。对外 URL：`https://collection-admin.mocasa.com/webhook/facade-callback`。

---

## 3. 同窗口失败样本（对照，证明闭环前卡在哪）

同一案、同一天，闭环前两次失败**没有**回调，因为电话没建批。

| 步骤 | PHT | 步骤结果 | 原因 | 回调 |
|---|---|---|---|---|
| 1392 | 12:16 | `SKIPPED` / `COMPLIANCE_BLOCKED` | `ExecutionGuard` SPI 硬超时 50ms（Redis 频控） | 无 |
| 1393 | 14:27 / 14:29 | 未发出，随后 `SKIPPED` | `POST <FACADE_BASE_URL>/batches` **PKIX**（Valubo 自签名 `CN=valubo-voice-test`）；第三次命中日上限 `AI_CALL 3/2` | 无 |
| 1414 | 14:41 | `COMPLETED` / `NO_ANSWER` | 容器 JRE 导入该证书 + 清当日频控计数后重试 | **有** |

L1 用 `insecure-tls=true` 绕过证书；本窗口按「导入证书、不关全局 TLS」处理。证书写在**当前容器** JRE `cacerts`，`docker rm` 后再起会丢掉。

---

## 4. 本窗口配置要点（复盘用，不含密钥）

| 项 | 值 |
|---|---|
| Profile | `pilot` |
| `collection.case-service` | `ai` |
| `collection.scheduler.enabled` | `true` |
| `collection.ingestion.enabled` | `false`（进件未测） |
| `COLLECTION_PILOT_LOAN_IDS` | `520049` |
| `engine.spi.execution-guard-timeout-ms` | `500`（env） |
| `engine.spi.step-resolver-timeout-ms` | `500`（env） |
| MyBatis `map-underscore-to-camel-case` | `true`（打进 `aicall-e2e` 镜像的 `application-pilot.yml`） |
| Facade | Valubo 测试环境（地址由 `channel.facade.base-url` 下发，见 `docs/ops/生产访问凭据.local.md`），账户级 callback URL，HMAC-SHA256 验签 |

计划创建：向 Redis DB 0 stream（`COLLECTION_REDIS_STREAM`）写入 `CASE_INGESTED`，payload 含 `dueDate` / `dpd` / 联系人字段。缺 `dueDate` 时 `PhtSlotScheduleCalculator` 生成 0 个槽位。

---

## 5. 如何复盘这一通

库：

```sql
SELECT id, status, result FROM t_contact_plan WHERE id=828;
SELECT id, status, result, original_trigger_time, dispatched_at, completed_at
  FROM t_contact_plan_step WHERE id=1414;
SELECT id, plan_id, step_id, provider_msg_id, result, signature_valid, received_at
  FROM t_channel_callback_audit WHERE id=7;
SELECT id, channel, result, created_at FROM t_contact_timeline WHERE case_id=520049;
```

Facade（自签名，需 TrustStore 或跳过校验）：

```text
GET /api/v1/facade/batches/591838ae-e3b7-4510-b537-036a2eb685c6
GET /api/v1/facade/sessions/4b43cf2c-5d5f-42ca-b518-da12859e29f6
Authorization: Bearer <channel.facade.api-key>
```

---

## 6. 未覆盖（上线前另开窗口）

- 数仓 Topic `intelligent-collection-cases-v1` / 订阅 `intelligent-collection-cases-v1-sub` 真进件
- 日切 `dailyRoll`、SMS / PUSH / EMAIL
- 真人接通（`was_ai_connected=true` 且 `reason=NORMAL`）
- Wave-2 同日第二通（本窗口为避开连打 5 天，已 SKIPPED 8/26 起步骤）
- 证书打进镜像 / 正规域名证书
- 白名单 50 案批量进件与排期
