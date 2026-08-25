# MOCASA 催收系统升级 — Phase 1 AI Call（Facade）回调入站交接

> **状态**：待实现，**T4 阻断**。闭合前 AI_CALL 只能靠回调超时收敛，外呼结果永远回不到引擎。
> **归属**：`collection-channel` / 入站端点由**编排同事**实现；引擎侧契约、`CHANNEL_CALLBACK` 语义与验收口径由主架构提供（本文）。
> **上游依据**：[Facade 客户接入手册](./FACADE客户接入手册.md) §11、[AI_Call Facade 接入说明](./MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md) §4 第 1 条未完成边界。

## 1. 为什么不能复用现有 `/webhook/channel-callback`

现有入口是**按我方口径**设计的：靠 query 参数定位步骤、验我方 HMAC。

```35:43:collection-admin/src/main/java/com/collection/admin/web/WebhookController.java
    @PostMapping("/channel-callback")
    public Map<String, Object> channelCallback(
            @RequestParam Long planId,
            @RequestParam Long stepId,
```

Facade 侧是三条都对不上：

| 维度 | 现有入口要求 | Facade 实际 |
|---|---|---|
| 回调地址 | 每次下单可带不同 URL，URL 里带 `planId` / `stepId` | **账户级单一 URL**，所有批次共用、对方控制台预先登记，**无法按批携带参数** |
| 请求体 | 无（全靠 query 参数） | `session.completed` / `batch.completed` 事件 JSON |
| 验签 | `X-Callback-Signature` = HmacSHA256(`planId:stepId:result:providerMsgId:disposition`) | `X-Valubo-Signature`：Callback secret 对规范 JSON（字典序、无空格）的 HMAC-SHA256 小写 hex |

因此需要**新增 Facade 专用入站端点**（建议 `POST /webhook/facade-callback`），把对方原生事件翻译成内部 `CHANNEL_CALLBACK`。现有 `/webhook/channel-callback` 保留给其它同步渠道与联调，不要改它的签名口径去迁就 Facade。

## 2. 关联：怎么从回调找回步骤

出站时我方已在每个 case 上带了内部标识，回调按下列优先级反查，**不要新增自定义字段**：

| 优先级 | 字段 | 出站写入点 | 说明 |
|---|---|---|---|
| 1 | `client_metadata.case_id` / `plan_id` / `step_id` | `FacadeAiCallAdapter#clientMetadata` | 首选。Facade 若在回调里回显 `business_context` / `client_metadata`，直接取这三个值 |
| 2 | `external_case_id` | `FacadeAiCallAdapter#externalCaseId` | 当前取值为 `case_id`，缺失时退化为去掉 `+` 的号码。**只能定位到案件，不能定位到步骤**，需再按 `providerMsgId`（批次号）+ 案件查活跃 `EXECUTING` 步骤 |
| 3 | 批次号 | `StepResult.providerMsgId` = Facade `batchId` | 一案一批时可唯一定位；聚合批次后不可单独依赖 |

Facade 手册约定 `client_metadata` 在 `session.completed` 原样回传；端点应优先使用其中的 `case_id`、`plan_id`、`step_id`。若实际载荷缺失该字段，才按 `external_case_id` 与批次号反查；反查不唯一时拒绝并告警，不按号码猜测。

## 3. 结果映射（**最容易埋雷的一段**）

引擎收到 `CHANNEL_CALLBACK` 后这样解析结果：

```630:639:collection-engine/src/main/java/com/collection/engine/lifecycle/PlanLifecycleManager.java
    private ContactResult mapCallbackToResult(String raw) {
        if (raw == null) {
            return ContactResult.ANSWERED;
        }
        try {
            return ContactResult.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ContactResult.ANSWERED;
        }
    }
```

它先读 `disposition`、没有才读 `result`，**且任何无法识别的取值都会静默变成 `ANSWERED`**。所以：

> **绝对不要把 Facade 原生词（`VOICEMAIL`、`CALL_SCREENING`、`MEDIA_NEGOTIATION_FAILED`、`NORMAL` 等）直接塞进 `result` 或 `disposition`。** 它们都不是 `ContactResult` 枚举值，会被当作 `ANSWERED` —— 未接通、进信箱的案件会被记成「已接通真人」，计划照常推进、频控照常计数，而日志里看不出任何异常。供应商原生词只写入回调审计表，不进事件。

映射必须在入站端点完成，且只能产出 `ContactResult` 枚举名（见 `collection-common/.../ContactResult.java`）：

