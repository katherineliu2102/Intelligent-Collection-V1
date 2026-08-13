# MOCASA 催收系统升级 — Phase 1 T5 Pilot 准备与演练手册

> **版本**: Phase 1 · 仅覆盖菲律宾市场  
> **日期**: 2026-08-04  
> **用途**: 合并 Redis 资源申请、Pilot 环境准备、部署演练、证据归档与回滚操作。  
> **边界**: 生产基础设施契约与实现差集以[基础设施交互规范](../MOCASA催收系统升级_Phase1_基础设施交互规范.md)为准；T5 用例、状态与出口以[测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md)为准。

---

## 1. 目标、输入与输出

T5 的目标是在不触达真实客户的前提下，准备并演练生产等价拓扑，形成进入 T6 受控切量前所需的配置、运行和回滚证据。

**输入**

- T4 已完成的证据包；
- 基础设施规范 [附录 C](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-c生产就绪差集登记)中所有“阻断”差集的闭合证明；
- 经批准的 Pilot 白名单、渠道 sandbox 地址和变更窗口。

**输出**

- 已完成的 T5/T5-R 测试证据；
- Redis、PubSub（案件与调度两条）、Cloud Scheduler、渠道与监控的配置快照（脱敏）；
- 回滚演练记录；
- 研发、运维、业务/产品的 T6 准入结论。

## 2. Pilot 拓扑与安全边界

| 组件 | Pilot 约束 |
|---|---|
| PubSub（案件） | 专用 topic `collection-ai-events-v1` + 订阅 `collection-ai-events-v1-sub`（与调度订阅分离） |
| PubSub（调度） | 调度专用主题 + 我们专用订阅（建议 `collection-schedule-ai-v1` / `collection-schedule-ai-v1-sub`）；**不得**复用案件订阅 |
| 应用 | `pilot` profile，Redis EventBus 与幂等均启用；首期单活跃实例，跨实例用例临时启动第二实例。常驻内网服务，不部署在 Cloud Run，不开放入站调度端口 |
| Redis | 独立于旧催收系统；Stream、幂等、合规频控按基础设施规范隔离 |
| MySQL | 使用正式表结构；Pilot 数据必须可按案件、计划、事件追溯 |
| 调度 | Cloud Scheduler 三个调度任务、四条 Job（`planStepDue` / `callbackTimeout` 每分钟；`dailyRoll` 拆两条 cron 覆盖 03:35–05:55 PHT 每 5 分钟），时区 Asia/Manila，发往调度专用主题 |
| 渠道 | 仅 sandbox、测试地址或批准白名单；强制限频、脱敏与 Webhook 签名校验 |

> **Pilot 与生产使用同一套调度实现**，只允许配置值不同（项目、订阅名、开关）；不存在「Pilot 一套、上线另一套」的调度模型。

**红线**

- 不向生产 topic 发布人为构造的测试消息。
- 不允许空白名单启动接入消费者。
- 不得以删除 Stream、PEL、DLQ 或业务表作为回滚手段。
- 不得将 Redis、数据库、渠道或 GCP 凭证写入仓库、日志或执行报告。

## 3. 角色与开始条件

| 角色 | 必须完成的事项 |
|---|---|
| 主架构 | 确认生产差集闭合、应用配置、事件与幂等语义、证据归档 |
| 运维 | Redis、网络、Secret、PubSub 订阅（案件 + 调度）、Cloud Scheduler Job 与 IAM、监控与告警 |
| 服务同事 | MySQL DDL、Repository、旧库只读权限与数据核对 |
| 编排同事 | 渠道 sandbox、限频、模板、Webhook 与供应商额度 |
| 业务/产品 | Pilot 白名单、触达窗口、停止条件、T6 切量边界 |

### 3.1 运维交付物与验收证据

进入 T5 前，运维须提供脱敏后的以下证据；代码仓不得保存凭证或真实地址：

