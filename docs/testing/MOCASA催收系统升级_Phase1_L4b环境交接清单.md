# L4b 环境交接清单（配置与操作 Runbook）

> 本文只保留 L4b 隔离联调的**环境配置与操作**。
> 测试准入、用例、当前状态、退出条件的唯一来源是[测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) §2 T0 / §6 T3。
> 一次运行结果写入测试报告，不得在本文作测试通过裁决。

## 1. 隔离拓扑与安全边界

| 维度 | L4b 隔离联调 | Pilot / 生产（非本 Runbook） |
|---|---|---|
| Topic | 合成源 `intelligent-collection-cases-test1`；真实源沿用 `intelligent-collection-cases-v1` | `intelligent-collection-cases-v1` |
| 新系统订阅 | 合成源 `intelligent-collection-cases-test1-sub`；真实源 `intelligent-collection-cases-v1-l4b-sub` | `intelligent-collection-cases-v1-sub` |
| 死信 | `intelligent-collection-cases-dlq(+sub)`，最大投递 5 次 | 待运维为正式订阅配置 |
| 旧 L4b（已废弃） | `collection-cases-test1` | `collection-cases` / `collection-cases-ai-v1-sub` |
| 测试数据 | `99000000`–`99000005`、`IC_TEST_*` | 批准后的灰度切片 |
| SMS / Push | `sms-test-mode=true`、test token | 按批准配置 |
| Email | 受控 126 测试邮箱 | 按批准配置 |
| 日切 | `POST /mock/daily-roll` | Cloud Scheduler → 调度 PubSub 主题（属性 `job=dailyRoll`）→ 应用侧调度订阅 |

**红线**

- 禁止向生产 topic `intelligent-collection-cases-v1`（及旧 `collection-cases`）发测试消息。**只读取、不发布**：
  真实源验证靠挂在该 topic 上的独立订阅拿消息副本，正式订阅 `-v1-sub` 的投递不受影响。
- L4b 必须使用独立测试订阅；测试前确认没有其他活跃消费者争抢本轮订阅。
- 只允许白名单测试 loan_id、测试手机/邮箱与渠道沙箱；凭证、数据库连接、白名单明细不得入仓。

## 2. 环境资源与责任人

| 资源/配置 | 联调取值或动作 | 责任人 |
|---|---|---|
| PubSub topic / subscription / IAM | ✅ 2026-08-20 由主架构自助开通，脚本 `scripts/test/provision-l4-pubsub.py`（幂等，`--delete` 可回收）；参数 ack 60s / 保留 1 天 / 闲置 7 天过期 / 死信 5 次 | 主架构 |
| GCP 凭证 | `authorized_user` ADC 或经批准的服务账号；配置应用和发布脚本 | 运维 + 主架构 |
| Nacos | 发布 L4b delta（`deploy/nacos/l4b-collection.publish.yml`）；同名顶层键已存在，须用 `scripts/dev/merge-nacos-config.py` 深合并而非追加 | 主架构 |
| 旧库 seed | `db/seed-test-cases.sql`、`seed-device-token.sql` | 主架构 + 服务同事 |
| 新库 | ✅ 2026-08-20 已在 `ai_collection_db` 执行 `db/schema.sql`（幂等），补齐 `t_ai_collection`、`t_ai_collection_inbox`、`t_event_outbox` 与 `t_contact_plan_step.original_trigger_time` / `dispatched_at` | 主架构 |
| 渠道沙箱 | SMS testSend、Push test token、测试收件人 | 编排同事 + 主架构 |

## 3. Nacos 与本地环境配置

> ⚠️ 下面是**片段**，不是可直接追加的整份配置。`intelligent-collection-local.yml` 里 `channel:`
> 与 `collection:` 各只能出现一次——现网已存在 `channel.sendgrid`，若把本片段的 `channel:` 块直接
> 追加到文件尾部，就会产生重复顶层键，SnakeYAML 抛 `DuplicateKeyException`，Spring 报成
> 「config data resource … does not exist」，应用直接启动失败（2026-07-27 已踩过一次）。
> 正确做法是把 `sms-test-mode` / `push-test-token` **合并进已有的 `channel.notification` 下**。
> 发布后跑 `./scripts/test/l4b-preflight.sh` 会自动检测重复顶层键。

