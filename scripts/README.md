# 本地脚本索引

> 项目根目录执行。Maven / CI **不依赖**本目录。

## 目录

| 目录 | 用途 |
|------|------|
| [`dev/`](./dev/) | 本地启停、健康检查、Nacos 密钥发布、环境校验 |
| [`test/`](./test/) | L4 端到端、冒烟、Email E2E |

## 常用命令

| 场景 | 命令 |
|------|------|
| 前台启动 App | `./scripts/dev/start-local.sh` |
| 后台启动 | `./scripts/dev/start-local.sh --detach` |
| 停止 | `./scripts/dev/stop-local.sh` |
| 一键 L4a（停→编译→起→测） | `./scripts/test/restart-and-l4a.sh`（须在 08:00–21:00 PHT；静默时段内会 `exit 2`，可用 `L4A_ALLOW_QUIET_HOURS=1` 放宽但该轮不验静默 Guard） |
| 仅跑 L4a 官方 8 条 | `./scripts/test/l4a-official-test.sh` |
| **L4b 跑前检查** | `./scripts/test/l4b-preflight.sh`（`--strict` 严格模式；仅预检，不替代 L4b 测试） |
| L4b 测试消息发布 | `GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1 ./scripts/test/l4b-pubsub/publish-test-messages.sh case`（仅隔离测试 topic） |
| L4b 官方闭环 | `./scripts/test/l4b-official-test.sh`；脚本存在，但历史输出不替代当前契约下的 L4b 取证 |
| L4b 分段重跑 | `L4B_ONLY=1,5,6 ./scripts/test/l4b-official-test.sh` |
| L4b 保留历史落库 | `L4B_RESET=0 ./scripts/test/l4b-official-test.sh`（默认清零） |
| L4a 单条 | `L4A_ONLY=6 ./scripts/test/l4a-official-test.sh` |
| Level A 冒烟 | `./scripts/test/smoke-level-a.sh all`（本地 Mock 冒烟，不等于 L4a/L4b） |
| 发布渠道密钥到 Nacos | `./scripts/dev/publish-channel-secrets-to-nacos.ps1` |
| **开通 L4 测试用 PubSub 资源** | `python3 scripts/test/provision-l4-pubsub.py [--dry-run\|--delete]`（幂等；测试 topic/订阅 + 死信，正式 topic 只挂订阅不改动） |
| 切到 L4a 阶段配置 | `python3 scripts/dev/merge-nacos-config.py deploy/nacos/l4a-collection.publish.yml [--apply]` |
| 切到 L4b 阶段配置 | `python3 scripts/dev/merge-nacos-config.py deploy/nacos/l4b-collection.publish.yml [--apply]` |
| 查上游实际在往哪个 topic 发 | `python3 scripts/test/observe-upstream-topics.py --create` → 等一个发布周期 → 不带参数 peek → `--delete` 回收 |
| **开通/复验调度链（O2/O6）** | `python3 scripts/test/provision-scheduler.py [--dry-run\|--verify\|--pause\|--delete-jobs]`（幂等；纠调度与案件两条订阅的 ack / 保留 / 永不过期 + 建 4 条 `asia-northeast1` Job。别手敲 gcloud：漏 `--attributes="job=..."` 会让四条 Job 全部空转、触达静默停摆。`--verify` 还会**逐 location 翻页全扫**调度 Topic 的发布者，用于发现别处的重复发布者） |

两个阶段的 `collection.case-service` 与 `collection.ingestion.enabled` 取值相反，**必须切换后再跑**：
L4a 走 `/mock/ingest` + `MockCaseService`，L4b 走真实订阅 + `AiCollectionCaseService`。
`--apply` 会先把现网备份到 `deploy/nacos/backup-<dataId>-<时间戳>.yml`，恢复即把备份再合并回去。
不带 `--apply` 是 dry-run，打印逐键差异（密钥值脱敏）。合并工具按 key 路径处理现网已有的同名顶层键，避免重复 YAML 键导致应用无法启动。

⚠ `.env` 里的 Nacos 账号目前对 `mocasa-dps` 命名空间**只读**，写配置会得到
`403 authorization failed`。此时脚本会把合并结果落到 `deploy/nacos/merged-<dataId>-<时间戳>.yml`
（含明文密钥，已 gitignore），去控制台粘贴发布即可；换到有写权限的账号后可直接 `--apply`。

## Maven 测试命令

| 场景 | 命令 |
|------|------|
| 全仓单测（L0 / L1 / L2） | `mvn -B -ntp clean test` |
| 单模块单测 | `mvn -B -ntp -pl collection-ingestion -am test` |
| 真实库集成测试（L3） | `mvn -B -ntp -pl collection-service,collection-admin -am test -Pintegration-tests` |

L3 集成测试用 `@Tag("integration")` 标记，默认构建排除；profile 名为 `integration-tests`（复数），
由 surefire 执行，没有绑定 failsafe，因此用 `test` 而非 `verify`。集成测试类分布在
`collection-service`（mapper 往返与发件箱）与 `collection-admin`（到期扫描推进），两个模块都要带上。

需先注入受控 MySQL 连接（**不得指向生产库，不得入仓**）：

```bash
export L3_IT_DB_URL=jdbc:mysql://<host>:3306/<db>
export L3_IT_DB_USER=<user>
export L3_IT_DB_PASS=<password>
```

未注入上述变量时集成测试自动跳过，因此 **CI 绿不代表 L3 通过**。

## L4b 官方脚本前置

```bash
source scripts/test/l4b-env.local.sh
export DB_HOST=... DB_PORT=3306 DB_USER=... DB_PASS=... DB_NAME=<db>
export GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1

./scripts/test/l4b-preflight.sh --strict
./scripts/dev/start-local.sh --detach     # 每轮必须重启：内存幂等标记随进程存活
./scripts/test/l4b-official-test.sh
```

裁决口径：入口一律走真实 topic，断言一律查 `t_contact_plan` / `t_contact_plan_step` / `t_contact_timeline`，
REST 预览不作为终态证据。可重复性前置与操作顺序见
[L4b 环境交接清单](../docs/testing/MOCASA催收系统升级_Phase1_L4b环境交接清单.md)。

## 日志

| 文件 | 说明 |
|------|------|
| `logs/run/admin.log` | 后台启动 stdout |
| `logs/run/admin.err.log` | 后台启动 stderr |
| `logs/run/admin.pid` | 后台 PID |
| `logs/run/l4a.last.log` | 最近一次 L4a 跑批输出 |
| `logs/collection/collection.log` | logback 应用日志（`logback-spring.xml` 默认路径，运行时生成） |

## 已删除（2026-06-26）

- `l4a-full-test.sh`、`l4a-continue-a4.sh` → 由 `test/l4a-official-test.sh` + `L4A_ONLY=` 替代
- `run-local-detached.ps1` → 使用 `dev/start-local.sh --detach` 或 Windows 下 `dev/start-local.ps1`
