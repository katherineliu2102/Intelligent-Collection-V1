# MOCASA 催收系统升级 — Phase 1 T3o 执行取证手册

把 T5-S、T5-R 与 T3o-O 逐条落成可照抄的注入与断言命令。判定口径以[测试 SSOT](../MOCASA催收系统升级_Phase1_测试文档.md) 为准，本手册只管「怎么做出来、证据在哪取」。执行顺序见测试 SSOT §7.1；环境准备与回滚见 [T5 Pilot 手册](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md)。

真实主机名、账号与口令一律不入库，见 gitignore 的 `docs/ops/生产访问凭据.local.md`。

---

## 0. 会话准备

应用只绑回环，管理面与观测端点都要经 SSH 隧道：

```bash
ssh -L 8080:127.0.0.1:8080 <SSH_USER>@<PILOT_HOST>
```

`/ops/**` 需要管理后台登录态，先拿 cookie，后续命令复用：

```bash
BASE=http://127.0.0.1:8080
curl -s -c /tmp/t3o.jar -H 'Content-Type: application/json' \
  -d '{"username":"<ADMIN_USER>","password":"<ADMIN_PASSWORD>"}' \
  "$BASE/auth/login"

# 之后一律带 -b /tmp/t3o.jar
ev() { curl -s -b /tmp/t3o.jar "$BASE/ops/evidence/$1"; }
```

Redis 在 **db3**（`collection:*` 全在这里，db0 是别的服务的），命令一律带 `-n 3`：

```bash
R="redis-cli -h <REDIS_HOST> -p 6379 -n 3"
STREAM=collection:pilot:events
GROUP=collection-engine-pilot
```

> db3 内可以 `FLUSHDB` 做状态重置；**db0 与整实例的 `FLUSHALL` 一律禁止**，会打掉邻居服务（台账 E1）。

取证快照统一用这三条，每个用例前后各抓一次做差：

```bash
ev redis | python3 -m json.tool                       # Stream/PEL/DLQ/去重键/日切/注入状态
curl -s "$BASE/actuator/prometheus" | grep '^collection_'
$R XPENDING $STREAM $GROUP
```

`ev redis` 的响应外层是 `{success, data, timestamp}`，下文写 `eventBus.pendingTotal` 一律指 `.data.eventBus.pendingTotal`。取单个字段：

```bash
evq() { ev redis | python3 -c "import json,sys;d=json.load(sys.stdin)['data'];print(eval('d'+''.join('[\"%s\"]'%k for k in '$1'.split('.'))))"; }
evq eventBus.pendingTotal
evq dailyRoll.cursor
```

---

## 1. 启动预检与 T5-R1

调度先关着起一次，确认基础链路，再开调度。

```bash
# .env.pilot 里 COLLECTION_SCHEDULER_ENABLED=false
deploy/pilot-run.sh
curl -s "$BASE/actuator/health" | python3 -m json.tool     # 期望 status=UP，各依赖逐项 UP
```

**T5-R1（消费组初始化幂等）**：重启一次，断言消费组仍在、且**不因建组行为自身产生 DLQ 记录**（F4 的回归点）。

```bash
$R XINFO GROUPS $STREAM                     # 期望 name=collection-engine-pilot
DLQ_BEFORE=$($R XLEN ${STREAM}:dlq)
deploy/pilot-run.sh                          # 再起一次
$R XINFO GROUPS $STREAM
[ "$($R XLEN ${STREAM}:dlq)" = "$DLQ_BEFORE" ] && echo "R1 PASS：重启未新增 DLQ"
```

### 配置优先级探针（T3o-2）

验证「环境变量 > `application-pilot.yml` > Nacos 导入配置」。在 Nacos 把 `management.endpoints.web.exposure.include` 改成 `health`（比 yml 里的 `health,info,prometheus` 少），重启后：

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/actuator/prometheus"
# 200 = application-pilot.yml 胜出（预期）；404 = Nacos 胜出，与文档不符须停下来查
```

验完把 Nacos 那条改回去。

---

## 1b. 触达隔离配置（T3o-4 前置）

**开调度之前必须先配好，否则第一条到期步骤就会真实发给借款人。** 口径见[测试 SSOT §7.4](../MOCASA催收系统升级_Phase1_测试文档.md#74-t3o-4-触达隔离口径2026-08-25-修订)：四个渠道一律靠 Adapter 出口强制改投，不依赖上游数据。

渠道开关在 Nacos 的 `channel:` 块下（**合并进已有的 `channel.notification` / `channel.sendgrid`，不要追加新的顶层 `channel:` 键**，会撞 `DuplicateKeyException` 导致启动失败）：

```yaml
channel:
  notification:
    sms-test-recipient: "+639451374358"      # 自持号 A；号 B +639451373897 属不同运营商号段
    push-test-token: 1a0018970bf0c19de04
  sendgrid:
    test-recipient: wzynju@126.com
  facade:
    test-callee: "+639451374358"
