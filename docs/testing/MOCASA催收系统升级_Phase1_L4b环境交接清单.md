# L4b 环境交接清单（配置与操作 Runbook）

> 本文只保留 L4b 隔离联调的**环境配置与操作**。
> 测试准入、用例、当前状态、退出条件的唯一来源是[测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md) §2 T0 / §7 T4。
> 一次运行结果写入测试报告，不得在本文作测试通过裁决。

## 1. 隔离拓扑与安全边界

| 维度 | L4b 隔离联调 | Pilot / 生产并行（非本 Runbook） |
|---|---|---|
| Topic | `collection-cases-test1` | `collection-cases` |
| 新系统订阅 | `collection-cases-test1-sub` | `collection-cases-ai-v1-sub` |
| 旧系统 | 不参与测试 topic | 继续消费 `collection-cases-sub` |
| 测试数据 | `99000000`–`99000005`、`IC_TEST_*` | 批准后的灰度切片 |
| SMS / Push | `sms-test-mode=true`、test token | 按批准配置 |
| Email | 受控 126 测试邮箱 | 按批准配置 |
| 日切 | `POST /mock/daily-roll` | XXL-Job `dailyRoll` |

**红线**

- 禁止向生产 topic `collection-cases` 发测试消息。
- L4b 必须使用独立测试订阅；测试前确认没有其他活跃消费者争抢 `collection-cases-test1-sub`。
- 只允许白名单测试 loan_id、测试手机/邮箱与渠道沙箱；凭证、数据库连接、白名单明细不得入仓。

## 2. 环境资源与责任人

| 资源/配置 | 联调取值或动作 | 责任人 |
|---|---|---|
| PubSub topic / subscription / IAM | 建 `collection-cases-test1` / `collection-cases-test1-sub`；给联调账号 publisher/subscriber | 运维 |
| GCP 凭证 | `authorized_user` ADC 或经批准的服务账号；配置应用和发布脚本 | 运维 + 主架构 |
| Nacos | 发布 L4b delta，订阅指向 `collection-cases-test1-sub` | 主架构 |
| 旧库 seed | `db/seed-test-cases.sql`、`seed-device-token.sql` | 主架构 + 服务同事 |
| 新库 | contact plan/step/timeline 等表可用 | 服务同事 + 运维 |
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
    subscription: collection-cases-test1-sub
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
./scripts/dev/publish-l4b-config-to-nacos.sh
./scripts/dev/publish-l4b-config-to-nacos.sh --apply
```

本地环境变量：

```bash
export GCP_PUBSUB_PROJECT=fintech-all
export GCP_PUBSUB_SUBSCRIPTION=collection-cases-test1-sub
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

export GCP_PUBSUB_TEST_TOPIC=collection-cases-test1
./scripts/test/l4b-pubsub/publish-test-messages.sh case
./scripts/test/l4b-pubsub/publish-test-messages.sh repay 99000001
```

样例 payload：`scripts/test/l4b-pubsub/case_push.sample.json` 与 `repayment.sample.json`。发布脚本内置拒绝生产 topic 的护栏。

### 4.3 手动日切

L4b 联调不接 XXL-Job。使用测试入口调用真实日切处理器：

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
- 触达正文/终端核对使用 [L4b 触达内容核对清单](./MOCASA催收系统升级_Phase1_L4b触达内容核对清单.md)。
- 一次运行的日志、SQL 摘要和异常写入带日期的测试报告，并在测试 SSOT T4 更新证据索引；不得在本 Runbook 修改状态。

## 5. 操作完成后的恢复

1. 停止本地/联调消费者，确认测试订阅不再被错误使用。
2. 如 Nacos 配置需要恢复，按已审批的环境配置恢复；不要把 L4b 测试订阅直接改为生产订阅。
3. 清理或标记测试 seed，保留脱敏运行证据。
4. Pilot/生产订阅、XXL-Job、切量与回滚仅按测试 SSOT T5/T6 的批准 Runbook 执行。
