# MOCASA Phase 1 — collection-channel 开发执行指南

> **版本**: v1.2 · **日期**: 2026-06-05  
> **定位**: 渠道模块负责人的**可执行开发手册**（按步骤写代码、编译、联调）。  
> **规格 SSOT**: [渠道文档索引](./README_渠道文档索引.md) → [collection-channel 总规格](./MOCASA催收系统升级_Phase1_collection-channel总规格.md) → [渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围)  
> **测试手册**: [collection-channel 功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)  
> **本地启动**: [操作说明_Nacos本地启动](../操作说明_Nacos本地启动.md)

---

## 0. 开发前准备

### 0.1 环境

| 项 | 要求 |
|----|------|
| JDK | 8 |
| Maven | 3.6+ |
| MySQL | 已执行 `db/schema.sql`（可选 `db/mock-data.sql`） |
| Nacos | 测试环境公共账号（见 §0.2） |
| Redis | 实现 `ComplianceExecutionGuard` / 渠道幂等时需要；Mock 链路阶段可暂用内存幂等 |

### 0.2 Nacos（测试环境公共账号）

项目通过 `collection-admin/src/main/resources/application.yml` 启动时拉取：

| Data ID | 生效条件 | 用途 |
|---------|----------|------|
| `intelligent-collection-common.yml` | 所有 profile | DB、Redis、引擎扫描间隔、**渠道供应商密钥与 URL** |
| `intelligent-collection-local.yml` | `SPRING_PROFILES_ACTIVE=local` | 本地/测试环境覆盖项 |

连接参数写入项目根目录 `.env`（从 `.env.example` 复制，**勿提交 Git**）：

```dotenv
SPRING_PROFILES_ACTIVE=local
NACOS_SERVER_ADDR=<测试环境地址>
NACOS_NAMESPACE=<命名空间>
NACOS_GROUP=<分组>
NACOS_USERNAME=<公共账号>
NACOS_PASSWORD=<公共密码>
APP_PORT=8080
```

本地覆盖：修改 `collection-admin/src/main/resources/application-local.yml`（优先级高于 Nacos，适合本机端口、DEBUG 日志）。

### 0.3 编译与启动（每完成一个阶段执行一次）

```bash
cd Intelligent-Collection-V1
mvn -pl collection-admin -am clean package -DskipTests
# Git Bash / Linux
export $(grep -v '^#' .env | xargs) && java -jar collection-admin/target/collection-admin.jar
```

Windows PowerShell 需逐条 `$env:VAR="value"` 设置后启动。