```yaml
collection:
  case-service: real
  ingestion:
    enabled: true
    project-id: fintech-all
    subscription: intelligent-collection-cases-test1-sub
    loan-id-whitelist: [99000000, 99000001, 99000002, 99000003, 99000004, 99000005]
    # L4b-7 受控 NACK 注入开关：仅联调环境为 true，生产必须 false（且端点只在 local/test profile 存在）
    fault-injection-enabled: true
    case-push:
      field-map: { caseId: loanID, userId: userID, name: realName, product: appName }
channel:
  notification:
    sms-test-mode: true
    push-test-token: 1a0018970bf0c19de04
```

发布前先检查 `deploy/nacos/l4b-collection.publish.yml` 的订阅仍为测试订阅：

```bash
python3 scripts/dev/merge-nacos-config.py deploy/nacos/l4b-collection.publish.yml
python3 scripts/dev/merge-nacos-config.py deploy/nacos/l4b-collection.publish.yml --apply
```

本地环境变量：

```bash
export GCP_PUBSUB_PROJECT=fintech-all
export GCP_PUBSUB_SUBSCRIPTION=intelligent-collection-cases-test1-sub
export GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/credentials.json
source scripts/test/l4b-env.local.sh
```

`credentials.json` 当前可为用户 ADC；IAM 必须授予该实际用户账号对应权限。不要提交本地环境文件或凭证。

## 4. 操作步骤

### 4.1 预检和启动

```bash
source scripts/test/l4b-env.local.sh
./scripts/dev/start-local.sh --detach
./scripts/test/l4b-preflight.sh --strict
```

`l4b-preflight.sh` 只做环境检查；它不等于 L4b 官方测试，也不能证明业务用例通过。

### 4.2 造数与发布

```bash
mysql -h<HOST> -P3306 -u<USER> -p ai_collection_db < db/seed-test-cases.sql
mysql -h<HOST> -P3306 -u<USER> -p ai_collection_db < db/seed-device-token.sql

export GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1
./scripts/test/l4b-pubsub/publish-test-messages.sh case
./scripts/test/l4b-pubsub/publish-test-messages.sh repay 99000001
```

样例 payload：`scripts/test/l4b-pubsub/caseEvent.sample.json` 与 `repaymentEvent.sample.json`。发布脚本内置拒绝生产 topic 的护栏，并只发布 v3 单案事件。

### 4.3 手动日切

L4b 联调不接生产调度通道（`collection.scheduler.enabled=false`）。使用测试入口调用真实日切处理器：

```bash
curl -X POST http://localhost:8888/mock/daily-roll
```

建议操作顺序：

| 用例辅助操作 | loan_id | 数据调整 |
|---|---|---|
| 升档 | 99000002 | 先以 S1 建计划，再将 `overdue_days` 调为 S2 范围后触发日切 |
| 停催 | 99000005 | 先建活跃计划，再将 `overdue_days` 调至 ≥91 后触发日切 |
| 幂等 | 99000002 | 在稳定态连续触发两次日切 |

### 4.4 SQL 与人工核对

```bash
mysql ... < db/l4b-assert.sql
```

- SQL 用于核对 plan/step/timeline 与快照，不得输出未脱敏的真实联系方式。
- 触达正文与终端核对使用 [触达内容验收清单](./MOCASA催收系统升级_Phase1_触达内容验收清单.md)。
- 一次运行的日志、SQL 摘要和异常写入带日期的测试报告，并在测试 SSOT 更新证据索引；不得在本 Runbook 修改状态。

快速查看单案的投递与步骤结果：

```sql
SET @caseId = <loan_id>;

SELECT channel, direction, result, provider_msg_id, source, created_at
  FROM t_contact_timeline
 WHERE case_id = @caseId
 ORDER BY created_at;

SELECT step_order, channel_type, status, result
  FROM t_contact_plan_step
 WHERE plan_id = (SELECT id FROM t_contact_plan WHERE case_id = @caseId ORDER BY id DESC LIMIT 1)
 ORDER BY step_order;
```

| `result` | 含义 |
|---|---|
| `DELIVERED` | 供应商已受理 |
| `SKIPPED` | 未发出（如 Email 里程碑未命中） |

REST 预览（仅快速查看，不作终态裁决）：

```bash
curl -s "http://localhost:8888/plans/timeline/<loan_id>?limit=20"
curl -s "http://localhost:8888/plans/observation/by-case/<loan_id>"
```

### 4.5 查库节奏