| 能力 | 运维交付 | 验收证据 |
|---|---|---|
| Redis | 独立实例、网络白名单、Secret、AOF 与 `noeviction` 策略 | 应用连通、Stream/PEL 可见、Redis 监控截图 |
| 监控与通知（**可后置**） | Prometheus 抓取 `/actuator/prometheus`、Alertmanager 路由、钉钉机器人 Webhook | 测试告警到达钉钉及静默/恢复记录；未就绪期间以每日人工巡检日志 + 手工抓取指标代偿，最迟 T6 前闭合 |
| PubSub（案件） | 独立订阅、服务账号 IAM 与死信策略 | 白名单消息消费和权限检查记录 |
| PubSub（调度）+ Cloud Scheduler | 见下方 [§3.2 调度交付清单](#32-调度交付清单o1o8) | `collection.schedule.triggered` 按周期增长的抓取记录；Scheduler Job 执行成功记录 |
| 渠道 | sandbox 地址、额度、签名 Secret 与批准测试白名单 | 受控投递和回调验证记录 |

### 3.2 调度交付清单（O1–O8）

调度入口已从 XXL-Job 迁为「Cloud Scheduler → 调度专用 Pub/Sub 主题 → 应用侧专用订阅」。应用侧代码、配置样例、启动校验与单测均已就位，下列各项**只能由运维 / GCP 交付**（SSOT：[基础设施 §5.5](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#55-运维--gcp-交付清单)）：

| # | 交付项 | 验收证据 |
|---|---|---|
| O1 | 调度专用 Pub/Sub 主题（建议 `collection-schedule-ai-v1`） | `gcloud pubsub topics describe` 输出（脱敏） |
| O2 | 我们专用的调度订阅（建议 `collection-schedule-ai-v1-sub`），不复用案件订阅、不与他方共享 | `gcloud pubsub subscriptions describe` 输出，确认 topic 指向 O1 且状态 `ACTIVE` |
| O3 | Scheduler 服务账号对 O1 的 `roles/pubsub.publisher` | IAM 绑定截图 / `get-iam-policy` 输出 |
| O4 | 应用服务账号对 O2 的 `roles/pubsub.subscriber` | 同上；应用日志出现 `[Scheduler] 调度订阅消费已启动` |
| O5 | 订阅 ack deadline 60s、`message-retention-duration` 10m、不配死信主题 | 订阅配置输出 |
| O6 | 三个调度任务、共四条 Cloud Scheduler Job（`dailyRoll` 拆两条精确覆盖 03:35–05:55 PHT；cron 与消息属性见 [§5.1 配置模板](#51-应用配置)） | 各 Job 的 `describe` 输出与一次成功执行记录 |
| O7 | Scheduler Job 失败告警 | 告警规则配置 + 一次测试告警到达记录 |
| O8 | 日切 06:00 PHT 未完成告警 | 告警规则配置 + 触发条件说明 |

> **为什么这些不能由研发闭合**：主题、订阅、IAM 绑定与 Scheduler Job 都是 GCP 侧资源，仓库内只能提供可复制的配置模板与占位符，真值与资源创建必须由运维执行。

开始前必须满足：

1. T4 已通过，且未关闭项已明确不影响 T5。
2. 基础设施规范 [附录 C](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-c生产就绪差集登记)的阻断项均已关闭；仅记录差集而未修复，不得宣告 Pilot 完成。
3. 白名单、sandbox 地址、回滚责任人和变更窗口均已审批。

## 4. Redis 资源申请与运行基线

| 项 | 要求 |
|---|---|
| 版本 | Redis 6.x 或以上；最低 Redis 5.0，必须支持 Stream |
| 隔离 | 新开独立实例，不与旧催收 Redis 共用 |
| 形态 | Phase 1 采用单机 + 主从；当前不需要 Cluster |
| 内存策略 | `maxmemory-policy=noeviction`；设置容量余量和内存告警 |
| 持久化 | `appendonly yes`、`appendfsync everysec`；RDB 仅作为补充 |
| 安全 | 密码认证；如基础设施支持则启用 TLS；仅放通应用部署环境来源 |
| 监控 | 内存、命令延迟、连接数、复制状态、AOF 状态、写入拒绝、Stream lag 和 PEL |

运维通过 Secret 或密钥管理系统提供下列信息，禁止通过邮件或 IM 明文传递：

| 变量 | 说明 |
|---|---|
| `COLLECTION_REDIS_HOST` | Redis 地址 |
| `COLLECTION_REDIS_PORT` | Redis 端口，缺省 `6379` |
| `COLLECTION_REDIS_PASSWORD` | Redis 密码 |
| `COLLECTION_REDIS_SSL` | TLS 开关 |

### 4.1 连接信息落位与变更方式

`application-pilot.yml` 只写 `${COLLECTION_REDIS_*}` 占位符，仓库不含真值。真值有两处来源，优先级为 Nacos > 环境变量：

| 位置 | 用法 | 适用 |
|---|---|---|
| 部署机 `.env.pilot`（已 gitignore，`docker-compose` 经 `APP_ENV_FILE` 加载） | `COLLECTION_REDIS_HOST/PORT/PASSWORD/SSL`；含 `#` 等特殊字符的口令**必须加双引号**，否则可能被当作行内注释截断 | 首次拉起 Pilot，改完重启容器即生效 |
| Nacos `intelligent-collection-pilot.yml`（`optional:` 导入，未创建不阻塞启动） | 直接写 `spring.redis.*` 覆盖占位缺省 | 后续改地址或轮换口令时集中下发，无需重新构建镜像 |

> `spring.redis.timeout` 必须大于消费轮询的 `XREADGROUP BLOCK`（当前 1s），Pilot 取 2s；短命令（合规 Lua 等）的延迟上界由 `SpiInvoker` 的 50ms 硬超时兜底，不靠客户端超时收紧。

容量规模与 Consumer 参数以[基础设施规范附录 B](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-b容量基线与生产技术准入)的回填结论为准，不使用旧“5,000 在催”口径定版。

## 5. 部署与配置预检

### 5.1 应用配置

以 `application-pilot.yml` 为基线，部署前核对：

- `collection.eventbus=redis`、`collection.idempotency=redis`；
- Redis Stream、Consumer Group 与 Consumer Name 已注入，Consumer Name 在组内唯一；
- `collection.ingestion.enabled=true`，且 `collection.ingestion.loan-id-whitelist` 为非空批准名单；
- `collection.scheduler.enabled=true`，`project-id` 与 `subscription` 已注入且订阅**不同于**案件接入订阅；
- Webhook 必须启用签名校验；
- 渠道不得回退到 mock，且 sandbox/测试地址与限频已生效；
- `collection.ingestion.fault-injection-enabled=false`。

> 缺 `collection.scheduler.project-id` / `subscription`、陈旧阈值大于任务周期，或误将 `local`/`test` profile 与调度同时启用（双调度入口），`SchedulerEntrypointValidator` 均会**拒绝启动**，不会静默降级。

#### 调度配置模板（可复制，不含真值）

应用侧（`application-pilot.yml` 已就位，真值走环境变量）：

```yaml
collection:
  scheduler:
    enabled: ${COLLECTION_SCHEDULER_ENABLED:true}
    project-id: ${GCP_PUBSUB_PROJECT:}
    subscription: ${GCP_SCHEDULER_SUBSCRIPTION:}
    stale-threshold-seconds: 60          # 必须 ≤ 60（每分钟任务周期）
    daily-roll-stale-threshold-seconds: 300  # 必须 ≤ 300（日切周期）
    max-concurrency: 1
```

GCP 资源（把 `<...>` 替换为真值执行，不要把真值写回仓库）：

```bash
# O1 调度专用主题
gcloud pubsub topics create <SCHEDULE_TOPIC> --project=<PROJECT_ID>

# O2 我们专用的调度订阅（O5：ack deadline 与消息保留）
gcloud pubsub subscriptions create <SCHEDULE_SUBSCRIPTION> \
  --project=<PROJECT_ID> \
  --topic=<SCHEDULE_TOPIC> \
  --ack-deadline=60 \
  --message-retention-duration=10m

# O3 Scheduler 服务账号：仅对调度主题发布
gcloud pubsub topics add-iam-policy-binding <SCHEDULE_TOPIC> \
  --project=<PROJECT_ID> \
  --member="serviceAccount:<SCHEDULER_SA>" \
  --role="roles/pubsub.publisher"

# O4 应用服务账号：仅对调度订阅消费
gcloud pubsub subscriptions add-iam-policy-binding <SCHEDULE_SUBSCRIPTION> \
  --project=<PROJECT_ID> \
  --member="serviceAccount:<APP_SA>" \
  --role="roles/pubsub.subscriber"
```

O6 三个（`dailyRoll` 拆两条，共四条）Cloud Scheduler Job，时区统一 `Asia/Manila`，5 段 cron：

```bash
# 1. 步骤到期扫描：每分钟
gcloud scheduler jobs create pubsub collection-plan-step-due \
  --project=<PROJECT_ID> --location=<REGION> \
  --schedule="* * * * *" --time-zone="Asia/Manila" \
  --topic=<SCHEDULE_TOPIC> \
  --message-body="scheduled-tick" \
  --attributes="job=planStepDue"

# 2. 回调超时哨兵：每分钟
gcloud scheduler jobs create pubsub collection-callback-timeout \
  --project=<PROJECT_ID> --location=<REGION> \
  --schedule="* * * * *" --time-zone="Asia/Manila" \
  --topic=<SCHEDULE_TOPIC> \
  --message-body="scheduled-tick" \
  --attributes="job=callbackTimeout"

# 3a. 日切窗口起始段：03:35–03:55 每 5 分钟
gcloud scheduler jobs create pubsub collection-daily-roll-open \
  --project=<PROJECT_ID> --location=<REGION> \
  --schedule="35,40,45,50,55 3 * * *" --time-zone="Asia/Manila" \
  --topic=<SCHEDULE_TOPIC> \
  --message-body="scheduled-tick" \
  --attributes="job=dailyRoll"

# 3b. 日切窗口续跑段：04:00–05:55 每 5 分钟
gcloud scheduler jobs create pubsub collection-daily-roll-continue \
  --project=<PROJECT_ID> --location=<REGION> \
  --schedule="*/5 4-5 * * *" --time-zone="Asia/Manila" \
  --topic=<SCHEDULE_TOPIC> \
  --message-body="scheduled-tick" \
  --attributes="job=dailyRoll"
```

> 拆 3a / 3b 的原因：5 段 cron 无法在单表达式内既从 03:35 起步又覆盖到 05:55。`t_collection` 约 03:00 更新完成，03:35 起跑保留 35 分钟稳定缓冲；两条 Job 发同一主题、同一 `job=dailyRoll` 属性，应用侧无差别。
>
> `--message-body` 内容不参与路由，路由只看 `job` 属性；未知 `job` 取值会被记录并 ack，不重投。

### 5.2 启动预检

1. 校验应用到 Redis、MySQL、案件订阅、调度订阅和渠道的网络与鉴权。
2. 启动应用并确认 health 为 UP、配置加载无缺失、Consumer Group 初始化可重复执行；日志出现 `[Scheduler] 调度订阅消费已启动`。
3. 在 Redis 中检查 Stream、Consumer Group、Consumer Name 与 `collection:ingestion:*` 去重键；记录脱敏配置快照。
4. 手工抓一次 `/actuator/prometheus` 并确认日志带 `event=`/`case=`/`plan=`/`step=`；Prometheus 抓取与告警通道属可后置项，未就绪不阻塞演练。
5. **调度巡检**（迁出 XXL 后无调度控制台执行记录页，这一步不可省）：间隔 5 分钟抓两次 `/actuator/prometheus`，按 [基础设施 §7.3 调度指标的人工巡检口径](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#调度指标的人工巡检口径)判读。最低要求：`collection.schedule.triggered{job=planStepDue}` 两次抓取间增量约为 5，`collection.schedule.failed` 恒为 0，稳态 `collection.schedule.stale.discarded` 不增长。

#### 调度排障速查

| 现象 | 首查 | 次查 |
|---|---|---|
| `collection.schedule.triggered` 不增长 | Cloud Scheduler Job 是否 `ENABLED` 且最近执行成功（O6/O7） | 调度订阅未确认消息数是否堆积；应用日志是否有 `[Scheduler] 调度订阅消费已启动` |
| 启动即失败并提示 `collection.scheduler.subscription` | 环境变量 `GCP_SCHEDULER_SUBSCRIPTION` 未注入 | Nacos `intelligent-collection-pilot.yml` 是否覆盖了占位缺省 |
| 启动即失败并提示 `TriggerScanner` | profile 里混入了 `local`/`test` | 确认 `SPRING_PROFILES_ACTIVE` 只含 `pilot` |
| 重启后 `stale.discarded` 一次尖峰 | 正常：停机期间累积的 tick 被丢弃，属预期防抖 | 若持续增长则为消费跟不上，查 ack deadline 与扫描耗时 |
| `skipped{reason=UNKNOWN_JOB}` 增长 | Scheduler Job 的 `--attributes` 里 `job` 拼写 | 是否有非预期发布者向调度主题写入 |
| `skipped{reason=IN_FLIGHT}` 持续增长 | 单次扫描耗时超过触发周期 | 降 `engine.consumer.scan_limit` / `daily-roll-batch-size`，查扫描 SQL |
| 日切 06:00 未完成 | 窗口内 `triggered{job=dailyRoll}` 次数是否达到预期（约 29 次） | 游标推进速率与 `daily-roll-batch-size` |

#### 调度回滚

停调度不需要改代码或重启，两条路径任选：

1. **GCP 侧**（首选，最快）：`gcloud scheduler jobs pause <JOB>` 暂停四条 Job。应用侧不再收到 tick，扫描自然停止，已在 Stream 中的事件仍会被消费完，不丢数据。
2. **应用侧**：置 `COLLECTION_SCHEDULER_ENABLED=false` 后重启，调度消费者不装配。

> 回滚**不得**删除调度主题、订阅或 Stream/PEL/DLQ。恢复旧链路时，先确认新链路已停止 tick，再按批准 Runbook 切回。

## 6. T5 演练执行顺序

本节定义操作顺序；具体断言、状态与证据格式以测试 SSOT 的 T5/T5-R 为准。

> 顺序按“先打通生产渠道，再做可靠性演练，监控最后补”排列（2026-08-05 决定）。第 7 步不阻塞第 1–6 步与 T5 结论。

1. **拓扑与安全**：验证案件与调度两条独立订阅、白名单、渠道生产/沙箱地址、限频、Webhook 签名，以及四条 Cloud Scheduler Job 的 cron、时区与 `job` 属性。
2. **渠道生产连通**（优先）：按 [§6.1](#61-渠道生产连通验证清单) 逐渠道打通真实投递与回调。
3. **正常路径**：执行 Consumer Group 初始化、发布/消费/XACK、日切、到期触发、回调超时和受控渠道投递。
4. **可靠投递**：注入 handler 异常，验证 PEL 可见、超时认领、重投与最终 XACK。
5. **DLQ 与重放**：构造持续失败消息，验证 Redis DLQ、MySQL `t_event_dlq`、可恢复消息的受控重放、窗口外延后与不可恢复消息的终止留存。
6. **跨实例、接入去重与合规**：临时启动第二实例，验证同一幂等键只有一次获取成功；重启任一实例后验证 Redis 合规计数未清零、接入去重与结转标记仍生效、原子上限仍成立。
7. **断连与背压**：短暂阻断 Redis 后恢复连接；制造慢渠道调用与并发事件，验证其他事件继续被工作线程处理、队列有界、背压日志限速且不丢消息。
8. **监控与容量**（可后置）：采集 PEL、Stream 长度、线程池、渠道耗时、Redis 内存、Guard Lua p99 与 fail-close 比率，回填容量基线；抓取与告警未就绪期间以人工巡检代偿。
9. **回滚**：停止新订阅、路由与调度；保全 Stream/PEL/DLQ/MySQL 证据；恢复旧链路；确认没有删除数据或未知重放。

### 6.1 渠道生产连通验证清单

目标是在真实供应商链路上确认“发得出、回得来、可审计、可停”。每个渠道都必须先对内部测试号码/邮箱验证，再放开批准白名单。

| 序 | 渠道 | 前置 | 验证内容 | 通过标准 |
|---|---|---|---|---|
| 1 | SMS | `channel.notification.app-key`、发送额度、菲律宾号段准入 | 对内部测试号发一条 S1 话术 | 供应商返回成功且拿到 `providerMsgId`；`t_contact_timeline` 落一条 SENT；真机收到且文案无占位符残留 |
| 2 | PUSH | `case_push` 携带的极光 token（用户已注册时） | 有 token 与无 token 两种案件 | 有 token 走 PUSH；无 token 自动 fallback SMS 且只产生一次投递 |
| 3 | EMAIL | `channel.sendgrid.api-key`、模板 ID、发件域名验证 | 对内部邮箱发一封 | SendGrid 202；模板渲染含正确金额与还款链接；脏邮箱（空/`0`）走 Guard SKIP 不发送 |
| 4 | AI_CALL | 已完成 [测试 SSOT L2-CB 第 1–3 级](./MOCASA催收系统升级_Phase1_测试文档.md#l2-cbai_call-分级联调方案)；稳定 HTTPS 回调地址、HMAC secret、真实 Adapter 与批准测试号码就绪 | 一次呼叫 + 回调 | 步骤保持 `STEP_EXECUTING` 等回调；回调经签名校验后推进；不回调时哨兵按 `callback_timeout_minutes` 收敛为 FAILED |
| 5 | 回调与审计 | Webhook 公网可达、签名开启 | 重复回调与伪造签名 | 重复回调幂等不重复计次；签名错误被拒并留审计 |
| 6 | 合规与停止 | 触达窗口、日上限、白名单 | 窗口外触达与超限触达 | 窗口外不发送（延后或 SKIP）；超限被 Guard 拦截并写 `COMPLIANCE_BLOCKED`；关闭开关后立即停止发送 |

> 金额、姓名等渲染值来自计划快照；部分还款后须确认下一步话术里的金额已刷新（对应测试 SSOT L4a-3b）。

> DLQ redrive 仅接受管理后台登录态提交的显式 eventId 列表与必填原因；`MAX_DELIVERY_EXCEEDED` 最多重放三次，解析失败/无 Handler 直接终止，触达窗口外的 `PLAN_STEP_DUE` 计入 `deferred` 并保持 `PENDING`。Pilot 应抓取 `/actuator/prometheus`；Alertmanager 通过 Webhook 将 `GUARD_ERROR`、SPI 超时、DLQ 终止和 Stream/PEL 阈值告警发送至钉钉。

## 7. 待完成项与闭合口径

以下项来自基础设施规范 [附录 C](../MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-c生产就绪差集登记)；除标注“可后置”的项外，未关闭不得将 T5 标记为完成。

代码侧实现已全部就位（DLQ 状态机与窗口门控、Redis Lua 频控、事件消费去重、指标与 MDC、日切 keyset 游标均有单测覆盖）；下表剩余项均需 Pilot 环境或运维配置才能关闭。

已在 2026-08-05 关闭、不再列为待办：`t_event_dlq` 的 redrive 列迁移已在催收库执行并核对（`redrive_count` / `redrive_reason` / `redriven_at` / `terminated_at` 四列存在）；`t_contact_plan`、`t_contact_plan_step`、`t_contact_timeline`、`t_decision_log`、`t_user_device_token` 与所需列、唯一索引均已存在；Redis key 已统一到 `collection:` 根命名空间，`collection:pilot:*` 临时前缀取消。

| 待完成项 | 闭合口径 | 责任模块 |
|---|---|---|
| Redis Consumer 并发与背压 | 真实 Redis 上验证慢渠道不阻塞其他事件，并压测定版线程数、队列容量与告警阈值 | collection-engine + 运维 |
| DLQ 持久化与重放 | Pilot MySQL/Redis 上跑通 R5/R6：落表、受控 redrive、窗口外延后、终止留存与告警到达 | common / engine / service + 运维 |
| Redis 合规频控 | 跨实例上限与真实 Redis 断连 fail-close 在 Pilot 验证并告警到达 | collection-channel / engine + 运维 |
| 接入去重连续性 | 重启应用后 `dedup:msg` / `last-seen` / `ingested` 仍生效；结清后可再次入案 | ingestion + 运维 |
| 渠道生产连通 | [§6.1](#61-渠道生产连通验证清单) 六项全部通过并留存投递与回调证据 | 编排同事 + 主架构 |
| 事件消费去重 | 真实 Redis 上验证 PEL 重投与同一 `eventId` 重放只执行一次业务，`collection:processed:*` 按 24h 过期 | collection-engine + 运维 |
| 调度通道生产化 | [§3.2](#32-调度交付清单o1o8) O1–O8 全部交付：调度主题、专用订阅、双向 IAM、四条 Scheduler Job（`35,40,45,50,55 3 * * *` + `*/5 4-5 * * *` 覆盖日切窗口）、ack deadline 与消息保留、Scheduler 失败与 06:00 未完成告警；并在 Pilot 上确认 Redis keyset 游标、当日完成标记与幂等行为 | ingestion / admin / 运维 |
| 可观测性（可后置，最迟 T6 前） | Prometheus 抓取、Alertmanager 路由与 Dashboard 接通，§7.4 告警到达钉钉；代偿期内每日人工巡检日志并手工抓取指标 | 运维 |

## 8. 证据归档与 T5 完成判定

证据包至少包含：

- 脱敏后的配置与 Secret 注入确认；
- Redis、案件订阅、调度订阅与 Cloud Scheduler、渠道 sandbox 的连通与权限确认；
- T5/T5-R 测试记录、相关日志、指标截图和 SQL 摘要；
- 容量基线与告警阈值；
- 回滚演练记录、责任人与完成时间。

只有测试 SSOT 中 T5 的全部出口满足、上述待完成项（除“可后置”项）均关闭、所有真实触达仍在批准范围内，且回滚演练完成后，方可提交 T6 准入；可后置的可观测性项必须在 T6 受控切量前补齐，代偿期内的人工巡检记录一并归档。