**阶段验收基线**（Mock 未全部替换前也应通过；Windows 见 [操作说明 §4](../操作说明_Nacos本地启动.md#4-环境确认开发前必跑)）：

```bash
# 端口 local profile 默认 8888
curl -X POST "http://localhost:8888/mock/ingest?caseId=91000&userId=91000&stage=S1"
# Mock 三步步 step2/3 各 delay 1min，建议等 90s
curl -s "http://localhost:8888/plans/timeline/91000?limit=10"
```

timeline 出现 **3 条**（SMS→PUSH→SMS，templateId 101/102/103）且 plan 终态 `PLAN_COMPLETED` 即基线通过。

### 0.4 替换 Mock 的通用规则

1. 实现 `collection-common` 中对应接口，加 `@Component`。
2. 与 Mock 并存时：真实类加 `@Primary`，或删除 Mock 类。
3. **跨模块契约变更**（改 SPI 签名、`EventType`、DTO 必填字段）：先与引擎负责人对齐，再改 `collection-common`。
4. 模块内部实现（Adapter、私有方法）：自行推进。

### 0.5 跨模块责任分界（避免「以为别人会做」）

| 事项 | 本指南主责（collection-channel / common） | 须联调的其他模块 |
|------|------------------------------------------|------------------|
| `CASE_CEASED` 枚举与 payload 常量 | **阶段 0** 合入 `collection-common` | 引擎 Consumer 取消 plan（E1）；ingestion 日切发布（E2） |
| `DefaultPlanFactory` | 8 套 Stage×Tone 模板、晚进案、S4 分段 | ingestion 发 `STAGE_CHANGED` 触发重建 |
| `ComplianceExecutionGuard` | 日限额 / 时段 / 冻结 / 无邮箱 | snapshot 由 ingestion 写入 `complaint_frozen` 等 |
| Adapter / Webhook | 供应商调用与回调解析 | admin 薄控制器挂载路径 |
| Override 取消 Wave-2 | `DefaultAdvancementPolicy` 分支 | 引擎 E3 事件中断（争议/需人工） |

渠道负责人**应主动推动** common 契约（含 `CASE_CEASED`）尽早合入；引擎/ingestion 消费端可并行开发，全链路验收见 §7 Checklist 末项。

---

## 1. 阶段 0：契约演进（collection-common / collection-service）

> 依据 [collection-channel 总规格 §4](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#4-collection-common-演进phase-1-必选)。**一切 Adapter / 真实 SPI 之前必须完成。**

### 1.1 任务清单

| # | 文件/模块 | 变更 | 验收 |
|---|-----------|------|------|
| 0-1 | `UserProfile.BasicInfo` | 增加 `email` | Mock Profile 可返回测试邮箱 |
| 0-2 | `UserProfile.DeviceInfo` | 增加 `jpushToken`（JPush Registration ID） | Mock Profile 可返回测试 token |
| 0-3 | `CaseContext` | 增加 `repaymentUrl` | ingest/snapshot 可带入深链 |
| 0-4 | `StepCommand` | 补全 metadata 常量：`META_SCRIPT_SLOT`、`META_SMS_BODY`、`META_DYNAMIC_TEMPLATE_DATA`、`META_CASE_ID`、`META_FALLBACK_SMS`（与总规格 §3.1 一致） | Resolver / Adapter 编译通过 |
| 0-5 | `EventType` | 新增 **`CASE_CEASED`** | `mvn compile`；枚举与 [编排规格 §4.2](./MOCASA催收系统升级_Phase1_渠道编排规格.md#42-完全停催d91) 一致 |
| 0-6 | `CollectionEvent` | 新增 payload 常量：`MAX_DPD`、`DISPOSITION`、`PROVIDER_MSG_ID`、`RESULT`（Voice 回调 / Webhook 复用） | 发布/订阅方可 `event.with(CollectionEvent.MAX_DPD, 91)` |
| 0-7 | `MockProfileService` | 按 `userId` 返回 phone / email / jpushToken | 单渠道联调有数据 |
| 0-8 | `collection-channel/pom.xml` | 增加 HTTP 客户端依赖（`spring-web` 或 `okhttp`） | `mvn compile` 通过 |

```bash
mvn -pl collection-common,collection-service,collection-channel -am compile
```

**`CASE_CEASED` 最小契约**（阶段 0 合入 common，与 [核心引擎规格 §2.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md) 一致）：

```java
// EventType.java
CASE_CEASED,

// CollectionEvent.java — payload 建议字段
public static final String MAX_DPD = "maxDpd";
// 发布示例（ingestion 日切 / 联调 mock）
CollectionEvent.of(EventType.CASE_CEASED)
    .with(CollectionEvent.CASE_ID, caseId)
    .with(CollectionEvent.MAX_DPD, 91);
```

**渠道侧配套**（阶段 3 `DefaultPlanFactory`，此处先约定）：

- `collection_status == CEASED` 或 snapshot `max_dpd >= 91` → **`create()` 返回 `null`**（不再建 plan）。
- 与引擎 `onCaseCeased`（cancel pending steps）联调；可用 admin mock 发布事件做冒烟（见 §7）。

### 1.2 不在本阶段做

- **编排表 DDL**（`t_contact_plan_template` 等）：Phase 1 模板走 **Java 常量 + Nacos `channel.plan-templates`**，见 §4.1、§6。
- **引擎 Consumer / ingestion 日切实现**：归属见 §0.5，但不阻碍本阶段先把 **事件类型与 payload 定稿**。

---

## 2. 阶段 1：执行子层 — 基础设施 + 单 Adapter 冒烟

> 目标：**跑通真实供应商 API**；在换 `DefaultStepResolver` 之前，先增强 Mock 以提供 Adapter 所需字段。

### 2.1 目录结构（新建）

```text
collection-channel/src/main/java/com/collection/channel/
├── config/
│   └── ChannelProperties.java
├── gateway/
│   ├── ChannelGatewayImpl.java
│   └── MockChannelGateway.java         # @Primary 切到 Impl 后删除
├── adapter/
│   ├── NotificationClient.java
│   ├── NotificationSmsAdapter.java
│   └── NotificationPushAdapter.java    # 过渡期可保留 LthSmsAdapter / FcmPushAdapter
└── strategy/                           # 阶段 3 陆续补齐
    ├── MockStepResolver.java           # 阶段 1b 最小增强
    └── ...
```

### 2.2 阶段 1a：配置与 Gateway 骨架

| 步骤 | 类 | 要点 |
|------|-----|------|
| 1 | `ChannelProperties` | `@ConfigurationProperties(prefix = "channel")` + `@RefreshScope`，字段见 §6 |
| 2 | `ChannelGatewayImpl` | 渠道幂等占位；`SMS` → `NotificationSmsAdapter`（现代码仍为 `LthSmsAdapter`，待迁移）；`PUSH` → `NotificationPushAdapter`；其余暂委托 Mock 或 `UnsupportedChannelException` |
| 3 | Bean | `ChannelGatewayImpl` `@Primary` 实现 `ChannelGateway` |

### 2.3 阶段 1b：MockStepResolver 最小增强（**Adapter 冒烟前置**）

在实现真实 `DefaultStepResolver` 之前，先改 `MockStepResolver`，否则 Adapter 拿不到正确 payload：

| 渠道 | `targetAddress` | `metadata` 最小填充 |
|------|-----------------|---------------------|
| SMS | `primaryPhone` | `sms_body`（联调可用 `"[MOCK] " + scriptSlot`）、`scriptSlot`、`language=tl` |
| PUSH | `jpushToken` | `title`、`body`、`data`（JSON 字符串）；无 token 时仍解析 phone 供 fallback |
| EMAIL | `email` | `dynamicTemplateData`（含 `repaymentUrl`）、`case_id` |
| AI_CALL / TTS | `primaryPhone` | `callbackUrl` = `channel.callback.base-url` + `/lth/voice`；`timeoutMinutes=60` |

异步渠道 **禁止** 使用相对路径 `"/webhook/channel-callback"`，必须拼完整 `base-url`。

### 2.4 阶段 1c：NotificationSmsAdapter + 单步联调

| 步骤 | 类 | 要点 |
|------|-----|------|
| 1 | `NotificationSmsAdapter` + `NotificationClient` | 读 `META_SMS_BODY`；`POST /v1/sms/send`；`contentType=collection`；映射 [Notification 对接说明](./channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) §1 |
| 2 | `MockPlanFactory` | Nacos `channel.debug.single-step: SMS` 时仅 1 步、`delayMinutes=0` |
| 3 | 验收 | [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) **TC-SMS-01** |

```java
// MockPlanFactory：channel.debug.single-step=SMS 时
steps.add(buildStep(1, ChannelType.SMS, 0, 0, 101L));
```

---

## 3. 阶段 2：四 Adapter + Gateway 完整路由

按风险从低到高实现（每完成一个跑对应 TC）：

| 顺序 | Adapter | 文档 | 同步/异步 | 备注 |
|------|---------|------|-----------|------|
| 1 | `NotificationSmsAdapter` | 通知中心 SMS | 同步 → STEP_COMPLETED（`requestSuccess=true`） | 阶段 1 占位完成（`LthSmsAdapter` 待替换） |
| 2 | `NotificationPushAdapter` | 通知中心 Push（JPush） | 入队成功 → STEP_COMPLETED | 无 token → **同槽 SMS fallback**；JPush 投递失败 Phase 1 不 fallback |
| 3 | `SendGridEmailAdapter` | SendGrid Email | 同步 | `custom_args.idempotency_key`；Event Webhook **不**完成 step |
| 4 | `LthVoiceAdapter` | LTH Voice | 异步 | **`AI_CALL` 与 `TTS` 共用一个 Adapter** |

### 3.1 StepResult 映射（必读，防引擎误重试）

引擎对 `success=false` 且 `retryable=true` 会注册退避重试（[核心引擎规格 §3.2](../MOCASA催收系统升级_Phase1_核心引擎规格.md)）。**业务结果 ≠ 基础设施失败**：

| 场景 | `success` | `contactResult` | `retryable` |
|------|-----------|-----------------|-------------|
| API 已受理 / 同步发送成功 | `true` | `DELIVERED`（消息类） | — |
| Voice 回调 `NO_ANSWER` / `BUSY` / `REJECTED` | **`true`** | 同名 `ContactResult` | `false` |
| Voice 回调 `ANSWERED` | `true` | `ANSWERED` | `false` |
| HTTP 5xx、网络超时、供应商宕机 | `false` | `FAILED` 或 `CHANNEL_DOWN` | **`true`** |
| 号码/token 永久无效 | `false` | `FAILED` | **`false`** |

**禁止**将 `NO_ANSWER`、`BUSY`、`REJECTED` 映射为 `success=false`，否则引擎会当作故障无限重试。  
Voice **终态**由 `CHANNEL_CALLBACK` + `AdvancementPolicy` 处理；Adapter dispatch 仅表示「已成功提交外呼」。

`ChannelGatewayImpl.dispatch`（总规格 §5.2）：

```
1. 渠道幂等 Redis GET/SET idempotency:channel:{idempotencyKey} TTL 24h
2. switch(channelType) → adapter.send(command)
   HUMAN_CALL → 禁止路由（抛 IllegalStateException，对齐 E4）
3. 返回最终 StepResult（熔断/fallback 在 adapter 内消化）
```

可选 **L0**：Adapter + WireMock 单测，不依赖引擎扫描。

---

## 4. 阶段 3：策略子层（SPI 替换）

> 依据 [渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围)。  
> **顺序原则**：先 **PlanFactory**（定义步骤），再 **StepResolver**（渲染命令），再 **Guard / Policy**。

### 4.1 `MockPlanFactory` → `DefaultPlanFactory`

**Phase 1 模板来源**：`collection-channel` 内 Java 常量（或读 Nacos `channel.plan-templates` YAML），**不依赖** `t_contact_plan_template` 表。

**`create()` 入口守卫（必须第一行，优先于 `match(stage, tone)`）**：

```
1. caseInfo.collectionStatus == CEASED
   OR snapshot.caseContext.collectionStatus == CEASED
   OR snapshot.caseContext.dpd >= 91  → return null
2. 再 match(stage, tone) 展开模板
```

> 勿仅依赖入参 `StageEnum`：D+92 时枚举仍可能是 S4，但 snapshot `caseContext.dpd` 已 ≥91，必须拒建 plan（[编排规格 §4.2](./MOCASA催收系统升级_Phase1_渠道编排规格.md#42-完全停催d91)）。

| 约束 | 编排依据 |
|------|----------|
| `match(stage, tone)` | 8 套骨架：S0–S4 **STANDARD** + S2–S4 **FIRM**（§7.1）；tone 读 snapshot `strategy_tone`；**FIRM 判定口径**见 [渠道编排 §6.3.1](./MOCASA催收系统升级_Phase1_渠道编排规格.md#631-难催子条件计算口径ingestion-层) |
| 一 Stage 一 plan | 进入 Stage 时 **单次 `create`** 展开全部未过期 `DayBlock`（S4 约 60 日块一次铺完，§7.1） |
| 晚进案（跨日） | 跳过已过期 `dpd_day` 日块，**不追溯补发**（§7.0） |
| **晚进案（同日）** | 见下表 |
| S4 D+61~D+90 | **仅 1 次 AI/日**，**不生成 Wave-2** step（§7.8） |
| 禁止生成 | `*_EMAIL_CONDITIONAL`、无互动相关 step、`HUMAN_CALL`（§3.5） |
| scriptSlot | 步骤 `templateId` 存 scriptSlot 名；与 [总规格附录 A](./MOCASA催收系统升级_Phase1_collection-channel总规格.md#附录-ascriptslot--供应商-template_id-映射表) 对齐 |

**晚进案 — 同日 missed 槽位（Phase 1 定稿）**：

| 情况 | 行为 |
|------|------|
| 今日 `dpd_day` 有效，但槽位绝对 `trigger_time` **已早于** `now`（PHT） | **跳过**，不补发、不立刻并发触发 |
| 今日仍有 `trigger_time >= now` 的槽位 | 从**第一个未过期槽位**起生成 step |
| 今日所有槽位均已过期 | 当日不生成 step；等下一自然日或下一 `dpd_day` 日块 |

示例：PHT 11:00 进案，当日本有 08:00 SMS — **不补发** 08:00，从 12:00 Push 或下一未过期槽位继续。

实现：`trigger_time = max(slotScheduledTime, planCreateTime)` 在 PHT 下计算；若 `slotScheduledTime < planCreateTime` 则**丢弃该 step**。

S0 最小日块（验收必含）：D-3/D-2 `S0_REMINDER`、D-1 `S0_REMINDER_URGENT`、D0 Push/SMS + D0 14:00 `S0_DUE_TODAY_EMAIL`（§7.4）。

### 4.2 `MockStepResolver` → `DefaultStepResolver`

| 项 | 要求 |
|----|------|
| I/O | **零 DB**；只读 `ExecutionContext` + `contextSnapshot` |
| 地址 | SMS→phone，EMAIL→email，PUSH→jpushToken，AI_CALL/TTS→phone |
| SMS | 按 `scriptSlot` + snapshot 渲染 **`sms_body`**（`repaymentUrl`、产品变量；**F10 Offer Phase 1 占位**，见下） |
| EMAIL | `dynamicTemplateData` + SendGrid `templateId`（[渠道模板清单 §3.1](./MOCASA催收系统升级_Phase1_渠道模板清单与配置.md#31-配置映射) `channel.sendgrid.templates`） |
| 异步 | `callbackUrl`、`timeoutMinutes`（默认 60） |
| metadata | `stage`、`language`（默认 `tl`）、`scriptSlot`、`case_id` |
| 禁止 | 输出 `HUMAN_CALL`（E4） |

**Phase 1 与 F10 Offer**：优先跑通链路；`sms_body` / `dynamicTemplateData` 中 **offer 减免字段可先留空或写固定占位文案**（如「详见 App 还款页」）。snapshot 中 offer 字段的完整注入留 Phase 2（[编排规格 §5.3](./MOCASA催收系统升级_Phase1_渠道编排规格.md#53-offer-与-bill-维度)）。

### 4.3 `MockExecutionGuard` → `ComplianceExecutionGuard`

| 规则 | 行为 | 依据 |
|------|------|------|
| 日限额 | Redis `compliance:daily:{userId}:{channel}:{date}` | §7.11 |
| **`{date}` 时区** | **必须** `ZoneId.of("Asia/Manila")` 格式化为 `yyyy-MM-dd`；**禁止**用服务器默认时区（UTC/CST） | 与触达窗 PHT 一致 |
| 触达窗 | **08:00–21:00 PHT**（与 `quiet-hours` 21:00–08:00 对称） | §7.11 |
| 无邮箱 | EMAIL 步骤 → `BLOCK`，reason=`NO_EMAIL` | §3.5 |
| 投诉/争议冻结 | snapshot `complaint_frozen` / `dispute_active` → **全渠道 BLOCK** | §6.1 |
| 外呼限额 | `AI_CALL` / `TTS` 共用 Voice 计数；默认 **2/日/户**；S4 D+61~90 由 PlanFactory 限 1 step/日，Guard 读配置 `AI_CALL: 2` 且 **不单独放宽** | §7.8、§7.11 |
| 性能 | 单次 Redis Lua，**硬超时 20ms**（HANDOFF A2） | 基础设施 |

```java
// 日限额 key 示例（PHT 自然日）
ZoneId PHT = ZoneId.of("Asia/Manila");
String date = LocalDate.now(PHT).format(DateTimeFormatter.ISO_LOCAL_DATE);
String key = "compliance:daily:" + userId + ":SMS:" + date;
```

### 4.4 `MockAdvancementPolicy` → `DefaultAdvancementPolicy`

| 场景 | 决策 |
|------|------|
| 消息类 step 成功 | `ADVANCE_NEXT` |
| Voice `ANSWERED` 但未还 | `ADVANCE_NEXT` + **取消当日 Wave-2**（`CONNECT_AND_STOP`，§7.2） |
| Voice disposition | 按 [LTH Voice](./MOCASA催收系统升级_Phase1_LTH_Voice对接说明.md) 映射 PTP / 需人工等（§7.10） |
| 最后一步完成 | `PLAN_COMPLETED` |

### 4.5 `MockExhaustionPolicy` → `DefaultExhaustionPolicy`

- 同 Stage 模板轮换 → `REBUILD`（`templateId` 必填）；受 `engine.plan.max-rebuild-count`（默认 2）限制。
- Stage 升阶由 **`STAGE_CHANGED`** 触发新 plan，**不**依赖每日 `PLAN_EXHAUSTED`（§7.1）。

每替换一个 SPI：`mvn package` → Mock 回归 + 对应 TC。

---

## 5. 阶段 4：Webhook（collection-admin + collection-channel）

| 路径 | 行为 | 文档 |
|------|------|------|
| `POST /webhook/channel-callback` | 扩展 `disposition`、`providerMsgId` → `CHANNEL_CALLBACK` | 总规格 §3.3 |
| `POST /webhook/lth/voice` | 解析 LTH 话单 → `CHANNEL_CALLBACK`（`AI_CALL`/`TTS`） | LTH Voice |
| `POST /webhook/sendgrid` | 验签（Phase 1 TODO 可开关）→ 按 `providerMsgId` **幂等**升级 timeline | SendGrid Email |

建议在 `collection-channel/webhook/` 放 Parser 服务，admin 只写薄控制器。

**不发布 `STEP_COMPLETED` 的路径**：SendGrid Event Webhook、LTH 非终态回调。

---

## 6. Nacos 配置清单

```yaml
collection:
  scan:
    interval-ms: 5000

channel:
  debug:
    single-step:        # 空=正常；SMS|PUSH|EMAIL|AI_CALL|TTS
  callback:
    base-url: https://<联调域名>/webhook

  # Phase 1 计划模板（可选；未配置则用 Java 内置常量）
  plan-templates:
    s1-standard: ...    # 结构与编排 §7.5 日块一致

  notification:
    base-url: https://service-test.mocasa.com/notification
    app-code: mocasa
    app-key: <运维下发>
    sms-content-type: collection
  lth:
    voice:
      url: https://...
  sendgrid:
    api-key: SG.xxx
    from-email: collections@...
    unsubscribe-group-id: 12345
    webhook-public-key: ...

  compliance:
    daily-limit:
      SMS: 3
      PUSH: 2
      EMAIL: 1
      AI_CALL: 2      # 与 TTS 共用 Voice 计数；S4 D+61~90 靠 PlanFactory 限 1 step/日
      TTS: 2          # 与 AI_CALL 同槽计数，勿省略
    timezone: Asia/Manila   # 日限额 {date} 与触达窗均用此时区
    quiet-hours-start: "21:00"
    quiet-hours-end: "08:00"
    touch-window-start: "08:00"   # Guard 触达窗起点
    touch-window-end: "21:00"

spring:
  datasource: { ... }
  redis: { host: ..., port: 6379 }
```

### 6.1 配置与代码映射

| Nacos 路径 | 使用方 | 说明 |
|------------|--------|------|
| `channel.notification.*` | `NotificationSmsAdapter` / `NotificationPushAdapter` | `base-url`、`app-code`、`app-key`；见 [Notification 对接说明](./channel/MOCASA催收系统升级_Phase1_Notification对接说明.md) §0.4 |
| `channel.sendgrid.*` | `SendGridEmailAdapter` | API Key、发件人、退订组 |
| `channel.lth.voice.*` | `LthVoiceAdapter` | 外呼 API |
| `channel.callback.base-url` | `DefaultStepResolver` / Mock 增强版 | 完整 callbackUrl |
| `channel.compliance.*` | `ComplianceExecutionGuard` | 限额（含 AI_CALL/TTS）、时区、触达窗 |
| `channel.plan-templates` | `DefaultPlanFactory` | 可选 YAML 模板 |
| `channel.debug.single-step` | `MockPlanFactory` → `DefaultPlanFactory` | 单渠道冒烟 |
| `collection.scan.interval-ms` | `TriggerScanner` | 联调等待 |

`@RefreshScope` 的 `ChannelProperties` 可热更新非密钥项；**API Key 变更后重启**。

### 6.2 本地覆盖

| 场景 | 做法 |
|------|------|
| 测试 Nacos 公共账号 | `.env` 填 `NACOS_*` |
| 本机端口/日志 | `application-local.yml` |
| 渠道密钥 | Nacos `intelligent-collection-local.yml` → `channel.sendgrid.*` / `channel.notification.app-key`（`scripts/dev/publish-channel-secrets-to-nacos.ps1`） |

---

## 7. 推荐开发时间线（Checklist）

```
[ ] 0.  .env + Nacos 连通；基线 Mock 链路 PLAN_COMPLETED
[ ] 1.  阶段 0：common 契约（含 CASE_CEASED、StepCommand/CollectionEvent 常量）
[ ] 2.  ChannelProperties + Nacos channel.* 段
[ ] 3.  MockStepResolver 最小增强（sms_body / 多地址 / 完整 callbackUrl）
[ ] 4.  NotificationSmsAdapter + ChannelGatewayImpl → TC-SMS-01
[ ] 5.  NotificationPushAdapter（含 fallback）→ TC-PUSH-01/02
[ ] 6.  SendGridEmailAdapter → TC-EMAIL-01/02
[ ] 7.  LthVoiceAdapter（AI_CALL + TTS）+ /webhook/lth/voice → TC-VOICE-01/02
[ ] 8.  DefaultPlanFactory（8 套骨架、晚进案、S4 分段、禁止条件 Email）
[ ] 9.  DefaultStepResolver（渲染 + 附录 A 映射）
[ ] 10. ComplianceExecutionGuard（限额 + 冻结 + NO_EMAIL）
[ ] 11. AdvancementPolicy（disposition + Wave-2 取消）+ ExhaustionPolicy
[ ] 12. Webhook SendGrid timeline 幂等升级
[ ] 13. 删除全部 Mock 类，全量回归
[ ] 14. E2E：CASE_CEASED 联调（引擎 onCaseCeased + PlanFactory null；ingestion 日切或 mock 发布）
```

**阶段 3 完成后建议冒烟矩阵**：

| 场景 | 预期 |
|------|------|
| S0 四日块 | Push/SMS + D0 Email |
| S1 全员 STANDARD | 无 FIRM |
| S2+ 难催 snapshot | FIRM 模板 |
| S4 D+65 | 仅 1 AI step，无 Wave-2 |
| 无邮箱 userId=90005 | EMAIL → SKIPPED |
| `CASE_CEASED` 后 | 无新 plan；pending step 已 cancel |

---

## 8. 常见问题

| 现象 | 排查 |
|------|------|
| 启动报 Nacos 连接失败 | `.env` 中 `NACOS_*`、VPN/内网 |
| 计划不推进 | `collection.scan.interval-ms`、`delay_minutes`、`trigger_time` |
| SMS 调通但 LTH 收到空正文 | MockStepResolver 未填 `sms_body`；或 DefaultStepResolver 未上线 |
| Email 目标地址是手机号 | StepResolver 未按渠道解析 `email` |
| Voice 一直 STEP_EXECUTING | LTH 回调 `/webhook/lth/voice` 或手动 `CHANNEL_CALLBACK` |
| Voice 未接却反复重试 | Adapter 将 NO_ANSWER 误标 `success=false`；见 §3.1 |
| 日限额零点不准 | Guard 未用 `Asia/Manila` 格式化 `{date}`；见 §4.3 |
| 11:00 进案补发 08:00 SMS | PlanFactory 未跳过同日已过期 `trigger_time`；见 §4.1 |
| D+92 仍建 S4 plan | `create()` 未先查 snapshot `caseContext.dpd`；见 §4.1 |
| Email step 卡住 | SendGrid Event **不应**完成 step；查 dispatch `success` |
| D+91 仍在触达 | 查 `CASE_CEASED` 是否发布、引擎是否订阅、`PlanFactory` 是否对 CEASED 返回 null |
| 配置不生效 | `@RefreshScope`；密钥类需重启 |

---

## 9. 关联文档

| 文档 | 用途 |
|------|------|
| [渠道文档索引](./README_渠道文档索引.md) | L1/L3 导航 |
| [渠道编排规格 §3.5](./MOCASA催收系统升级_Phase1_渠道编排规格.md#35-phase-1-实现范围) | Phase 1 裁剪边界 |
| [核心引擎规格 §2.4](./MOCASA催收系统升级_Phase1_核心引擎规格.md) | `CASE_CEASED`、Override 中断 |
| [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | TC 与 curl |
| [操作说明_Nacos本地启动](../操作说明_Nacos本地启动.md) | 本地/Docker 启动 |