落库是异步的，过早查询会得到假失败。建议按下表节奏核对：

| 刚完成的动作 | 立刻查什么 | 期望 |
|---|---|---|
| 发布 `caseEvent` | inbox、案件投影、`t_contact_plan` 与快照 | 投影与 inbox 一致；每案有计划；快照字段与 payload 一致 |
| 到期扫描跑完（多步案） | `t_contact_plan_step`、`t_contact_timeline` | 步骤推进，timeline 含 `provider_msg_id` |
| 发布 `repaymentEvent` | 最新 `t_contact_plan` | 全额结清取消计划；部分还款仅刷新运行态 |
| 触发日切 | 同案全部计划行 | 升档取消并新建；D91 停催取消；重复触发不新增取消 |

`db/l4b-assert.sql`（改 `@caseId`）为主要工具；REST 查询仅作快速预览，**终态裁决以 SQL 为准**。

### 4.6 受控 preview 渲染

不走 Pub/Sub、不真实投递，仅返回渲染后的正文，用于与终端实收内容比对：

```bash
curl -s -X POST "http://localhost:8888/mock/send-sms?caseId=<loan_id>"
curl -s -X POST "http://localhost:8888/mock/send-push?caseId=<loan_id>"
```

响应含 `scriptSlot` 与正文字段。内容层面的期望与判据见[触达内容验收清单](./MOCASA催收系统升级_Phase1_触达内容验收清单.md)。

### 4.7 供应商侧对账入口

| 渠道 | 凭证 | 查什么 |
|---|---|---|
| SMS | timeline 的 `provider_msg_id`（= 通知中心 requestId） | 目标手机号 + requestId |
| Email | SendGrid Activity | 收件人地址 |
| Push | 极光控制台 | 目标 token |

详见 [Notification 对接说明](../channel/MOCASA催收系统升级_Phase1_Notification对接说明.md)。

### 4.8 可重复性前置

以下任一缺失都会产生假失败，均已在官方脚本内固化：

| 前置 | 原因 |
|---|---|
| 清空白名单案的 plan / step / timeline | 上轮遗留的终态计划与旧快照会污染建计划判定与逐字段溯源 |
| 重放 `db/seed-test-cases.sql` | 升档与停催用例会改写 `overdue_days`，不重放则第二轮起点已被污染 |
| 清除上轮去重标记 | 重放同一 `eventId` 或相同内容指纹会被直接跳过。内存去重下重启应用即可清空；启用 Redis 去重后须显式清理测试专用 key 与对应 inbox 记录 |
| 停催用例紧跟建计划执行 | 停催只取消当前活跃计划；测试环境步骤延迟被压缩，计划很快转终态，延后执行将无计划可取消 |
| 重复触达断言前等案件收敛 | 扫描器持续运行，未跑完的步骤会让 timeline 在观察窗内自然增长 |

### 4.9 NACK 重投的受控注入

采用**热态注入，不重启进程**：预约一次注入后，消费者在**落库与写幂等标记之前**对下一条白名单 `caseEvent`
抛瞬态异常 → 不 ack → Pub/Sub 重投 → 第二次走完整真实路径成功。这样断言的是纯粹的重投幂等，
不会混入「内存幂等在重启后失效」这个变量（后者属 Redis 专项议题）。

三重安全约束：注入开关默认 false、只对白名单 `loan_id` 生效、每次预约只失败一次；
注入端点只在 `local` / `test` profile 存在。**生产必须为 false。**

### 4.10 审计字段与溯源边界

`t_contact_timeline` 的模板版本字段记录的是**执行时应用侧使用的模板版本**，不等同于管理后台的模板发布版本。
在两者建立稳定映射前，追溯具体文案需结合执行时间与配置快照，不能仅凭该字段回放原文。
模板 SPI 属热路径，实现方不得在其中做数据库 I/O，否则会触发 SPI 硬超时——该约定的验证归属 `collection-channel`。

## 5. 操作完成后的恢复

1. 停止本地/联调消费者，确认测试订阅不再被错误使用。
2. 如 Nacos 配置需要恢复，按已审批的环境配置恢复；不要把 L4b 测试订阅直接改为生产订阅。
3. 清理或标记测试 seed，保留脱敏运行证据。
4. Pilot/生产订阅、调度通道（Cloud Scheduler Job 与调度订阅）、切量与回滚仅按测试 SSOT T5/T6 的批准 Runbook 执行。
