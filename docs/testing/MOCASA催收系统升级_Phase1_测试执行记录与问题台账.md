# MOCASA 催收系统升级 — Phase 1 测试执行记录与问题台账

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-08-19  
> **关联文档**: [数据接入规格](../MOCASA催收系统升级_Phase1_数据接入规格.md)、[数仓 Pub/Sub 交付契约](../数仓_PubSub交付契约.md)、[T5 Pilot 准备与演练手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md)、[L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md)
> **文档分工**: 本文保存从测试 SSOT 迁出的执行过程、取证摘要、问题定位与裁定理由；当前测试范围、用例与完成状态以[测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md)为准。
> **待办（T3o 跑完后执行，2026-08-21 决定）**: 附录 C 已达 16 条超长条目，拆为独立《缺口台账》、正文只留状态矩阵与指针。**现在不拆**——该附录正处于高频改动期，拆分会引入两份文档不同步的风险。

---

## 1. 术语与范围

### 两个正交维度

测试层级与测试阶段正交，不得混用：

- **测试层级**：被测的集成边界。同一层级可在多个阶段复跑。
- **测试阶段**：按风险与环境成熟度的准入顺序。一个阶段可覆盖多个层级。
- **Mock（替身）**：测试策略，不是层级。可用于 SPI、Repository、事件源、供应商或测试数据；必须在用例的“允许替身”列中说明。

| 维度 | 定义 | 取值 |
|---|---|---|
| 测试层级 | 被测集成边界 | L0、L1、L2、L3、L4a、L4b |
| 测试阶段 | 准入顺序、风险与运行成熟度 | T0、T1、T2、T3、T3o、T4、T5、T6 |

| 层级 | 定义 | 当前主载体 |
|---|---|---|
| L0 | 单模块纯逻辑测试；外部边界可替身。按 engine、channel、ingestion 与 admin 组织，admin 再分运行入口与后台管理 | 各模块 unit tests |
| L1 | 引擎内存集成；真实引擎组件 + 内存总线/仓储 | engine 集成测试 |
| L2 | 引擎↔渠道执行契约；双方以同一契约断言对接 | engine 与 channel 契约测试 |
| L3 | 真实 MyBatis/MySQL 持久化集成 | service/admin 集成测试 |
| L4a | 合成数据源 + 真实渠道的端到端验证 | L4a 官方脚本 |
| L4b | 隔离真实 Pub/Sub 来源 + 真实投影/渠道的端到端验证 | L4b 官方脚本与 SQL 断言 |

### 阶段定义与放行边界

Phase 1 测试覆盖 `caseEvent / repaymentEvent → 投影与 inbox → 内部事件 → 计划与触达` 全链路，以及其在 Pilot 和渐进切量中的可靠性、合规与回滚要求。

| 阶段 | 目标 | 主要层级 | 放行条件 |
| --- | --- | --- | --- |
| T0 | 环境就绪与文档代码对齐 | — | 配置、权限、停止开关可用；断言依据无漂移 |
| T1 | 契约与组件正确 | L0、L1、L2 | 单测/内存集成全绿 |
| T2 | 持久化与可靠性 | L3 | 真实 MySQL 事务语义通过 |
| T3 | 隔离端到端 | L4a、L4b | Pub/Sub 主路径闭合 |
| T3o | 生产等价演练与简版观测 | 见 §7 | Redis、调度、观测 MVP 可操作 |
| T4 | 50 案真实 Pilot | 真实白名单 | 数仓消息、逐案零安全阻断 |
| T5 | 渐进切量 | 每日 cap 放量 | 每级观察窗达标 |
| T6 | 稳态运营 | 生产 | 监控、告警、复盘闭合 |

**阶段依赖**

```text
T0 → T1 → T2 → T3 → T3o → T4 → T5 → T6
```

- T1：任何代码变更后持续回归。
- T2 与 T3 环境准备可并行；T3 出口未闭合前不得进入 T3o。
- T3o 未闭合前不得启动 T4 真实客户触达。
- T4 通过固定 50 案后，才进入 T5 按 cap 放量。

**各阶段边界**

| 阶段 | 边界 |
| --- | --- |
| T0 | 不验证业务语义，不触达客户 |
| T1 | 外部依赖可替身；接入变更至少跑 ingestion 单测 |
| T2 | 不允许用内存 Repository 替代被测持久化边界 |
| T3 | L4b 主断言为 payload ↔ inbox ↔ 投影 ↔ plan；旧库仅作兼容对账 |
| T3o | 只用隔离 topic、sandbox 或测试地址；不要求 Dashboard |
| T4 | 数仓按正式契约发布；仅处理批准 50 案，其余 ACK 跳过 |
| T5 | 每批 cap 由业务审批；未达标立即冻结或回退。监控平台可后置，但每日人工巡检记录不可省 |
| T6 | 自动监控与告警为全量运营前置；移除白名单前必须闭合 |

### 事实来源与职责边界

