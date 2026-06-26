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
| L4a 单条 | `L4A_ONLY=6 ./scripts/test/l4a-official-test.sh` |
| Level A 冒烟 | `./scripts/test/smoke-level-a.sh all` |
| 发布渠道密钥到 Nacos | `./scripts/dev/publish-channel-secrets-to-nacos.ps1` |

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
