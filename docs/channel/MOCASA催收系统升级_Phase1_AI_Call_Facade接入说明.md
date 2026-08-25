# MOCASA 催收系统升级 — Phase 1 AI Call（Facade）L1 接入说明

> **状态**：仅完成 L1「一案一批、直连 Facade」验证能力；未完成项见 §4。  
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

- 当前按**一案一批**发送，适合 L1 冒烟和单 step 验证。
- `targetAddress` 支持 `+63` / `63` / `0` / `9` 开头的菲律宾号码，统一转为 E.164 `+63xxxxxxxxxx`。
- 发送前强制校验姓名和 `overdueAmount > 0`；缺失时安全失败，不伪造业务金额。
- 出站不传 `script.language`，使用 Facade 租户默认语言；固定 `script.domain=collection`、`product_type=Quick Loan`、`currency=PHP`、时区 `Asia/Manila`。
- Facade 回调为账户级配置；Adapter 不随批次传 `callback_url`。入站闭环见[回调入站交接](./MOCASA催收系统升级_Phase1_AI_Call_Facade回调入站交接.md)。
- 自签名 TLS 仅允许 `local/test` 设置 `channel.facade.insecure-tls=true`；该客户端不影响 JVM 全局 TLS。Pilot / 生产必须使用受信任证书或专用 TrustStore。

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
- `CONNECT_AND_STOP` 取消下午 Wave-2、还款后 Facade 撤单；
- 同波次聚合、批次拆分、供应商限流与日终对账；
- 录音、脚本 URL 与承诺信息的 timeline 审计。

在这些能力闭合前，AI Call 仅可用于批准号码的 L1 / 单 step 联调，不得对真实全量案件启用。