| 信息 | 权威来源 | 本文的处理方式 |
|---|---|---|
| 测试准入、用例、出口、当前状态 | **本文** | 唯一 SSOT |
| 领域、契约、数据接入语义 | 核心引擎规格、契约文档、数据接入规格 | 作为断言依据引用，不复制为第二份规格 |
| 渠道 `TC-*` 细节与供应商参数 | 渠道功能测试指南 | 本文仅索引 TC-ID |
| 测试命令、脚本入参与日志路径 | [`scripts/README.md`](../../scripts/README.md) | 本文不写命令 |
| L4b topic、订阅、Nacos、凭证、操作顺序 | [L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | Runbook，不承担测试裁决 |
| Pilot 配置、50 案执行、切量与回滚操作 | [T5 Pilot 准备与演练手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | Runbook，不承担测试裁决 |
| 指标口径与告警阈值 | 基础设施交互规范 | 引用，不在本文重复定义 |
| 单次执行日志与历史报告 | 运行报告/日志 | 证据材料；历史结果不进入本文状态列 |

### 状态与证据规则

| 标记 | 含义 |
|---|---|
| ✅ | 本轮已执行并满足退出条件，且有可追溯证据 |
| 🟡 | 本轮部分通过，存在明确未关闭项 |
| ⬜ | 本轮未执行或未满足准入 |
| ⏭ | Phase 2 或范围外 |

- 状态只反映**当前代码与契约版本**的执行结果。
- 本轮为全量重测：所有用例状态归零为 ⬜，逐条重跑后回填。
- 历史运行结果可作参考，但不得直接回填状态列。
- ⬜ 有三种成因（缺测试、缺实现、缺环境），闭合责任与排期方式不同，逐条登记见[附录 C](#附录-c缺口登记)。

---

## 2. T0 环境预检与基线对齐

### 目标

确认环境、隔离、权限与停止能力允许开始测试，并确保断言依据（文档）与实现（代码）一致。T0 不验证业务语义，不触达真实用户。

### 准入用例

| ID | 预检项 | 证据 | Owner | 状态 |
|---|---|---|---|---|
| T0-1 | 构建与 CI 可用；全仓单测可执行 | CI 记录 | 主架构 | ✅ |
| T0-2 | L4a 应用、Nacos、健康检查正常 | health、配置加载日志 | 主架构 | ✅ |
| T0-3 | L4b 隔离 topic/订阅、白名单、渠道沙箱就位 | preflight 输出、Nacos 片段 | 主架构 + 运维 | ✅ |
| T0-4 | 测试订阅独占消费，无其他活跃 consumer | 单条 publish 命中本机消费日志 | 运维 | ✅ |
| T0-5 | 数据库表、只读旧库访问、脱敏输出可用 | 表结构与账号范围确认 | 服务同事 + 运维 | ✅（T3 实际跑在本机 MySQL，见下注） |
| T0-6 | **文档与代码对齐**：接入契约、事件语义、回调入口、配置键、脚本入参无漂移 | 对齐结论与修正记录 | 主架构 | 🟡 |
| T0-7 | 观测基线盘点：确认 T3o 所需证据项当前是否可获取，缺口登记为代码交付 | 证据项清单与缺口列表 | 主架构 | ✅ |

> T0-6 是本轮全量重测的前置。已知需重点核对：Pub/Sub envelope 与指纹规则、回调 URL 生成与实际入口、发布脚本的 `caseVersion` 取值、白名单与开关配置键。

> T0-2 判定口径（2026-08-21）：`/actuator/health` 返回 **DOWN 属预期**，唯一 DOWN 项是
> `RedisReactiveHealthIndicator`——Phase 1 的事件总线、幂等、接入去重都是内存版（`collection.eventbus: memory`
> / `idempotency: memory` / `InMemoryIngestionDedupStore`），本地不起 Redis。故健康判据用
> `GET /plans/active/by-case/0` 返回 200（L4b preflight 与 `wait-health.sh` 同口径），
> 不用聚合 health。Redis 通路本身在 T3o 验收。

> T0-5 判定口径（2026-08-21）：共享 `ai_collection_db` 的表结构、账号范围与脱敏输出都已确认可用，
> 该项本身达标。但 T3 两组**最终跑在本机 MySQL 8.0.46** 上——共享库上有外来实例无过滤地抢走全库到期步骤
> （附录 C 有诱饵实验取证），L4a 在那上面无法取得可信结论。本机库与远端同版本、结构对齐 25 张表、
> 配置数据与 `t_collection` 的 IC_TEST_% 行已搬齐，故不影响 T3 结论的有效性。
> **Pilot 前必须回到受控共享库**，届时需先处置「扫描 SQL 无租户维度」这条遗留风险。

> **T0-3 的 Nacos 部分已改为环境变量驱动（2026-08-21，原计划的「发布 L4a/L4b 阶段配置」不再执行）**：
> `intelligent-collection-local.yml` 是与编排同事共用的 Data ID，每发一次都会扰动对方的联调，
> 而 `application-local.yml` 的优先级本就高于 Nacos，发上去的值也未必生效（`collection.case-service`
> 就吃过这个亏）。现在 L4a/L4b 需要的开关全部走环境变量：渠道回落 Mock、案件服务实现、
> 扫描与接入白名单、回调 HMAC、合规上限、故障注入、库连接。脚本 `l4b-env.local.sh` /
> `restart-and-l4a.sh` 即完整声明，可重复、不入仓、不影响他人。

#### T0-6 核查结论（2026-08-21 执行，逐条已复核到文件行号）

盘点已完成，因此 T0-6 不再是 ⬜（缺执行），而是 🟡。12 条中 **A1、A2、A7、A10、A12 已修**（代码 + 新单测，全仓 309 例全绿），**A4、A5、A6、A11 已按文档单侧更正**，剩 **A3（Facade 回调入口与验签口径不匹配，已升级为缺实现）、A8/A9（接入校验宽于契约，低危）** 未闭合。A3 只影响 AI_CALL 回调回流，不阻塞 T3 的其余链路取证，但 **T4 前必须闭合**（否则外呼结果永远回不来，只能等超时）。

| # | 漂移 | 权威侧 | 实现侧 | 性质与影响 |
|---|---|---|---|---|
| A1 | ~~回调地址下发 `{base}/lth/voice`，但应用只暴露 `POST /webhook/channel-callback`~~ | 渠道总规格规划三条入口 | `ChannelProperties#voiceCallbackUrl`、`DefaultStepResolver:107` vs `WebhookController:35` | **✅ 2026-08-21 已改**。系统确定只对接 Facade，`/lth/voice` 判为 LTH 时代死命名：`voiceCallbackUrl()` 更名 `callbackUrl()` 并指向 `/channel-callback`，2 例单测钉死路径段。**渠道模块非我主写，需知会编排同事** |
| A2 | ~~`FacadeAiCallAdapter` 未把 `metadata.callbackUrl` 传给 Facade~~ | ContextSnapshot 透传说明 | `FacadeAiCallAdapter#buildBatchBody` | **✅ 2026-08-21 已闭合，结论是「不该传」**。[Facade 客户接入手册](../channel/FACADE客户接入手册.md)确认 callback 是**账户级**配置、所有批次共用、由对方控制台登记，故当日先加的随单 `callback_url` 已回退。同一份修订说明还收紧了 `dial_policy`（只准 `timezone`/`windows`/`weekdays`，带 `retry`、`ring_timeout_sec`、`predictive`、`terminal_sip_codes`、顶层 `prepare_mode` 一律 **HTTP 422**，旧版是静默丢弃）——已用 1 例单测同时钉住「不带回调字段」与「不带这 5 个已取消字段」，因为静默丢弃期的回归在真实联调前不会暴露 |
| A3 | Facade 回调与现有入口**结构不匹配**：`WebhookController` 靠 query 参数 `planId`/`stepId`/`result` 定位步骤、验 `X-Callback-Signature`（HmacSHA256，canonical=`planId:stepId:result:providerMsgId:disposition`）；Facade 回调是账户级单一 URL，POST `session.completed` / `batch.completed` JSON，签名头用对方自定义方案 | 渠道文档只列 payload 字段，未定义签名 | `WebhookController:35-43` vs Facade 修订说明 §3 | **缺实现（本轮由「文档登记缺失」升级）**。账户级 URL 无法按批携带 `planId`/`stepId`，现有入口对 Facade 回调**根本不可用**：需新增 Facade 专用入口，按对方签名方案验签、按 `client_metadata`（case/plan/step，见 AI_Call 接入说明 §2）或 `external_case_id` 反查步骤、按 `session_id` 幂等、把 `was_answered` / `was_ai_connected` / `line_outcome.reason` 映射为触达结果。与 [AI_Call 接入说明 §4](../channel/MOCASA催收系统升级_Phase1_AI_Call_Facade接入说明.md) 第 1 条未完成边界同源。**渠道模块非我主写，需与编排同事定归属** |
| A4 | 附录 A.2 登记 `engine.compliance.daily_limit` / `quiet_hours_start/end`，代码从不读该前缀 | 基础设施规范附录 A.2 | 实际读 `channel.compliance.*`（`ConfigurableExecutionGuard`） | **文档过期**。运维按附录 A 配置静默时段与限频**不生效**，属高危 |
| A5 | 附录 A.4 登记 `collection.notification.owner`（LEGACY/PARALLEL/NEW），全仓无读取点 | 基础设施规范附录 A.4 | 无实现 | **文档过期**。D-3~D0 触达归属开关不存在 |
| A6 | Pilot 依赖但附录 A 未登记的键：`collection.webhook.signature-required` / `hmac-secret`、`collection.ingestion.redis-dedup-enabled` / `reserved-subscriptions` / `fault-injection-enabled`、`collection.case-service`、`collection.compliance.counter`、`engine.delivery-audit.*`、`engine.decision-log.*`、`collection.repayment-url-template`、`spring.redis.*` | 附录 A 无条目 | 代码与 `application-pilot.yml` 已在用 | **登记缺失**。运维照附录 A 配 Pilot 会漏配验签与 Redis 后端切换 |
| A7 | ~~L4b 发布脚本与样例用占位指纹 `0000…000${i}`、非 UUID `eventId`~~ | 本文档 §6.2「必须使用真实契约 envelope 与真实指纹，占位值不可用于取证」；契约 §2.3 样例为真实 MD5 | `publish-test-messages.sh:80`、`caseEvent.sample.json:8`、`l4b-official-test.sh:448` | **✅ 2026-08-21 已改**：两个脚本各加 `case_version()`（按契约六字段真算 MD5）与 `new_event_id()`（UUID），样例 JSON 同步为真值——`caseEvent.sample.json` 的 `60ecd3bd…` 即 `md5("99000000"+"2"+"3000.00"+"0.00"+"0"+"0")`，可复算校验。顺带修掉一处旧 bug：日志打印的 `eventId` 与消息体内的值来自两次独立 `date +%s`，可能不一致 |
| A8 | `repaymentEvent` 未校验 `eventType=REPAYMENT`、未拒绝契约禁止字段、`repayTime`/`paidAmount` 读都不读 | 契约 §2 字段表与禁止项 | `CasePayloadMapper#mapRepaymentDelta:144-175` | **实现偏离，低危（复核后下调）**。必填校验实际有六项（`caseId`/`userId`/`isFullCleared`/`dpd`/`overdueAmount`/`overduePenaltyAmount`，缺一即 poison，金额还查负值）；冻结样例 `repaymentEvent.sample.json` 不带任何禁止字段，故收紧的收益与风险都低。`repayTime`/`paidAmount` 属信息字段——余额更新用的是还款**后**的 `overdueAmount`，不读不影响语义，但对账时拿不到单笔金额 |
| A9 | 消费者允许无 `{dataType, data}` 信封的平铺 body | 契约「body 固定为 `{dataType, data}`」 | `PubSubCaseConsumer:172-181` | **实现偏离，仅此一条为真**。同时复核：`occurredAt` 兼容 `yyyy-MM-dd HH:mm:ss` 与 ISO 偏移、`caseId`/`userId` 兼容数字与字符串，这两项**不是**漂移而是必要兼容——两份冻结样例本身就一份用本地格式+数字、一份用 ISO+字符串。`caseEvent` 不带 `eventType` 也合规（代码只在非空且不等于 `CASE_INGESTED` 时 poison） |
| A12 | ~~`repaymentEvent` 携带 `stage`，接入侧直接写进投影~~ | 引擎 §4.6「余额更新不改写 stage」 | `repaymentEvent.sample.json:14` 带 `"stage": "S4"`；原 `CaseProjectionAssembler:54` 解析、`mergeRepayment` `setStage(delta.getStage())`、`updateRepaymentDelta` SQL 含 `stage` 列 | **✅ 2026-08-21 已改（接入侧忽略，不要求数仓删字段）**。选接入侧的理由：单侧可改、不等上游排期，且上游保留该字段对日切全量快照仍有用。做成**三层结构性保证**而非「记得不要 set」：①`updateRepaymentDelta` SQL 去掉 `stage` 列，调用方误传也写不进去；②`mergeRepayment`（真库与内存两套实现同步）不再复制 stage；③`CaseProjectionFields` 直接删掉 `stage` 字段，还款路径**不解析**它——否则一个非法取值就会让 `parseStage` 抛 poison 丢掉真实还款，代价是已还清的客户继续被催，比忽略字段严重得多。覆盖：1 例 L1 单测（`stage:"NOT_A_STAGE"` 仍正常解析出还款）+ 1 例仓储单测（合并后保留基线 stage）+ 1 例 L3 IT（真库落库后 `stage` 不变而余额已变，证明是 SQL 层保证）<br>**⚠️ 2026-08-24 本条已被推翻，改为「还款同步 stage」**——理由见 A19 |
| A10 | ~~L4b-10 要求「较新指纹后补投旧 `caseEvent` 快照，投影不回退」，但 `upsert()` 只比指纹是否相等~~ | 本文档 §6.2 L4b-10 vs 数仓契约「caseEvent 乱序当前不作为设计前提」 | `AiCaseProjectionRepository#upsert` | **✅ 2026-08-21 已补防护，L4b-10 维持原样**。裁定理由：该故障不需要上游乱序即可发生——Pub/Sub at-least-once 下，先投的旧消息 nack 重试、后投的新消息先成功，重试到来时 `eventId` 去重帮不上（旧消息从未成功处理过），旧快照就会覆盖新投影。实现：改用 `selectProjectionForUpdate`，只拒绝**严格更早**的 `updatedAt`（该值由 `occurredAt` 赋值，缺失即 poison），与还款路径同构；等时刻的每日刷新照常放行。`InMemoryCaseProjectionRepository` 同步同口径，否则 L4a 与 L4b 会对同一条重投给出不同结论。3 例单测覆盖（更旧拒绝 / 同指纹跳过 / 更新与等时刻放行） |
| A11 | 渠道功能测试指南回调示例用 `localhost:8080`，本地实际 `server.port=8888` | 渠道功能测试指南 | `application-local.yml:2` | **文档过期**（低危，仅误导本地联调） |
| A20 | 数仓新 dpd 口径（提前还清后 dpd 为负、下一期超 3 天则 stage=null）在现有代码里会连环失效 | 业务口径（2026-08-24 数仓确认）：提前还清后 dpd = 距下一个未还 dueDate 的天数（负值）；该 dueDate 超过 3 天时 stage 置 null | 四处叠加：①`RepaymentConsistencyGuard` 对「逾期归零」一律拒绝同步，会丢掉负 dpd 这条合法更新；②`mergeRepayment` 判 `stage != null` + SQL `COALESCE(#{stage}, stage)`，两层都把显式 null 当「字段缺失」，null 永远写不进列；③`AiCollectionCaseService#stage` 无条件退回 `Stage.fromDpd`，而该函数对 dpd&lt;-3 兜底返回 **S0**，把数仓明确表达的「无阶段」静默还原成 S0；④`MockPlanFactory#shouldRejectPlan` 只有 dpd≥91 上界、无下界 | **✅ 2026-08-24 已改（口径上线前先行适配）**。合并后果：提前还清、下一期还有 5 天的客户仍会被建出 S0 计划。四处对应修复：①护栏改判据为「逾期归零时 dpd 必须≤0，报正 dpd 才是口径错误」，负 dpd 正常放行；②新增 `stagePresent` 标志（与既有 `nextDueDatePresent` 同构），**非法取值仍算「未携带」以保基线**，SQL 去掉 `COALESCE` 改普通赋值——「缺字段保基线」由合并逻辑负责，其入参本就是 `SELECT ... FOR UPDATE` 读出的基线行；③读取侧仅在 dpd≥-3 时才按 dpd 兜底，`dpd < -3` 直接返回 null；④`shouldRejectPlan` 补下界拒建。日切侧同步补 `newStage == null` 分支，否则 `compareTo` 会 NPE。<br>覆盖：3 例 L1 映射（同步 S4 / 显式 null 算 present / 缺字段不算 present）+ 3 例仓储单测（同步、缺字段保基线、显式 null 清空）+ 2 例建计划闸门（dpd=-5 拒建、dpd=-3 边界放行）+ 7 例护栏专项 + 1 例 L3 真库 IT（同步 S4 → 缺字段仍 S4 → 显式 null 真正落成 NULL） |
| A19 | 投影出现「stage 来自 caseEvent、dpd 来自 repaymentEvent」的自相矛盾组合 | 业务口径（2026-08-24 确认）：`stage` 与 `dpd` 均以数仓字段为准，还款后两者同步变化 | 案 502880 落库为 `stage=S2 / dpd=66`：stage 来自入案事件，dpd 来自还款事件，互不自洽 | **✅ 2026-08-24 已改，推翻 A12**。A12 当时把「投影 stage 列」误同于「计划快照 stage」而整体禁写；实际引擎 §4.6 约束的是 `CaseContext`，与投影列是两回事。现改为还款事件同步写投影 `stage`，并保留 A12 真正有价值的那层防护：**非法取值只跳过该字段、绝不 poison 整笔还款**（丢还款＝已还清客户继续被催）。兜底做在 SQL 层 `stage = COALESCE(#{stage}, stage)`，缺字段时保持基线。<br>**未绕过升档决策**：`balanceUpdated` 不带 stage，计划快照不受影响；投影 stage 变化仍由日切比对计划 stage 后发 `STAGE_CHANGED`，且单调前进护栏仍拦回退。<br>覆盖：2 例 L1（同步 S4 / 非法值不丢还款）+ 2 例仓储单测（同步、为空保基线）+ 1 例 L3 IT 真库（同步 S4 → 再投不带 stage 的增量仍为 S4，证明 COALESCE 生效） |

> A4 / A5 / A6 是文档单侧修正，我可直接改；A1 / A2 / A7 / A8 / A9 / A10 / A12 涉及运行时行为或验收口径，已逐条裁定后再动。

#### T0-7 观测基线（2026-08-21 盘点完成）

结论：**引擎与 Redis 总线、调度链的埋点齐备**——基础设施规范 §7.3 表列的指标在 `collection.eventbus=redis` 下逐条存在，tag 一致，且代码未私自新增表外指标。缺口集中在接入侧与查询入口，已登记为代码交付：

| 缺口 | 影响的验收项 | 性质 |
|---|---|---|
| `collection-ingestion` 全模块零 Micrometer 埋点（ACK / NACK / poison / `eventId` 去重跳过都只有日志，去重命中甚至静默 return） | T3o-O1 | 缺实现 |
| 接入路径不写 MDC（`eventId` / `caseId`），无法按案件串起消费链 | T3o-O1 | 缺实现 |
| inbox 无非 SQL 查询入口；`countPendingPublish()` 已实现但未注册 gauge、未暴露 HTTP | T3o-O1 · O2 | 缺实现 |
| inbox 无失败原因结构化字段，失败信息只在 NACK 日志的异常串里 | T3o-O2 | 缺实现 |
| 外部 `event_id` 与内部领域事件 UUID 无同层关联，只能靠 `case_id` + 时序间接对齐 | T3o-O1 · O2 | 缺实现（设计层） |
| 日切完成标记只在 Redis key 与日志里，无 gauge / HTTP | T3o-O3 | 缺实现 |
| 渠道失败无专用 counter（`collection.touch.total` 成功失败都 +1）；Resolver 策略跳过不计入 `collection.step.skipped` | T3o-O3 | 缺实现 |
| `t_event_dlq` 与 Redis DLQ stream 只有 `POST /ops/dlq/redrive`（写），无只读明细查询 | T3o-O4 | 缺实现 |
| 内存总线（local / L3 / L4 默认）下 §7.3 事件总线指标全部不上报；`/actuator/prometheus` 仅 pilot profile 暴露 | T3o-O4 取证前置 | 缺文档 + 缺环境 |

**T0-3 / T0-4 落地口径（2026-08-20 自助开通，脚本 [`scripts/test/provision-l4-pubsub.py`](../../scripts/test/provision-l4-pubsub.py) 可重复执行）**：
运维短期无法交付资源，改由主架构用自有 GCP 身份开通（已核验具备 `pubsub.topics.create` /
`subscriptions.create` / `topics.attachSubscription` 权限）。

| 用途 | 资源 | 参数 |
|---|---|---|
| L4a 合成源 | topic `intelligent-collection-cases-test1` + sub `intelligent-collection-cases-test1-sub` | ack 60s、保留 1 天、闲置 7 天自动删除、死信 5 次 |
| L4b 真实源 | sub `intelligent-collection-cases-v1-l4b-sub`（挂正式 topic） | 同上；扇出独立副本，正式订阅 `-v1-sub` 投递不受影响 |
| 死信 | topic `intelligent-collection-cases-dlq` + sub `intelligent-collection-cases-dlq-sub` | 保留 7 天；已授 Pub/Sub 服务代理 publisher/subscriber |

**T0-4 的机械保证**（不依赖人工纪律）：`IngestionIsolationGuard` 在 local / test profile 下、且
`collection.ingestion.enabled=true` 时，于建立订阅**之前**拒绝两种配置：订阅名落在
`collection.ingestion.reserved-subscriptions`（默认含 `intelligent-collection-cases-v1-sub`、
`collection-cases-sub`、`collection-cases-ai-v1-sub`），或白名单为空（空名单等于放行全部案件）。
`l4b-preflight.sh` 用同一份保留清单做前置硬失败，`publish-test-messages.sh` 则拒绝向正式 topic 发布。
7 例单测覆盖（含 pilot profile 不受本闸门约束，由 `PilotReadinessValidator` 接管）。

> 合成消息**只能**发往 `intelligent-collection-cases-test1`。往正式 topic 发测试消息会同时投给正式订阅，污染下游。
> L4b 订阅只接收创建时刻之后发布的消息；创建后 20 分钟内 peek 为空，数仓实际发布节奏待确认（不影响 L4a）。
> 正式订阅 `-v1-sub` 仍是 ack 10s、无死信策略，与[基础设施规范](../MOCASA催收系统升级_Phase1_基础设施交互规范.md)口径不一致，Pilot 前需由运维修正；本轮测试不依赖它。

### 出口

- T1 需要 T0-1。
- T2 需要 T0-5。
- T3/L4a 需要 T0-2 与受控渠道配置；T3/L4b 需要 T0-3、T0-4、T0-5。
- T0-6、T0-7 未闭合时，允许执行 T1/T2，但不得据其结论放行 T3 之后的阶段。
- 禁止向生产案件 topic 发布人为构造的测试消息。

**T0 出口宣告（2026-08-21）：条件性通过 —— T0-1…T0-5、T0-7 全部闭合，T0-6 仍为 🟡，故本轮只放行到 T3。**

放行到 T3 的依据是 T0-6 剩下的三条与 T3 链路不相交：A3 是 AI_CALL 回调入站，而本轮测试范围已排除
AI_CALL；A8/A9 是 `repaymentEvent` 校验宽于契约，两份冻结样例都不会命中。**T3o 及之后不得据此放行**
—— A3 不闭合外呼结果永远回不来，只能等超时。

两条与原计划的偏离已按上文各自入档，此处只记结论：**T0-5 的"受控库"实际是本机 MySQL 8.0.46**
（远端共享库有外来实例抢步骤，L4a 在其上不可能跑通）；**T0-3 计划的 Nacos 阶段配置最终没发**
（开关已全部环境变量驱动，往共用 Data ID 发只会扰动编排同事）。

---

## 3. T1 契约与组件回归：L0、L1、L2

### 目标

以纯逻辑与内存集成验证接入映射、引擎状态机、七步管线、异常兜底、幂等与异步回调语义。替身仅用于隔离外部边界。

### 覆盖与状态

| 层级 | 覆盖范围 | Owner | 状态 |
|---|---|---|---|
| L0 · engine | 生命周期、七步管线、PreFlight、SPI 超时、发件箱、停摆巡检 | 主架构 | ✅ |
| L0 · channel | Adapter 映射、策略与本地合规逻辑 | 编排同事 | ✅ |
| L0 · ingestion | 消息路由、字段映射、投影决策、ACK/NACK、去重 | 主架构 | ✅ |
| L0 · admin 运行入口 | 调度入口路由、陈旧丢弃、单飞、DLQ 重放、Pilot 预检、库时钟闸门、回调入口验签与审计 | 主架构 | ✅ |
| L0 · admin 后台管理 | 鉴权、配置模板增删改与回滚、合规冻结解冻、异常队列、审计日志、目录查询 | 主架构 | ⏭ |
| L1 | 内存总线/仓储下的全链路与异步回调闭环 | 主架构 | ✅ |
| L2 | 引擎↔渠道执行契约（见 §4） | 主架构 + 编排同事 | ✅ |

> 证据：`mvn -B -ntp clean test` 全绿，共 **315 例 0 失败**（service 9 / channel 63 / engine 135 / ingestion 63 / admin 45；
> 2026-08-21 晚补 L4b-9…14 与 L4a-3b 时净增 6 例：投影后注入点 2、入站 `data` 非 object/为 null 的 poison 判定 2、
> 内存合规计数器的计数与按用户清理 2（清理用的前缀必须带分隔符，否则用户 9 会连 94999 一起清掉）。此前 309 例含 T0-6 修正净增 7 例：回调地址拼接 2、Facade 建批不带回调与已取消字段 1、caseEvent 快照单调性 3、还款忽略 stage 2——其中「Facade 随单透传回调」的 2 例已随账户级口径回退）。L2 中依赖真实供应商 HTTP 的判据仍以 §4 的分项状态为准。

> **admin 拆分口径**：`collection-admin` 同时承载催收主体的运行入口与后台管理界面，两者风险与上线节奏不同，故分列。
> 运行入口在 Pilot 主链路上，必测；后台管理后置到 Pilot 之后，**前提是其写接口在 Pilot 期间关闭或降为只读**（见 T4-6）。
> 若 Pilot 期间需要启用任一后台写接口，该接口即回到本轮必测范围。

### 必测用例域

| 域 | 退出条件 |
|---|---|
| 计划生命周期 | 入案、还款、升档、停催、重建的状态迁移与取消原因正确 |
| 七步管线 | Guard/Resolver/Gateway 的 fail-close、retryable、终态拦截正确 |
| SPI 调用 | 硬超时生效、异常透传、MDC 传递正确 |
| 内存闭环 | 事件驱动计划/步骤推进与回调收敛正确 |
| 事件不丢 | 步骤终态与事件入箱同事务；确定性 `eventId`；重发、退避与耗尽转终态正确 |
| 停摆巡检 | 仅计数告警，不写库、不重发触达 |
| 调度入口 | 按 `job` 路由、陈旧消息丢弃、重复投递均 ACK、并发单飞、入口唯一 |
| 接入消息映射 | `caseEvent` 完整快照与 `repaymentEvent` 增量的字段映射与校验正确 |
| 接入投影决策 | 首次入催发事件、同指纹静默刷新、投影先于事件、`PENDING` 补发正确 |
| 接入异常处置 | 外部阶段事件 poison、无基线还款 poison、陈旧 `occurredAt` 跳过、未知类型 ACK |
| 接入去重 | 同 `eventId` 重投不重复写投影或发事件 |
| 日切逻辑 | 阶段变化与 D91 停催的产出条件、同日重跑不重复发事件 |

> 本层级原登记的缺口（日切同日重跑、部分还款可写字段、无基线与陈旧还款、白名单过滤、回调入口验签、`repaymentEvent.stage` 契约冲突）已全部补齐，单测全绿。还款路径新增一条不变量：**stage 完全不参与还款增量**（不解析、不合并、SQL 无该列），由 L1 + 仓储单测 + L3 IT 三层守护，见[附录 C](#附录-c缺口登记)。

### 出口

全部层级绿；接入变更至少执行 ingestion 单测，事件 payload 或语义变化时追加 engine 回归。T1 通过不豁免 T2 的真实持久化验证。

**T1 出口宣告（2026-08-21）：通过 —— `mvn -B -ntp clean test` 315 例 0 失败，L0/L1/L2 必测域全覆盖。**

两处不算失败但需说清：**L0 · admin 后台管理为 ⏭（跳过，非未做）**，前提是其写接口在 Pilot 期间关闭
或降为只读（T4-6）——若 Pilot 期间要启用任一写接口，该接口立刻回到必测范围；**L2 中依赖真实供应商
HTTP 的判据不由这 313 例担保**，以 §4 的分项状态为准。

---

## 4. T1 引擎↔渠道执行契约：L2

### 目标

冻结引擎调用渠道的语义边界，验证真实渠道实现接入后不改变引擎对计划构建、执行守卫、步骤解析、渠道网关与推进/穷尽策略的约束。

| ID | 契约主题 | 允许替身 | 关键断言 | Owner | 状态 |
|---|---|---|---|---|---|
| C1 | 计划结构、步骤顺序与成功落库 | 仅供应商 HTTP | 三渠道顺序完成；timeline 含 `providerMsgId` 与审计元数据，无 PII | 主架构 + 编排同事 | ✅ |
| C2 | PUSH 无 token → 同槽 fallback SMS | 仅供应商 HTTP | 仅一次 SMS、零次 Push；timeline 仍记 PUSH | 主架构 + 编排同事 | ✅ |
| C3 | Guard 拦截 → SKIPPED | 无 | 空地址类拦截写 `COMPLIANCE_BLOCKED`，不出网 | 主架构 + 编排同事 | ✅ |
| C4/C5 | 渠道异常与重试分类 | 仅供应商 HTTP | 瞬态失败退避不写 timeline；业务拒绝转终态并写 timeline | 主架构 + 编排同事 | ✅ |
| C6 | 步骤完成与推进语义 | 仅供应商 HTTP | 同步渠道受理即完成，不进入等待态 | 主架构 + 编排同事 | ✅ |
| C7 | 重复 due 不重复 dispatch | 仅供应商 HTTP | 单次供应商请求与单条 timeline；终态步骤为 no-op | 主架构 + 编排同事 | ✅ |

### 执行记录（2026-08-24 16:20–16:48 PHT）

环境：本机 MySQL（避开共享库抢步骤）+ `case-service=mock` 合成案例 + **真实通知中心 / 极光 / SendGrid**，
`fallback-to-mock=false`（全程无 Mock 回落，已逐条核对适配器日志）。

| ID | 证据 |
|---|---|
| C1 | plan 257（94999）SMS→PUSH→EMAIL 按序全部 `DELIVERED`，plan `PLAN_COMPLETED`；timeline 三条分别带 `providerMsgId` = `116632102`(QHSms) / `18103421364704596`(极光) / `-H496D3PTlOEpC_3touZfQ`(SendGrid)，及 `script_slot`、`template_version`；表内不存正文与地址 |
| C2 | 两次独立验证。直连：94201 `jpushToken=null` → 目标落手机号、SMS 受理成功。计划链路：94101 日志 `no jpushToken, fallback SMS ... fallback=true`，timeline 仍记 `channel=PUSH` |
| C3 | 94801 日志 `blocked by guard: NO_PHONE / NO_PHONE`；step `SKIPPED`，timeline `COMPLIANCE_BLOCKED`，未出网 |
| C4/C5 | 瞬态（base-url 指向不可达端口）：`transient failure` → 客户端内重试 2 次 → `retry step in 60s`，退避期不写 timeline。业务拒绝（桩返回 `code=1001`）：`errorCode=NOTIFICATION_CODE_1001` → 无重试（`retry_count=0`）、同秒推进、step `FAILED` 且写入 timeline |
| C6 | SMS/PUSH/EMAIL 受理即 `COMPLETED` 并推进，从未停留在等待回调态；末步成功后 `last step success → PLAN_COMPLETED` |
| C7 | 确定性构造：把已发的 step 255/1 置回 `EXECUTING` + 过去 `trigger_time`，扫描重新投递 → `duplicate event, key=255:1:0 skipped`；供应商请求仍为 2 次、timeline 前后均为 2 条，零重复触达 |

> C7 说明：自然状态下造不出重复 due——`markExecuting` 在抢到步骤时把 `trigger_time` 置 NULL，
> 该步骤即退出扫描视野。把扫描间隔压到 200ms 也未复现，故改用上述构造直接命中幂等锁。
> 第二道闸（终态 CAS `markExecuting` 返回 0）无法经扫描触达（终态步骤不被 `selectDueSteps` 选中），
> 由 `StepExecutionOrchestratorTest` 单测覆盖。

### 本轮两项新发现

**F1（高危）：`sms-test-mode=true` 不抑制投递。** 实测 `/v1/sms/testSend` 返回的 `data.channel` 为
CreativeBlue / QHSms / bori / HiWaySms **四个真实运营商通道**，且通知中心不存在 Virtual 账号
（显式指定 `accountName=Virtual` 返回 `code:2001 no valid account`）。该开关只免签名，Adapter 也从不替换手机号。
此前文档与 `PilotReadinessValidator` 告警均称"短信不发真实号码"，属事实错误——pilot 上一旦调度打开，
真实借款人会收到真实短信。已修正三处说法，并新增启动闸门：pilot 下开启该开关必须同时配非空
`collection.scan.case-id-whitelist` 且不得 `allow-full-scan`，否则拒绝启动。

**F2：瞬态失败吃掉合规配额，且掩盖根因。** plan 256 实况：第一次 SMS 因供应商不可达失败（消息根本没发出），
但配额在 dispatch 前已占用且失败不释放；60 秒后重试撞上
`FREQUENCY_LIMIT / DAILY_LIMIT_EXCEEDED SMS 2/1` 被拦，计划直接 `PLAN_COMPLETED`。
后果有二：生产日限为每渠道 1 次时，**任一次瞬态故障 = 该客户当天该渠道零触达**；
且 step 最终 result 是 `COMPLIANCE_BLOCKED` 而非渠道故障，**告警指向合规而非供应商**，排障会被带偏。

**已修（2026-08-24 17:10 PHT）。** 未采用"确认发出后再计数"：那会在校验与提交之间开并发窗口，
同一用户同渠道的两个步骤可能双双通过校验后双双发出，而超发是合规事故，比少发严重。
保留先占后发，改为两处闭合：

1. **重试不重复占配额**（`ConfigurableExecutionGuard.checkFrequency`）。`retryCount > 0` 直接跳过频控——
   重试是同一次触达尝试的延续，配额已在首次尝试预占。这条直接消掉了"重试撞上自己首次尝试"的死循环，
   连带修好可观测性：终态不再被记成 `COMPLIANCE_BLOCKED`，而是真实的渠道错误码。
   触达上限仍由 `engine.step.max-retry-count` 封顶，跳过频控不会放大触达。
2. **确认未发出的终态归还配额**（`StepExecutionOrchestrator` → `ComplianceCounterService.release`）。
   归还条件是 `StepResult.retryable=true`，即渠道能证明请求未写给供应商（熔断未调用、凭证缺失、
   连接被拒、供应商显式拒绝受理）。结果未知（读超时、写后中断、5xx）一律不还——宁可少发一次。
   解析异常与策略性跳过同样归还（根本没走到渠道）。

配额键由 Guard 随 `GuardVerdict.QuotaReservation` 交回，引擎原样传回，避免引擎按自己的时区
重算日期而打到相邻一天的计数上。两个计数器实现的 `release` 都把值夹在 0 以上：未占先还或重复归还
不得出现负数，否则等于凭空放大该用户当天的额度。归还失败只告警不上抛（少还一次偏保守）。

> 契约变更需同步编排/服务同事：`GuardVerdict` 新增 `quotaReservation`（沿用 `allow()` 的自定义 Guard
> 行为不变，引擎不会代为归还）；`ComplianceCounterService` 新增 `release`，自定义实现须一并落地。

**F3（配置一致性）：Nacos 上 `channel.notification.app-key` 与 `channel.sendgrid.api-key` 均为空串**，
真值只在未入库的 `deploy/nacos/nacos-publish.local.yml`。本轮 PUSH/EMAIL 初次均报 `not configured`，
补齐后才跑通。pilot 走 `.env.pilot` 注入不受影响，但任何依赖 Nacos 取值的环境这两个渠道都不可用。

| L2-CB | AI_CALL 异步回调契约 | 分级方案第 1–2 级允许脚本注入回调 | 见下方分级方案 | 编排同事 + 主架构 | ⬜ |

### L2-CB：AI_CALL 分级联调

> AI_CALL 为异步渠道：dispatch 成功仅表示供应商受理，步骤须保持执行中，仅回调或超时可收敛。未具备稳定 HTTPS 与签名前，不得对真实借款人外呼。

| 级别 | 环境 | 必验结果 | 准入下一级 |
|---|---|---|---|
| 1 | 本地，不调供应商，可关闭验签 | dispatch 后保持执行中；回调后完成并推进；重复回调不重复计次；超时转失败 | L0/L1/L2 回归全绿 |
| 2 | 供应商 sandbox，回调仍由脚本注入 | 下单持久化 `providerMsgId`；回调可关联同一 plan/step | 供应商任务 ID 可稳定透传 |
| 3 | 临时 HTTPS 回调入口，测试白名单 | 回调可达、限流与访问日志有效；错误回调被拒；不含真实 PII | 稳定域名、证书与签名就绪 |
| 4 | Pilot 环境，HMAC 验签与批准号码 | 验签通过；终态、审计与超时哨兵正确；伪造签名与重复回调被拒或幂等吸收 | 可纳入 T3o 渠道验收 |

### 执行记录（2026-08-25 14:41–14:42 PHT，编排同事在 bdp01 执行）

**级别 4 的主路径已打通**：pilot profile、真实域名 `https://collection-admin.mocasa.com`、
HMAC-SHA256 验签、白名单案 520049。调度扫到步骤 1414 → dispatch → Facade `create/cases/start`
（`batch_id=591838ae…`）→ 约 55s 后 `POST /webhook/facade-callback` 收到 `session.completed`
→ `signature_valid=1` → 写 `t_channel_callback_audit#7` 与 `t_contact_timeline#712`
→ 步骤结为 `NO_ANSWER`、计划 `PLAN_COMPLETED`。1/1 成功，明细见
[真拨 Webhook 测试记录](records/MOCASA催收系统升级_Phase1_AI_Call_引擎真拨Webhook测试记录_20260825.md)。

回调路径是 `POST /webhook/facade-callback`（账户级 URL），**不是** L1 文档里的 `/webhook/facade/voice`。

同窗口两次失败样本值得留档，因为它们暴露的是配置而非代码问题：步骤 1392 被 `ExecutionGuard`
50ms 硬超时判成 `COMPLIANCE_BLOCKED`（已通过 pilot 抬到 500ms 处置）；步骤 1393 因 Valubo 自签名
证书 PKIX 失败，改为把该证书导入镜像 TrustStore（不关全局 TLS）。

### 当前差集

**2026-08-26 在统一基线（ca_branch 镜像）上复跑，四项差集全部闭合。** 逐项证据：

| 差集 | 结论 |
|---|---|
| 真人接通映射 | ✅ 闭合（构造侧）。真拨拿不到样本：批准测试号 `+639451374358` 实测三次都是 `MEDIA_NEGOTIATION_FAILED`（SIP 406），压根接不通。故对新起的 EXECUTING 步骤 1684 注入 `was_ai_connected=true` + `reason=NORMAL`（`FacadeCallbackMapper` 唯一映射到 `ANSWERED` 的组合），步骤落 COMPLETED/ANSWERED。**我方映射这一侧已证，Facade 真回接通报文的形态仍未见过** |
| 伪造签名被拒 | ✅ 闭合。伪造签名与完全不带签名头两种都返 HTTP 401，审计留 `signature_valid=0`，步骤状态不变 |
| 重复回调幂等 | ✅ 闭合。取审计 id=18 的真实 `session.completed` 原文重放：HTTP 200、审计 `signature_valid=1`、日志记 duplicate `session_id` 跳过，timeline 与步骤状态均不变 |
| 身份反查分支 | ✅ 闭合。不带 `client_metadata` 注入，服务端按 `external_case_id` 反查到唯一 EXECUTING AI_CALL 步骤 1684，`planId`/`stepId` 解析正确 |
| 基线不一致 | ✅ 闭合。步骤 1667（plan 843 / case 517301）在 ca_branch 镜像上真拨：受理成功、改投 `test-callee`、步骤入 EXECUTING，约 6 分钟后真实回调落地（`result=FAILED`，因测试号 `MEDIA_NEGOTIATION_FAILED`），步骤转 COMPLETED/FAILED、timeline 更新、计划推进——出站到回调的全程走通 |
| 数仓进件 | 该窗口 `COLLECTION_INGESTION_ENABLED=false`，计划由 Redis 注入 `CASE_INGESTED` 创建，未验真实 Pub/Sub 进件 |
| 回调超时任务 | 本轮 Pilot 只跑同步渠道，该任务扫描结果为空属预期，不作故障信号 |
| 受理证据 | 同步渠道以受理成功与 `providerMsgId` 为证据，不要求回调审计行 |
| 证书有效期 | 镜像内导入的是 Valubo 测试环境自签名证书（`CN=valubo-voice-test`）。上线换正规域名证书时须删掉 `deploy/Dockerfile` 的 keytool 段 |

### 出口

C1–C7 全绿且断言遵循[引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md)。渠道生产实现替换后必须复跑。L2-CB 未闭合前不进入 AI_CALL 的真实验收。

**2026-08-26：L2-CB 记为通过**（基线一致 + 四项差集闭合）。据此把 S1–S4 的 150 个 AI_CALL 槽位保留在位。
两项残留不阻塞 T4，但须在 T5 放量前拿到真实样本：Facade 真回「接通」报文的形态未见过（测试号接不通），
以及批准测试号的 SIP 406 是号码侧限制还是 Facade 侧配置未定论。

---

## 5. T2 真实持久化与可靠性：L3

### 目标

在真实 MySQL 环境验证计划、步骤、timeline、快照、发件箱与案件投影的持久化与事务语义。不允许用内存 Repository 替代被测边界。

| ID | 用例 | 证据 | Owner | 状态 |
|---|---|---|---|---|
| L3-1 | plan/step/timeline 往返与 timeline 幂等 upsert | IT 记录 | 服务同事 + 主架构 | ✅ |
| L3-2 | `context_snapshot` JSON 往返与语义字段一致 | IT 记录 | 服务同事 | ✅ |
| L3-3 | 取消/完成状态与完成时间戳 | IT 记录 | 服务同事 | ✅ |
| L3-4 | 单活跃计划约束与并发串行化 | IT 记录 | 服务同事 + 主架构 | ✅ |
| L3-5 | 到期扫描查询与状态推进 | IT 记录 | 主架构 + 服务同事 | ✅ |
| L3-6 | 发件箱事务原子性：业务回滚不留记录；同 `eventId` 重入不重置重发进度；脱离事务入队失败 | IT 记录 | 主架构 + 服务同事 | ✅ |
| L3-7 | 排期审计列与停摆判定 SQL：原始触发时间不被退避改写；停摆查询与到期/超时查询严格互补；时间列落 PHT，同一行内不出现两套时区 | IT 记录 | 主架构 + 服务同事 | ✅ |
| L3-8 | 案件投影事务语义：inbox 与投影同事务提交/回滚；行锁使同案并发串行；拒绝相同或更低版本；重入不重写投影 | IT 记录 | 主架构 + 服务同事 | ✅ |

> 执行记录（2026-08-20，`ai_collection_db`，`mvn -B -ntp -pl collection-service,collection-admin -am test -Pintegration-tests`）：
> `ContactPlanMapperIT` 9 例、`EventOutboxMapperIT` 3 例、`PlanStepTriggerPublisherIT` 2 例，共 14 例全绿。
> 首次真实库运行暴露一个**测试自身**的缺陷：租约用例把参考时刻取在 `lease_until` 之后再断言「租约未到期不可认领」，
> 与自己的入参自相矛盾；生产 SQL 正确，已改为在租约窗口内、窗口外各断言一次。
>
> 补充执行记录（2026-08-21，同库同命令）：新增 `StepScheduleAuditMapperIT` 3 例（L3-7）与
> `AiCaseProjectionRepositoryIT` 9 例（L3-8），**12 例全绿**，L3 累计 26 例。
> L3-8 覆盖：同事务提交、语句级失败后靠事务回滚兜住（证明 `@Transactional` 承重而非装饰）、同 `eventId` 重入、
> 相同指纹判陈旧、新指纹刷新且每日校准不排队事件、还款增量陈旧跳过、无基线抛 `MissingCaseBaselineException`、
> `markEventPublished` 幂等、同案并发被 `FOR UPDATE` 串行化。
> L3-7 首跑暴露一个**生产缺陷**（非测试缺陷）：数据库时区与应用时区不一致，见[附录 C](#附录-c缺口登记)「时区口径」条。
>
> **状态回退说明（2026-08-21 时区修复后）**：`ContactPlanMapper` / `ContactPlanStepMapper` / `ContactTimelineMapper`
> 的时间列改为应用侧 `ServiceClock.now()` 传参，被测 SQL 已变更，因此依赖这三个 mapper 的 L3-1…L3-5、L3-7 由 ✅ 回退为 🟡，
> 须带 `L3_IT_DB_URL` 复跑后回填；L3-6（发件箱）与 L3-8（案件投影）不涉及这三个 mapper，状态保持。
> 新增 L3-7c `timeColumns_landOnPhtNotDatabaseSessionTimeZone` 作为时区回归守卫（同一行内 `created_at` /
> `updated_at` / `executed_at` 与应用 PHT 时钟偏差须在 5 分钟内）；另新增 L3-8 `repaymentDelta_neverRewritesStage`
> （还款增量落真库后 `stage` 保持基线值而余额已更新，守护 T0-6 A12 的 SQL 层保证）。
>
> **复跑记录（2026-08-21 17:19，本机 MySQL 8.0.46，同命令）：30 例全绿，`BUILD SUCCESS`**
> —— `AiCaseProjectionRepositoryIT` 10 + `ContactPlanMapperIT` 9 + `StepScheduleAuditMapperIT` 6 +
> `EventOutboxMapperIT` 3 + `PlanStepTriggerPublisherIT` 2。L3-1…L3-5、L3-7 状态回填 ✅。
> 复跑暴露一个**测试自身**的缺陷（生产代码正确）：`PlanStepTriggerPublisherIT` 绕过仓储直写 mapper 播种数据，
> 而时区修复后 `created_at` 不再由库端 `NOW()` 兜底，两例以 `Column 'created_at' cannot be null` 报错；
> 已在播种处显式赋 `ServiceClock.now()`。同时把该测试里的 `trigger_time`/`timeout_time` 参照系从
> `LocalDateTime.now()`（JVM 默认时区）改为 `ServiceClock.now()`——扫描 SQL 的比较值是 PHT，
> 在时区不是 +08 的机器上，原写法的「1 分钟前」可能落在未来。
> 另跑 `mvn -B -ntp clean test` 全量单测：`BUILD SUCCESS`，无回归。

### 执行约束

- 集成测试只接受非生产的受控 MySQL；连接信息经环境变量注入，不入仓。
- 默认 CI 不连库，因此 CI 绿不能替代 L3 出口。
- L3-6/L3-7/L3-8 的逻辑分支虽已在 L0 覆盖，但事务边界与 SQL 语义只有真实库能证明。
- 本轮共用 `ai_collection_db`：现有 IT 均按 `case_id` / `event_id` 精确删除自造数据，不执行 TRUNCATE，
  因此不会影响库内其它数据；新增 IT 必须沿用同一清理口径。
- **L4a 已于 2026-08-21 迁到本机 MySQL**，不再用远端 `34.124.218.94/ai_collection_db`。原因是那上面有外来实例
  无过滤地抢走全库到期步骤，L4a 在其上不可能跑通（见附录 C「外来应用实例」）；且该账号只有
  `GRANT ALL ON ai_collection_db.*`，无 `CREATE DATABASE` 权限，没法在同实例另开 schema。
  本机环境的搭法（可复现）：
  1. `brew install mysql@8.0 && brew services start mysql@8.0` —— 本机 8.0.46 与远端 8.0.46 **同版本**；
     本机 `system_time_zone=CST(+08:00)`，与 Manila 同偏移，比远端的 UTC 更贴近生产口径。
  2. 建库建号（与 Nacos 里同名同密，便于配置平移）：`ai_collection_db` / `ai_collection`。
  3. `mysql ai_collection_db < db/schema.sql` 建我们的 10 张表；再从远端 `mysqldump --no-data`
     补齐**另外 15 张**（`t_collection` / `t_channel_config` / `t_contact_plan_template` / `t_script_template` /
     `t_strategy_rule` / `t_compliance_rule` / `t_user_extend` 等，属渠道与旧系统），共 25 张与远端结构一致。
     `schema.sql` 只覆盖 10 张，**单靠它起不来完整链路**。
  4. 只灌配置类表的数据（`t_channel_config` 3 / `t_contact_plan_template` 6 / `t_script_template` 9 /
     `t_strategy_rule` 5 / `t_compliance_rule` 2 / `t_evaluation_setting*` / `t_config_version_seq`），
     运行时表与 PII 表一律不灌。`t_user_device_token` 与远端**逐行对齐为只有 `99000000..99000005`**——
     多灌会破坏 L4a-2「PUSH 无 token 回落 SMS」（94201 必须无 token）。
  5. 切换入口：`COLLECTION_DB_URL` / `_USER` / `_PASS`（`start-local.sh` 里显式覆盖优先于 Nacos），
     `restart-and-l4a.sh` 已默认指向本机。
  顺带记录两条远端事实：`t_user_extend` 有 **287 万行真实用户数据**，`t_ai_collection` **0 行**——
  即共享库既有真实数据风险、又提供不了 L4b 需要的真实案件源。

命令与连接注入方式见 [`scripts/README.md`](../../scripts/README.md)。

### 出口

上述用例在真实库全绿。**出口已于 2026-08-21 17:19 在时区修复后重新宣告：30 例全绿**（此前 26 例的宣告因时区修复改动被测 SQL 而作废，本次为复跑结果）。L3 与 L4a 可并行；T3 真实来源验证的 L3-8 前置条件已闭合。

---

## 6. T3 隔离端到端：L4a、L4b

### 6.1 L4a 合成源渠道冒烟

L4a 用合成案件驱动真实渠道，验证引擎到渠道的投递链路。它不接真实 Pub/Sub，也不承担投影验证。当前编排装配含临时策略实现，因此 L4a 是“可运行”的证明，不是生产策略已评审的证明。

| ID | 场景 | 关键断言 | 状态 |
|---|---|---|---|
| L4a-1 | 三渠道顺序完成 | 顺序执行、`providerMsgId` 齐备、计划完成 | ✅ |
| L4a-2 | PUSH 无 token → SMS fallback | fallback 元数据正确、仅一次投递 | ✅ |
| L4a-3 | 整笔结清取消 | 仅该案计划按还款原因取消，后续不触达 | ✅ |
| L4a-3b | 部分还款运行态刷新 | 快照余额更新；模板、步骤、计划状态与 stage 不变 | ✅ |
| L4a-4 | 升档取消并新建 | 旧计划按升档取消，新计划 stage 正确 | ✅ |
| L4a-5 | 停催 | 计划按停催取消且不重建 | ✅ |
| L4a-6 | 同步渠道完成冒烟 | 受理即完成，不进入等待态 | ✅ |
| L4a-7 | 重复入案幂等 | 仅一个活跃计划、仅一轮投递 | ✅ |
| L4a-8 | 话术槽位 × stage | 各 stage 的槽位与渠道匹配，`providerMsgId` 可追溯 | ✅ |
| L4a-G | Guard 与重建 | 空地址、频控与穷尽路径行为正确 | ✅ |
| L4a-收口 | 步骤行收敛性 | 静默后无非终态写 `completed_at`、无两个扫描都摸不到的 `EXECUTING` | ✅ |

> 同步渠道观察期为 0；不得再以旧版观察期等待时间作为用例。L4a 必须在 SPI 硬超时按生产默认启用的前提下执行，不得关闭该开关取得通过。

**最终执行记录（2026-08-21 21:49–22:08，本机 MySQL 8.0.46，`scripts/test/restart-and-l4a.sh`）：
PASS=29 FAIL=0**，日志 `logs/run/l4a-final4.out`。这一轮含新补的 L4a-3b。取证：26 条有
`provider_msg_id` 的 timeline **全部为 mock 前缀，非 mock 为 0 条**；非终态写 `completed_at` 的步骤 0 行；
`created_at` 与 `executed_at` 相差超 10 小时（时区错位特征）的步骤 0 行。
**注意该轮以 `L4A_ALLOW_QUIET_HOURS=1` 放宽了静默窗口（当时 21:49 PHT），故不构成静默时段 Guard 的证据**，
其余用例不受影响；下一次窗口内（08:00–21:00 PHT）复跑即可补齐这一项。

**前一轮执行记录（2026-08-21 17:05–17:16，同环境，L4a-3b 补齐之前）：PASS=26 FAIL=0**，
日志 `logs/run/l4a-localdb.out`。三项取证：

1. **零真实出站**：14 条 `DELIVERED` timeline 的 `provider_msg_id` **全部为 mock 前缀，无一条非 mock**；
   应用日志 36 次 `not configured`（三个适配器均未配密钥）+ 18 次 `fallback-to-mock=true`，
   零 `requestId`。合规拦截 2 条、失败 12 条均无 `provider_msg_id`。
2. **时区口径已闭环**：31 条步骤行的 `created_at`/`executed_at`/`dispatched_at`/`completed_at`/`updated_at`
   **同行全为 PHT**，此前顽固落 UTC 的 `executed_at` 已归位——应用侧传参（`ServiceClock`）这条修法验穿。
   本机 `system_time_zone=CST(+08:00)` 也与 Manila 同偏移，消除了远端 UTC 服务器这一层干扰。
3. **步骤全部收敛**：31 行无一悬挂，收口断言在静默后通过（这是它首次在无外来干扰的环境下判定）。

**L4a-3b 补齐（2026-08-21 晚）**：L4a 没有真实 `repaymentEvent` 入口，端到端验「部分还款只刷余额」
只能新增 `/mock/balance-updated` 注入 `CASE_BALANCE_UPDATED`。断言的重点是**不变量**
（计划身份、stage、模板、总步数、步骤集合与渠道），而不只是余额变了：客户还了一部分反而被换话术或
重排触达是对客可见的错误，只断言「余额已更新」抓不到它。

不变量里**故意不含** `status` / `currentStep` / 步骤 `status`：计划一建好调度器就在独立推进它，这些字段
本来就会自己往前走。首版把它们也当不变量，于是报「部分还款改动了计划：PENDING→STEP_SCHEDULED」，
其实测出来的只是调度器在工作。余额也不能按字符串比——快照里是 JSON number，`800.00` 会序列化成 `800.0`。

**两条纪律转机制（2026-08-21 晚）**。当晚一轮 L4a 报出 PASS=17 FAIL=12，表象全是
「SMS 未在时限内被渠道受理」「NO_PHONE 步骤非 SKIPPED」，而**应用日志里没有任何 WARN/ERROR**，
很像产品回归。查下去是两条纯环境原因，各自都只有「脚本头部一行提示」在防守：

1. **静默时段（本次 FAIL=12 的真因）**。这轮跑到 21:00 PHT 之后，`ConfigurableExecutionGuard` 把每一步
   defer 到次日 08:00，于是所有用例只剩超时失败。日志里首条 `blocked by guard: TIME_WINDOW /
   QUIET_HOURS 21:00-08:00` 正好在 21:00:18，之前的用例全过。`restart-and-l4a.sh` 头部原本就写了
   「08:00–21:00 PHT 跑」，但它不检查。已改为**跑前拦下**：落在静默窗口内直接 `exit 2` 并给出两条出路
   （等到 08:00，或 `L4A_ALLOW_QUIET_HOURS=1` 放宽窗口）；放宽时打印「本轮不构成静默时段 Guard 的证据」。
   窗口本身改为 `CHANNEL_QUIET_HOURS_START/END` 驱动，缺省仍是生产口径 21:00–08:00。
2. **进程内合规计数（同轮暴露，但不是本次失败原因）**。日限计数在
   `InMemoryComplianceCounterService` 里，`reset-l4a` 原先只清库行、不清它，因此同一实例里重复跑 L4a，
   频控用例（94805 单独限 1）第二遍会在**第一步**就被挡掉，测不到「第二步 SKIPPED」。已加
   `clear(userIds)` 并挂进 `reset-l4a`（响应回 `complianceCounterCleared`）。Redis 实现无对应能力，
   故该方法只留在内存实现上、不上提到 SPI。

官方跑法仍是 `restart-and-l4a.sh`（干净实例、窗口内），但现在两种漏做都不会再静默污染结论。

### 6.2 L4b 隔离真实来源复验

L4b 用真实 Pub/Sub 消费 + 真实投影落库 + 渠道沙箱，验证接入主路径。隔离由独立 topic、独立订阅、发布护栏、应用白名单与触达沙箱五层叠加实现；`t_collection` 及旧库仅作迁移兼容或附加对账，不作主断言来源。

主断言链路：

```text
caseEvent / repaymentEvent
→ 同事务写 inbox + t_ai_collection
→ 提交后发布内部事件
→ 计划、步骤与 timeline
```

| ID | 场景 | 关键断言 | 状态 |
|---|---|---|---|
| L4b-1 | 入案、建计划、投递、timeline | 投影落表、inbox 已发布、计划与 timeline 一致 | ✅ |
| L4b-2 | 还款取消与余额刷新 | 全额结清取消活跃计划；部分还款仅更新运行态 | ✅ |
| L4b-3 | 日切升档 | 旧计划按升档取消并新建目标 stage 计划 | ✅ |
| L4b-4 | 日切停催 | 活跃计划按停催取消且不重建 | ✅ |
| L4b-5 | 快照字段溯源 | payload、投影与计划快照逐字段一致 | ✅ |
| L4b-6 | 到期扫描执行 | 到期步骤投递成功，审计字段写入且摘要无 PII | ✅ |
| L4b-7 | NACK 重投与幂等 | 重投后仅新增一个计划，无重复 `providerMsgId` | ✅ |
| L4b-8 | 日切幂等 | 同日重复日切不重复升档或触达 | ✅ |
| L4b-9 | 投影与 inbox 状态一致 | 投影字段与快照一致；inbox 终态为已发布 | ✅ |
| L4b-10 | 乱序不回退 | 较新指纹后补投旧快照、或较新还款后补投旧增量，投影不回退且 inbox 记为跳过 | ✅ |
| L4b-11 | 投影已落库但事件未发出 | 重投只补发事件、不重复写投影，无重复计划与投递 | ✅ |
| L4b-12 | 每日快照静默刷新 | 在催案刷新投影但不重复入催与触达，日切据新投影升档 | ✅ |
| L4b-13 | 外部阶段事件被拒 | poison 后 ACK 并告警，投影与计划不变 | ✅ |
| L4b-14 | poison 消息处置 | 不可恢复契约错误记录 poison/告警后 ACK；投影、计划与渠道触达均不变 | ✅ |

#### L4b-9…14 补齐（2026-08-21 晚，脚本 `scripts/test/l4b-official-test.sh`）

六条此前只有 L0/L1 覆盖、端到端为空白。补齐时的三条设计取舍值得记下来：

1. **L4b-11 需要一个新的注入点**。原 `IngestionFaultInjector` 只在**投影落库前**失败，重投走的是
   `APPLIED` 全新路径，跟 L4b-7 是同一条。L4b-11 要验的是**投影已提交、领域事件还没发出去**这段窗口
   （生产上就是提交后进程被杀），重投必须命中收件箱的 `PENDING_PUBLISH` 只补发分支。故新增
   `failAfterProjectionIfArmed` 与 `/mock/ingestion-fault/arm-post-projection`，同一套三重约束
   （开关 + 白名单 + 显式 arm）。替代方案是手工把 inbox 行改成 `PENDING` 再重投，但那是伪造现场，
   而且进程内 `eventId` 去重会直接把重投吞掉，取不到证。注入点写错一行就退化成 L4b-7，
   已用 `postProjectionFault_writesProjectionButNotEvent` 单测钉住。
2. **poison 只能靠日志取证**。poison 走 ack + WARN，既不写 inbox 也不落 DLQ 表，
   所以 L4b-13/14 断言应用日志。这要求前台运行也落盘 `admin.log`（终端文件是 1MB 环形缓冲，
   跑一轮 L4b 足以把前半段冲掉），已在 `start-local.sh` 用 `tee` 解决，并在启动前转存旧日志。
3. **探针必须打在停催案（99000005）上**。它按 D91+ 规则从不建计划，因此断言窗口里不会有执行中的
   步骤写 timeline。首跑打在有活跃计划的案件上，被 L4b-6 排的到期步骤撞出一次假红
   （timeline 11 → 12），排查成本远超收益。同理「零副作用」只按该案计数，不按全部白名单案。

**L4b-14 首跑抓到一个真缺陷（已修）**：`data` 为标量（非 object）时，`PubSubCaseConsumer.payload()`
用 `getJSONObject("data")` 取值，而 fastjson 对字符串值会**把它当 JSON 文本再解析一遍**并抛
`JSONException: error parse new`。该异常不是 `PoisonMessageException`，于是逃出 poison 判定、
落进消费者的通用 `catch` 变成 **nack**：一条永远处理不成的消息被反复重投，实测 5 次后耗尽投递次数进 DLQ。
契约要求不可恢复的契约错误 **ack + 告警**，因此走错了分支——白烧 5 次重投、占一个 DLQ 名额，
且运维在 DLQ 里看不到任何说明原因的 poison 日志。已改为先判类型再取值，并补
`nonObjectData_isPoisonAndAcked` / `nullData_isPoisonAndAcked` 两条单测。
严重度不高（DLQ 兜住了，没有死循环），但它正是 L4b-14 这条用例存在的理由：
在补上端到端脚本之前，这个分支的 L0 覆盖只测了 `data: null`，测不到 `data: "字符串"`。

**同轮还暴露两个把测试结论变成假红的环境/脚本缺陷**：

1. **不能在应用运行时重新打包**。Spring Boot fat jar 的类是按需从 jar 里读的，`mvn package`
   覆盖掉正在被读的 jar 之后，所有尚未加载的类都报 `NoClassDefFoundError`。实测一轮 L4b 跑到一半被
   重新打包，结果 L4b-1/2 无计划、注入端点 500，失败长得像产品缺陷。已让 `start-local.sh`
   把 jar 复制到 `logs/run/collection-admin.running.jar` 后从副本启动，从机制上堵掉。
2. **`/mock/ingestion-fault` 的解析太脆**。L4b-7 原来用 `tr -dc '0-9'` 把响应里所有数字拼起来比
   `"0"`，我给该端点加了第二个计数器 `remainingPostProjection` 后拼出来是 `"00"`，于是注入其实已命中
   （日志有 `注入瞬态失败 剩余=0`）却报「未命中」。已改为按字段名取值。
3. **不能在脚本运行期间编辑脚本**。bash 是边读边执行的，文件字节偏移一变就会在错误位置继续解析，
   实测一轮跑到 L4b-9 时报 `syntax error near unexpected token '*'`，而 `bash -n` 对同一文件是通过的。
   与「运行中重新打包」同类，都是把正在被读的东西换掉。

**另外去掉了一条手工前置**：L4b 的重置原先只清 DB 行，而「本周期已入催」标记是进程内状态，
必须靠「跑之前先重启应用」维持，漏做就会让入案事件被当成每日刷新而只刷投影不建计划，
L4b-1 报「90s 内未落 t_contact_plan」——这一轮已因漏做烧掉一次。现新增
`POST /mock/ingestion-dedup/clear?caseIds=...`，重置阶段自动调用，纪律改成机制。
（messageId 去重标记无需清：脚本每条事件都用新 UUID。）

> `repaymentEvent` 不携带 `caseVersion`：乱序验证须分别使用 `caseEvent` 内容指纹与 `repaymentEvent.occurredAt`。发布脚本必须使用真实契约 envelope 与真实指纹，占位值不可用于取证。

**执行记录（2026-08-21 21:05–21:36，本机 MySQL 8.0.46 + 真实 Pub/Sub `intelligent-collection-cases-test1`）：
14 条用例全部有脚本覆盖，PASS=69 FAIL=0 SKIP=0**，日志 `logs/run/l4b-final.out`。
上一轮只跑 L4b-1…8（PASS=41），本轮含新补的 L4b-9…14。
本轮为**合成源**：`t_ai_collection` 在共享库与本机库上都是 0 行，真实案件源不存在（见附录 C），
真实源用例按决议降级为缺口登记，不阻塞 T3 其余出口。

取证：
- **投影真落库**：6 案投影齐备且逐案不同（S0/S1/S3/S4/S4，`dpd` = -1/-1/20/95/31/95，
  D91+ 两案 `collection_status=CEASED`），此前该表为 0 行。
- **零真实出站**：9 条 `DELIVERED` timeline 的 `provider_msg_id` 全为 mock 前缀，无一条非 mock。
- **日切与幂等**：同日重复 `daily-roll` 计划数/取消数/timeline 三项均不变；
  NACK 重投后只新增 1 个计划、静置 30s 无重复、无重复 `providerMsgId`。

首跑暴露三个**测试环境与脚本的缺陷**（生产代码均正确），三者失败时都长得像产品缺陷：
1. `collection.case-service` 在 `application-local.yml` 里缺省为 `mock`（只有 `application-pilot.yml` 设了 `ai`），
   于是投影只写内存。日切 `DpdStageRollHandler` **只扫 `t_ai_collection`**，投影为空即无对象可扫，
   L4b-4 报「120s 内无 PLAN_CANCELLED/CEASED」。已改为 `${COLLECTION_CASE_SERVICE:mock}`，L4b 环境设 `ai`。
   注意 Nacos 里该键写死为 `mock`，靠 `application-local.yml` 优先级更高才覆盖得掉。
2. 发布脚本把 6 案的 `dpd` / `stage` 写死成 `2` / `S1`。数仓直发改造（commit 3fa6c4e）后事件载荷成了唯一真相来源，
   写死就让六案塌缩成同一个 stage，而 L4b-1（停催案不建计划）、L4b-3（升档）、L4b-5（逐字段溯源）
   验的正是案件间差异。7 月那几轮能看到 S0…S4 是因为当时 stage 还从旧库推导。
   已改为逐案取 `t_collection` 的 IC_TEST_% 行（`dpd`/金额），`stage` 按 `Stage.fromDpd` 同边界换算；
   联系方式仍用脚本自带测试地址，零真实触达优先于字段保真。
3. L4b-3/L4b-4 改 `t_collection` 造升档/停催条件，但日切只读投影，改动到不了投影。
   已补 `publish case1 <loanId>` 单案重发 + 等投影 `dpd` 更新再跑日切——这正是生产口径
   （数仓每日校准发全量 `caseEvent` → 接入更新投影 → 日切扫投影产出阶段迁移）。
   同时在重置段清空投影并把旧库 `dpd` 复位为 0/1/4/20/31/95，否则上一轮改过的 20/95 会让下一轮的
   stage 分布随运行次数漂移。

### 出口

- L4a 与 L4b 全部用例按当前契约取证；
- 主证据为消息 payload、inbox、投影与 plan/step/timeline 的可追溯关联；
- SPI 硬超时保持生产默认启用，且全程无 SPI 超时；
- 环境准备、操作顺序、查库节奏与可重复性前置见 [L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md)。

**T3 出口宣告（2026-08-21，含当晚补完的用例）：全部用例有脚本覆盖且全绿 —— L4a 29 项、L4b 69 项，FAIL=0。**
两组均在本机 MySQL 8.0.46 上执行（迁库理由见 §5「执行约束」），SPI 硬超时全程按生产默认启用
（`ENGINE_SPI_TIMEOUT_ENABLED=true`），全程无 SPI 超时；两组各自核对「非 mock 的 `provider_msg_id` 为 0 条」。

**脚本覆盖缺口已于当晚补齐**：L4a-3b 与 L4b-9…14（投影/inbox 一致、乱序不回退、投影已落但事件未发、
每日静默刷新、外部阶段事件被拒、poison 处置）当时登记为「无脚本覆盖」，现已全部有脚本并取证 ——
**L4b 复跑 PASS=69 FAIL=0**（`logs/run/l4b-final.out`），L4a 含 3b 复跑见上文执行记录。
补这批用例还抓出一个真缺陷（入站 `data` 非 object 被无限 nack 而非 poison+ack），详见 §6.2。

**尚未闭合，不随本次宣告放行**：
- L4b 真实源：`t_ai_collection` 为 0 行，真实案件数据不存在，本轮只跑合成源。
- A3（Facade 回调入口与验签口径不匹配）仍未闭合；它只影响 AI_CALL 回调回流，不阻塞本次 T3，但 **T4 前必须闭合**。
- L4a 最终一轮跑在 21:49 PHT，以 `L4A_ALLOW_QUIET_HOURS=1` 放宽了静默窗口，因此**静默时段 Guard
  在本轮无证据**（前几轮窗口内的运行有 12 条 `TIME_WINDOW` defer 记录可佐证其生效）。补法只是在
  08:00–21:00 PHT 内复跑一次，无代码改动。

---

## 7. T3o 生产等价演练与简版观测

### 目标

在不触达真实客户的隔离环境验证 Pilot 所需的 Redis、调度、回滚与最小观测能力。T3o 是 T4 的硬前置。

> 环境申请、配置、演练顺序与回滚操作见 [T5 Pilot 准备与演练手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md)。用例编号沿用 `T5-R` / `T5-S`，避免打断既有证据索引。

> **2026-08-24 决定：pilot 暂不跑 AI_CALL。** 回调依赖的公网入口未就绪，硬跑会让每个 AI_CALL 步骤
> 都走满 10 分钟超时收敛成 `FAILED`，在 pilot 数据里留下系统性噪声，把其余三个渠道的结论也一起污染。
> 已用 `scripts/pilot/strip-ai-call-slots.py` 生成 `scripts/pilot/2026-08-24-strip-ai-call-slots.sql`，
> 从 S1～S4 摘掉 150 个 AI_CALL 槽位（S0 本就没有），**94 个日块无一被清空**，全天节奏结构完整保留。
> 摘除前整行快照存于 `t_contact_plan_template_bak_20260824_aicall`，
> 回滚执行 `scripts/pilot/restore-ai-call-slots.sql`。
> 代价是 L2-CB 与 AI_CALL 的真实回调仍是空白，须在还原模板后单独收口，不能由本轮 pilot 结论代替。

| ID | 准入/用例 | Owner | 状态 |
|---|---|---|---|
| T3o-1 | T3 全部出口已通过 | 主架构 | ⬜ |
| T3o-2 | Pilot 案件/调度订阅、Nacos 与 Redis 隔离配置就位 | 运维 | 🟡 配置已就位并实连；但 Redis 非独立实例且未开 AOF，见 E1/E2 |
| T3o-3 | 调度通道生产化（T5-S1…S7） | 运维 + 主架构 | ⬜ |
| T3o-4 | 渠道 sandbox、白名单、脱敏与限频 | 编排同事 + 运维 | ⬜ 无可用 sandbox（F1），出口措辞待改 |
| T3o-5 | Redis 总线/幂等专项（T5-R1…R15） | 主架构 | 🟡 环境已摸底，R1 单测覆盖，余待重部后执行 |
| T3o-6 | 简版观测 MVP（T3o-O1…O4） | 主架构 | 🟡 2026-08-25 代码已交付，待 Pilot 注入取证 |
| T3o-7 | Pub/Sub 死信演练：持续瞬态失败超过订阅最大投递次数后进入 DLQ；保留原始 payload 与 `eventId`，修复后受控重放 | 运维 + 主架构 | ⬜ |

### T3o-O 简版观测 MVP

> 不要求 Prometheus、Dashboard 或 Alertmanager。本组验收的是代码能提供可查询的最小证据；能力缺失时须先作为代码交付补齐，不能以事后人工推断替代。

**2026-08-25 代码交付。** 盘点后确认计数类埋点原本已基本齐全（事件链 published/consumed/deduped/dlq、接入侧 `IngestionMetrics` 的 ack/nack/poison/deduped、调度五个 `collection.schedule.*`、步骤与渠道的 skipped/touch/duration、发件箱三个、Redis 深度三个 gauge），缺的是三块，已一并补齐：

1. **关联证据面**：新增 `/ops/evidence` 四个只读端点（`event/{eventId}`、`case/{caseId}`、`plan/{planId}`、`redis`），挂 `/ops/**` 复用管理后台登录态。计数器回答不了「这一条为什么没触达」，而 O1–O4 每条都要求可关联到 event/case 或 plan/step。出参裁掉 `payload` / `context_snapshot` / `resolved_params` / `content_summary` / `canonical_payload` / `borrower_name` / `push_token`，电话邮箱走 `PiiMask`；单测 `EvidenceControllerTest` 锁住读源与这条 PII 边界。
2. **调度链路 MDC**：`PubSubScheduleConsumer` 加 `msgId`、`ScheduledJobRunner` 加 `job` 与 `scanId`。指标是聚合值，分不开同一任务的两次投递，而 T5-S5/S6 要判定每条 tick 各自的去向。步骤链路在 `StepExecutionOrchestrator` 按入参补写 `caseId`/`planId`/`stepId`/`stepOrder`/`channel`——原先只由 Redis 总线消费线程写入，内存总线下缺 `stepId`，重试与 SPI 跨线程后也需续上。
3. **日切进度可查**：`RedisDailyRollDeduplicator` 新增 `evidenceSnapshot()` 与 `cursor-advanced-at` 键。只看完成标记分不出「在推进」与「卡住不动」，而 T5-S7 要判的正是「窗口内逐页推进并按时完成」。

Redis 证据一律实时打 Redis，不用三个 gauge 的采样值——gauge 只在 PEL 扫描周期（默认 30s）刷新，故障注入后读到的往往是注入前的旧值。PEL 明细只取最老若干条，深度取 XPENDING 汇总值；去重键用 SCAN 取有界样本，不用 KEYS。

| ID | 受控场景 | 最小可查询证据 | 状态 |
|---|---|---|---|
| T3o-O1 | 正常消息消费 | ACK/NACK/poison、投影与 inbox 状态，可关联 event/case | ✅ 2026-08-26：真实案件 489935 全链取证（inbox `projectionApplied=true`/`publishStatus=PUBLISHED` → 案件投影 → 计划 → 步骤），借款人手机与邮箱按 `PiiMask` 脱敏 |
| T3o-O2 | 发布失败与补发 | 失败原因、inbox 状态迁移与补发结果可查，不泄露 PII | 🟡 端点与指标可查，但无注入点构造发布失败（同 T5-R14：`EngineFaultInjector` 无发布侧位置）。顺延至 T4 |
| T3o-O3 | 调度、渠道与日切异常 | 任务成功/失败、扫描与完成标记、Guard 拦截与渠道失败可关联 plan/step | ✅ 2026-08-26：本组恰好靠这条证据面查出 F12——`scan_rows{callbackTimeout}` 每 tick +1 却无收敛，顺着 `/ops/evidence/plan` 定位到 plan 829 步骤 1416 的撕裂行 |
| T3o-O4 | Redis PEL/DLQ 与重复投递 | Stream/PEL/DLQ 深度、消费去重与重复投递证据可查 | ✅ 2026-08-26：`/ops/evidence/redis` 实测 `streamLength=341`、`dlqSize=6`、`pendingTotal=0` 与去重键样本；重复投递证据由 T5-R13 一并覆盖 |

### T5-S 调度通道专项

> 手动触发一律通过向调度主题发布带 `job` 属性的消息；证据取自应用侧调度指标与日志。Cloud Scheduler 的执行记录只能证明消息已发出，不能证明扫描已执行。

| ID | 场景 | 断言 | Owner | 状态 |
|---|---|---|---|---|
| T5-S1 | 调度链路连通 | 各周期任务按频率触发；失败计数为 0 | 运维 + 主架构 | ✅ 2026-08-26：`planStepDue`（每分钟）与 `callbackTimeout` 按频率自然触发，`collection_schedule_failed_total` 无样本 |
| T5-S2 | 按属性路由 | 三类任务各自触发且互不串扰 | 主架构 | ✅ 2026-08-26：逐个 job 属性注入，各自 `triggered{job=...}` +1，其余 job 计数不动 |
| T5-S3 | 未知 / 缺失 job 取值 | 记录并 ACK 不重投；无扫描发生。须含「`job` 只写在 body、未配 attribute」场景：计入 `skipped{reason=UNKNOWN_JOB}` 且 `triggered` 不增长 | 主架构 | ✅ 2026-08-26：未知取值与「job 只写 body、不带 attribute」两种都计入 `skipped{reason=UNKNOWN_JOB}`，`triggered` 不增长，消息 ACK 不重投 |
| T5-S4 | 陈旧消息丢弃 | 停机积压重启后仅放行当前 tick，无扫描风暴 | 主架构 | ✅ 2026-08-26：重启时积压的 19 条陈旧 tick 一次性丢弃，`skipped{reason=STALE}` 尖峰后归零，无扫描风暴 |
| T5-S5 | 重复投递 | 均 ACK；因步骤幂等不产生重复触达 | 主架构 | ✅ 2026-08-26：连发三条同 job tick，全部 ACK、`triggered` 按条增长，而 `scan_rows_total{planStepDue}` 保持 0，无重复业务 |
| T5-S6 | 并发单飞 | 第二条被跳过；游标不重复推进 | 主架构 | ✅ 2026-08-26：`max-concurrency=1` 下顺序投递摸不到单飞分支，临时抬到 16 并在单次 publish 里突发 200 条，175 条命中 `IN_FLIGHT` 跳过、`scan_rows_total` 仍为 0；验毕还原为 1 |
| T5-S7 | 日切窗口续跑与完成标记 | 窗口内逐页推进并按时完成，写当日完成标记 | ingestion + 运维 | 🟡 白名单模式下不适用：`DpdStageRollHandler` 仅在全量扫描模式走游标与完成标记，白名单模式直接遍历指定案件。已实测 `dailyRoll` 扫 51 案、零 `STAGE_CHANGED`/`CASE_CEASED`（与 dry run 一致）。「逐页推进 + 完成标记」需 `allow-full-scan=true`，顺延至 T4 |
| T5-S8 | 调度订阅独占 | 调度 subscription 无第二个订阅者；四条 Job 的 `describe` 输出含正确 `job` attribute 与 `Asia/Manila`；tick 不被其他消费者分流 | 运维 + 主架构 | ✅ 2026-08-26：摘除抢占该订阅的 `collection-admin-aicall` 容器后独占；四条 Job 的 `describe` 含正确 `job` 与 `Asia/Manila`，tick 不再分流 |

### T5-R Redis 专项

> 本组验证 Redis 实现特有的失败模式（崩溃恢复、PEL、DLQ、跨实例幂等），内存实现的测试结果不能替代任何一条。

#### 环境实况（2026-08-24 17:30–18:00 PHT 摸底）

开发机到 Redis（内网 `:6379`）仍不通，Pilot 机可达且自带 `redis-cli`，故本组全部在 Pilot 机执行。
pilot 应用（`SPRING_PROFILES_ACTIVE=pilot`、`COLLECTION_SCHEDULER_ENABLED=false`、50 案白名单、
`allow-full-scan=false`）已在跑并真连 Redis，Stream `collection:pilot:events` 长度 91、消费组
`collection-engine-pilot` pending=0 / lag=0，说明消费链路本身是通的。

**两项环境事实与手册要求不符，需在 T3o-2 判定时一并处理：**

**E1：Redis 不是独立实例。** 该库 `DBSIZE=28371`，按前缀分布是 `detect:*` 26069（id_detection_agent）、
`ds:*` 1969、`collection:*` 188、`db:*` 54 等。手册要求"独立实例"未满足。直接后果有二：
①任何 `FLUSHDB` / `FLUSHALL` 一律禁止，T5-R 的状态重置只能按 `collection:*` 前缀定点清理；
②**T5-R9（Redis 断连恢复）不能靠重启该实例做**，那会同时打断另外几个服务，只能在客户端侧断连
（如对应用容器做网络隔离或只 `CLIENT KILL` 我方连接）。

**E1 的 2026-08-25 处置（T3o 判定口径）：** 已改用独立 db3（`application-pilot.yml` 的
`database: ${COLLECTION_REDIS_DB:0}`，`pilot.env` 设 `COLLECTION_REDIS_DB=3`；我方键全在 db3，
db0 归 `id_detection_agent` 等）。这解除了上述后果①——db3 内可直接 `FLUSHDB`，T5-R 的状态重置
不必再按前缀定点清理，也少一类误删邻居键的风险。**后果②不解除**：db 只隔键空间不隔进程，内存、
单线程命令执行、AOF 与重启仍然共享，F6 那次全库丢数据正是 db 隔离防不住的类型。故 T5-R9 保持
上述降级口径。**T3o 接受在共用实例上完成，E1 记为明确环境偏差；独立实例推迟到 T4 前，与凭证
一次性轮换同批处理。** 另记一笔：非 0 的 db 将来若迁 Redis Cluster 需先迁回 db0，Cluster 只支持 db0。

**E2：AOF 未开启**（`appendonly no`，`maxmemory 0`，当前用量 19.64M；`maxmemory-policy noeviction` 符合要求）。
Redis 一旦重启，幂等锁、合规计数、接入去重键与事件流全部丢失。引擎事件有发件箱兜底，
接入去重与合规计数没有，需在 T3o-2 明确接受或要求运维开 AOF。

#### F6（已修）：消费组消失后总线永久停摆，且健康检查全程报 UP

2026-08-25 复核运维交付时发现：`<PILOT_INTERNAL_IP>` 已开 AOF（`appendfsync everysec`），
但开启过程伴随一次重启，而重启发生在 AOF 生效**之前**——全库数据丢失（`detect:*` 从 26069 掉到 71），
我方 `collection:*` 188 个键**一个不剩**，Stream、消费组、接入去重键、合规计数全没了。

这正是 Redis 变更清单第 1 项所述风险的实际发生。所幸影响可控：Stream 里 91 条事件此前
pending=0 已全部消费，无在途丢失；合规计数因调度关闭本就为空；接入去重键丢失后若数仓重推，
投影层 `case_version` 内容指纹仍能挡住重复入案（该层是 MySQL，未受影响）。

**但暴露了两个真缺陷：**

**其一，消费组消失后不自愈。** 建组原先只在 `@PostConstruct` 执行一次，组没了之后每次
`XREADGROUP` 都抛 `NOGROUP`，应用**空转十余小时未消费任何事件**，直到人工重启才会恢复。
而 T5-R9 的断言恰恰是"恢复后消费与认领自愈，无需重启，事件不丢"——这条在真实故障里已经失败，
不必等演练。修复：`consume` 与 `reclaimPending` 捕获异常，识别 NOGROUP 后就地重建消费组；
异常不再上抛（`@Scheduled` 默认会把栈打进日志，1 秒一轮足以刷满磁盘，真实原因反被自己的
重复日志淹没），改为按 1/2/4/8… 退避打印。

**临时处置（2026-08-25 09:57 PHT）**：修复须重部才生效，而部署要等独立实例地址，故先用
`XGROUP CREATE collection:pilot:events collection-engine-pilot $ MKSTREAM` 手工建组。
正在运行的实例下一轮 `consume()` 即恢复，01:57:07Z 后不再出现 NOGROUP，消费者已重新注册、pending=0。
起点取 `$` 是安全的：建组前 Stream 不存在且 `collection:*` 零键，无历史条目会被跳过。

> **待部署期间的风险**：此刻若数仓推送案件，接入端会写 MySQL 投影、`XADD` 进流、ACK 掉 Pub/Sub 消息，
> 但若届时消费组仍缺失，将来以 `latest()` 建组会把这些条目永久跳过，而 Pub/Sub 已 ACK 不再重投——
> 案件进了库却永远不建计划，静默且不可恢复。手工建组即为堵住该窗口。

**其二，总线全死而 `/actuator/health` 全程 UP。** 内置 `redis` 健康项只做 PING，连得上就算健康，
看不出组没了、消费停了。与此前 Pub/Sub 订阅流的教训完全同型。新增
`RedisEventBusHealthIndicator`（置于 admin，engine 无 actuator 依赖），最近一次拉取失败即判 DOWN
并带上连续失败次数与原因；总线不消费时本实例不会执行任何到期步骤，等同失去承载能力，故判 DOWN 而非
OUT_OF_SERVICE。

#### 执行记录（2026-08-25 10:08–10:11 PHT）：切 db3 + 四项修复上线并验证

运维授权在共用实例上任选 db（该实例 16 个库，此前仅 db0 在用），取 **db3**——避开 db1/db2，
减少他人同样"任选"时撞库的概率；键本就带 `collection:` 前缀，撞库不致键冲突，但会把
"不能整库操作"的问题重新带回来。`application-pilot.yml` 补 `database: ${COLLECTION_REDIS_DB:0}`
（原先无此项，Spring 默认锁死 db0），`pilot.env` 设 `COLLECTION_REDIS_DB=3`。
切换时机选在此刻：db0 中我方仅剩一个空消费组，零迁移成本。切换后已删除 db0 的孤儿组，
避免将来排障时看到两个同名组。

> **换 db 的边界**：只隔键空间，可安全做 `FLUSHDB`、`DBSIZE`/`--scan` 只见自身数据。
> **不隔进程**——内存、单线程命令执行、AOF 与重启仍与 db0 邻居共享：邻居一条慢命令照样阻塞我方，
> 昨晚那次"重启致全量丢失"即使当时在 db3 也一样会被清空，`maxmemory` 仍是实例级的 0。
> 故运维清单第 2、4 项保留，第 3 项降级为"独立 db，已接受，缺口记录在案"。
> 另注：**Redis Cluster 只支持 db0**，若将来迁集群需先迁回。

上线后 `/actuator/health` 组件列表新增 `redisEventBus: UP`（F6 的健康指示器生效）。

**F6 自愈生产验证**：在 db3 直接 `DEL collection:pilot:events` 模拟消费组丢失，8 秒内应用自动重建，
日志仅一条 `consumer group ... is gone (failure #1), recreating`（退避生效，不再每秒刷屏），
全程无需重启。对照昨天同型故障靠人工重启且十余小时无人察觉。

**F4/F5 生产验证**：向 db3 注入三条记录，结果与设计一致，pending 全部归 0（均已 ACK）：

| 注入内容 | DLQ reason | DLQ 载荷 | MySQL `payload` |
|---|---|---|---|
| `foo=bar baz=qux`（无 event 字段） | `MISSING_EVENT_FIELD` | `{"foo":"bar","baz":"qux"}` | 同左，`event_type=UNKNOWN`，`event_id` 回落 Redis 记录 ID |
| `event={not-json` | `DESERIALIZATION_FAILURE` | `{"raw":"{not-json"}` | 同左 |
| `event=` 合法 JSON，`eventType=PTP_EXPIRED`（订阅已注释，无 handler） | `NO_HANDLER` | 事件原文逐字保留 | 同左，`event_id=t5r-nohandler-001`、`event_type=PTP_EXPIRED` |

第二行即 F5 的关键证明：**非法 JSON 被包装后成功落进 `JSON NOT NULL` 列**。修复前该 INSERT 必然报错，
进而不 ACK、毒消息回 PEL 反复重投、再次走进同一段代码——隔离退化成死循环。

**T5-R6「不可恢复项终止」（10:21 PHT）**：以上三条恰好覆盖全部不可恢复原因，
`POST /ops/dlq/redrive` 返回 `{redriven:0, deferred:0, terminated:3, skipped:0}`，
三行 `status` 转 `TERMINATED`、`terminated_at` 为 PHT、`failure_reason` 变为
`NON_RECOVERABLE:<原因>`，`[DLQ]` 日志留有汇总行。**幂等复验**：同一批再重放一次得
`{terminated:0, skipped:3}`，不会重复终止。

> **发现（未修，待定）：终止路径丢弃操作人填写的重放理由。** `markTerminated` 的 SQL 是
> `SET status='TERMINATED', failure_reason=#{reason}, terminated_at=NOW()`，
> 只写入派生的 `NON_RECOVERABLE:<原因>`，不写 `redrive_reason`——该列仅在 `claimForRedrive`
> 的重放路径被填。于是终态行只答得出「何时终止、属哪类不可恢复」，答不出**谁决定放弃、依据什么**，
> 这个信息只存在于应用日志里。DLQ 表本是审计表（`redrive_count`/`redrive_reason`/`redriven_at`/
> `terminated_at` 都是为审计而设），而 DLQ 条目可能代表一次未送达的客户触达，「谁决定不再重试」
> 正是审计要回答的问题。
>
> 另一处可议：把 `failure_reason` 原地改写成 `NON_RECOVERABLE:<原因>` 会让按该列聚合的告警
> 把同一类故障拆成两个桶（终止前后各一），虽然可用 `status` 过滤，但列语义已从"为何失败"
> 漂移成"为何终止"。建议改为 `failure_reason` 保持不变，终止分类与操作人理由一并写入
> `redrive_reason`。改动会触及 `DlqControllerTest` 中 `verify(repository).markTerminated("e1",
> "NON_RECOVERABLE:NO_HANDLER")` 一类断言，属审计契约变更，需确认后再动。

#### F8（已修）：管理面曾公网可达，且登录不校验口令

跑 T5-R6 需要过 `/ops/**` 的登录拦截，读代码时发现 `AuthController.login` 是 Phase 1 的开发态实现：
**不校验任何口令**，请求体里写什么 `username`/`role` 就发什么会话，默认还是 `SYSTEM_ADMIN`。
本以为它只在开发机上跑，遂核对生产暴露面，结论是三项条件同时成立：

1. 容器映射为 `0.0.0.0:8080`（`docker port` 与宿主 `ss` 均确认），宿主 `iptables INPUT` 策略为 `ACCEPT`；
2. **从公司外的开发机直接访问 `http://<PILOT_HOST>:8080/actuator/health` 得 HTTP 200**，即 GCP 防火墙未拦 8080；
3. 从同一台开发机 `POST /auth/login` 带任意用户名即得 `SYSTEM_ADMIN` 会话，再以该会话调
   `POST /ops/dlq/redrive` 返回正常业务结果（用了不存在的 id，无副作用）；无会话时对照请求为 401。

也就是说**互联网上任何人都能拿到本系统的管理员会话**。拦截器本身是好的（无会话确实 401），
问题是这扇门没装锁。按 `AdminWebConfig` 的注册，该会话覆盖 `/cases/**`、`/compliance/**`、
`/ops/**`、`/admin/**`、`/config/**`——含**债务人 PII（姓名/手机/邮箱/欠款）**、合规配置与
DLQ 重放（可驱动真实触达）。这是菲律宾市场的真实借贷客户数据，属数据泄露与合规事故级别。

**建议的即时缓解**（不改代码）：`pilot-run.sh` 的端口映射改为 `-p 127.0.0.1:8080:8080`，
运维经 SSH 隧道访问。当前 AI_CALL 已摘出 pilot、尚无公网回调需求，故绑本地不影响任何在用功能。
待回调 URL 上线时，正确做法是用反向代理**只**放通回调路径（该路径本就不在拦截器名单内，
依赖签名校验），而非把整个 8080 敞开。**真正的修复**是给 `AuthController` 接上实际的凭据校验，
属 Phase 1 收口前必须完成项。

> 注：此前 L4b/T3o 的多轮验证都在生产机本地用 `127.0.0.1:8080` 完成，未触及暴露面，
> 所以这个问题一直没被发现。

**处置（2026-08-25 10:28 PHT，已完成第一步）**：`deploy/pilot-run.sh` 增加 `BIND_ADDR`
（默认 `127.0.0.1`），端口映射由 `-p ${PORT}:8080` 改为 `-p ${BIND_ADDR}:${PORT}:8080`，已重部。
复验四项：`docker port` 显示 `127.0.0.1:8080`；从公司外开发机探测 `HTTP=000`（连接被拒）；
公网 `POST /auth/login` 不可达；生产机本地与 SSH 隧道（`ssh -L 8080:127.0.0.1:8080`）
均返回 200，运维日常访问不受影响。

**处置第二步（10:36 PHT，已完成）：补凭据校验。** 新增 `AdminAuthProperties`
（`collection.admin.auth.accounts`，字段 `username` / `password-hash` / `role`）与
`AdminAuthenticator`，`AuthController.login` 改为校验 BCrypt 哈希后才发会话。
只引 `spring-security-crypto` 取 BCrypt，**不引 `spring-boot-starter-security`**——后者会给
全部端点套上默认过滤器链，与既有的 `AdminAuthInterceptor` 及免鉴权的回调路径冲突。

四点设计取舍：
- **角色由配置决定，不由请求决定**。此前请求体写 `role=SYSTEM_ADMIN` 就发什么，是提权的直接入口。
- **用户名不存在时也跑一次 BCrypt**（对固定的无效哈希）。否则「立即返回」与「算 100ms」的
  耗时差可用来枚举有效用户名。对外只有一种失败文案 `Invalid credentials`。
- **登录成功时换新 session id**，防固定会话攻击。
- **不加失败锁定**。BCrypt cost 10 本身约 100ms/次，已构成足够的暴力破解阻尼；
  而锁定会引入反向 DoS——攻击者可用错误口令把真实管理员锁在门外。失败登录打 WARN 日志留痕。

启动闸门加一条：pilot 下 `hasUsableAccount()` 为假即拒绝启动，避免带着"人人可登录"
（修复前的行为）或"无人可登录"（配漏了）的状态上线。local profile 内置固定弱口令
`admin / local-dev`，敢入仓是因为该 profile 从不连生产库、也不对外暴露端口；
pilot 账号只经 `pilot.env` 注入，哈希不入仓。

> **部署踩点**：BCrypt 哈希形如 `$2y$10$...`，而 `pilot-run.sh` 是 `set -a; . "$ENV_FILE"`
> 走 shell 解析的，**不加单引号时 `$2y`、`$10` 会被展开成空串**，得到一个永远匹配不上的
> 残缺哈希，表现为"口令正确却登录失败"，极难往这上面想。已在 `pilot.env`、
> `AdminAuthProperties` Javadoc 与两处 YAML 注释同时写明。实测注入后 shell 解析结果
> 为 60 字符、`$2y$` 开头，完整。

**生产复验七项**（10:36–10:37 PHT，全部符合预期）：①无口令登录 401；②口令错误 401；
③请求体自带 `role` 提权 401；④未登录调 `/ops` 401；⑤正确凭据 200 且角色取自配置；
⑥该会话调 `/ops` 200；⑦登出后会话失效 401。失败与成功均有 `[AdminAuth]` 日志留痕。
单测 9 条（`AdminAuthenticatorTest`）锁住上述语义，含 htpasswd 产出的 `$2y$` 前缀可被校验。

**处置第三步（2026-08-25 20:0x PHT，已完成）：反向代理收口。** 上文「待回调 URL 上线时用反向代理
只放通回调路径」这一步此前未做，而 nginx 站点 `collection-admin.mocasa.com` 的 `location /` 是
`proxy_pass http://127.0.0.1:8080` —— 也就是说容器绑回环挡住了直连 8080，却被反向代理原样绕过，
管理面与 `/actuator` 仍在公网可达。`/webhook/sendgrid` 上线需要公网入口，正好一并收口。

配置改为只保留 `location ^~ /webhook/`（另加 `client_max_body_size 1m`，回调都是小 JSON，
全局 500M 只会放大打空请求的成本）与 ACME 挑战路径，其余 `location / { return 403; }`。
返回 403 而非 404，是为了让「端口开着但这条路径不对外」与「应用没起来」在排查时可区分。
三个入站回调（Valubo AI_CALL、通知平台、SendGrid）都在应用侧验签，nginx 只做路径收口不做鉴权。
配置纳入仓库 `deploy/nginx/collection-admin.mocasa.com.conf`，避免它只存在于机器上。

**公网侧复验**（从公司外开发机 curl，非机器内部）：`POST /webhook/sendgrid` → 401
（到达应用并被 ECDSA 验签拦下，日志 `rejected reason=MISSING_HEADER`）；
`POST /webhook/channel-callback` → 500（到达应用）；
`/ops/evidence/*`、`/actuator/prometheus`、`/actuator/health`、`/cases`、`/admin`、`/config`、
`/login`、`/` 一律 403。运维访问仍走 SSH 隧道。

**遗留**：口令为单账号共享，无按人区分与轮换机制；如需审计到人，Phase 2 应接 SSO 或建用户表。

#### F9（进行中，需协调）：同事覆盖部署导致 pilot 崩溃循环，并暴露 F8 判断有缺口

2026-08-25 10:43 注入 T5-R3 毒消息后无任何消费迹象，回查发现应用自 **10:38 起崩溃循环**，
到 10:47 已重启 23 次，每轮启动约 6.5 秒即退出，公网域名返回 502。

**根因一：他人覆盖部署了一个起不来的构建。** `ubuntu` 的 `bash_history` 显示 10:38 有人
`cp /tmp/collection-admin.jar` 到部署位置、`docker build --no-cache`、再跑 `pilot-run.sh`，
覆盖了我 10:36 部署并验证通过的版本（`who` 显示 `zongqiang` 与另一个 `ubuntu`
交互式会话在线）。该构建启动即抛
`IllegalStateException: Pilot requires collection.case-service=real`。

拆包比对确认这是个**半合并的分支**，自身就无法启动：其 `PilotReadinessValidator` 要求
`caseService instanceof RealCaseService`，而同一个 jar 内置的 `application-pilot.yml` 写的是
`case-service: ai`——工厂产出 `AiCollectionCaseService`，闸门要 `RealCaseService`，
无论环境怎么配都必然失败。该 jar 也**不含我当日的 F8 鉴权修复**（内置 yml 中 `admin.auth` 零命中），
即其 `/auth/login` 仍是不校验口令的旧实现。

**根因二（更要紧）：F8 的暴露面判断有缺口。** 本机装有 nginx，
`/etc/nginx/conf.d/collection-admin.mocasa.com.conf` 把
`https://collection-admin.mocasa.com` 整站反代到上游 `<PILOT_INTERNAL_IP>:8080`，
而 **`<PILOT_INTERNAL_IP>` 正是本机 `ens4` 的地址**（Redis 也在这台机器上，此前一直以为是独立主机）。
关键是它用的是 `location /`——**放通全部路径**，不只是回调。

于是此前的真实暴露面是两扇门而非一扇：裸端口 `<PILOT_HOST>:8080`（我已封堵），
以及 `https://collection-admin.mocasa.com/`（**带 TLS、有正式域名、更容易被发现，一直开着**）。
访问日志显示该域名自 2026-08-24 06:04 UTC 起接受公网请求。**我先前"绑回环即封堵完成"的结论不成立。**

**连带影响：绑回环反而打断了公网回调。** nginx 连的是 `<PILOT_INTERNAL_IP>:8080`，
而 Docker 现在只绑 `127.0.0.1:8080`，两者不通。应用恢复后经域名访问仍会是 502。

**入侵痕迹排查（无实际利用证据）**：access log 中 52 条 401 全部来自自动化扫描器探测
`.env` 与 PHP 后门（`/config/.env.php`、`/admin/.env`、`/wp-login.php` 等，
恰好命中 `/config/**`、`/admin/**`、`/ops/**` 前缀而被拦截器挡下）；
**无任何请求打过 `/auth/login`**；返回 200 的仅有 ACME 证书校验与
来自团队 IP 的 `/actuator/health`。即暴露窗口真实存在，但未见被利用。

**当前处置（2026-08-25 10:50 PHT）**：已暂停对生产的一切操作，等与同事对齐。
容器仍在崩溃循环（`--restart unless-stopped`），公网 502。该状态不致数据损坏——
调度关闭、事件留在 Redis db3 未消费、10:43 注入的 T5-R3 毒消息仍在流中待处理。

**待办（对齐后一次性执行）**：①把同事的 `/webhook/facade-callback` 合并进 `ca_branch`，
解决 `case-service` 的 `ai`/`real` 冲突（主线为 `ai`），跑全量回归后统一构建部署；
②同批次改 nginx；③约定部署互斥，避免再次互相覆盖。

**待定的正确形态**（涉及共享 nginx，需运维确认后再动）：
①nginx 上游改 `127.0.0.1:8080`，使应用可继续只绑回环；
②公网 server 块只放通 `/webhook/**` 与 `/actuator/health`，其余路径直接 403，
管理面改由 SSH 隧道访问；③与同事约定部署互斥，避免再次互相覆盖。

> **附带收获**：`https://collection-admin.mocasa.com/webhook/facade-callback` 已可用，
> 且同事已在联调该回调（access log 中该路径 16 次）。**这正是此前阻塞 AI_CALL 与 T3o 的公网回调 URL**，
> 摘除 AI_CALL 的前提条件可能已经消失，需与同事对齐进度后重新评估。

#### F7（已修）：PEL 深度指标恒被夹在 50

`reclaimPending` 用 `XPENDING` 的**明细**形式（受 `pel-batch-size` 限制，默认 50）
的返回条数喂 `collection.event.pending`。积压涨到几千也只显示 50：阈值设在 50 以上的告警
永远不触发，Dashboard 上是一条压在 50 的直线。指标存在、能抓取、数值是错的，属最难发现的观测缺陷，
且正砸在 T3o-O4 要求的"PEL 深度可查"上。改为用**汇总**形式
`pending(key, group).getTotalPendingMessages()` 采样，明细查询仍保留给认领逻辑，两者职责分开。

> 附带风险（未改）：`reclaimPending` 每 30 秒最多认领 `pel-batch-size` 条，恢复速率约 100 条/分钟。
> 本身不算错，但在指标被夹住时看不见积压，两者叠加比单独任何一件都糟。指标修好后可观察再定是否调参。

#### 需向运维提出的 Redis 变更清单

| # | 诉求 | 为什么 | 不做的后果 | 优先级 | 状态 |
|---|---|---|---|---|---|
| 1 | 开启 AOF（`appendonly yes`，`appendfsync everysec`） | 合规日计数与接入去重键只存在于 Redis，没有任何持久化兜底 | 实例重启后当日合规计数归零 → **同一客户当天可被重复触达，属合规事故**；接入去重键丢失 → 数仓重推的旧消息被当新消息重复入案 | 高 | ✅ 2026-08-25 已开（开启过程的重启已致一次全量丢失，见 F6） |
| 2 | 设置 `maxmemory` 上限并保持 `noeviction` | 现为 `maxmemory 0`（无上限），与另外几个服务共用 28K key 的实例 | 邻居服务写爆内存时由 OS OOM 决定杀谁，可能直接杀掉整个 Redis；设了上限才会以可预期的写入报错方式暴露 | 高 | ⬜ 仍为 0 |
| 3 | 独立实例，或至少独立 `db` 编号 | 当前与 `detect:*`（26069 key，id_detection_agent）等混用 | ①任何全库操作一律禁止，故障时无法快速重置我方状态；②**T5-R9（断连恢复）无法通过重启实例演练**，重启会同时打断邻居服务 | 中 | 🟡 运维称已提供，但 `<PILOT_INTERNAL_IP>` 上 `detect:*` 仍在，新实例地址待确认 |
| 4 | 若维持共用，需一条可对我方连接做网络隔离的手段 | T5-R9 必须验证断连后消费与 PEL 认领能自愈 | 该用例只能降级为"客户端侧 `CLIENT KILL`"，覆盖不到服务端不可达期间的重连行为 | 中 | 🟡 若第 3 项落实则不再需要 |
| 5 | Redis 侧监控接入（内存、连接数、Stream 长度） | 简版观测 MVP（T3o-O4）要求 Stream/PEL/DLQ 深度可查 | 只能靠应用侧 gauge 单点自证，Redis 自身异常无外部佐证 | 低 | ⬜ |

> 第 1、2 项建议在 T4 放量前闭合；第 3、4 项若运维无法提供，则在台账中把 T5-R9 记为"降级覆盖"并说明缺口。

#### F4（已修）：每次重启往 DLQ 灌一条假失败

生产 DLQ `collection:pilot:events:dlq` 有 15 条记录，MySQL `t_event_dlq` 同样 15 行，
全部 `reason=DESERIALIZATION_FAILURE`、`event=null`、`deliveries=1`，时间跨 09:13–17:05 PHT，
条数正好等于当日重启次数，消费组里也累积了 16 个消费者名。

根因在 `RedisStreamEventBus.initConsumerGroup()`：建组前先写一条 `{"bootstrap":"1"}` 把流撑起来。
首次启动时组以 `latest()` 建立，这条记录不会被消费；**此后每次重启写的那条都落在 last-delivered-id 之后，
必被投递，又因缺 `event` 字段判成失败进 DLQ**。于是 DLQ 深度随重启次数单调增长，
`collection.event.dlq.size` 指标与任何基于它的告警恒为红，真实故障淹没在噪声里——
而这正是 T3o-O4 与 T5-R5/R6 要测量的对象，基线已被污染。

那条假记录本就多余：`opsForStream().createGroup(...)` 底层是 `xGroupCreate(key, group, offset, mkStream=true)`
（已反编译 `spring-data-redis-2.7.18` 的 `DefaultStreamOperations` 确认），流不存在会自动建出来。

修复三点：①删掉 bootstrap 写入，只调 `createGroup`；②缺 `event` 字段与解析失败分开归类，
前者记 `MISSING_EVENT_FIELD` 并打错误日志带上实际字段名；③DLQ 载荷在缺字段时改存整条记录，
不再写字面 `"null"`——进了 DLQ 的记录已经没法自我解释，再把唯一线索丢掉就完全无从排障。
`RedisStreamEventBusStartupTest` 四条单测锁住：建组不写任何记录、重复建组幂等、两类失败的归类与载荷。

**生产验证（2026-08-24 17:52 PHT 重部后）**：重启前 DLQ=15 / Stream=91 / 消费者=16，
重启后 DLQ 仍为 15、Stream 仍为 91（未写入 bootstrap 记录）、pending=0。存量已清理：
Redis DLQ `XTRIM ... MAXLEN 0`（15→0）、MySQL `t_event_dlq` 删 15 行（判据
`JSON_TYPE(payload)='NULL'`）、`XGROUP DELCONSUMER` 删掉 16 个 pending=0 的陈旧消费者名（17→1）。
删除前的原文存于生产机 `/opt/app/logs/evidence/dlq-before-cleanup-20260824.txt` 与
`consumers-before-cleanup-20260824.txt`。带 F5 修复二次重部后复验：DLQ 仍为 0（Redis 与 MySQL 两侧）、
Stream 仍为 91、健康 UP。至此 T5-R1 在生产取到干净证据。

> **遗留（低危，未修）**：消费者名取自容器 HOSTNAME，每次 `docker run` 都是新值，消费组里的消费者条目
> 只增不减（清理前已累积 17 个）。功能无影响，但会污染 T3o-O4 的 PEL/消费者证据。
> 不宜在 `@PreDestroy` 里自删——`XGROUP DELCONSUMER` 会连同该消费者的 PEL 条目一并销毁，
> 若停机时尚有未 ACK 消息就等于直接丢事件。可行做法是在 `reclaimPending` 里定期剪除
> 「非本机 + pending=0 + idle 超阈值」的消费者，待确认后再实施。

#### F5（已修）：解析失败的原文写不进 DLQ 表，隔离会退化成死循环

清理存量时发现 `t_event_dlq.payload` 是 **`JSON NOT NULL`** 列（此前 `payload='null'` 删不掉，
必须用 `JSON_TYPE(payload)='NULL'`，因为存进去的是 JSON null 字面量而非字符串）。

由此暴露一条此前从未被走到的致命路径：`DESERIALIZATION_FAILURE` 的输入按定义就是非法 JSON，
而 `persistDlq` 把原文直接交给该列，**INSERT 必然报错**。落库失败 → 不 ACK → 毒消息回到 PEL
反复重投 → 到达投递上限后走 `deadLetter` → 再次调用同一段代码 → 再次失败。
「进 DLQ 隔离」于是变成死循环，且期间该消费者被这条消息持续占用。

之所以一直没暴露，是因为迄今 DLQ 里只有 F4 那批 bootstrap 记录，它们的载荷恰好是合法 JSON 的
`null` 字面量。真正的毒消息一到就会触发——而这正是 T5-R5 要测的场景。

修复：`dlqPayload` 保证返回合法 JSON。原文能解析则原样保留；不能解析则包成 `{"raw":"<原文>"}`，
线索不丢且一定能落库。两条单测分别锁住"非法 JSON 被包装且 `raw` 可还原"与"合法 JSON 不被额外包装"。

#### F10（已修）：MDC 写入是一条失败路径，毒丸每被投递一次吃掉一个消费线程

2026-08-25 19:55 Pilot 重部到 ca_branch 基线后，启动即出现三行没有时间戳、没有 MDC 的
`Exception in thread "engine-consumer-N" java.lang.NumberFormatException: For input string: "not-a-number"`
（N = 1/2/7）。来源是此前埋下的 T5-R 毒丸样本 `t5r-poison-001`，payload 里 `planId` 是非数字。

根因在 `RedisStreamEventBus.putMdc` 的调用位置：它夹在 `process` 的「缺 event 字段」「反序列化失败」
两段 try 与「handler 失败」那段 try **之间**，自身无任何保护；而 `CollectionEvent.getLong` 用
`Long.valueOf(v.toString())`，畸形值直接抛。异常于是一路逃过 `submit` 的 lambda（当时只有
`finally { MDC.clear(); }`，无 catch）到达线程池的 uncaught handler，**打死一个工作线程**，
而该记录既未 ACK 也未进 DLQ，只能等 reclaim——下一次投递再杀一个。

**毒丸最终仍被隔离**：第 5 次投递触发 `MAX_DELIVERY_EXCEEDED`，Redis DLQ 流与 MySQL
`t_event_dlq`（id 26，`delivery_count=5`）双写成功并 ACK，PEL 归零。也就是说兜住它的是投递次数
上限，不是异常处理。这个区别是要紧的：上限是**每条消息**的计数，线程死亡是**全局**的容量损耗，
并发毒丸多几条时池会比上限先被抽干，而 `ThreadPoolExecutor` 重建工作线程期间吞吐静默下降，
外部只看到「消费变慢」。加之线程死亡只在 stderr 留一行无时间戳无 MDC 的文本，与业务日志对不上
时间线，排查会绕远路。

修复两处：①`putMdc` 改用 `CollectionEvent.getString`——MDC 的值本来就是字符串，解析成 Long 再
`toString` 回去没有收益，却凭空造了一条失败路径；改后畸形值原样进 MDC，日志里直接看得到
`plan=not-a-number`，事件则继续走到 handler，由后者抛出有业务含义的错误并进入正常的重试 / DLQ 路径。
②`submit` 补 catch 作兜底网（不是主防线），任何意外逃逸至少留下可检索的 ERROR 而不是静默杀线程。
单测 `RedisStreamEventBusDedupTest#nonNumericIdInPayloadReachesHandlerInsteadOfEscaping`。

**生产验证（2026-08-26 09:33–09:43 PHT）**：重部后注入同型毒丸 `t5r-poison-002`，**死线程数 0**
（对照修复前 3）。每次投递只产出一行带完整 MDC 的
`[event=t5r-poison-002 case= plan=not-a-number step=999999] handler failed ..., leaving pending`，
共 5 次；随后 `MAX_DELIVERY_EXCEEDED` 收敛，`t_event_dlq` id 27（`delivery_count=5`），PEL 归零。

这一轮同时把 T5-R5 的 `MAX_DELIVERY_EXCEEDED` 路径跑成了**目标路径**而非**兜底路径**：
同样是 5 次投递、同样落 DLQ，区别在于隔离由异常处理完成，消费池容量全程无损耗。

#### F11（已修）：并发重复 `CASE_INGESTED` 撞单活跃计划唯一键，报 ERROR 后靠重试自愈

同一次启动中，`520049:S3` 的两条 `CASE_INGESTED`（`adecc921` 与 `36c28edf`）几乎同时到达：
`createPlanForStage` 的预检查 `findActivePlanByCaseAndStage` 是**非加锁读**，两条都通过，
先提交的建成 plan 829，后提交的在 INSERT 处撞 `uk_active_stage_key`，
`DuplicateKeyException` 上抛成 `handler failed ..., leaving pending`。

数据是对的——唯一键正是为此存在，没有出现第二个活跃计划；重投时预检查读到已提交的 829 便幂等
跳过（`collection:processed:36c28edf` 已写）。**所以它本就自愈**，代价是一条 ERROR 噪声加一轮
无谓重试。但 Pub/Sub 至少一次投递叠加数仓日常重推，T4 之后这会是常态，ERROR 级噪声会淹没真故障。

修复：在 `EventConsumerDispatcher` 的**事务边界之外**归一——`onCaseIngested` 带 `@Transactional`，
约束冲突已把事务标记为 rollback-only，在 `createPlanForStage` 内部吞掉只会换来
`UnexpectedRollbackException`；而 dispatcher 位于代理之外，事务已完成回滚，该事务除这条失败的
INSERT 外无其它写入，回滚即无副作用。按**约束名**判定而非见 `DuplicateKeyException` 就吞，
否则将来新增的任何唯一键都会被静默跳过。覆盖三个会建计划的事件类型
（`CASE_INGESTED` / `STAGE_CHANGED` / `PLAN_EXHAUSTED` 的 REBUILD 路径）。
单测 `EventConsumerDispatcherTest` 五条。

#### F12（已修）：步骤乱序完成时，推进取到已终结的后继并把它复活成待执行

生产序列（plan 829，取自 `/opt/app/logs/collection/collection.log`）：

```
09:55:53  step 1416 → STEP_EXECUTING，挂 60min 回调超时
10:02:12  [callback]  plan 829 step 1416 result FAILED   ← 按回调正常终结
10:02:13  [advance]   plan 829 → 下一步 1417 at 12:00     ← 推进正确
10:04:56  [advance]   plan 829 → 下一步 1416 at 10:04:56  ← step 1415 退避重试后才落地
10:05:04  [execStep]  duplicate event, key=829:2:0 skipped ← 幂等锁挡住了真实重复外呼
```

根因是推进取「下一步」的口径：`selectByPlanAndOrder` 按 `step_order = 当前 + 1` 取，**不判该步骤是否已终结**。
步骤会乱序完成——退避重试的步骤晚于其后继落地，于是后继完成时推进一次，重试步骤完成时又推进一次，
第二次取到的正是那个已 COMPLETED 的后继。调用方随后按「无 `trigger_time` 就排期」调
`updateTriggerTime` 把它改回 PENDING，`PLAN_STEP_DUE` 再抢占成 EXECUTING。

**只剩幂等锁挡在真实触达前**：该行呈撕裂态（`status=EXECUTING`，而 `result=FAILED` /
`completed_at=10:02:12` 是上一次终态的残留）。且 `onCallbackTimeout` 要求
`plan.status=STEP_EXECUTING`，计划已推进到 STEP_SCHEDULED，于是 `callbackTimeout`
每分钟扫到它却永远处理不掉——这就是 T3o-O3 看到的 `scan_rows` 只增不收敛。

修在两处，缺一不可：

1. `selectByPlanAndOrder` 改取「序号更大且尚未终结的第一条」（`step_order >= #{stepOrder}`
   + `status NOT IN (终态)`）。同时兼顾停摆一侧：跳过终态后继续往后找，否则计划会停在一个
   永远不会再到期的步骤上；全部后继皆终结时返回 null，由调用方判 `PLAN_EXHAUSTED`。
2. `updateTriggerTime` / `updateTimeoutTime` 加终态谓词。调用方的「先读后写」不具原子性——
   读到的状态与写入之间隔着 SPI 调用与渠道 I/O，期间回调或超时随时可能把步骤终结，
   所以判定必须落在 UPDATE 语句自身。这是写时刻的最后防线，与 `markExecuting` 同源。

L3 集成测试 `advanceAfterOutOfOrderCompletion_skipsTerminalStepAndCannotReviveIt` 打真库复刻该序列。
修复前已产生的那一行由 `scripts/pilot/2026-08-26-fix-f12-revived-step-1416.sql` 按 10:02:12
那次回调（`callbackAudit` id=11，`signature_valid=1`）订正回 COMPLETED/FAILED 并清空 `timeout_time`，
不新增触达、不改 timeline。

#### F13（缺口，未修）：`MAX_DELIVERY_EXCEEDED` 分类下缺显式终止入口

`/ops/dlq/redrive` 只对 `failure_reason != 'MAX_DELIVERY_EXCEEDED'` 的行判 NON_RECOVERABLE 并直接终止。
但该原因只描述**重投次数用尽**，不描述失败性质：真正不可恢复的毒丸若每次都落进通用 catch
（而非被判 `DESERIALIZATION_FAILURE` / `MISSING_EVENT_FIELD`），就会带着「可恢复」标签进 DLQ。

T3o 收尾遇到的正是这种：`t5r-poison-001/002` 的 payload 是 `planId="not-a-number"`，重放必然再失败，
要连打三轮把 `MAX_REDRIVE_COUNT` 耗尽才会自动 `REDRIVE_LIMIT_EXCEEDED` 终止——代价是往 stream 里
再注六条注定失败的事件。本次按 API 的三段式审计格式（分类\|操作人\|理由）直接落终态
（`scripts/pilot/2026-08-26-terminate-t5r-poison-dlq.sql`）。

修法待定，倾向给端点加显式 `terminate` 动作并强制带理由，而不是让运维靠耗尽重放配额绕过。
不阻塞 T4：DLQ 项本身不会自行触达，且现有绕法留全审计。

| ID | 场景 | 断言 | Owner | 状态 |
|---|---|---|---|---|
| T5-R1 | Consumer Group 初始化 | 重复初始化幂等，不阻塞启动；**且不得因建组行为自身产生 DLQ 记录**（见 F4） | 主架构 | ✅ 2026-08-25 生产证据；2026-08-26 重启复验：消费组未被重建，无新增 DLQ |
| T5-R2 | 正常消费与 ACK | 事件被及时消费，待处理列表清空 | 主架构 | ✅ 2026-08-26：真实入案（数仓推送案件 489935 等）走通 `CASE_INGESTED` → 建计划 → 步骤到期 → 触达全链，PEL 归零 |
| T5-R3 | handler 异常滞留 PEL | 不 ACK，消息在待处理列表可见 | 主架构 | ✅ 2026-08-26：对 `PLAN_STEP_DUE` 注入 `BEFORE_HANDLER` 故障，消息不 ACK 且在 `XPENDING` 可见 |
| T5-R4 | PEL 认领与重投 | 未超空闲阈值不认领；超时后认领并最终 ACK | 主架构 | ✅ 2026-08-26：`pel-min-idle-seconds` 临时降至 20s（手册允许），阈值内不认领、超时后认领并重放成功、PEL 归零 |
| T5-R5 | 毒消息进 DLQ | 超投递上限后移出 PEL 并入 DLQ，附原因 | 主架构 | ✅ 2026-08-25 生产证据：四条原因全覆盖。`MAX_DELIVERY_EXCEEDED` 由 `t5r-poison-001` 实证（5 次投递 → Redis DLQ 流 + `t_event_dlq` id 26 双写 + ACK，PEL 归零）。**代价见 F10**：该路径当时靠投递上限而非异常处理收敛，沿途打死 3 个消费线程 |
| T5-R6 | DLQ 落库与受控重放 | 落表含原始信封与原因；可恢复项重放成功；不可恢复项终止并告警；窗口外触达延后 | 主架构 | ✅ 2026-08-26：R5 的 DLQ 项经管理台带理由重放，审计留操作人与理由；因底层步骤已终结，重放事件被幂等跳过（这正是「重放不重复执行业务」的期望）。终止一路见 F13 |
| T5-R7 | 幂等锁跨实例互斥 | 同一键仅一次获取成功，TTL 到期后可再获取 | 主架构 | ✅ 2026-08-26：Pilot 上另起一个隔离探针实例，两个不同 eventId 指向同一 PUSH 步骤并发注入，主实例执行、探针实例撞重复闸门跳过，timeline 仅一条 |
| T5-R8 | 合规频控连续性 | 计数不因重启清零；边界并发只放行一个；断连 fail-close | 主架构 | 🟡 跨重启连续性已证（计数与 TTL 原值保留）；边界并发与断连 fail-close 需构造真实触达临界，随 T4 放量验 |
| T5-R9 | Redis 断连恢复 | 恢复后消费与认领自愈，无需重启，事件不丢 | 主架构 + 运维 | ✅ 2026-08-26：按 db3 定向 kill 本应用连接（不波及共用方，解 E1），Lettuce ConnectionWatchdog 毫秒级重连，消费失败计数 0，无事件丢失 |
| T5-R10 | 并发与背压 | 慢渠道不阻塞其他事件；队列有界；背压不丢消息 | 主架构 | ✅ 2026-08-26：向流注入 200 条指向已完成步骤的 `PLAN_STEP_DUE`，10s 内消费完毕、PEL 归零、无丢弃，全部走幂等跳过 |
| T5-R11 | 指标与 MDC | 关键指标可抓取，日志携带 event/case/plan/step | 主架构 + 运维 | ✅ 2026-08-26：四键在日志实证；**并修掉 `job`/`scanId` 缺失**——`ScheduledJobRunner` 一直往 MDC 放，但 `logback-spring.xml` 的模式没写这两键，S6/R11 判读所需的扫描切分此前不可见 |
| T5-R12 | 接入去重跨重启 | 重启后重复消息仍被拦截；相同快照跳过；陈旧还款不覆盖新投影 | 主架构 | ✅ 2026-08-26：`collection:processed:*` 与入案记录跨重启完整存续，重复消息仍被拦截 |
| T5-R13 | 事件消费去重 | PEL 重投与 DLQ 重放都不重复执行业务 | 主架构 | ✅ 2026-08-26：注入 `AFTER_HANDLER` 故障（业务已执行、ACK 失败），重投命中去重路径，`collection_event_deduped_total` +1 且 timeline 无重复行 |
| T5-R14 | 发件箱兜底重发 | 即时发布失败后由发件箱补发，全程只发出一次触达 | 主架构 | ⬜ 无注入点：`EngineFaultInjector` 只有 `BEFORE_HANDLER`/`AFTER_HANDLER` 两个位置，发布侧失败无法在不改代码的前提下构造。补发逻辑本身有 L1/L3 覆盖，生产等价演练顺延至 T4 |
| T5-R15 | 停摆巡检 | 仅计数告警；不改库、不新增触达；正常计划不误报 | 主架构 + 运维 | ✅ 2026-08-26：构造非终态且 `trigger_time`/`timeout_time` 皆空的合成计划，`StuckPlanReaper` 检出并累加 `collection_plan_stuck_total`，未改库、未新增触达 |

### 出口

**2026-08-26 全序列执行完毕，T3o 出口达成。** 统一基线固定在当日 11:26 重启的 ca_branch 镜像
（`max-concurrency` 验毕已还原 1）。汇总：T5-S 除 S7 外全过（S7 的「逐页推进 + 完成标记」白名单模式下不适用，
顺延 T4）；T5-R 除 R14 外全过（无发布侧注入点）、R8 部分（跨重启已证，边界并发随 T4 放量验）；
T3o-O 除 O2 外全过（同 R14）；T3o-7 全过。查出并修掉 F10 / F11 / F12 三个引擎缺陷，
登记 F13 一个运维可用性缺口。收尾：故障注入置 false 并重启生效、合成 loan id 摘出白名单、
DLQ 无未决项（1 REDRIVEN + 12 TERMINATED，含 08-21 陈旧 GCP 死信）。四项改投开关按 T3o 要求保留在位——
清空它们等于让触达真实发往借款人，属 T4 准入动作。

T5-S、T5-R、T3o-O 与 T3o-7 全部通过，回滚机制可操作，演练触达均在 sandbox 或测试地址范围内。完整监控平台（Prometheus 抓取、Alertmanager 路由、Dashboard）未接通不阻塞本出口，也不阻塞 T4 与 T5 按 cap 放量；代偿期以每日人工巡检 + 手工抓取 `/actuator/prometheus` 顶替，最迟在申请移除白名单（T6 准入）前闭合。简版观测 MVP 不在可后置范围内，缺失即阻断 T4。

---

## 8. T4 固定 50 案真实白名单 Pilot

### 准入

T4 使用业务批准的**固定 50 个真实案件**，由数仓按正式契约发布、系统消费驱动；不允许以人工构造消息替代。接入可订阅 Pilot/生产案件 Topic，但仅处理批准白名单，其余一律 ACK 跳过且不得触达。

| ID | 准入项 | 退出条件 | Owner | 状态 |
|---|---|---|---|---|
| T4-1 | T3 与 T3o 已通过 | 证据包完整 | 主架构 | ⬜ |
| T4-2 | 50 案审批与范围冻结 | 脱敏清单、渠道、触达窗口、值守人与观察期已批准 | 业务 + 运维 | ⬜ |
| T4-3 | Pilot 配置双人复核 | 订阅、白名单、Redis、渠道与触达归属配置正确 | 运维 + 主架构 | ⬜ |
| T4-4 | 停止与回滚已演练 | 订阅、路由、渠道开关与归属恢复步骤可执行 | 运维 + 主架构 | ⬜ |
| T4-5 | 供应商额度与合规就绪 | 限频、模板、审计与投诉处理可用 | 编排同事 | ⬜ |
| T4-6 | 后台管理写接口收口 | 配置模板增删改与回滚、合规冻结解冻在 Pilot 期间关闭或降为只读；配置变更一律走 Nacos 并留变更记录 | 主架构 + 运维 | ⬜ |

### 覆盖矩阵

50 案按风险分层选取，而非随机抽样；每个关键路径至少有一个独立可审计样本：

| 维度 | 需覆盖的取值 |
|---|---|
| 阶段 | S0–S4 各阶段，以及 D91 停催 |
| 产品与金额 | 单期与三期产品；不同逾期金额档 |
| 触达地址 | 手机、Push token、邮箱的有效与缺失组合 |
| 案件事件 | 首次入案、每日静默刷新、部分还款、全额结清 |
| 异常路径 | 重复投递、乱序、poison、窗口外与频控拦截 |

### 观察与出口

- 连续覆盖至少三个完整日循环。
- 每日按消息类型对账：发布量、ACK/NACK/poison/去重、inbox、投影、计划与渠道受理结果。
- 逐案归档：批准编号、脱敏案件标识、输入事件标识、预期与实际触达、证据位置、责任人与结论。文档内不保存 PII 或凭证。
- **零容忍阻断**：错误或非白名单触达、重复触达、漏停催、越窗或越限、PII 泄露、无法解释的对账差异。任一发生即冻结，不得以总体成功率抵消。
- 非安全性的短暂基础设施故障，可在根因修复后从受影响批次重新开始。

---

## 9. T5 每日上限渐进切量

每个台阶使用业务批准的每日上限 `cap[n]`；本文不预设未经批准的数量或比例。

| ID | 动作 | 放行条件 | 失败处置 | 状态 |
|---|---|---|---|---|
| T5-1 | 50 案结论评审 | T4 出口达成、三方签字 | 留在 T4 或回滚 | ⬜ |
| T5-2 | 提升至 `cap[1]` | 对账无差异、无安全阻断 | 冻结新增案件 | ⬜ |
| T5-3 | 逐级提升至 `cap[n]` | 每级观察窗达标、归属切换审计通过、巡检正常 | 回退至上一已证明安全的 cap | ⬜ |
| T5-4 | 申请移除白名单 | 自动抓取、阈值告警与通知路由已完成 | 不移除白名单 | ⬜ |

每批放量前须重跑最小回归包：全仓单测、L4b 接入相关子集、接入去重跨重启用例与当日对账。

> **监控平台的闭合位置**：T5-2 / T5-3 的逐级放量不以 Prometheus / Alertmanager / Dashboard 为前置，代偿期以每日人工巡检与手工抓取指标作为放行证据（判读口径见[基础设施 §7.3](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#调度指标的人工巡检口径)）。监控平台是 T5-4（移除白名单 / T6 准入）的前置，最迟在此闭合；代偿期内巡检缺记录等同于该级放量证据不完整。

---

## 10. T6 稳态运营与复盘

前置为 T5 已批准全量切换。必须完成指标抓取、Dashboard、阈值告警与通知路由，并持续保留日对账、DLQ/PEL、渠道与合规审计、日切完成与回滚演练证据。业务 KPI 与工程质量门禁分开统计；50 案不用于得出长期 KPI 结论。

### 回滚原则（T3o–T6 通用）

回滚不得通过删除数据或重放未知消息实现。顺序为：停止新路由与调度触发 → 关闭渠道发送 → 保全 Pub/Sub、Redis、MySQL 与供应商证据 → 按已演练配置切回旧系统 → 对账与受控补偿。

---

## 11. 全局矩阵

| 阶段 | 环境 | 允许替身 | 最小证据 | Owner | 退出条件 |
|---|---|---|---|---|---|
| T0 | CI / local / 联调环境 | 基础设施可模拟 | CI、健康检查、预检、对齐记录 | 主架构 + 运维 | 环境隔离且断言依据无漂移 |
| T1 | CI / local | SPI、Repository、EventBus | JUnit / CI | 模块 owner | L0（后台管理除外）、L1、L2 全绿 |
| T2 | 受控 MySQL | 不允许内存持久化替代 | IT 记录与 SQL | 服务同事 + 主架构 | L3 事务与投影语义通过 |
| T3 | 合成源 + 隔离 Pub/Sub、DB、渠道 | L4a 仅合成事件源；L4b 不允许 mock 入口 | payload、inbox、投影、SQL、日志 | 主架构 + 服务同事 + 运维 | L4a 与 L4b 全部取证 |
| T3o | Pilot 等价拓扑 + 隔离数据 | 仅 sandbox 或测试地址 | Redis、调度、观测与回滚证据 | 全员 | 可靠性与观测 MVP 通过 |
| T4 | 真实数仓消息 + 批准 50 案 | 不允许伪造消息或名单外触达 | 逐案证据与日对账 | 业务 + 运维 + 主架构 | 三日循环且零安全阻断 |
| T5 | 生产受控切量 | 不允许测试替身 | 变更记录、对账、巡检 | 业务 + 运维 + 主架构 | 每级观察窗达标 |
| T6 | 稳态生产运营 | 不允许测试替身 | 自动监控、告警、复盘 | 业务 + 运维 + 主架构 | 可持续运行与复盘 |

---

## 附录 A：业务链路覆盖索引

| 业务链路 | T1 | T2 | T3 | T3o / T4 |
|---|---|---|---|---|
| 入案建计划 | 接入映射、L1、C1 | plan/快照往返 | L4a-1/7、L4b-1 | 日对账 / 50 案真实入案 |
| 案件投影与刷新 | 接入投影决策 | L3-8 | L4b-5/9/10/11/12/13 | 断连重启 / 每日对账 |
| 调度执行 | 调度入口、C3–C5/C7 | 扫描与步骤推进 | L4a-1/2/6/8、L4b-6 | T5-S / 真实日切 |
| 结果回收 | L1、C6 | 状态与 timeline | L4a-6、L4b-6 | `providerMsgId` 对账 |
| 异步回调 | L1、L2-CB | 回调落库 | 范围外 | 真实 AI_CALL 接入后 |
| 中断与重建 | 生命周期用例 | 取消与唯一约束 | L4a-3/4/5、L4b-2/3/4/8 | 真实停催与还款审计 |
| 可靠性与恢复 | 发件箱、停摆巡检 | L3-6/L3-7 | L4b-7/14 | T5-R 全组 |

## 附录 B：外部测试资产索引

- 测试命令与脚本：[`scripts/README.md`](../../scripts/README.md)
- L4b 环境与操作：[L4b 环境交接清单](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md)
- 触达内容验收判据（T3 / T4 通用）：[触达内容验收清单](./MOCASA催收系统升级_Phase1_触达内容验收清单.md)
- Pilot 与切量操作：[T5 Pilot 准备与演练手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md)
- 渠道测试细节：[渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md)
- 快照契约：[ContextSnapshot 契约对齐](../contracts/README_ContextSnapshot契约对齐.md)
- 执行契约：[引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md)

## 附录 C：缺口登记

> 本表解释状态列 ⬜ 的具体成因。三类缺口的闭合责任与排期方式不同：
> **缺测试**由本轮测试工作直接闭合；**缺实现**必须先排开发，测试只能暴露不能补齐；**缺环境**依赖运维或外部交付。
> 缺口闭合后从本表移除，并在对应用例回填状态。

| 缺口 | 类型 | 闭合方式 | 卡住 | Owner |
|---|---|---|---|---|
| `repaymentEvent` 携带 `stage` 但接入侧直接把它写入投影，与[引擎 §4.6](../MOCASA催收系统升级_Phase1_核心引擎规格.md#46-部分还款余额更新)「余额更新不改写 stage」相悖；阶段仍应由日切独占产出 | 缺实现 | 定契约后改映射（数仓侧删字段或接入侧忽略） | T1 · L0 ingestion | 主架构 |
| ~~L4a-3b 与 L4b-9…14 无脚本覆盖~~ | ~~缺测试~~ | **已闭合（2026-08-21 晚）**：L4a-3b 补 `/mock/balance-updated` + 快照/计划/步骤三项「不变」断言；L4b-9…14 补脚本，L4b-11 另加投影后注入点 | — | 主架构 |
| **`ai_collection_db` 上存在外来应用实例，与我们抢同一批步骤（2026-08-21 16:23 诱饵实验确证）**：`TriggerScanner` 的到期扫描**没有任何案件/租户过滤**，任何指向本库的实例都会捞走全库到期步骤。取证方法：停掉本机应用（`lsof -i:8888` 空、`ps` 无任何 java 进程），插入一条 5 分钟前即已到期的合成步骤（case 99009901），2 分钟内该行被改成 `EXECUTING`、`trigger_time` 被清空、`executed_at`/`updated_at` 落 **UTC**（08:23:40），计划被推到 `STEP_EXECUTING`。近两小时内步骤表有 10 行由 UTC 会话写入、22 行由我们（PHT）写入；外来实例还写了 1 行 timeline，**说明它会实际派发触达**。<br>影响：①L4a 上一轮 4 行 `CONTRADICT`（step 1325/1327/1328/1329）全部出自它，我此前把它们归因为 `markExecuting` 缺谓词是**误判**；②我诊断的「同一行两个时间列差 8 小时」其实是**两个应用写同一行**，不是单条语句的时区问题；③本库上任何依赖扫描器的结论（L4a 收敛性、频控计数、Reaper 噪音）都不可信；④共享库 + 无过滤的扫描器在 Pilot 是**真实触达风险** | 缺环境（阻断级） | **已做单边隔离（2026-08-21，不依赖对方停机）**：`collection.scan.case-id-whitelist` 下推到 `selectDueSteps` / `selectTimeoutSteps` 的 `AND p.case_id IN (...)`，`ScanIsolationGuard` 在 `local`/`test` profile 下拒绝空名单启动（同源于 `IngestionIsolationGuard`，但堵的是另一个入口——到期扫描直接读库，不经过接入白名单）；`restart-and-l4a.sh` 固定下发 L4a 的 12 个案件号。<br>**归属排查已按决议放弃（2026-08-21）**：不再去找那台机器的归属与 job。测试侧改走本机 MySQL 绕开（见 §5「执行约束」），
本条从「阻断 T3」降级为「**Pilot 前必须处理的遗留风险**」——扫描 SQL 无租户维度这件事本身没变，
Pilot 一旦有第二个实例指向同一个库，双方就会互抢步骤并对彼此的案件发起真实触达。
可选处置：Pilot 库独占、或把 `case-id-whitelist` 之外再加一层租户/实例维度。

**隔离是单向的，且已实测不足以跑 L4a（2026-08-21 16:41）**：过滤生效（SQL 已带 `AND p.case_id IN (12 个)`、闸门日志确认），但 L4a-1 直接卡死——plan 825 于 16:41:46 建好三步，step 1374 在 **7 秒后（16:41:53）被外来实例抢走**（`executed_at=08:41:53` 为 UTC，同行 `created_at=16:41:46` 为 PHT），`trigger_time` 被清空、`timeout_time` 未写、无 timeline、无派发。我方日志全程 `selectDueSteps <== Total: 0`、零次 `markExecuting`、零次 `STEP_DUE`：**我们一次都没抢到过自己的步骤**，100 秒后 timeline 仍为 0，该轮作废。<br>对方每次都赢是因为它的扫描间隔比我们短或抢占更早，而它拿到步骤后不派发（很可能没有对应案件配置），于是步骤变成两个扫描都摸不到的永久悬挂行。**结论：在本库上 L4a 不可能跑通**，与我们自己的实现无关。<br>**残留清理（2026-08-26 09:30 PHT）**：plan 825 此后一直挂着，`StuckPlanReaper` 每 30 分钟报一次「非终态且到期/超时扫描均不可达，需人工介入」，会污染 T3o-O 的可观测取证。已按 `MANUAL_CLEANUP`（该枚举正是为「人工清理测试计划」预留、引擎不主动写入）取消：plan 825 转 `PLAN_CANCELLED`、`active_stage_key` 归 NULL，三个步骤（1374 SMS / 1375 PUSH / 1376 EMAIL）转 `SKIPPED` 而非 `FAILED`——`FAILED` 在引擎里可重试，对一个从未派发过的步骤会变成一次真实触达。时间列按 PHT 显式写入，不用 `NOW()`。该用例是合成的（userId 94999，姓名 `l4a three channel`，手机/邮箱/Push token 全为团队自持地址），清理无客户影响。<br>彻底解决仍需查清实例归属（其代码早于 `ServiceClock`，仍用 `NOW()`；出口 IP 与我们同为 213.155.143.66）并停掉，或换独立库 | T3 全组前置 | 主架构 + 服务同事 |
| **L4a 收口断言误报**：`assert_step_convergence` 在单点采样上判定，而步骤在 `markExecuting`（清空 `trigger_time`）与终态写入之间**合法地**处于 `EXECUTING` 且 `trigger_time`/`timeout_time` 皆空。2026-08-21 实测 step 1330 被判 `UNREACHABLE`，断言后 0.3 秒即收敛为 `FAILED` | 缺测试（**已修**） | 改为静默后判定：连续两次采样（间隔 20s，上限 120s）完全一致才评估，未达静默直接判 FAIL 而非放过。静默判据用「两次快照相等」而非「距上次写入 N 秒」，因为 `/plans/{id}/steps` 不暴露 `updated_at` | T3 · L4a | 主架构 |
| **步骤悬挂（`markExecuting` 无状态前置）**：SQL 只有 `WHERE id = ?`，会把已终结的步骤无条件改回 `EXECUTING`，同时 `trigger_time` 被清空、`timeout_time` 从未写过 → 到期扫描与超时扫描都摸不到该行。生产 Pub/Sub 为 at-least-once，重复或并发的 `STEP_DUE` 必然触发；`PlanLifecycleManager` 已有的终态检查是「先读后写」不具原子性，只挡串行重复。计划已终态时留下孤儿行 + Reaper 噪音，计划未终态时该计划真正停摆。2026-08-21 干净一轮官方 25 项全绿、库里仍残留 3 行（step 1143/1164/1165：`result`/`completed_at` 已写、`status` 仍为 `EXECUTING`）| 缺实现（**已实施，待 L4a 复跑确认**） | 已改：`markExecuting` 加 `AND status NOT IN ('COMPLETED','SKIPPED','FAILED')`；`ContactPlanRepository.markStepExecuting` 签名 `void`→`boolean`（**契约变更，需知会服务同事**：该谓词是承重的，旧库映射改写时丢掉它会让仓储无条件返回「抢到了」，保护静默消失且无编译错误）；`prepareStepDue` 改为**先抢步骤再动计划**（抢占失败时计划状态一律不动，否则会把已推进的计划按回 `STEP_EXECUTING`）；`StepExecutionOrchestrator` 调用渠道前的抢占失败直接放弃执行，避免重复触达。<br>未采纳「claim 时一并写 `timeout_time`」：超时收敛走 `recordTerminal(EXECUTING→FAILED)`，而 `FAILED` 在引擎里可重试，会让「已投递成功但状态回写丢失」的步骤真的再发一次——方向与目标相反。检测手段保留 `StuckPlanReaper`。<br>**2026-08-26 复议，维持不采纳。** 该修法因 plan 825 悬挂被重新提出，重新评估后结论未变：补 `timeout_time` 只能让超时扫描「捞得到」，捞到之后的收敛动作仍是 `FAILED`，等于用一次重复触达换一次悬挂消除，在催收场景交换方向是反的。若要走这条路，前置条件是先能可靠区分「已派发」与「从未派发」并让后者收敛到不可重试的终态，该前置本身不成立（派发成功而状态回写丢失时，库里看到的就是「从未派发」）。<br>另外 plan 825 的成因已查明**不在状态机**：是共享库上的外来实例在 7 秒内抢走 step 1374 后不派发（见本表上一条）。故真根因归口到「Pilot 库独占」，与 T4 前的凭证轮换同批处理；在此之前悬挂靠 `StuckPlanReaper` 检测 + 人工按 `MANUAL_CLEANUP` 处置。<br>测试：L1 三条（终态并发抢占全失败且不清 `trigger_time`、串行重投不复活不二次触达、并发重投只一次触达）、L3-7b 真库谓词守护（三种终态拒绝 + 两种非终态必须放行）、L4a 收口断言 + `L4A_SKIP_RESET=1` 取证开关 | T3 · 修复前基线已复现（2026-08-21 15:32，5 行不收敛，其中 2 行 `result=COMPLIANCE_BLOCKED` 且 `completed_at` 已写而 `status=EXECUTING`） | 主架构 + 服务同事 |
| **时区口径不一致**：MySQL 服务器 `system_time_zone=UTC`（`NOW()` 返回 UTC），而 `ContextAssembler` 用 `Asia/Manila` 计算当日频控边界、`StuckPlanReaper` 用 JVM 默认时区计算停摆宽限。`t_contact_timeline.created_at` 与 `t_contact_plan.updated_at` 均由 `NOW()` 写入，与应用侧传入的比较值相差 8 小时：①当日触达计数漏掉 PHT 00:00–08:00 的记录 → 频控可能超发（与 JVM 时区无关，恒定存在）；②停摆宽限窗口恒被满足 → Reaper 无宽限期。`selectDueSteps` / `selectTimeoutSteps` / 发件箱租约不受影响（比较双方均为应用写入） | 缺实现 | **基础设施层两条修法均已试过、均不足**：①Hikari `connection-init-sql` 只覆盖到部分连接；②JDBC URL `connectionTimeZone=%2B08:00&forceConnectionTimeZoneToSession=true`（`start-local.sh` 注入，**必须用数值偏移**——该实例未加载时区表，下发命名时区会以 `Unknown or incorrect time zone: 'Asia/Shanghai'` 让每条连接都建不起来）加上后绝大多数列已落 Manila，但 `t_contact_plan_step.executed_at` 仍会落 UTC，同一行 `dispatched_at`/`updated_at` 却是 Manila。**故采用第三条并已落地（2026-08-21）**：plan / step / timeline 三个 mapper 的时间列改由 `ServiceClock.now()`（PHT，截到秒）传参，`updated_at` 在每条 UPDATE 显式赋值以压制 `ON UPDATE CURRENT_TIMESTAMP`；JDBC `+08:00` 与 `connection-init-sql` 保留，只服务于仍用 `NOW()` 的审计表（DLQ / 收件箱 / 发件箱 / 回调审计）。启动自检 `DatabaseClockValidator` 已加：偏差超 `collection.db.clock-drift-threshold-seconds`（默认 120s）时 pilot 拒启、其余 profile 告警（4 例单测）。L3 IT 参照系已由数据库时钟改为应用时钟。**剩余**：带 `L3_IT_DB_URL` 复跑 `ContactPlanMapperIT` / `StepScheduleAuditMapperIT`，确认 `executed_at` 与同行其余列同为 PHT | T2 · L3-7 首跑暴露；影响 T3o 频控与 T5-R 停摆巡检取证 | 主架构 + 服务同事 |
| 共用 Nacos `intelligent-collection-local.yml` 需临时置空三个渠道密钥以保证零真实触达；该 Data ID 与编排同事共用 | 缺环境 | 用 `scripts/dev/merge-nacos-config.py` 深合并发布，测试窗口结束后恢复密钥；发布前须与编排同事同步 | T3/L4a、L4b | 主架构 + 编排同事 |
| **AI_CALL 回调回流未打通（出站已闭合，入站不可用）**：出站侧原 `DefaultStepResolver:107` 经 `voiceCallbackUrl` 下发 `{base}/lth/voice`，而全仓唯一入口是 `POST /webhook/channel-callback`（`/lth/voice`、`/sendgrid` 从无 Controller）→ 已按「只对接 Facade」裁定改名 `callbackUrl()` 并指向 `/channel-callback`，另按 [Facade 客户接入手册](../channel/FACADE客户接入手册.md) 确认回调是**账户级**配置（对方控制台登记、所有批次共用），随单下发的 `callback_url` 已回退，`metadata.callbackUrl` 对 Facade 仅作信息用。**入站侧仍不可用**：现有入口靠 query 参数 `planId`/`stepId`/`result` 定位步骤、验我方 `X-Callback-Signature`；Facade 打来的是单一账户级 URL + `session.completed` / `batch.completed` JSON + 对方自定义签名头，两者结构对不上，账户级 URL 也无法按批带步骤参数 | 缺实现 | 需新增 Facade 专用入站端点：按对方签名方案验签、按 `client_metadata`(case/plan/step) 或 `external_case_id` 反查步骤、按 `session_id` 幂等、把 `was_answered`/`was_ai_connected`/`line_outcome.reason` 映射为触达结果（信箱与筛选**不计**真人接通）。闭合前 AI_CALL 只能靠超时收敛，不得进入 T4；**归属需与编排同事确定**，我方须同步提供账户级回调 URL 与验签口径给 Facade 登记 | L2-CB 第 3/4 级 · T0-6 A1–A3 · **T4 阻断** | 编排同事 + 主架构 |
| 简版观测的可查询证据未齐（**2026-08-21 完成盘点**）：`collection-ingestion` 全模块零 Micrometer 埋点（ACK/NACK/poison 只有日志，`eventId` 去重命中静默 return）、接入路径不写 MDC、inbox 无 gauge 与只读 HTTP、inbox 无失败原因字段、外部 `event_id` 与内部事件 UUID 无同层关联、日切完成标记只在 Redis key、渠道失败无专用 counter、Resolver 策略跳过不计入 `step.skipped`、DLQ 只有 redrive 写接口无只读明细。引擎与 Redis 总线、调度链的 §7.3 指标已齐 | 缺实现 | 按 [T0-7 观测基线](#t0-7-观测基线2026-08-21-盘点完成) 逐项排开发；接入侧计数与 MDC、inbox 查询入口优先（阻断 T3o-O1/O2） | T3o-O1 / O2 / O3 / O4 | 主架构 |
| ~~**L4b 发布脚本用占位指纹**~~（**2026-08-21 已闭合**）：原三处写 `0000…000${i}` 且 `eventId` 非 UUID，会让投影层「指纹相等即跳过」的判断失真（同内容异串 = 假刷新，异内容同串 = 漏刷新） | 缺实现（测试资产） | 已改：`publish-test-messages.sh` 与 `l4b-official-test.sh` 各加 `case_version()`（按契约六字段 `loan_id`+`maxDpd`+`overdueAmount`+`upcomingAmount`+`nextDueDate`+`isFullCleared` 真算 MD5；`caseVersion` **本身就是指纹字段**）与 `new_event_id()`（UUID）；两份样例 JSON 同步真值，`caseEvent.sample.json` 的 `60ecd3bd…` 可复算验证。消费侧视该值为不透明串，故不要求与数仓 MySQL 的十进制渲染逐字节一致，只要求同内容同值 | T3/L4b 全组 | 主架构 |
| **`repaymentEvent` 校验宽于契约（低危）**：不校验 `eventType=REPAYMENT`、不拒绝契约禁止字段、`repayTime`/`paidAmount` 不读；`PubSubCaseConsumer:172-181` 允许无信封的平铺 body。必填校验实际有六项且查负值，冻结样例也不带禁止字段，故现网不会命中 | 缺实现 | 低优先级；若收紧只收「平铺 body」与 `eventType` 两项，禁止字段与 `repayTime`/`paidAmount` 不必动。**注意不要收紧** `occurredAt` 格式与 `caseId` 类型：两份冻结样例分别用本地格式+数字与 ISO+字符串，现有兼容是必要的 | T3/L4b · T0-6 A8–A9 | 主架构 + 数仓 |
| ~~**`repaymentEvent.stage` 改写投影（高危）**~~（**2026-08-21 已闭合**）：冻结样例 `repaymentEvent.sample.json:14` 带 `"stage": "S4"`，原 `CaseProjectionAssembler:54` 解析后 `mergeRepayment` 直接 `setStage`、`updateRepaymentDelta` SQL 也写 `stage` 列，与[引擎 §4.6](../MOCASA催收系统升级_Phase1_核心引擎规格.md#46-部分还款余额更新)「余额更新不改写 stage」相悖，等于让还款分走日切的唯一升档权 | 缺实现 | 已选**接入侧忽略**（不等上游改契约，且该字段对日切全量快照仍有用），做成三层结构性保证：SQL 去掉 `stage` 列 → 合并逻辑不复制（真库/内存两套同步）→ `CaseProjectionFields` 删字段、还款路径**不解析**。第三层是关键：继续解析会让一个非法 stage 取值 poison 掉真实还款，而丢还款意味着已还清的客户继续被催。覆盖 1 例 L1（非法 stage 不影响还款解析）+ 1 例仓储单测（保留基线 stage）+ 1 例 L3 IT（真库 stage 不变、余额已变） | T3/L4b-2 · T4 | 主架构 |
| ~~**L4b-10 与数仓契约冲突**~~（**2026-08-21 已闭合**）：原 `upsert()` 只判指纹是否相等，不同指纹的旧快照会覆盖新投影 | 文档冲突 | 已选「补防护、保用例」：`upsert()` 改用 `selectProjectionForUpdate`，只拒绝严格更早的 `updatedAt`（由 payload `occurredAt` 赋值，缺失即 poison），`InMemoryCaseProjectionRepository` 同口径，3 例单测。裁定依据是该故障与上游是否乱序无关——Pub/Sub 重投即可让旧快照后到，而 `eventId` 去重对「从未成功处理过」的旧消息无效 | T3/L4b-10 | 主架构 |
| **`collection.notification.owner` 无实现**：基础设施附录 A.4 登记了 `LEGACY`/`PARALLEL`/`NEW` 三态，但全仓无读取点，配置该键不产生任何行为差异 | 缺实现 | 迁移双写启用前补实现，或明确改由部署侧（旧系统停发）承担切换；已在附录 A.4 标注 | T5 切换 · D-3~D0 触达归属 | 主架构 + 业务 |
| **上游未向契约 topic 发布**：`-v1-l4b-sub` 建于 2026-08-20 19:15，跨过 03:00 PHT 日切窗口后（8-21 10:10）peek 仍为 0 条；契约 §1.2/§2 约定每日快照 03:00 PHT 前发完、`repaymentEvent` 每 15 分钟。扇出语义保证订阅创建后的消息必有副本，故可判定当前无发布。`intelligent-collection-cases-v1` / `collection-cases` / `collection-cases-test1` 三个 topic 的只读观测订阅覆盖一个完整 15 分钟窗口（8-21 10:16–10:34）后仍全为 0 条 | 缺环境 | 向数仓确认实际发布 topic 与节奏；探针 `scripts/test/observe-upstream-topics.py` 可复跑 | T3/L4b **真实源**（合成源不受影响） | 数仓 + 主架构 |
| **真实源候选案件缺失**：`ai_collection_db.t_collection` 仅 694 行，其中 688 行 `overdue_days<=0` 且 `total_not_paid=0`（2026-06-08 一次性灌入、快照已过期两个月），实际可催案件只有人工种的 `99000000..99000005` 与 `92002`。业务侧提供的 `1025` / `1822` 在 `loan_id`/`user_id`/`loan_no`/`colleciton_no` 四列均查无此号，说明它们来自另一套库 | 缺环境 | 由业务/数仓给出「数仓确实会发布」的案件号及其所在库；或退一步用真实数据形态构造 caseEvent 发到自建 topic（覆盖字段映射与脏值，但不覆盖上游投递） | T3/L4b **真实源** | 业务 + 数仓 + 主架构 |
| 共用 Nacos Data ID 的阶段切换会波及编排同事：`ChannelProperties` 带 `@RefreshScope`，置空密钥后他**正在运行**的进程数秒内跟着回落 Mock | 缺环境 | 已可自动切换（写权限账号经环境变量传入 `merge-nacos-config.py`）；每次切换前后知会编排同事，测试窗口结束按 `deploy/nacos/backup-*.yml` 恢复。长期解法是给联调单独开 Data ID | T3/L4a 与 L4b 阶段切换 | 主架构 + 编排同事 |
| 联调 Redis 内网不可达（开发机连 6379 超时），且无 redis-cli / docker。**只卡 T3o/T5-R**：L3/L4a/L4b 全走内存总线与内存去重 | 缺环境 | 开通部署机/开发机到 Redis 的访问路径；**不采用本地 Redis 替代**（2026-08-21 决定），T5-R 全组证据必须来自该独立实例 | T3o-5 · T5-R 全组 | 运维 |
| ~~Cloud Scheduler 四条 Job 未创建~~（**2026-08-21 已闭合 O1/O2/O3/O6**）：盘点发现 topic `intelligent-collection-schedule-v1` 与订阅 `intelligent-collection-schedule-v1-sub` 本就存在，但订阅参数不合规（`ackDeadlineSeconds=10`、保留 7 天，O5 要求 60s/10m），而 `asia-northeast1` 零条催收 Job（当时误判为「全 location 零条」，实际 `asia-southeast1` 有运维早前建的三条畸形 Job，见下条）；此前「已确认自有身份具备 `cloudscheduler.jobs.create`」的记录不成立——`keliu@indiacashgo.com` 当时在本项目上连 `pubsub.topics.list` / `cloudscheduler.jobs.list` 都 `IAM_PERMISSION_DENIED`（**运维已于 2026-08-21 补开 Scheduler 权限；Pub/Sub 只读仍缺**） | 缺环境 → **已交付** | 已用新增的幂等脚本 `scripts/test/provision-scheduler.py` 完成：订阅 PATCH 为 60s/600s；在 **`asia-northeast1`**（与项目既有 `telemarket_out_reach_push` 同区；**Job 可分布在多个 location**，排查发布者须逐 location 扫）建 4 条 `ENABLED` Job，cron / `Asia/Manila` / `job` attribute 逐字对齐 [T5 手册模板](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md#调度配置模板可复制不含真值)。**端到端已验**：两条每分钟 Job 的 `lastAttemptTime` 正常推进，从调度订阅 pull 到 4 条 tick 且 `attributes` 为 `job=planStepDue` / `job=callbackTimeout`（取证 pull 不 ack；此后不得再挂第二个 subscriber，竞争消费会随机分走 tick）。用生产名而非 `-pilot`：重复创建会 `ALREADY_EXISTS` 显性失败，而 `-pilot` 与生产并存是静默双发。同批还修掉两条订阅的同类隐患：**案件接入订阅 `intelligent-collection-cases-v1-sub` 的 ack 由 10s 纠为 60s**（应用侧 `collection.ingestion.ack-deadline-seconds=60`，接入规格 §2.1 要求一致；10s 会让处理未完成即重投，幂等能保正确但把重复消费变成常态）；**两条订阅的 `expirationPolicy.ttl=31 天` 改为永不过期**——这是静默数据丢失的雷，Pilot 应用尚未部署即无 pull 活动，订阅被 GCP 自动删除后数仓继续发的消息会无处投递且不报错。**剩余仅 O7/O8 告警**与应用侧拉取凭证（`.env.pilot` 的 `GOOGLE_APPLICATION_CREDENTIALS` 在部署位不存在、`GCP_PUBSUB_PROJECT` 为空） | T3o-3 · T5-S1/S8 | 主架构（已建）+ 运维（O7/O8 与凭证） |
| ~~**调度 Topic 上的畸形 tick 与重复发布者**~~（**2026-08-21 已闭合**）：每分钟 `:01` 前后有两条消息落入 `intelligent-collection-schedule-v1`，**attributes 为空**、body 是《数仓 PubSub 交付契约》旧版示意块的原文（`body: scheduled-tick\nattributes:\n  job: callbackTimeout`）——运维早前按我们的需求配置时，把文档里的「示意」当消息体逐字发出。应用只按 attribute `job` 路由，这些 tick 会被判 `UNKNOWN_JOB`、记 WARN 后 ack 丢弃，**一次扫描都不会触发**，而 Job 侧 `status: {}` 显示成功——两侧都「正常」、链路静默停摆。**定位曾走错一轮，两个坑叠加**：①误信「Scheduler location 在项目内唯一」，只扫 `asia-northeast1`；②补扫时又踩了分页——Cloud Scheduler 的 `jobs.list` 对 `asia-southeast1` 返回的**首页 0 条却带 `nextPageToken`**，不翻页就会得出「该 location 没有 Job」的错误结论。实际它们在 **`asia-southeast1`**（`intelligent-collection-schedule-{planStepDue,callbackTimeout,dailyRoll}`，与数仓 push 系列同区）。`provision-scheduler.py --verify` 已加「逐 location + 翻页」的发布者全扫，并对无权限的 location 显式报「结论不完整」而非静默跳过。第二处更隐蔽的问题：其 `dailyRoll` 用 `*/5 3-5 * * *` 即 **03:00 起跑**，早于规范的 03:35 缓冲——日切空页即写当日完成标记，若在数仓 03:00 批次消费完前扫完旧数据并标记完成，当天阶段升档与 D+91 停催**整天不发生且指标看起来正常**，故该条即便补上 attribute 也不能直接启用 | 文档冲突 + 缺环境 | ①改掉文档根因：契约里的示意块换成 `gcloud` 命令形式并附事故记录；②运维已给 `keliu` 开通 Scheduler 权限，**归属改为主架构持有**，正式入口为 `asia-northeast1` 四条 Job；③`asia-southeast1` 三条已 `PAUSED`（保留配置备查），待运维确认无其他用途、且无 IaC 会重建后删除，见[变更通知与确认请求](../../scripts/test/provision-scheduler.py)；④调度 Topic 现为单一发布者，T3o 首次消费**不应**再看到 `skipped{reason=UNKNOWN_JOB}` 增长——若仍增长说明还有第三个发布者 | T3o-3 · T5-S8 | 主架构（已处置）+ 运维（确认删除 / Pub/Sub 只读权限） |
| **仓库根 `credentials.json` 是同事个人 OAuth ADC（长期 refresh token）**：`hanxiaoyan@indiacashgo.com`，`provision-l4-pubsub.py` / `provision-scheduler.py` / `observe-upstream-topics.py` 默认读它。**本轮调度资源（订阅 PATCH + 4 条 Job）即以该身份创建**，因此 GCP 审计记在他名下、与实际执行人不符；且他一旦轮换令牌，上述脚本同时失效 | 缺环境（凭证卫生） | 换成 Pilot 专用服务账号密钥并只授所需角色（`roles/pubsub.editor` 或更细、`roles/cloudscheduler.admin`），落位 `deploy/secrets/` 且不入仓；换好后用 `provision-scheduler.py --verify` 复验四条 Job 归属与参数未变。与[T5 手册 §7 凭证一次性轮换](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md)合并处理，最迟 T4 前 | T4 阻断 | 主架构 + 运维 |
| 正式案件订阅 `-v1-sub` 无死信策略、ack-deadline 10s；调度订阅保留期 7d（O5 要求 10m） | 缺环境 | Pilot 前按规范修订正式订阅参数（本轮自建测试订阅已合规，测试不依赖正式订阅） | T3o-3 · Pilot 准入 | 运维 |
| Nacos 无 `intelligent-collection-pilot.yml`，`.env.pilot` 的 Nacos 地址、GCP 项目、Pilot 名单与回调密钥仍为空 | 缺环境 | 运维交付 | T3o-2 | 运维 + 主架构 |
| **Pilot 部署位与凭证挂载未确认**：`.env.pilot` 落位的部署机、`GOOGLE_APPLICATION_CREDENTIALS` 挂载路径、`SPRING_PROFILES_ACTIVE=pilot` 的拉起方式，以及该部署网络到 Redis / MySQL / 两条订阅 / 渠道的连通与鉴权均无验证记录。`PilotReadinessValidator` 会在配置不全时拒绝启动，因此这是 T3o 的第一道硬门槛 | 缺环境 | 运维交付部署机与 Secret 注入，主架构按 [T5 手册 §5.2](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md#52-启动预检) 做启动预检并留存脱敏快照 | T3o-2 · T3o-5 | 运维 + 主架构 |
| **Pilot MySQL 访问未确认**：Pilot 使用的库实例、应用账号权限与网络授权尚未确认（L3 用的是联调账号），旧库只读账号范围也未按 Pilot 复核 | 缺环境 | 服务同事与运维确认库/账号/网络，并与时区口径修复一并验收 | T3o-2 | 服务同事 + 运维 |
| **渠道 sandbox 与批准测试地址未确认**：sandbox 地址、供应商额度、测试号码/邮箱/Push token、限频与脱敏配置无交付证据。T3o 要求 `channel.fallback-to-mock=false`，缺配置会直接导致 Pilot 启动校验失败或零触达 | 缺环境 | 编排同事与运维交付地址、额度与 Secret 引用，并按 [T5 手册 §6.1](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md#61-渠道生产连通验证清单) 留存受控投递与回调记录 | T3o-4 | 编排同事 + 运维 |
| HTTPS 回调域名、证书与 HMAC secret | 缺环境 | 运维交付 | L2-CB 第 3/4 级 | 运维 |