```

> `sms-test-mode` 与隔离**无关**，它只免签名。真正改投的是 `sms-test-recipient`。

发布后重启，确认启动日志四条齐全：

```bash
docker logs collection-admin 2>&1 | grep 'PilotReadiness'
# 期望看到 sms-test-recipient / push-test-token / sendgrid.test-recipient / test-callee 四条
```

只有四条齐全 + 案件白名单非空 + `allow-full-scan=false`，才允许把 `COLLECTION_SCHEDULER_ENABLED` 打开。

---

## 2. T5-S 调度专项

前置：`COLLECTION_SCHEDULER_ENABLED=true`、`GCP_PUBSUB_PROJECT` 与 `GCP_SCHEDULER_SUBSCRIPTION` 非空、`GOOGLE_APPLICATION_CREDENTIALS` 落位、应用 SA 对调度订阅有 subscriber。缺任一项 `pilot-run.sh` 会点名拒启。

手工发 tick 一律带 `job` 属性（应用只按属性路由，不看消息体）：

```bash
tick() { gcloud pubsub topics publish intelligent-collection-schedule-v1 \
           --message=scheduled-tick --attribute=job="$1"; }
sched() { curl -s "$BASE/actuator/prometheus" | grep '^collection_schedule_'; }
```

| 用例 | 注入 | 断言 |
|---|---|---|
| **S1** 链路连通 | 等两个周期（约 2 分钟），不手工发 | `collection_schedule_triggered_total{job="planStepDue"}` 与 `{job="callbackTimeout"}` 各 +2；`collection_schedule_failed_total` 全为 0 |
| **S8** 订阅独占 | `python3 scripts/test/provision-scheduler.py --verify` | 输出里调度订阅**无第二个订阅者**；四条 Job 的 `describe` 含正确 `job` 属性与 `Asia/Manila`；`triggered` 增速与 cron 一致（不被分流） |
| **S2** 属性路由 | `tick planStepDue`；`tick callbackTimeout`；`tick dailyRoll` | 三个 `triggered{job=...}` 各自 +1，互不串扰 |
| **S3** 未知/缺失 job | `tick bogusJob`；再发一条**只把 job 写在消息体、不带 attribute** 的：`gcloud pubsub topics publish intelligent-collection-schedule-v1 --message='job: planStepDue'` | 两次都使 `collection_schedule_skipped_total{reason="UNKNOWN_JOB"}` +1，且 `collection_schedule_triggered_total` **不**增长；日志有未知调度任务告警并带 `msgId` |
| **S5** 重复投递 | 连发两条 `tick planStepDue` | 两条都被 ACK；因步骤幂等不产生重复触达——查 `/ops/evidence/plan/{planId}` 的 timeline 无重复 SENT |
| **S6** 并发单飞 | 同一秒连发多条 `tick dailyRoll` | 只有一次真扫描：`collection_schedule_skipped_total{job="dailyRoll",reason="IN_FLIGHT"}` 增长，日志里多个 `scanId` 中只有一个走到「扫描完成」 |
| **S4** 陈旧丢弃 | 停容器 → 让 tick 积压 3–5 分钟 → `deploy/pilot-run.sh` 重启 | 重启后 `collection_schedule_stale_discarded_total` 出现**一次尖峰后归零**，无扫描风暴；持续增长说明消费跟不上 |
| **S7** 日切窗口 | 只能在 **03:35–05:55 PHT** 真窗口执行，单独排一天 | 窗口内 `collection_schedule_triggered_total{job="dailyRoll"}` 约 29 次；`dailyRoll.cursor` 逐页推进、`dailyRoll.lastAdvancedAt` 持续更新；06:00 前 `dailyRoll.completedToday=true` |

> S4 会打断连续运行，务必排在 S1/S2/S3/S5/S6 之后。
>
> `collection_schedule_skipped_total{reason="UNKNOWN_JOB"}` 在 S3 之外**不应**增长。若平时也在涨，说明调度 topic 上还有第三个发布者（前两个已在 2026-08-21 处置），按台账那条的逐 location + 翻页方法全扫。

---

## 3. T5-R Redis 专项

R3–R6、R13 依赖受控故障注入。开关默认关闭且需重启才能开：

```bash
# .env.pilot 加 ENGINE_FAULT_INJECTION_ENABLED=true，然后 deploy/pilot-run.sh
curl -s -b /tmp/t3o.jar "$BASE/ops/fault-injection"        # 期望 enabled=true, armed=false

