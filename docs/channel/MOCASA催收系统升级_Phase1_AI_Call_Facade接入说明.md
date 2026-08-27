# MOCASA 催收系统升级 — Phase 1 AI Call（Facade）L1 接入说明

> **状态**：默认一案一批；开启 `channel.facade.batch-aggregation.enabled` 后按触达槽聚合成一批多案（§1.1）。未完成项见 §4。  
> **范围**：菲律宾 `AI_CALL` 渠道；不替换 LTH 的人工外呼职责。  
> **凭证**：`channel.facade.api-key` 仅由 Nacos / 部署 Secret 注入，禁止写入仓库。
> **外部 API**：[Facade 客户接入手册](./FACADE客户接入手册.md)。

## 1. 已接入能力

`FacadeAiCallAdapter` 作为 `ChannelAdapter` 注册到 `ChannelGateway`：

```text
AI_CALL StepCommand
  → POST /batches
  → POST /batches/{batchId}/cases
  → POST /batches/{batchId}/start
  → StepResult(DELIVERED, providerMsgId=batchId)
```

- 默认按**一案一批**发送，适合 L1 冒烟和单 step 验证；聚合开关打开后改走 §1.1。
- `targetAddress` 支持 `+63` / `63` / `0` / `9` 开头的菲律宾号码，统一转为 E.164 `+63xxxxxxxxxx`。
- 发送前强制校验姓名和 `overdueAmount > 0`；缺失时安全失败，不伪造业务金额。
- 出站不传 `script.language`，使用 Facade 租户默认语言；固定 `script.domain=collection`、`product_type=Quick Loan`、`currency=PHP`、时区 `Asia/Manila`。
- Facade 回调为账户级配置；Adapter 不随批次传 `callback_url`。入站闭环见[回调入站交接](./MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md)。
- 自签名 TLS 仅允许 `local/test` 设置 `channel.facade.insecure-tls=true`；该客户端不影响 JVM 全局 TLS。Pilot / 生产必须使用受信任证书或专用 TrustStore。

### 1.1 波次聚合（一批多案）

Facade 的并发额度按批次分配，一案一批会让同一时刻的 N 通电话各占一套并发，资源不可控。开启聚合后，同一触达槽到期的步骤合成一个批次：

```text
AI_CALL StepCommand  ─┐
AI_CALL StepCommand  ─┼→ FacadeBatchCoordinator（Redis 波次缓冲，按 original_trigger_time 归槽）
AI_CALL StepCommand  ─┘      │  每案立即返回 StepResult(DELIVERED, providerMsgId=external_batch_id)
                             │
              FacadeBatchFlusher（每 5s 轮询，Redis 锁单飞）
                             ↓  静默 15s / 满 500 案 / 首案入批满 120s
                    起批前逐案复检还款 → 剔除
                    POST /batches → POST /batches/{id}/cases（一次全量）→ POST /batches/{id}/start
                    回写各步骤 timeout_time = 按批内案数估算的回调截止时刻
```

**案件在 `start` 之前只存在于我方 Redis**，还款或计划取消时直接从缓冲里剔除，不需要 Facade 提供 case 级撤单接口。起批之后无法撤单（与一案一批时相同）——**禁止**改用批级 `cancel` 代偿，那会停掉同批其他借款人的电话。

`providerMsgId` 写的是我方生成的 `external_batch_id`（形如 `mocasa-20260827-1430-1`，日期 + 槽位 + 代次），不是 Facade 的 `batch_id`。回调身份反查走 `client_metadata.plan_id/step_id`（每案独立），不受影响；用 `batch_id` 反查的兜底路径在聚合下不再唯一，本期本就未实现。

失败与剔除都不在渠道层改步骤状态，而是把 `timeout_time` 置为当前时刻，交给既有的 `callbackTimeout` 哨兵按标准路径收口并推进计划。

| 配置项（`channel.facade.batch-aggregation.*`） | 默认 | 作用 |
|---|---|---|
| `enabled` | `false` | 关掉即回到一案一批 |
| `silence-seconds` | `15` | 最后一案入批后静默这么久即起批 |
| `max-wait-seconds` | `120` | 首案入批起的最长等待 |
| `max-cases-per-batch` | `500` | Facade 单次上传上限 |
| `poll-interval-ms` | `5000` | flusher 轮询间隔 |
| `assumed-concurrency` / `assumed-call-seconds` | `5` / `90` | 超时估算用的并发与单通时长，Facade 未给准确值前是保守估计 |
| `timeout-buffer-minutes` | `15` | 超时估算的固定缓冲 |
| `min-timeout-minutes` / `max-timeout-minutes` | `30` / `120` | 超时下界（与一案一批时一致）与上界；同时不得越过当日拨打窗结束 |

回调超时按 `⌈案数 ÷ 并发⌉ × 单通时长 + 缓冲` 估算。这条是聚合的关键风险点：一批多案后队尾要排队，固定 30 分钟窗会把**还没拨出去**的电话误判成 FAILED。

## 2. 引擎所需字段

`DefaultStepResolver` 对 `AI_CALL` 自动写入以下 metadata：

| Facade 字段 | 来源 |
|---|---|
| `borrower.name` | `ContextSnapshot.userProfile.basic.name` |
| `debt.overdue_amount` | `CaseContext.overdueAmount` |
| `debt.days_past_due` | `CaseContext.dpd` |
| `debt.due_date` | `CaseContext.dueDate`；缺失时 Adapter 按 PHT 的 `today - dpd` 回退 |
| `client_metadata.case_id / plan_id / step_id` | 当前案件、计划、步骤 |

这使 AI Call 可进入现有 `ChannelGateway` 与引擎的异步 `AI_CALL` 分支；`DELIVERED` 仅代表 Facade 已接受批次，不代表客户接通。

## 3. L1 冒烟

本地启动后，先检查 JSON（不拨号）：

```bash
curl -X POST "http://localhost:8888/mock/send-ai-call?dryRun=true"
```

确认 `preview.batch.script` 没有 `language` 字段，再拨批准的测试号：

```bash
curl -X POST "http://localhost:8888/mock/send-ai-call?poll=true"
```

可选参数：`phone`、`name`、`overdueAmount`、`dpd`、`dueDate`。`/mock/**` 仅在 `local` / `test` profile 暴露。

Nacos 示例见 `deploy/nacos/nacos-publish.local.yml.example`；非敏感的 local 环境变量见 `.env.example`。

## 4. 未完成边界

以下能力**未随本次 L1 接入合并**，不得据此宣称 AI Call 已完成生产化：

- Facade callback 的 HMAC 验签、`session_id` 幂等和 `CHANNEL_CALLBACK` 映射；
- 真人接通、信箱、忙线、媒体失败等回调结果映射；
- `CONNECT_AND_STOP` 取消下午 Wave-2；**起批之后**的还款撤单（Facade 无 case 级取消接口，起批前的剔除已由 §1.1 覆盖）；
- 批次拆分、供应商限流与日终对账；
- 录音、脚本 URL 与承诺信息的 timeline 审计。

在这些能力闭合前，AI Call 仅可用于批准号码的 L1 / 单 step 联调，不得对真实全量案件启用。