| Facade 回调 | 判定依据（修订说明 §3） | 映射为 `ContactResult` |
|---|---|---|
| 真人接通 | `was_ai_connected=true` 且 `line_outcome.reason=NORMAL` | `ANSWERED` |
| 线路接通但进信箱 | `was_answered=true`、`reason=VOICEMAIL` | `SENT_NO_RESPONSE`（**不计真人接通**） |
| 来电筛选 | `was_answered=true`、`reason=CALL_SCREENING` | `SENT_NO_RESPONSE`（同上） |
| 未接 | `reason=NO_ANSWER` | `NO_ANSWER` |
| 忙线 | `reason=BUSY` | `BUSY` |
| 拒接 | 对方拒接类 reason | `REJECTED` |
| 媒体协商失败耗尽后回传 | `MEDIA_NEGOTIATION_FAILED`（406，对方已做技术短重试） | `FAILED` |
| 其它/未知 reason | — | `FAILED` + **告警**，不要落 `ANSWERED` |

信箱与筛选是否算触达，直接决定频控计数与后续 wave 是否继续拨，这是业务口径而非技术细节：本表按修订说明 §3「信箱 / 筛选不计真人接通」定，如需改动须同步主架构与业务方。

## 4. 幂等与迟到回调

- **幂等键用 `session_id`**（单次通话唯一），不要用 `batch_id`：同批多案、同案重拨都会复用 batchId。同一 `session_id` 重复到达必须只产生一次 `CHANNEL_CALLBACK`。
- **迟到回调是常态，且引擎已能安全吸收**：`callbackTimeout` 哨兵会在超时后把步骤置终态，此时计划不再是 `STEP_EXECUTING` / `STEP_WAITING`，`onChannelCallback` 直接静默返回（见 `PlanLifecycleManager:346-350`）。端点**仍必须写回调审计**——否则「对方回调了但我们已超时」这种最需要复盘的情况会没有任何痕迹。
- 手工重拨（`/retry`）会为同一步骤产生新 session。若步骤已终态，引擎照上条吸收；需要在审计里能看出是重拨产生的。

## 5. 端点需要发布的事件字段

验签通过后发布 `EventType.CHANNEL_CALLBACK`，字段与现有入口一致（`WebhookController:60-67` 是可照抄的参考实现）：

| 事件字段 | 取值 |
|---|---|
| `planId` / `stepId` | 按 §2 反查结果，二者缺一不可 |
| `caseId` | 可选，便于排查 |
| `result` | §3 映射后的 `ContactResult` 枚举名 |
| `disposition` | **留空或与 `result` 同值**；因为引擎优先读它，放原生词等于覆盖掉正确结果 |
| `providerMsgId` | Facade `batchId`（会写入步骤的供应商消息号） |

## 6. 我方需向 Facade 提供 / 索取

**提供**（账户级登记一次，非每批下发）：

- 账户级回调 URL：`https://<pilot 外网入口>/webhook/facade-callback`。注意这是**入站**地址，需要对外可达；`.env.pilot` 里现有的 `collection.webhook.*` 是我方自有验签配置，与 Facade 无关。
- 若对方支持我方指定验签密钥，则同时提供密钥并走安全渠道，不写入仓库。

**实施前确认**：

1. 在 Facade 控制台登记账户级回调 URL 与 Callback secret，并用真实小批次验证 `X-Valubo-Signature` 的规范 JSON 签名。
2. 确认生产回调实际回显 `client_metadata`；缺失时仅允许按 §2 的受限反查路径处理。
3. 确认回调重试策略与超时，决定我方 `timeout_time` 是否需要放宽。
4. 补齐 `line_outcome.reason` 的完整取值枚举；未识别取值按 §3 收敛为失败并告警。

## 7. 验收（L2-CB 级，纳入测试 SSOT）

1. 合法签名 + 真人接通 → 步骤 `COMPLETED` / `ANSWERED`，计划推进。
2. 合法签名 + 信箱 → `SENT_NO_RESPONSE`，**不得**记为 `ANSWERED`（本条是 §3 那个默认值陷阱的守护用例，必须有）。
3. 未知 `reason` → `FAILED` + 告警，不落 `ANSWERED`。
4. 错误签名 → 401，事件不发布，审计留痕且 `signature_valid=false`。
5. 同一 `session_id` 重复投递 → 只有一次 `CHANNEL_CALLBACK`、只有一次终态写入。
6. 超时后迟到回调 → 引擎静默吸收，不改已终态步骤，但审计有记录。
7. `client_metadata` 缺失且 `external_case_id` 可用 → 能正确反查到唯一活跃步骤；反查到多条时拒绝并告警，不猜。