arm() { curl -s -b /tmp/t3o.jar -X POST \
  "$BASE/ops/fault-injection/arm?position=$1&eventType=$2&remaining=$3"; }
armId() { curl -s -b /tmp/t3o.jar -X POST \
  "$BASE/ops/fault-injection/arm?position=$1&eventId=$2&remaining=-1"; }
disarm() { curl -s -b /tmp/t3o.jar -X DELETE "$BASE/ops/fault-injection"; }
```

> 注入必须靶向（给 `eventType` 或 `eventId`），接口拒绝无靶向武装——Pilot 上真实事件与演练事件在同一条流里，无靶向会把无关案件推进 DLQ 且事后分不清来源。
>
> 时序：PEL 认领要等 `pel-min-idle-seconds`（默认 120s），投递上限 `max-delivery-count`（默认 5）。所以 `MAX_DELIVERY_EXCEEDED` 约需 **8 分钟**，不是卡住。想缩短就在本组期间临时下调 `collection.redis.pel-min-idle-seconds`，并在台账记为环境偏差。

| 用例 | 注入 | 断言 |
|---|---|---|
| **R2** 正常消费 ACK | 不注入，等真实事件流过 | `eventBus.pendingTotal` 回到 0；`collection_event_consumed_total` 增长 |
| **R3** handler 异常滞留 PEL | `arm BEFORE_HANDLER PLAN_STEP_DUE 1` 后 `tick planStepDue` | `eventBus.pendingTotal` +1，`eventBus.oldestPending[0]` 的 idle 持续增长、`deliveryCount=1`；日志 `handler failed ... leaving pending` |
| **R4** 认领与重投 | 承接 R3，`disarm` 后等待 | idle 未到阈值时不认领；超 120s 后被认领、`deliveryCount` +1，业务成功后 `eventBus.pendingTotal` 归 0 |
| **R13** 消费去重 | `arm AFTER_HANDLER PLAN_STEP_DUE 1` 后 `tick planStepDue` | 业务执行了但未 ACK；重投时命中去重——`collection_event_deduped_total` +1，日志 `duplicate delivery skipped`，**业务不重复执行**（timeline 无第二条） |
| **R5** 毒消息进 DLQ | 取一个 eventId：`ev redis` 看 `eventBus.oldestPending`。`armId BEFORE_HANDLER <eventId>`（不限次），等约 8 分钟 | `deliveryCount` 涨到 5 后移出 PEL、`eventBus.dlqSize` +1；`t_event_dlq` 落一行 `failure_reason=MAX_DELIVERY_EXCEEDED` 且保留原始信封 |
| **R6** DLQ 落库与重放 | 承接 R5，`disarm` 后经管理后台提交该 eventId 的 redrive（必填原因） | 可恢复项重放成功；不可恢复项终止留存；触达窗口外的 `PLAN_STEP_DUE` 计 `deferred` 并保持 `PENDING`；`MAX_DELIVERY_EXCEEDED` 最多重放三次 |
| **R14** 发件箱兜底 | 制造一次即时发布失败（短暂切断 Redis，见 R9 的断连方式） | `collection_outbox_republished_total` +1；全程只发出一次触达 |
| **R7** 幂等锁跨实例 | 用不同 `HOSTNAME` 临时起第二个容器（**不要**用 `collection-admin` 这个名字，脚本会拒绝并存实例；本用例是唯一例外，手工 `docker run` 并在结束后立即删除） | 同一幂等键只有一次获取成功；TTL 到期后可再获取 |
| **R8** 合规频控连续性 | 触达到接近日上限后 `deploy/pilot-run.sh` 重启 | 重启后计数**未清零**，仍按 `CHANNEL_DAILY_LIMIT_*` 拦截；边界并发只放行一个；Redis 断连时 fail-close（记 `collection_step_skipped_total{reason="GUARD_ERROR"}`，与合规拦截的 `COMPLIANCE_BLOCKED` 是两回事） |
| **R12** 接入去重跨重启 | 与 R8 共用那次重启 | 重复消息仍被拦截；相同快照跳过；陈旧还款不覆盖新投影 |
| **R10** 并发与背压 | 制造慢渠道调用 + 并发事件 | `eventBus.consumerPool` 的队列深度有界、剩余容量不为负；其他事件继续被处理；背压日志限速；不丢消息 |
| **R9** 断连恢复 | **不得重启 Redis 实例**（共用，会打断邻居服务）。用 `$R CLIENT KILL LADDR <应用连接>` 或对容器做网络隔离 | 恢复后消费与认领自愈、无需重启、事件不丢；`eventBus.consecutiveConsumeFailures` 回落到 0 |
| **R11** 指标与 MDC | 抓一次指标 + 翻一段日志 | 关键指标可抓取；调度日志带 `job`/`scanId`/`msgId`，步骤日志带 `eventId`/`caseId`/`planId`/`stepId`/`channel` |
| **R15** 停摆巡检 | 构造一个非终态但不会被任何扫描拾取的计划 | 仅计数告警（`collection_plan_stuck_total`）；不改库、不新增触达；正常计划不误报 |

**本组结束后必须 `disarm` 并把 `ENGINE_FAULT_INJECTION_ENABLED` 置回 false 重启**，再确认 `eventBus.faultInjection.enabled=false`。

---

## 4. T3o-O 简版观测

不需要单独构造场景——O1–O4 的证据在上面各组执行时顺带取到即可，但要逐条留档。

| ID | 取证动作 | 留档内容 |
|---|---|---|
| **O1** 正常消息消费 | 取一个真实 eventId：`ev event/<eventId>`；再 `ev case/<caseId>` | inbox 的 `projectionApplied` / `publishStatus` / `publishedAt`，投影行，派生的 plan 与 steps，能连成一条链 |
| **O2** 发布失败与补发 | 用 R14 那次注入的证据 | `publishStatus` 由 `PENDING` 迁移到已发出；`collection_outbox_republished_total` 增量；响应中**无** payload 原文与姓名 |
| **O3** 调度/渠道/日切异常 | `ev plan/<planId>`；S7 的 `ev redis` 快照 | 步骤 `status=SKIPPED` 时 `result` 给出跳过原因（`COMPLIANCE_BLOCKED` / `GUARD_ERROR` 是两回事）；渠道失败与回调审计可关联到 plan/step；日切游标与完成标记 |
| **O4** PEL/DLQ 与重复投递 | R3→R5 全程每步 `ev redis` | `eventBus` 的 `streamLength`、`pendingTotal`、`oldestPending` 的 idle 与 deliveryCount、`dlqSize`、`dedupKeys` 样本的连续演进；以及 `dlqByStatus` 的结案分布 |

> 证据面出参不含 `payload` / `context_snapshot` / `resolved_params` / `content_summary` / `canonical_payload` / `borrower_name` / `push_token`，电话邮箱已脱敏。需要原文时按返回的主键到库里单独取，并在台账留一笔。

---

## 5. T3o-7 Pub/Sub 死信

前置（**改变接入链路的失败行为，须显式执行并记录**）：

```bash
python3 scripts/test/provision-scheduler.py --fix-cases-dlq --dry-run   # 先看将做什么
python3 scripts/test/provision-scheduler.py --fix-cases-dlq
python3 scripts/test/provision-scheduler.py --verify                    # 确认 deadLetterPolicy 已挂
```

脚本会一并补两条授权（死信 topic 的 publisher、源订阅的 subscriber）。**缺任一条，转投会静默失败**：消息继续重投、死信 topic 空空如也，看起来像「没有失败」。

演练：向案件 topic 发一条能被解析、但处理必然瞬态失败的白名单消息，令其连续 nack 超过 `maxDeliveryAttempts=5`。

断言：消息进入 `intelligent-collection-cases-dlq`，**原始 payload 与 `eventId` 保留**；修复后从 `intelligent-collection-cases-dlq-sub` 受控重放，业务只执行一次。

> 注意区分两套死信，它们不是一回事：GCP Pub/Sub DLQ 管的是**接入侧**（消息还没进系统），Redis `{stream}:dlq` + `t_event_dlq` 管的是**引擎侧**（事件已入系统但处理失败）。T3o-7 验前者，T5-R5/R6 验后者。

---

## 6. 收尾

进入 T4 前必须停掉的测试资产（与 T5 手册 §9.0 合并核对）：

- `ENGINE_FAULT_INJECTION_ENABLED=false`，且 `/ops/fault-injection` 返回 `enabled=false`；
- §1b 的四个改投开关逐项清空（`sms-test-recipient`、`push-test-token`、`sendgrid.test-recipient`、`test-callee`），另确认 `sms-test-mode`——启动日志的 `[PilotReadiness]` 段会把生效中的全部列出来。**清空后触达即真实发往借款人，务必与 50 案白名单审批同步**；
- 演练产生的 DLQ 记录逐条结案（重放或终止），不留未决项;
- 证据包按 [T5 手册 §8](./MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md#8-证据归档与阶段完成判定) 归档。
