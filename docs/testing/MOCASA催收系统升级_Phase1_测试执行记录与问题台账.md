# MOCASA 催收系统升级 — Phase 1 测试执行记录与问题台账

> **版本**：Phase 1 · 仅覆盖菲律宾市场
> **日期**：2026-08-26
> **阶段灯**：[测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md)（本文不维护灯）
> **操作**：[Pilot 操作手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md)（含 T3o 取证命令）· [L4b 交接](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) · [触达验收](./MOCASA催收系统升级_Phase1_触达内容验收清单.md)
>
> **本文职责**：未闭合项、T4 核对、缺陷/环境、已闭合阶段的裁定与证据。
> 收工先改 §1；与 SSOT 灯不一致时以 SSOT 为准，以本文证据回填 SSOT。

---

## 0. 读法与基线

| 要什么 | 看哪 |
|---|---|
| 过到哪、出入口 | [测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) |
| 还开着什么 | [§1](#1-未闭合项) |
| T4 怎么核 | [§3](#3-进行中t4) |
| 缺陷 / 环境 | [§2](#2-缺陷与环境) |
| 某条怎么测、怎么裁定 | [§4](#4-已闭合裁定与证据) |
| 0825 真拨通次（非出口） | [附录 A](#附录-a历史会话2026-08-25-ai_call-真拨) |

**当前基线**：ca_branch 镜像，2026-08-26 11:26 重启，`max-concurrency=1`。
改投保留：SMS/AI_CALL `+639451374358`，PUSH 测试设备，EMAIL `wzynju@126.com`。
清空改投 = 开始对借款人触达，属 T4 准入动作。

T3o 收尾（2026-08-26）：故障注入 `enabled=false`；合成 loan id `99000915` 已摘；
DLQ 无 PENDING（1 REDRIVEN + 12 TERMINATED，含 08-21 陈旧 GCP 死信）；
S0 误注入的 8 个 AI_CALL 已回退；S1–S4 共 150 槽在位。

---

## 1. 未闭合项

只列还开着的。已修缺陷见 §2.1。

| 项 | 挡谁 | 下一步 |
|---|---|---|
| T4-2～T4-6 准入 | T4 正式开门 / 关改投 | 按 §3 逐条签字 |
| Pilot 库独占 | T4 对账可信 | 共享库外来实例会抢步骤（plan 825）；与凭证轮换同批 |
| 凭证轮换 | T4 手册仍列为阻断 | `credentials.json` 现为同事个人 OAuth ADC（`hanxiaoyan`）；联调期明文曾出现在协作记录。换成 Pilot 服务账号，落 `deploy/secrets/` 不入仓 |
| T5-S7 日切游标 | 不挡 T4 | 白名单模式无游标；勿为勾选用例开 `allow-full-scan` |
| T5-R14 / T3o-O2 发布失败补发 | 不挡 T4 | 无发布侧注入点，先改代码再打 |
| T5-R8 后半 | 不挡 T4 | 跨重启已证；边界并发与断连 fail-close 随放量 |
| T3 真实数仓矩阵 | 不挡 T4 | 用 50 案三日循环覆盖，不回 T3 重跑 |
| F13 DLQ 缺显式终止 | 不挡 T4 | 给 `/ops/dlq` 加带理由的 `terminate` |
| Facade 真接通报文 | T5 放量前 | 测试号三次 SIP 406；我方映射已证 |
| A8 / A9 `repaymentEvent` 校验宽于契约 | 低危 | 不校验 `eventType=REPAYMENT`、允许无信封平铺 body。冻结样例不命中。收紧只动这两项，勿收紧 `occurredAt` / `caseId` 兼容 |
| `collection.notification.owner` 无实现 | T5 切换 | 附录 A.4 登记了 LEGACY/PARALLEL/NEW，全仓无读取点 |
| Redis `maxmemory` 仍为 0 | T4 配置复核 | 共用实例无上限，邻居写爆由 OS OOM 决定杀谁 |
| L4a 静默时段 Guard | 不挡 | 最终一轮 `L4A_ALLOW_QUIET_HOURS=1`，该项无证据；窗口内复跑即可 |
| 消费组消费者名只增不减 | 低危 | 名取自容器 HOSTNAME。不宜 `@PreDestroy` 自删（会连 PEL 一起丢）。可在 `reclaimPending` 剪「非本机 + pending=0 + idle 超阈值」 |
| `asia-southeast1` 三条畸形 Job | 运维确认 | 已 `PAUSED`；确认无 IaC 重建后删除 |

---

## 2. 缺陷与环境

### 2.1 缺陷

| ID | 摘要 | 状态 | 要点 |
|---|---|---|---|
| F1 | `sms-test-mode=true` 不抑制投递 | 已修 2026-08-24 | `/v1/sms/testSend` 走真实运营商；pilot 开此开关须同时配扫描白名单且不得 `allow-full-scan` |
| F2 | 瞬态失败吃掉合规配额 | 已修 2026-08-24 | 先占后发保留（防并发超发）。`retryCount>0` 跳过频控；确认未发出（`retryable=true`）才 `release`。契约：`GuardVerdict.quotaReservation`、`ComplianceCounterService.release` |
| F3 | Nacos 上通知/SendGrid 密钥为空串 | 已记 | 真值只在未入库 `nacos-publish.local.yml`。pilot 走 `.env.pilot` 不受影响 |
| F4 | 每次重启往 DLQ 灌一条假失败 | 已修 2026-08-24 | `initConsumerGroup` 曾写 `{"bootstrap":"1"}`；`createGroup` 已带 `mkStream`。存量已清，证据在生产机 `/opt/app/logs/evidence/dlq-before-cleanup-20260824.txt` |
| F5 | 非法 JSON 写不进 `JSON NOT NULL` 的 DLQ 列 | 已修 2026-08-24 | `dlqPayload`：能解析则原样，不能则 `{"raw":"..."}`。否则落库失败→不 ACK→死循环 |
| F6 | 消费组消失后总线空转，health 仍 UP | 已修 2026-08-25 | `NOGROUP` 就地重建并退避打日志。新增 `RedisEventBusHealthIndicator`。生产验证：`DEL` 流后 8s 自愈 |
| F7 | PEL 深度指标恒被夹在 50 | 已修 | 改用 `XPENDING` 汇总。认领仍按 `pel-batch-size`（约 100 条/分钟） |
| F8 | 管理面曾公网可达且登录不校验口令 | 已修 2026-08-25 | 绑 `127.0.0.1` + BCrypt（角色由配置决定；用户名不存在也跑一次哈希；成功换 session id；不加失败锁定）。BCrypt `$2y$` 在 `pilot.env` 必须单引号，否则 shell 展开成空。nginx 只放通 `/webhook/`，其余 403 |
| F9 | 同事覆盖部署致崩溃循环；nginx 整站反代绕过绑回环 | 已处置 2026-08-25 | 半合并 jar 要求 `case-service=real` 与 yml `ai` 冲突。已统一 ca_branch、摘 `collection-admin-aicall`、nginx 收口。暴露窗口 access log 无 `/auth/login`，未见利用 |
| F10 | `putMdc` 用 `getLong` 读毒丸 `planId`，打死消费线程 | 已修 2026-08-26 | 改 `getString`；`submit` 补 catch。`t5r-poison-002` 验证死线程 0，5 次后正常进 DLQ |
| F11 | 并发 `CASE_INGESTED` 撞 `uk_active_stage_key` | 已修 2026-08-26 | 在 dispatcher（事务外）按**约束名**吞重复插入。三个建计划事件均覆盖。数据本就自愈，修的是 ERROR 噪声 |
| F12 | 乱序推进取到已终结步骤并复活 | 已修 2026-08-26 | `selectByPlanAndOrder` 跳过终态；`updateTriggerTime`/`updateTimeoutTime` 加终态谓词。plan 829 步骤 1416 已订正回 COMPLETED/FAILED |
| F13 | `MAX_DELIVERY_EXCEEDED` 只能靠耗尽重放配额终止 | 未修，不阻断 T4 | `/ops/dlq/redrive` 只对非该原因判不可恢复。`t5r-poison-001/002` 按三段式审计直接落终态（`scripts/pilot/2026-08-26-terminate-t5r-poison-dlq.sql`）。倾向加显式 `terminate` |

**F12 生产序列**（plan 829，`/opt/app/logs/collection/collection.log`）：

```
09:55:53  step 1416 → EXECUTING，挂 60min 回调超时
10:02:12  [callback]  result FAILED          ← 正常终结
10:02:13  [advance]   → 1417 at 12:00
10:04:56  [advance]   → 1416 at 10:04:56    ← 1415 退避后落地，按 order+1 取到已终结的 1416
10:05:04  [execStep]  duplicate key=829:2:0 skipped  ← 幂等锁挡住重复外呼
```

撕裂态：`status=EXECUTING` 而 `result=FAILED` / `completed_at` 残留。`callbackTimeout` 每分钟扫到却处理不掉（计划已是 `STEP_SCHEDULED`）。订正脚本：`scripts/pilot/2026-08-26-fix-f12-revived-step-1416.sql`（按 callbackAudit id=11）。

**不采纳的修法（两议维持）**：步骤入 `EXECUTING` 时写 `timeout_time`。超时收敛是 `FAILED`（可重试），「已投递成功但状态回写丢失」会被再发一次。检测靠 `StuckPlanReaper` + 人工 `MANUAL_CLEANUP`。plan 825 成因是共享库外来实例，不在状态机。

### 2.2 环境偏差

| ID | 摘要 | 处置 |
|---|---|---|
| E1 | Redis 与邻居共用实例（`detect:*` 等） | 已切独立 **db3**（`COLLECTION_REDIS_DB=3`）。db 只隔键空间：可 `FLUSHDB`，不隔进程/AOF/重启。断连只用 `CLIENT KILL` 本应用连接；禁 `FLUSHALL`。迁 Cluster 须先回 db0 |
| E2 | 未开 AOF 时重启会丢幂等/去重/合规键 | 2026-08-25 已开 AOF，但开启前那次重启清空全库（`collection:*` 188 键全没）。Stream 当时 pending=0，投影层 `case_version` 仍能挡重入。T3o 接受共用实例；T4 复核是否要独占 |
| 共享库 | `ai_collection_db` 上外来实例抢全库到期步骤 | 扫描已加 `case-id-whitelist`；`ScanIsolationGuard` 在 local/test 拒空名单。隔离是单向的：对方扫描无过滤，仍会抢走我方步骤（plan 825，7 秒）。T4 要库独占 |
| Nacos | Pilot 不可达，健康检查误报 UP | 改验 env > yml。联调期开关走环境变量，不往共用 Data ID `intelligent-collection-local.yml` 发（会扰动编排同事） |
| 时区 | MySQL 远端 `system_time_zone=UTC`，`NOW()` 与 PHT 差 8h | plan/step/timeline 改 `ServiceClock.now()` 传参；`updated_at` 显式赋值压 `ON UPDATE CURRENT_TIMESTAMP`。`DatabaseClockValidator`：偏差超 120s 时 pilot 拒启 |

**共享库取证（2026-08-21 16:23）**：本机应用已停，插入 5 分钟前到期的合成步骤（case 99009901），2 分钟内被改成 `EXECUTING`，时间列落 **UTC**。外来实例会写 timeline，即会实际派发。近两小时 10 行 UTC / 22 行 PHT。L4a 上一轮 CONTRADICT（step 1325/1327/1328/1329）出自它，不是 `markExecuting` 缺谓词。

**plan 825（2026-08-26 09:30 清理）**：L4a 合成案（userId 94999）。step 1374 建后 7 秒被抢走、不派发，成永久悬挂。已按 `MANUAL_CLEANUP` 取消：plan → `PLAN_CANCELLED`，三步 → `SKIPPED`（不用 `FAILED`，以免可重试变成真触达）。时间列显式写 PHT。

---

## 3. 进行中：T4

数仓已向 `intelligent-collection-cases-v1` 推当前白名单（与 `.env.pilot` 的 `COLLECTION_PILOT_LOAN_IDS` / `COLLECTION_SCAN_CASE_IDS` 同一批 50 案）。
**可以做改投仍开着的观察和对账；不能宣布 T4 已开始、不能关改投。**

### T4-1 前置

T3o 已过。T3 真实数仓矩阵随三日循环覆盖，不回 T3。

### T4-2 50 案审批与范围冻结

| # | 核对 | 现状 |
|---|---|---|
| 2.1 | 业务书面确认：当前白名单 = 批准的固定 50 案，期间不增不删 | ⬜ 工程侧已按这 50 案跑，纸面批准未落 |
| 2.2 | 脱敏清单、允许渠道、触达窗口、值守人、观察期（至少三日）已批 | ⬜ |
| 2.3 | 名单外消息必须 ACK 跳过、零触达；抽一条非白名单 eventId 对账 | ⬜ |
| 2.4 | 关改投必须与本项同批签字，不得提前清空 | 改投仍开着（正确） |

### T4-3 Pilot 配置双人复核

| # | 核对 | 现状 |
|---|---|---|
| 3.1 | 接入白名单与扫描白名单条数、集合一致，无 T3o-7 合成号 | 工程已摘 `99000915`，待第二人复核 |
| 3.2 | 案件订阅 / 调度订阅独占；`allow-full-scan=false` | T3o 已证，待复核签字 |
| 3.3 | 四项改投仍在位，且双方知道「清空=真实触达」 | 启动日志仍列出四条 |
| 3.4 | Redis db3、Nacos 不可达（接受 env>yml）、故障注入 false | T3o 收尾已验 |
| 3.5 | 凭证轮换：历史明文失效，仓内无真值 | ⬜ 手册仍列为 T4 阻断 |

### T4-4 停止与回滚已演练

按手册走一遍并留时戳，不得用删 Stream / PEL / DLQ / 业务表代替。

| # | 动作 | 现状 |
|---|---|---|
| 4.1 | 暂停四条 Cloud Scheduler Job，确认无新 tick | ⬜ 原则写了，没演练留证 |
| 4.2 | 关渠道发送后，在途步骤不新增触达 | ⬜ |
| 4.3 | 保全 Pub/Sub、Redis、MySQL 证据后再谈切回 | ⬜ |
| 4.4 | 恢复 Job，确认 tick 恢复且无扫描风暴 | ⬜ |

### T4-5 供应商额度与合规

| # | 核对 | 现状 |
|---|---|---|
| 5.1 | SMS / PUSH / EMAIL / AI_CALL 额度够 50 案三日，且知悉关改投后打真实号码 | ⬜ |
| 5.2 | 模板、投诉/退订处理入口可用（SendGrid 抑制名单已接） | 代码在，运营流程 ⬜ |
| 5.3 | 限频键与窗口与生产口径一致 | T3o 用过测试上限，须还原生产值再复核 |

### T4-6 后台写接口收口

| # | 核对 | 现状 |
|---|---|---|
| 6.1 | 配置模板增删改、回滚、合规冻结解冻：Pilot 期间关闭或只读 | ⬜ 接口仍开（nginx 只暴露 `/webhook/`，后台走 SSH，不是收口） |
| 6.2 | 若任一写接口保持启用，该接口回到 T1 必测范围 | 未裁定 |
| 6.3 | 配置变更走审批记录，不在 Pilot 窗口用 UI 改模板 | ⬜ |

相关但不在 T4-2～T4-6 编号内：Pilot 库独占（§1）。未落实前，三日循环对账不能当最终证据。

---

## 4. 已闭合裁定与证据

用例灯以 SSOT 为准。下面只留出口、偏离与取证键。

### 4.1 T0

**出口（2026-08-21）**：条件性通过，放行到 T3。T0-1…T0-5、T0-7 闭合；T0-6 当时 🟡（A3 未闭合，已由 L2-CB 收口）。

偏离：T3 最终跑在**本机 MySQL 8.0.46**（远端共享库抢步骤）；T0-3 计划的 Nacos 阶段配置没发，开关改环境变量。T0-2 本地 health DOWN 属预期（内存总线，Redis 指示器 DOWN），判据用 `GET /plans/active/by-case/0` = 200。

自助开通（`scripts/test/provision-l4-pubsub.py`）：L4a topic `intelligent-collection-cases-test1`；L4b sub `intelligent-collection-cases-v1-l4b-sub`（扇出，不影响正式订阅）；死信 `intelligent-collection-cases-dlq`。`IngestionIsolationGuard`：local/test 且接入开启时，拒绝订生产订阅名或空白名单。合成消息只能发 `*-test1`。

#### T0-6 对齐（A1–A20）

| # | 裁定 |
|---|---|
| A1 | ✅ 只对接 Facade：`voiceCallbackUrl` → `callbackUrl()` → `/channel-callback` |
| A2 | ✅ 回调是账户级，不随单传 `callback_url`；`dial_policy` 只准 timezone/windows/weekdays，带 retry 等一律 422 |
| A3 | ✅ 2026-08-26 由 L2-CB 闭合：专用入口 `POST /webhook/facade-callback`，`X-Valubo-Signature`，按 metadata 或 `external_case_id` 反查 |
| A4 | 文档过期：静默/限频读 `channel.compliance.*`，不读 `engine.compliance.daily_limit` |
| A5 | 文档过期：`collection.notification.owner` 无读取点（仍开，见 §1） |
| A6 | 附录 A 漏登记一批 Pilot 键（验签、Redis 去重、fault-injection、case-service 等） |
| A7 | ✅ 发布脚本改真算 MD5 `caseVersion` + UUID `eventId`；样例 `60ecd3bd…` 可复算 |
| A8 | 低危未收紧：不校验 `eventType=REPAYMENT`、不读 `repayTime`/`paidAmount`（见 §1） |
| A9 | 低危未收紧：允许无信封平铺 body。`occurredAt`/`caseId` 双格式兼容是必要的（见 §1） |
| A10 | ✅ `upsert` 用 `FOR UPDATE`，只拒绝严格更早的 `occurredAt`（Pub/Sub 重投即可让旧快照后到） |
| A11 | 渠道指南回调示例端口 8080，本地 8888（低危） |
| A12 | ~~2026-08-21 禁写投影 stage~~ → **2026-08-24 被 A19 推翻** |
| A19 | ✅ 还款同步投影 `stage`（与 `dpd` 同以数仓为准）。引擎 §4.6 约束的是 `CaseContext`，不是投影列。非法取值只跳过该字段、不 poison 整笔还款（丢还款＝已还清客户继续被催） |
| A20 | ✅ 负 dpd / `stage=null`：护栏改「逾期归零且 dpd>0 才拒」；`stagePresent` 标志写显式 null；读取侧 dpd<-3 返回 null 不兜成 S0；建计划补下界 |

#### T0-7 观测缺口（2026-08-21 盘点 → 2026-08-25 代码交付）

当时缺：接入侧 Micrometer/MDC、inbox 查询、日切进度、渠道失败 counter、DLQ 只读。
2026-08-25 补齐三块：`/ops/evidence` 四只读端点（裁 PII）；调度 MDC 补 `job`/`scanId`；`RedisDailyRollDeduplicator.evidenceSnapshot()`。
Redis 证据打实时值，不用 30s 采样 gauge。T3o-O 按此取证。

### 4.2 T1 / L2

**出口（2026-08-21）**：`mvn clean test` 当时 315 例全绿（后续增至 SSOT 所记 415）。
L0 admin 后台管理 ⏭：前提是 Pilot 写接口关闭或只读（T4-6）；启用任一写接口即回必测。

**C1–C7（2026-08-24 16:20–16:48，本机库 + 真实通知中心/极光/SendGrid，无 Mock）**

| ID | 证据键 |
|---|---|
| C1 | plan 257（94999）SMS→PUSH→EMAIL 全 `DELIVERED`；`providerMsgId` = `116632102` / `18103421364704596` / `-H496D3PTlOEpC_3touZfQ` |
| C2 | 94201 无 token → SMS；94101 计划链路 `fallback=true`，timeline 仍记 PUSH |
| C3 | 94801 `NO_PHONE`，step SKIPPED，未出网 |
| C4/C5 | 瞬态退避不写 timeline；业务拒绝 `code=1001` 转终态。F2 即本轮发现 |
| C6 | 同步渠道受理即完成，不进等待态 |
| C7 | 构造 step 255/1 回 EXECUTING → `duplicate event, key=255:1:0 skipped`；供应商请求与 timeline 条数不变 |

### L2-CB：AI_CALL 分级联调

> SSOT 链到本标题。裁决以 **2026-08-26 统一基线**为准；0825 通次见[附录 A](#附录-a历史会话2026-08-25-ai_call-真拨)。

回调路径是 `POST /webhook/facade-callback`（账户级），不是 `/webhook/facade/voice`。
同窗口配置样本：step 1392 被 Guard 50ms 硬超时判 `COMPLIANCE_BLOCKED`（pilot 已抬到 500ms）；
step 1393 Valubo 自签名 PKIX，证书导入镜像 TrustStore（`CN=valubo-voice-test`，不关全局 TLS）。上线换正规证书须删 `deploy/Dockerfile` 的 keytool 段。

| 差集 | 结论 |
|---|---|
| 真人接通映射 | ✅ 构造侧。测试号 `+639451374358` 三次皆 `MEDIA_NEGOTIATION_FAILED`（SIP 406）。对步骤 1684 注入 `was_ai_connected=true` + `reason=NORMAL` → COMPLETED/ANSWERED。Facade 真回接通报文未见过 |
| 伪造签名被拒 | ✅ 伪造签名与不带签名头均 HTTP 401，`signature_valid=0`，步骤不变 |
| 重复回调幂等 | ✅ 审计 id=18 原文重放：HTTP 200、`signature_valid=1`、duplicate `session_id` 跳过，timeline/步骤不变 |
| 身份反查 | ✅ 不带 `client_metadata`，按 `external_case_id` 反查到步骤 1684 |
| 基线一致 | ✅ 步骤 1667（plan 843 / case 517301）ca_branch 真拨：改投 test-callee → EXECUTING → 约 6 分钟真实回调 FAILED → COMPLETED/FAILED、计划推进 |
| 数仓进件 | 该窗口 `COLLECTION_INGESTION_ENABLED=false`，计划由 Redis 注入，未验真实 Pub/Sub |
| 受理证据 | 同步渠道以受理成功与 `providerMsgId` 为证，不要求回调审计行 |

**2026-08-26：L2-CB 通过。** S1–S4 的 150 个 AI_CALL 槽保留。两项残留不挡 T4，须在 T5 放量前拿真实样本：Facade 真「接通」报文；测试号 SIP 406 归属。

**槽位史**：2026-08-24 从 S1–S4 摘 150 槽（公网回调未就绪，硬跑会系统性超时噪声）；快照 `t_contact_plan_template_bak_20260824_aicall`。0825 还原时 S0 被误注入 8 个 AI_CALL（到期前不拨，合规回归）。2026-08-26 用 `scripts/pilot/2026-08-26-revert-s0-ai-call-slots.sql` 回退 S0；须 `current_version + 1` 失效缓存，不能 `GREATEST`。

### 4.3 T2（L3）

**出口（2026-08-21 17:19，本机 MySQL 8.0.46）**：30 例全绿。
此前 2026-08-20 远端库 14 例；08-21 补 L3-7/L3-8 至 26 例后因 `ServiceClock` 改 SQL 回退复跑。

测试自身缺陷（生产 SQL 正确）：租约用例参考时刻取在 `lease_until` 之后；`PlanStepTriggerPublisherIT` 直写 mapper 未赋 `created_at`（时区修复后库端不再兜底）。

**本机库搭法**（L4a/L4b 亦用此，避开共享库）：`mysql@8.0` 8.0.46；`schema.sql` 10 张 + 远端 dump 另 15 张（共 25）；只灌配置表；`t_user_device_token` 仅 `99000000..99000005`（多灌会坏 L4a-2）。入口 `COLLECTION_DB_URL`。远端事实：`t_user_extend` 287 万行真实用户，`t_ai_collection` 0 行。CI 不连库，CI 绿不能替代 L3。

### 4.4 T3（L4a / L4b）

**出口（2026-08-21）**：L4a PASS=29、L4b PASS=69，FAIL=0。两组本机库、SPI 硬超时保持生产默认、非 mock `provider_msg_id` = 0。真实数仓源当时不存在，不随本次放行；2026-08-26 起数仓已推，由 T4 覆盖。

**L4a**

| 轮次 | 结果 |
|---|---|
| 17:05–17:16 | PASS=26，`logs/run/l4a-localdb.out`。14 条 DELIVERED 全 mock；31 行时间列同行全 PHT；步骤全收敛 |
| 21:49–22:08 | PASS=29（含 L4a-3b），`logs/run/l4a-final4.out`。`L4A_ALLOW_QUIET_HOURS=1`，静默时段 Guard 本轮无证据 |

L4a-3b：无真实还款入口，补 `/mock/balance-updated`。断言不变量是计划身份/stage/模板/步数/渠道，不含 `status`（调度器会自己推进）。余额按 JSON number 比，不能字符串比 `800.00` vs `800.0`。

脚本改机制（曾静默假红）：静默窗口跑前 `exit 2`（或显式放宽）；`reset-l4a` 清内存合规计数（Redis 实现无此方法，不上 SPI）。

**L4b**（21:05–21:36，本机库 + topic `intelligent-collection-cases-test1`）：`logs/run/l4b-final.out`。
6 案投影齐（S0/S1/S3/S4/S4，两案 CEASED）；9 条 DELIVERED 全 mock；同日重复日切三项不变。

补齐裁定：

| 项 | 裁定 |
|---|---|
| L4b-11 | 原注入点在投影前，与 L4b-7 同路。新增 `failAfterProjectionIfArmed` / `/mock/ingestion-fault/arm-post-projection`，打 `PENDING_PUBLISH` 只补发 |
| L4b-13/14 | poison 只 ack+WARN，不写 inbox/DLQ，断言打日志。`data` 为字符串时 fastjson `getJSONObject` 抛的不是 `PoisonMessageException` → 曾 nack 进 DLQ；改为先判类型 |
| 探针案件 | 必须打停催案 99000005（不建计划），避免 L4b-6 到期步骤撞假红 |
| 环境假红 | 运行中不可 `mvn package`（fat jar 被覆盖 → `NoClassDefFoundError`），改为从 `logs/run/collection-admin.running.jar` 副本启动；不可运行中改脚本；`/mock/ingestion-fault` 按字段取值；重置清进程内入催标记（`/mock/ingestion-dedup/clear`） |
| `case-service` | local 缺省 mock，日切只扫 `t_ai_collection`。L4b 环境设 `ai`；Nacos 写死 mock，靠 yml 优先级盖掉 |
| 发布脚本 | 不可写死 dpd/stage；逐案取 `t_collection` IC_TEST_% 行。升档/停催须先重发 caseEvent 更新投影，再日切 |

`markExecuting` 已加终态谓词，签名 `void`→`boolean`（**契约变更**，旧库映射丢掉谓词会静默失效）。`prepareStepDue` 先抢步骤再动计划。

### 4.5 T3o

**出口（2026-08-26）**：达成。S7 / R14 / O2 / R8 后半有据顺延，不阻断 T4。
完整监控平台最迟 T6 准入前闭合；简版观测 MVP 不可后置。

调度 Job 在 `asia-northeast1`（与既有 `telemarket_out_reach_push` 同区）。`asia-southeast1` 三条早前畸形 Job（attributes 空、body 是文档示意原文）已 PAUSED——那些 tick 会被判 `UNKNOWN_JOB` 后 ack，链路静默停摆。`jobs.list` 对 southeast1 首页 0 条却带 `nextPageToken`，不翻页会误判「没有 Job」。案件订阅 ack 由 10s 纠为 60s；两条订阅 `expirationPolicy` 改为永不过期（31 天闲置删除是静默丢数）。

#### T5-S

| ID | 状态 | 证据 |
|---|---|---|
| S1 | ✅ | `planStepDue`/`callbackTimeout` 按频率自然触发，`failed_total` 无样本 |
| S2 | ✅ | 逐个 job 注入，各自 `triggered` +1，其余不动 |
| S3 | ✅ | 未知取值与「job 只写 body」均 `skipped{UNKNOWN_JOB}`，ACK 不重投 |
| S4 | ✅ | 重启积压 19 条陈旧 tick 一次丢弃，无扫描风暴 |
| S5 | ✅ | 连发三条同 job，`triggered` 按条增，`scan_rows{planStepDue}` 保持 0 |
| S6 | ✅ | `max-concurrency=1` 摸不到单飞；临时抬 16、突发 200 条，175 条 `IN_FLIGHT`；验毕还原 1 |
| S7 | 🟡 | 白名单模式直接遍历指定案，无游标/完成标记。实测扫 51 案、零 STAGE_CHANGED/CASE_CEASED。顺延 T4 |
| S8 | ✅ | 摘 `collection-admin-aicall` 后独占；四条 Job `describe` 含正确 `job` 与 `Asia/Manila` |

#### T5-R

| ID | 状态 | 证据 |
|---|---|---|
| R1 | ✅ | 重启不重建消费组、无新增 DLQ（F4 修复后） |
| R2 | ✅ | 真实入案 489935 全链，PEL 归零 |
| R3 | ✅ | `BEFORE_HANDLER` 注入，消息在 XPENDING |
| R4 | ✅ | `pel-min-idle` 临时 20s：阈值内不认领、超时后认领成功 |
| R5 | ✅ | 四类原因全覆盖。`t5r-poison-001` 5 次 → DLQ id 26。当时代价见 F10 |
| R6 | ✅ | 管理台带理由重放，步骤已终结则幂等跳过。不可恢复终止见 F13。操作人理由须写入 `redrive_reason`（三段式：分类\|by\|reason），不可只改 `failure_reason` |
| R7 | ✅ | 第二实例并发同一 PUSH 步骤：主执行、探针跳过，timeline 一条 |
| R8 | 🟡 | 跨重启计数与 TTL 保留；边界并发 / fail-close 随 T4 |
| R9 | ✅ | 按 db3 `CLIENT KILL`，Lettuce 毫秒重连，失败计数 0 |
| R10 | ✅ | 200 条指向已完成步骤的 DUE，10s 内消费完，全幂等跳过 |
| R11 | ✅ | 四键在日志实证；并补 `job`/`scanId` 进 logback 模式 |
| R12 | ✅ | `collection:processed:*` 与入案记录跨重启存续 |
| R13 | ✅ | `AFTER_HANDLER` 故障后重投命中去重，`deduped_total` +1，timeline 无重复 |
| R14 | ⬜ | `EngineFaultInjector` 无发布侧位置。补发逻辑有 L1/L3 覆盖。顺延 T4 |
| R15 | ✅ | 合成悬挂计划：Reaper 计数 +1，不改库、不触达 |

F4/F5 注入验证（2026-08-25）：缺 event 字段 → `MISSING_EVENT_FIELD`；非法 JSON → `DESERIALIZATION_FAILURE` 且 `raw` 可还原；无 handler → `NO_HANDLER`。三条 `redrive` 一次终止、再打 skipped。

#### T3o-O

| ID | 状态 | 证据 |
|---|---|---|
| O1 | ✅ | 案 489935：inbox `projectionApplied=true`/`PUBLISHED` → 投影 → 计划 → 步骤；手机邮箱 `PiiMask` |
| O2 | 🟡 | 同 R14，无发布失败注入点 |
| O3 | ✅ | `scan_rows{callbackTimeout}` 只增不收敛 → 定位 F12 / 步骤 1416 |
| O4 | ✅ | `/ops/evidence/redis`：`streamLength=341`、`dlqSize=6`、`pendingTotal=0`；重复投递由 R13 覆盖 |

#### T3o-7

案件订阅挂 `deadLetterPolicy`（`maxDeliveryAttempts=5` → `intelligent-collection-cases-dlq`）。
Pub/Sub 服务代理须同时有 DLQ topic publisher 与源订阅 subscriber（`provision-scheduler.py` 无条件跑 IAM grant；`getIamPolicy` 是 GET）。

演练：白名单临时加 `99000915`，`overdueAmount` 超 `DECIMAL(18,2)` → 解析过、写投影被严格模式拒 → nack×5 → DLQ，原文与 eventId 保留。
修复金额后同 eventId 重放只执行一次；再发被去重。脚本：`scripts/test/publish-cases-dlq-drill.py`。

---

## 附录 A：历史会话（2026-08-25 AI_Call 真拨）

独立成文已删。**裁决以 2026-08-26 统一基线复跑为准**（L2-CB 通过）。下面只留通次，便于复盘，不当出口。

环境：`bdp01`，容器 `collection-admin-aicall`，镜像 `aicall-e2e`（不是 ca_branch）。`COLLECTION_INGESTION_ENABLED=false`，计划由 Redis 注入。回调 `POST /webhook/facade-callback`。

**主路径**：案 520049 / user 4538503，plan 828，step 1414（`step_order=23`）。
14:41:00 到期 → 14:41:05 dispatch → 14:41:06 Facade start（`batch_id=591838ae-e3b7-4510-b537-036a2eb685c6`）→ 14:42:00 回调（`session_id=4b43cf2c-5d5f-42ca-b518-da12859e29f6`）→ `signature_valid=1`，审计 id=7，timeline id=712，步骤 `COMPLETED/NO_ANSWER`，计划 `PLAN_COMPLETED`。`was_ringing=true`，未接通。被叫脱敏 `+639635****98`。

**同窗口失败（无回调）**：step 1392 `COMPLIANCE_BLOCKED`（Guard 50ms，后抬到 500ms）；step 1393 PKIX 后撞日上限。

复盘：`t_contact_plan` 828、`t_contact_plan_step` 1414、`t_channel_callback_audit` 7、`t_contact_timeline` case 520049。Facade `GET /batches/{batch_id}`、`GET /sessions/{session_id}`。

---

## 附录 B：手册与资产

| 文档 | 用途 |
|---|---|
| [Pilot 操作手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | 部署、隔离、T3o 取证命令、回滚、T4/T5 切量 |
| [L4b 环境交接](./MOCASA催收系统升级_Phase1_L4b环境交接清单.md) | 隔离联调。禁止往生产 topic 发测试消息 |
| [触达内容验收](./MOCASA催收系统升级_Phase1_触达内容验收清单.md) | 终端正文对错，不是系统过没过 |
| [`scripts/README.md`](../../scripts/README.md) | 命令与脚本 |
| [渠道功能测试指南](../channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | 渠道 `TC-*` |
| [ContextSnapshot 契约对齐](../contracts/README_ContextSnapshot契约对齐.md) | 快照契约 |
| [引擎渠道执行契约](../contracts/MOCASA催收系统升级_Phase1_引擎渠道执行契约.md) | 执行契约 |

回滚不得删数据或重放未知消息：停调度 → 关渠道发送 → 保全 Pub/Sub / Redis / MySQL / 供应商证据 → 切回 → 对账。
