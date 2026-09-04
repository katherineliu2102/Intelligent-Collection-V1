# MOCASA 催收系统升级 — Phase 1 T5 Pilot 准备与演练手册

> **版本**: Phase 1 · 仅覆盖菲律宾市场 · 2026-08-19
> **日期**: 2026-08-19
> **用途**: 合并 Redis 资源申请、T3o 生产等价演练、T4 固定 50 案真实白名单 Pilot、渐进切量、证据归档与回滚操作。
> **边界**: 生产基础设施契约与实现差集以[基础设施交互规范](../../MOCASA催收系统升级_Phase1_基础设施交互规范.md)为准；T5 用例、状态与出口以[测试 SSOT](../MOCASA催收系统升级_Phase1_测试文档.md)为准。
> **写作约定**: 本手册只写**怎么做、怎么验、失败怎么办**。逐次实测记录、缺口登记与裁定理由一律写进[测试 SSOT 附录 C](../MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md#附录-c缺口登记)，此处只留结论与指针——手册被当作值班操作台使用，掺入过程叙述会让人在故障时读不到该做的动作。

---

## 1. 目标、输入与输出

本手册有两个严格分离的目标：

1. **T3o**：不触达真实客户，在隔离数据/渠道 sandbox 中演练生产等价拓扑和简版观测 MVP；
2. **T4/T5**：在 T3o 通过后，以业务批准的固定 50 案真实白名单开始，按每日上限逐级切量。

50 案用于验证正确性、合规和可运营性，不用于得出长期触达率或回收率结论。

**输入**

- T3 与 T3o 已完成的证据包；
- [交接板 D.1](../../../HANDOFF.md#d1-生产就绪差集登记)中所有“阻断”差集的闭合证明；
- 经批准的 Pilot 白名单、渠道 sandbox 地址和变更窗口。

**输出**

- 已完成的 T3o/T5-R/T5-S 测试证据；
- Redis、PubSub（案件与调度两条）、Cloud Scheduler、渠道与监控的配置快照（脱敏）；
- 回滚演练记录；
- 研发、运维、业务/产品的 T4/T5/T6 准入结论。

## 2. T3o / T4 拓扑与安全边界

| 组件 | Pilot 约束 |
|---|---|
| PubSub（案件） | T3o 仅隔离测试 topic/subscription；T4 起使用批准的 Pilot/生产案件 topic + 专用 subscription（与调度订阅分离） |
| PubSub（调度） | 调度专用主题 + 我们专用订阅（建议 `intelligent-collection-schedule-v1` / `intelligent-collection-schedule-v1-sub`）；**不得**复用案件订阅 |
| 应用 | `pilot` profile，Redis EventBus 与幂等均启用；首期单活跃实例，跨实例用例临时启动第二实例。常驻内网服务，不部署在 Cloud Run，不开放入站调度端口 |
| Redis | 独立于旧催收系统；Stream、幂等、合规频控按基础设施规范隔离 |
| MySQL | 使用正式表结构；Pilot 数据必须可按案件、计划、事件追溯 |
| 调度 | Cloud Scheduler 三个调度任务、四条 Job（`planStepDue` / `callbackTimeout` 每分钟；`dailyRoll` 拆两条 cron 覆盖 03:35–05:55 PHT 每 5 分钟），时区 Asia/Manila，发往调度专用主题 |
| 渠道 | T3o 仅 sandbox/测试地址；T4 起仅批准真实白名单，强制限频、脱敏与 Webhook 签名校验 |

> **Pilot 与生产使用同一套调度实现**，只允许配置值不同（项目、订阅名、开关）；不存在「Pilot 一套、上线另一套」的调度模型。

**红线**

- 不向生产 topic 发布人为构造的测试消息；T4 的真实消息只能由数仓按正式契约发布。
- 不允许空白名单启动接入消费者。
- T3o 不允许触达真实客户；T4 不允许处理或触达批准 50 案之外的客户。
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

进入 T3o 前，运维须提供脱敏后的以下证据；代码仓不得保存凭证或真实地址。进入 T4 前，还须补齐本手册 §9 的真实白名单审批和停止条件：

| 能力 | 运维交付 | 验收证据 |
|---|---|---|
| Redis | 独立实例、网络白名单、Secret、AOF 与 `noeviction` 策略 | 应用连通、Stream/PEL 可见、Redis 监控截图 |
| 监控与通知（**可后置至 T6 准入**） | Prometheus 抓取 `/actuator/prometheus`、Alertmanager 路由、钉钉机器人 Webhook | 测试告警到达钉钉及静默/恢复记录；未就绪期间按下方代偿口径执行，最迟在申请移除白名单（T6 准入）前闭合 |
| PubSub（案件） | 独立订阅、服务账号 IAM 与死信策略 | 白名单消息消费和权限检查记录 |
| PubSub（调度）+ Cloud Scheduler | 见下方 [§3.2 调度交付清单](#32-调度交付清单o1o8) | `collection.schedule.triggered` 按周期增长的抓取记录；Scheduler Job 执行成功记录 |
| 渠道 | sandbox 地址、额度、签名 Secret 与批准测试白名单 | 受控投递和回调验证记录 |

#### 监控代偿口径（T3o 起生效，至 T6 准入闭合）

监控平台后置的前提是代偿可执行且留痕。缺代偿记录时，该日不得作为放量观察窗的有效证据。

| 项 | 要求 |
|---|---|
| 频率 | 每日至少一次；T4 三日循环与每次 T5 放量当日必须有记录 |
| 内容 | 手工抓取 `/actuator/prometheus`，按[基础设施 §7.3 调度巡检口径](../../MOCASA催收系统升级_Phase1_基础设施交互规范.md#调度指标的人工巡检口径)判读调度指标，并记录 PEL 深度、Stream 长度、DLQ 入列与 `collection.step.skipped` 的跳过原因 |
| 判据 | 异常口径复用[基础设施 §7.4 告警最低要求](../../MOCASA催收系统升级_Phase1_基础设施交互规范.md#74-告警最低要求)：`collection.schedule.failed` 或 `skipped{reason=UNKNOWN_JOB}` 任意增长、`triggered` 停止增长、DLQ 持续入列、日切 06:00 PHT 未完成，均按该表处置并暂停下一批放量 |
| 归档 | 巡检记录进入 [§8 证据包](#8-证据归档与阶段完成判定)，与当日对账同批留存 |
| 责任人与时点 | **主架构**每日一次，固定在日切窗口收尾后（06:30 PHT 前）执行并归档（2026-08-21 确定）；主架构不可用时须提前指定代班人，不得跳过 |
| 升级路径 | 命中判据即按[基础设施 §7.4](../../MOCASA催收系统升级_Phase1_基础设施交互规范.md#74-告警最低要求)处置，并同步业务与运维值守人；涉及触达安全的（越窗、越限、非白名单、重复投递）立即暂停下一批放量 |

> 代偿只覆盖“人能定期看到”的部分，不覆盖实时告警。因此代偿期内**不得**取消值守、也不得把停止条件的判断推迟到次日巡检；Cloud Scheduler 侧的 O7 / O8 告警仍应尽早交付。

### 3.2 调度交付清单（O1–O8）

调度入口已从 XXL-Job 迁为「Cloud Scheduler → 调度专用 Pub/Sub 主题 → 应用侧专用订阅」。应用侧代码、配置样例、启动校验与单测均已就位。GCP 侧交付项与当前状态（口径 SSOT：[基础设施 §5.5](../../MOCASA催收系统升级_Phase1_基础设施交互规范.md#55-运维--gcp-交付清单)）：

| # | 交付项 | 状态 | 验收证据 |
|---|---|---|---|
| O1 | 调度专用 Pub/Sub 主题（`intelligent-collection-schedule-v1`） | ✅ 本就存在 | `provision-scheduler.py --verify` |
| O2 | 我们专用的调度订阅（`intelligent-collection-schedule-v1-sub`），不复用案件订阅、不与他方共享 | ✅ 存在且已纠参数 | 同上：topic 指向 O1，无其他 subscriber |
| — | **案件接入订阅** `intelligent-collection-cases-v1-sub`（非 O 系列，但同批发现同批修） | ✅ ack 已由 10s 纠为 60s | `--verify` 输出；[数据接入规格 §2.1](../../MOCASA催收系统升级_Phase1_数据接入规格.md) 要求与 `collection.ingestion.ack-deadline-seconds=60` 一致 |
| O3 | Scheduler 服务账号对 O1 的 `roles/pubsub.publisher` | ✅ 服务代理默认具备 | Job 成功发布即为证据（tick 已落订阅） |
| O4 | 应用服务账号对 O2 的 `roles/pubsub.subscriber` | ⬜ 待应用侧凭证落位 | 应用日志出现 `[Scheduler] 调度订阅消费已启动` |
| O5 | 订阅 ack deadline 60s、`message-retention-duration` 10m、不配死信主题 | ✅ 已 PATCH（原 10s / 7d） | `--verify` 输出 |
| O6 | 三个调度任务、共四条 Cloud Scheduler Job（`dailyRoll` 拆两条精确覆盖 03:35–05:55 PHT；cron 与消息属性见 [§5.1 配置模板](#51-应用配置)） | 🟡 以当前环境 `--verify` 为准 | 四条 Job 的 `--verify` 输出 + 每分钟 Job 的 `lastAttemptTime` 推进 + 订阅内 tick 的 `job` attribute + 发布者全扫（逐 location 翻页）显示 ENABLED 恰为这四条 |
| O7 | Scheduler Job 失败告警 | ⬜ 待运维 | 告警规则配置 + 一次测试告警到达记录 |
| O8 | 日切 06:00 PHT 未完成告警 | ⬜ 待运维 | 告警规则配置 + 触发条件说明 |

操作要点（实测记录、证据与裁定理由见[测试 SSOT 附录 C](../MOCASA催收系统升级_Phase1_测试执行记录与问题台账.md#附录-c缺口登记)，本节不复述）：

- **创建与复验一律用 `scripts/test/provision-scheduler.py`**（幂等，`--dry-run` / `--verify` / `--pause`），不要手敲 gcloud：漏 `--attributes="job=..."` 会让四条 Job 全部空转、触达链路静默停摆，而 Job 执行记录仍显示成功。
- **location 必须与当前环境已验证的 Job 一致**。Cloud Scheduler 的 Job 可分布在多个 location；不得仅凭项目名假定唯一 location。
- **资源用生产名，不加 `-pilot` 后缀**：重复创建会 `ALREADY_EXISTS` 显性失败；两套并存则是同一 topic 双发 tick，只能靠指标异常事后发现。
- **四条 Job 全程保持 `ENABLED`**，T3o→T6 不需要暂停（`--pause` 只用于「T3o 长期推迟、不想让 tick 空转」，恢复后须 `--verify` 确认四条均 `ENABLED`）。
- **调度订阅只允许应用一个 subscriber**：Pub/Sub 同订阅是竞争消费，任何验证用 subscriber 都会分走 tick，取证请另建独立订阅。
- **发布者归属**：正式入口、旧 Job 的暂停或删除均以当次 `provision-scheduler.py --verify` 的全量输出和运维变更单为准；删除前必须确认不存在自动化重建或其他业务用途。
- **排查发布者要逐 location 扫**：Cloud Scheduler 的 Job 可分布在多个 location（本项目 `asia-northeast1` + `asia-southeast1` 都有）。只查一个 location 会把同项目的 Job 误判成来源不明的发布者。

T3o 开始前必须满足：

1. T3 已通过，且未关闭项已明确不影响 T3o。
2. [交接板 D.1](../../../HANDOFF.md#d1-生产就绪差集登记)的阻断项均已关闭；仅记录差集而未修复，不得宣告 Pilot 完成。
3. T3o 测试白名单、sandbox 地址、回滚责任人和变更窗口均已审批。

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

`application-pilot.yml` 只写 `${COLLECTION_REDIS_*}` 占位符，仓库不含真值。配置生效优先级为 **环境变量 > `application-pilot.yml` > Nacos 导入配置**：

| 优先级 | 位置 | 说明 |
|---|---|---|
| 高 | 部署机 `.env.pilot`（已 gitignore，`pilot-run.sh` 经 `--env-file` 注入） | 所有 `${...}` 占位符从这里取值。含 `#` 等特殊字符的口令**必须加双引号**，否则可能被当作行内注释截断；`docker --env-file` 不做 shell 解析，引号会进值里，注意区分两种加载方式 |
| 中 | `application-pilot.yml`（profile 专属，随镜像走） | 写进这里的键**无法被 Nacos 覆盖**，`spring.redis.*` 全在此列 |
| 低 | Nacos `intelligent-collection-pilot.yml`（`optional:` 导入，未创建不阻塞启动） | 只对**没有**写进 `application-pilot.yml` 的键生效，如 `channel.facade.api-key` / `callback-secret` |

**因此 Redis 地址与口令不能靠 Nacos 轮换**：改 Nacos 后重启，容器仍用 `.env.pilot` 里的旧值，且不报错。轮换路径是改 `.env.pilot` + 重启容器。

> 同一口径在代码里有两处佐证：`application-pilot.yml` 注释说明 `facade.api-key`/`callback-secret` 之所以**故意不写占位符**，正是因为写了会把 Nacos 下发的值冲掉；`application-local.yml` 也注明 profile 专属文件优先级高于 Nacos 被导入文档。
>
> **待 Pilot 机实测确认**（无副作用、一条命令可判读）：在 Nacos `intelligent-collection-pilot.yml` 里写 `management.endpoints.web.exposure.include: health`（该键在 `application-pilot.yml` 中是字面量 `health,info,prometheus`，不涉及环境变量），重启容器后 `curl -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/prometheus`：得 **200** 即上表成立（profile 文件胜出），得 **404** 则说明 Nacos 胜出、上表需推翻。测完删掉该 Nacos 键并重启还原。

> `spring.redis.timeout` 必须大于消费轮询的 `XREADGROUP BLOCK`（当前 1s），Pilot 取 2s；短命令（合规 Lua 等）的延迟上界由 `SpiInvoker` 的 50ms 硬超时兜底，不靠客户端超时收紧。

容量规模与 Consumer 参数以[基础设施规范附录 B](../../MOCASA催收系统升级_Phase1_基础设施交互规范.md#附录-b容量基线与生产技术准入)的回填结论为准，不使用旧“5,000 在催”口径定版。

## 5. 部署与配置预检

### 5.1 应用配置

以 `application-pilot.yml` 为基线，部署前核对：

- `collection.eventbus=redis`、`collection.idempotency=redis`；
- Redis Stream、Consumer Group 与 Consumer Name 已注入，Consumer Name 在组内唯一；
- T3o：`collection.ingestion.enabled=true`，且 `collection.ingestion.loan-id-whitelist` 为非空测试名单；T4：同配置替换为批准的固定 50 案名单，并双人复核；
- `collection.scan.case-id-whitelist` 非空（环境变量 `COLLECTION_SCAN_CASE_IDS`）。到期扫描不经过接入白名单、也没有租户维度，只配接入名单不足以把处理范围限制在批准案件内；`ScanIsolationGuard` 在 pilot 下会拒绝空名单启动，T6 全量切换时以 `collection.scan.allow-full-scan=true` 显式放开；
- `collection.scheduler.enabled=true`，`project-id` 与 `subscription` 已注入且订阅**不同于**案件接入订阅；
- Webhook 必须启用签名校验；
- T3o 渠道不得回退到 mock，且 sandbox/测试地址与限频已生效；T4 必须切换为批准真实地址，仍保持限频；
- `collection.repayment-url-template` 已显式配置（环境变量 `COLLECTION_REPAYMENT_URL_TEMPLATE`，Phase 1 取 App 官方短链 `https://mocasa.com/s/4cTu`）。该值会原样渲染进 SMS 正文、Push `deep_link` 与 Email `payment_link`，漏配会落到代码默认模板，客户点开是打不开的链接；
- `channel.compliance.daily-total-limit=5`、`daily-limit.AI_CALL=2`。里程碑日单案槽位是 08:00 SMS / 09:15 AI / 12:00 Push / 14:00 Email / 14:30 AI 共 5 次，沿用默认的合计 3 次会让当天后两个槽位被静默拦掉；
- `t_script_template` 中 **SMS 10 槽 + Push 7 槽全部 ACTIVE**（`db/seed-phase1-config.sql`）。`DefaultStepResolver` 对缺槽是跳过不发（与 Email 一致），漏配不会发占位串但会静默少触达，故 `PilotReadinessValidator` 在启动时逐槽校验并列出缺失项；
- `t_contact_plan_template` 使用 **dayBlocks 绝对槽位**而非 `delayMin`。`delayMin` 是 L4 联调节奏（步骤间隔 1 分钟），在真实客户上会造成 3 分钟内连收 3 条；
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
> `--message-body` 内容不参与路由，路由**只看 `job` attribute**；未知或缺失 `job` 取值会被记录并 ack，不重投。
>
> **两个易错点**：①`--attributes="job=..."` 不可省——把 `job` 只写进 body（如 `--message-body='{"type":"scheduled-tick","job":"planStepDue"}'`）不会生效，四条 Job 全部空转，触达链路静默停摆。②调度订阅（O2）**不得**被其他订阅者共用：Pub/Sub 同订阅是竞争消费，任何自建的验证用 subscriber 挂在同一 subscription 上都会分走 tick，造成随机漏扫；验证工具须使用独立 subscription。
>
> 创建后逐条自检，确认 `attributes` 与 `timeZone` 落库正确：
>
> ```bash
> for j in collection-plan-step-due collection-callback-timeout \
>          collection-daily-roll-open collection-daily-roll-continue; do
>   gcloud scheduler jobs describe "$j" --project=<PROJECT_ID> --location=<REGION> \
>     --format="table(name, schedule, timeZone, pubsubTarget.topicName, pubsubTarget.attributes)"
> done
> ```

### 5.2 启动预检

1. 校验应用到 Redis、MySQL、案件订阅、调度订阅和渠道的网络与鉴权。
2. 启动应用并确认 health 为 UP、配置加载无缺失、Consumer Group 初始化可重复执行；日志出现 `[Scheduler] 调度订阅消费已启动`。
3. 在 Redis 中检查 Stream、Consumer Group、Consumer Name 与 `collection:ingestion:*` 去重键；记录脱敏配置快照。
4. 验证 T3o 简版观测 MVP：关键成功/失败路径可查到 event/case/plan/step 关联证据。三个入口逐一确认——
   - 指标：手工抓一次 `/actuator/prometheus`（只绑回环，经 SSH 隧道）；
   - 关联证据：`/ops/evidence/redis` 应返回 Stream 长度、PEL 深度与最老条目 idle、DLQ 深度、消费去重键样本、工作池水位与日切游标/完成标记；随后用一个真实 `eventId` 与 `caseId` 各调一次 `/ops/evidence/event/{eventId}`、`/ops/evidence/case/{caseId}`，确认 inbox 的 `projectionApplied` / `publishStatus` 与派生计划、步骤能连起来；
   - 日志：确认调度日志带 `job` / `scanId` / `msgId`，步骤日志带 `eventId` / `caseId` / `planId` / `stepId` / `channel`。
5. **调度巡检**（迁出 XXL 后无调度控制台执行记录页，这一步不可省）：在观测 MVP 中验证计划任务的触发、失败、陈旧消息和完成状态可查询。自动抓取与告警通道可后置，最迟在移除白名单（T6 准入）前闭合；但基础查询能力缺失时不得进入 T4。

#### 调度排障速查

| 现象 | 首查 | 次查 |
|---|---|---|
| `collection.schedule.triggered` 不增长 | Cloud Scheduler Job 是否 `ENABLED` 且最近执行成功（O6/O7） | 调度订阅未确认消息数是否堆积；应用日志是否有 `[Scheduler] 调度订阅消费已启动` |
| 启动即失败并提示 `collection.scheduler.subscription` | 环境变量 `GCP_SCHEDULER_SUBSCRIPTION` 未注入 | Nacos `intelligent-collection-pilot.yml` 是否覆盖了占位缺省 |
| 启动即失败并提示 `TriggerScanner` | profile 里混入了 `local`/`test` | 确认 `SPRING_PROFILES_ACTIVE` 只含 `pilot` |
| 重启后 `stale.discarded` 一次尖峰 | 正常：停机期间累积的 tick 被丢弃，属预期防抖 | 若持续增长则为消费跟不上，查 ack deadline 与扫描耗时 |
| `skipped{reason=UNKNOWN_JOB}` 增长 | **已知项**：调度主题上的第二个发布者发的是畸形 tick（见 §3.2 与 SSOT 附录 C），首次消费时稳定增长属预期 | 排除已知项后，再查自家 Job `--attributes` 里的 `job` 拼写 |
| `skipped{reason=IN_FLIGHT}` 持续增长 | 单次扫描耗时超过触发周期 | 降 `engine.consumer.scan_limit` / `daily-roll-batch-size`，查扫描 SQL |
| 日切 06:00 未完成 | 窗口内 `triggered{job=dailyRoll}` 次数是否达到预期（约 29 次） | 游标推进速率与 `daily-roll-batch-size` |

#### 调度回滚

停调度不需要改代码或重启，两条路径任选：

1. **GCP 侧**（首选，最快）：`gcloud scheduler jobs pause <JOB>` 暂停四条 Job。应用侧不再收到 tick，扫描自然停止，已在 Stream 中的事件仍会被消费完，不丢数据。
2. **应用侧**：置 `COLLECTION_SCHEDULER_ENABLED=false` 后重启，调度消费者不装配。

> 回滚**不得**删除调度主题、订阅或 Stream/PEL/DLQ。恢复旧链路时，先确认新链路已停止 tick，再按批准 Runbook 切回。

## 6. T5 演练执行顺序

本节定义操作顺序；具体断言、状态与证据格式以测试 SSOT 的 T5/T5-R 为准。

> **2026-08-25 修订**：原顺序为“先打通生产渠道，再做可靠性演练，监控最后补”（2026-08-05 决定），在当前接线下有两处不成立，已按下列顺序调整。完整表述见[测试 SSOT §7.1](../MOCASA催收系统升级_Phase1_测试文档.md#71-执行顺序2026-08-25-修订)。
>
> - **渠道不能先于调度**：Pilot 下 `TriggerScanner` 是 `@Profile({"local","test"})` 不装配，步骤执行的唯一入口是调度订阅。调度未通时 [§6.1](#61-渠道生产连通验证清单) 的六项没有步骤可发。
> - **“监控最后补”只适用于完整监控**：可后置到 T6 的是 Prometheus 抓取、Alertmanager 与 Dashboard；简版观测 MVP（T3o-O）是 T4 阻断项，且 T5-S 多条断言本身就是读指标，观测缺失时无法判定。§5.2 第 4、5 步已将其置于启动预检，以 §5.2 为准。

1. **拓扑与安全**：验证案件与调度两条独立订阅、白名单、渠道生产/沙箱地址、限频、Webhook 签名，以及四条 Cloud Scheduler Job 的 cron、时区与 `job` 属性。此步须先确认 **O4 已交付**（应用 SA 对调度订阅的 subscriber 权限、`GOOGLE_APPLICATION_CREDENTIALS` 落位、`GCP_PUBSUB_PROJECT` 与 `GCP_SCHEDULER_SUBSCRIPTION` 非空），否则后续全部阻塞。
2. **调度专项**（T5-S1…S8）：先打通调度链路，渠道验证依赖它产生步骤。S7 日切只能在 03:35–05:55 PHT 窗口执行，单独排期。
3. **正常路径**：执行 Consumer Group 初始化、发布/消费/XACK、日切、到期触发、回调超时和受控渠道投递。
4. **可靠投递**：注入 handler 异常，验证 PEL 可见、超时认领、重投与最终 XACK。
5. **DLQ 与重放**：构造持续失败消息，验证 Redis DLQ、MySQL `t_event_dlq`、可恢复消息的受控重放、窗口外延后与不可恢复消息的终止留存。
6. **跨实例、接入去重与合规**：临时启动第二实例，验证同一幂等键只有一次获取成功；重启任一实例后验证 Redis 合规计数未清零、接入去重与结转标记仍生效、原子上限仍成立。
7. **断连与背压**：短暂阻断 Redis 后恢复连接；制造慢渠道调用与并发事件，验证其他事件继续被工作线程处理、队列有界、背压日志限速且不丢消息。Redis 与其他服务共用实例（[§4](#4-redis-资源申请与运行基线) E1），断连**不得**用重启实例的方式制造，只能 `CLIENT KILL` 我方连接或对应用容器做网络隔离。
8. **渠道生产连通**：按 [§6.1](#61-渠道生产连通验证清单) 逐渠道打通真实投递与回调。此时调度已通，步骤才发得出来。
9. **监控与容量**（可后置，最迟 T6 准入前；指**完整监控**，不含已在第 1 步前置的简版观测 MVP）：采集 PEL、Stream 长度、线程池、渠道耗时、Redis 内存、Guard Lua p99 与 fail-close 比率，回填容量基线；抓取与告警未就绪期间以 [§3.1 的人工巡检代偿](#31-运维交付物与验收证据)顶替。
10. **回滚**：停止新订阅、路由与调度；保全 Stream/PEL/DLQ/MySQL 证据；恢复旧链路；确认没有删除数据或未知重放。

### 6.1 渠道生产连通验证清单

目标是在真实供应商链路上确认“发得出、回得来、可审计、可停”。每个渠道都必须先对内部测试号码/邮箱验证，再放开批准白名单。

| 序 | 渠道 | 前置 | 验证内容 | 通过标准 |
|---|---|---|---|---|
| 1 | SMS | `channel.notification.app-key`、发送额度、菲律宾号段准入 | 对内部测试号发一条 S1 话术 | 供应商返回成功且拿到 `providerMsgId`；`t_contact_timeline` 落一条 SENT；真机收到且文案无占位符残留 |
| 2 | PUSH | `case_push` 携带的极光 token（用户已注册时） | 有 token 与无 token 两种案件 | 有 token 走 PUSH；无 token 自动 fallback SMS 且只产生一次投递 |
| 3 | EMAIL | `channel.sendgrid.api-key`、模板 ID、发件域名验证 | 对内部邮箱发一封 | SendGrid 202；模板渲染含正确金额与还款链接；脏邮箱（空/`0`）走 Guard SKIP 不发送 |
| 4 | AI_CALL | 已完成 [测试 SSOT L2-CB 第 1–3 级](../MOCASA催收系统升级_Phase1_测试文档.md#l2-cbai_call-分级联调)；稳定 HTTPS 回调地址、HMAC secret、真实 Adapter 与批准测试号码就绪 | 一次呼叫 + 回调 | 步骤保持 `STEP_EXECUTING` 等回调；回调经签名校验后推进；不回调时哨兵按 `callback_timeout_minutes` 收敛为 FAILED |
| 5 | 回调与审计 | Webhook 公网可达、签名开启 | 重复回调与伪造签名 | 重复回调幂等不重复计次；签名错误被拒并留审计 |
| 6 | 合规与停止 | 触达窗口、日上限、白名单 | 窗口外触达与超限触达 | 窗口外不发送（延后或 SKIP）；超限被 Guard 拦截并写 `COMPLIANCE_BLOCKED`；关闭开关后立即停止发送 |

> 金额、姓名等渲染值来自计划快照；部分还款后须确认下一步话术里的金额已刷新（对应测试 SSOT L4a-3b）。

> DLQ redrive 仅接受管理后台登录态提交的显式 eventId 列表与必填原因；`MAX_DELIVERY_EXCEEDED` 最多重放三次，解析失败/无 Handler 直接终止，触达窗口外的 `PLAN_STEP_DUE` 计入 `deferred` 并保持 `PENDING`。T3o 必须先完成可查询的简版观测 MVP；Prometheus 抓取及 Alertmanager 通知最迟在移除白名单（T6 准入）前闭合，代偿期按 §3.1 人工巡检。

## 7. 待完成项与闭合口径

以下项来自[交接板 D.1](../../../HANDOFF.md#d1-生产就绪差集登记)；除标注“可后置”的项外，未关闭不得将 T3o 标记为完成。简版观测 MVP 是 T4 阻断项；完整抓取/告警/Dashboard 是 T6 阻断项（移除白名单前闭合），T3o–T5 期间以人工巡检代偿。

DLQ 状态机、窗口门控、Redis Lua 频控、事件消费去重和日切 keyset 游标已有代码/测试基础；简版观测指标、结构化证据和查询能力仍须按测试 SSOT T3o-O1…O4 实施并验证，不能假定已完成。

已在 2026-08-05 关闭、不再列为待办：`t_event_dlq` 的 redrive 列迁移已在催收库执行并核对（`redrive_count` / `redrive_reason` / `redriven_at` / `terminated_at` 四列存在）；`t_contact_plan`、`t_contact_plan_step`、`t_contact_timeline`、`t_decision_log`、`t_user_device_token` 与所需列、唯一索引均已存在；Redis key 已统一到 `collection:` 根命名空间，`collection:pilot:*` 临时前缀取消。

| 待完成项 | 闭合口径 | 责任模块 |
|---|---|---|
| Redis Consumer 并发与背压 | 真实 Redis 上验证慢渠道不阻塞其他事件，并压测定版线程数、队列容量与告警阈值 | collection-engine + 运维 |
| DLQ 持久化与重放 | Pilot MySQL/Redis 上跑通 R5/R6：落表、受控 redrive、窗口外延后、终止留存与告警到达 | common / engine / service + 运维 |
| Redis 合规频控 | 跨实例上限与真实 Redis 断连 fail-close 在 Pilot 验证并告警到达 | collection-channel / engine + 运维 |
| 接入去重连续性 | 重启应用后 `dedup:msg` / `last-seen` / `ingested` 仍生效；结清后可再次入案 | ingestion + 运维 |
| 渠道生产连通 | [§6.1](#61-渠道生产连通验证清单) 六项全部通过并留存投递与回调证据 | 编排同事 + 主架构 |
| 事件消费去重 | 真实 Redis 上验证 PEL 重投与同一 `eventId` 重放只执行一次业务，`collection:processed:*` 按 24h 过期 | collection-engine + 运维 |
| 调度通道生产化 | [§3.2](#32-调度交付清单o1o8) 剩余项交付：**O4**（应用 SA 对调度订阅的 subscriber 权限与凭证落位）、**O7/O8**（Scheduler 失败与 06:00 未完成告警）；并处置第二个发布者，使同一 `job` 只剩一个发布者。O1/O2/O3/O5/O6 已闭合。此外在 Pilot 上确认 Redis keyset 游标、当日完成标记与幂等行为 | ingestion / admin / 运维 |
| 简版观测 MVP（T4 阻断） | 按 T3o-O1…O4 提供可查询的接入、投影、调度、渠道和 Redis 证据；隔离故障注入验证通过。**代码已于 2026-08-25 交付**（`/ops/evidence` 四个只读端点、调度链路 `job`/`scanId`/`msgId` 与步骤链路 `planId`/`stepId`/`channel` 的 MDC、日切游标与完成标记查询）；剩余为 Pilot 上的注入取证 | 主架构 |
| 完整可观测性（T6 阻断，移除白名单前） | Prometheus 抓取、Alertmanager 路由与 Dashboard 接通，告警到达演练通过；闭合前每日人工巡检记录连续无缺口 | 运维 + 主架构 |
| 凭证一次性轮换（T4 阻断） | 联调期间 Redis 口令、渠道与第三方 API Key 曾以明文出现在协作记录与 `deploy/nacos/backup-*.yml` 中。进入 T4 前统一轮换一次（2026-08-21 决定：不逐项处理），确认历史明文全部失效，并核对仓库与备份文件不再含真值 | 运维 + 主架构 |

## 8. 证据归档与阶段完成判定

证据包至少包含：

- 脱敏后的配置与 Secret 注入确认；
- Redis、案件订阅、调度订阅与 Cloud Scheduler、渠道 sandbox 的连通与权限确认；
- T3o/T5-R/T5-S 测试记录、相关日志、简版观测证据和 SQL 摘要；
- 容量基线与告警阈值；
- 回滚演练记录、责任人与完成时间。

T3o 的完成要求其全部出口及简版观测 MVP 通过，之后才可提交 T4 准入。T4 通过固定 50 案与三日循环后才可提交 T5；每次 T5 放量前须有当日人工巡检与对账记录。完整抓取、阈值告警和通知路由最迟在申请移除白名单（T6 准入）前闭合，并留存告警到达证据。

## 9. T4 固定 50 案与 T5 渐进切量操作

### 9.0 T3o → T4 之间必须「停掉」的测试资产

调度侧**不需要**停任何东西：四条 Cloud Scheduler Job 就是生产配置本身，T3o / T4 / T5 / T6 全程保持 `ENABLED`（`provision-scheduler.py --pause` 只在「T3o 长期推迟、不想让 tick 空转」时用，进入 T4 前必须确认已恢复且 `--verify` 四条均 `ENABLED`）。要停的是**测试期专用资产**，它们共用生产 topic，留着就有向真实客户触达或污染对账的风险：

| 要停 / 清理的 | 为什么 | 动作 |
|---|---|---|
| L4 合成消息发布 | L4a/L4b 脚本会向 topic 发合成 `caseEvent`；T4 起生产 topic 只允许数仓发真实消息 | 停止运行 `publish-test-messages.sh` / `l4b-official-test.sh`；契约已明确禁止注入 |
| `intelligent-collection-cases-test1` topic 与 `-sub` | L4a 合成源，T4 无用 | `provision-l4-pubsub.py --delete` |
| `intelligent-collection-cases-v1-l4b-sub` | 挂在**生产**案件 topic 上的测试订阅，留着会持续堆积真实案件消息（保留 1 天）并让对账多一个口子 | 同上（该脚本一并回收） |
| `*-observer-tmp` 订阅 | 临时观测用；本身有 7 天空闲自动过期，但不应带进 T4 | `observe-upstream-topics.py --delete` |
| `collection.ingestion.fault-injection-enabled` | 故障注入开关 | 确认为 `false`（§5.1 已列） |
| `collection.scan.case-id-whitelist` / `collection.ingestion.loan-id-whitelist` | T3o 用的是 L4a 的 12 个合成案号 | **替换为批准的 50 案名单并双人复核**；两个白名单都要改——一个管接入、一个管到期扫描 |
| `local` / `test` profile 与 `/mock/**` | 双调度入口与 mock 触达 | `SPRING_PROFILES_ACTIVE` 只含 `pilot`；`SchedulerEntrypointValidator` 会拒绝启动兜底 |
| 仓库根 `credentials.json`（个人 ADC） | 审计不可追溯 | 换 Pilot 专用服务账号，见 §7 凭证一次性轮换 |

### T4 执行清单

1. 业务冻结并审批脱敏 50 案清单、渠道、触达窗口与值守责任人；名单外消息必须 ACK 跳过。
2. 运维与主架构双人复核 Nacos 的案件/调度订阅、Redis、白名单及 `collection.notification.owner`；数仓只按正式契约发布真实消息，禁止向生产 topic 注入测试消息。
3. 每案归档批准编号、脱敏 caseId、eventId/version 或 occurredAt、预期/实际计划、providerMsgId、inbox/投影/timeline 证据和结论。
4. 连续至少三个完整日循环；每日按 `dataType` 对账 Publisher 发布、消费 ACK/NACK/poison/dedup、inbox、投影及触达。

任一错误/非白名单触达、重复 `providerMsgId`、漏停催、越窗/越限、PII 泄露或无法解释的对账差异，立即暂停下一批。

### T5 每日 cap 放量

每次放量使用审批的 `cap[n]`，不在本手册预设具体数量或比例。每批前执行最小回归包（全仓单测、L4b-9/10/11/12 子集、T5-R12、当日对账），并由业务、运维和主架构确认上一个观察窗无 P0/P1 事故。失败时先停止新增路由/调度和渠道发送，保全证据，恢复旧 owner，再从上一个已证明安全的 cap 继续。

