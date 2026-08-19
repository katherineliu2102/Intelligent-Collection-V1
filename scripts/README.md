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
| 一键 L4a（停→编译→起→测） | `./scripts/test/restart-and-l4a.sh` |
| 仅跑 L4a 官方 8 条 | `./scripts/test/l4a-official-test.sh` |
| **L4b 跑前检查** | `./scripts/test/l4b-preflight.sh`（`--strict` 严格模式；仅预检，不替代 L4b 测试） |
| L4b 测试消息发布 | `GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1 ./scripts/test/l4b-pubsub/publish-test-messages.sh case`（仅隔离测试 topic） |
| L4b 官方闭环 | `./scripts/test/l4b-official-test.sh`；脚本存在，但历史输出不替代当前契约下的 L4b 取证 |
| L4b 分段重跑 | `L4B_ONLY=1,5,6 ./scripts/test/l4b-official-test.sh` |
| L4b 保留历史落库 | `L4B_RESET=0 ./scripts/test/l4b-official-test.sh`（默认清零） |
| L4a 单条 | `L4A_ONLY=6 ./scripts/test/l4a-official-test.sh` |
| Level A 冒烟 | `./scripts/test/smoke-level-a.sh all`（本地 Mock 冒烟，不等于 L4a/L4b） |
| 发布渠道密钥到 Nacos | `./scripts/dev/publish-channel-secrets-to-nacos.ps1` |

## Maven 测试命令

| 场景 | 命令 |
|------|------|
| 全仓单测（L0 / L1 / L2） | `mvn -B -ntp clean test` |
| 单模块单测 | `mvn -B -ntp -pl collection-ingestion -am test` |
| 真实库集成测试（L3） | `mvn -B -ntp -pl collection-service -am verify -Pintegration-test` |

L3 集成测试需先注入受控 MySQL 连接（**不得指向生产库，不得入仓**）：

```bash
export L3_IT_DB_URL=jdbc:mysql://<host>:3306/<db>
export L3_IT_DB_USER=<user>
export L3_IT_DB_PASSWORD=<password>
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
