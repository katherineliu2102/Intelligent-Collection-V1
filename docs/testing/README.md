# Phase 1 测试文档

> **SSOT**：[测试主文档](./MOCASA催收系统升级_Phase1_测试文档.md)（L0–L4、链路 × 层级矩阵、§L4a 用例清单）。  
> 运行入口与脚本对照见测试主文档 §0 / §L4a 与 [`../../scripts/README.md`](../../scripts/README.md)。

## 文档

| 文档 | 说明 |
|------|------|
| [测试主文档](./MOCASA催收系统升级_Phase1_测试文档.md) | Phase 1 测试唯一信息源 |
| [L4a 编排同事补全清单](./MOCASA催收系统升级_Phase1_L4a全量前置_编排同事补全清单.md) | L4a-全 SPI 切换、CaseRegistry、官方脚本 |
| [_archive/L4a_对齐纪要_20260622.md](./_archive/L4a_对齐纪要_20260622.md) | 2026-06-22 channel↔engine 一次性对齐（只读） |

## L4 脚本（`scripts/test/`）

| 脚本 | 层级 |
|------|------|
| `l4a-official-test.sh` | L4a 官方 8 条 + Guard/REBUILD |
| `restart-and-l4a.sh` | 停服 → 编译 → 起 → L4a |
| `smoke-level-a.sh` | Level A 冒烟 |
| `run-email-e2e.ps1` | Email 5 封联调（见 `docs/email-templates/email-e2e-test-cases.md`） |

## L4b

| 脚本 | 说明 |
|------|------|
| [`test/l4b-preflight.sh`](../scripts/test/l4b-preflight.sh) | 跑前自动检查（§L4b.1）；`l4b-official-test.sh` 待 B1/B2 真实化后补 |

后续在同目录新增 `l4b-official-test.sh`，并在测试主文档增 **§L4b** 章节（不新建子目录）。

## 后续落位约定（2026-06-26 起）

| 新增内容 | 落在哪里 | 不要放哪里 |
|----------|----------|------------|
| 测试 SSOT 章节（L4b、差集、矩阵） | 写入/扩展 [测试主文档](./MOCASA催收系统升级_Phase1_测试文档.md) 对应 § | 不另建 `testing/l4b/` 子目录 |
| L4x 协作文档（补全清单、对齐纪要） | `docs/testing/` 同层 `L4x_*.md`；一次性纪要完成后 → `testing/_archive/` | 不放 `docs/` 根 |
| L4 可执行脚本（Shell/PS1） | `scripts/test/`（前缀 `l4a-` / `l4b-` / `smoke-` / `email-`） | 不放 `scripts/` 根 |
| 本地启停 / Nacos / 环境校验 | `scripts/dev/` | — |
| L0–L3 Java 集成/单测 | 仍在各模块 `src/test/java/`（如 `collection-engine/.../integration/`） | 不进 `scripts/test/` |
| Email 用例表 / 模板资产 | `docs/email-templates/`（渠道域） | 不复制进 testing 主文档 |
| 渠道侧 TC 细节 | `docs/channel/…功能测试指南.md`（编排同事） | testing 只索引外链 |
| 审计 / 一次性对齐结论 | `docs/audit/` 或 `docs/testing/_archive/`、`docs/contracts/_archive/` | 不删，只归档 |
| 运行时日志 | `logs/run/`（启停/L4 跑批）、`logs/collection/`（logback） | 不放项目根 |

**入口**：新人从 [docs/README.md](../README.md) §三 → 本目录 → [scripts/README.md](../../scripts/README.md)。

## 2026-06-26 目录整理 · channel 链接变更记录

主架构整理 `docs/testing/` + `scripts/{dev,test}/` 时，**已更新**以下 channel 文档中的外链（供 merge 时编排同事知悉）：

| 文件 | 变更 |
|------|------|
| `docs/channel/MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md` | `../操作说明.md` → `../操作说明_Nacos本地启动.md`（3 处） |
| `docs/channel/MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md` | 同上（2 处） |
| `docs/channel/MOCASA催收系统升级_Phase1_渠道模板清单与配置.md` | `scripts/publish-…` → `scripts/dev/publish-channel-secrets-to-nacos.ps1` |
| `docs/channel/MOCASA催收系统升级_Phase1_collection-channel开发执行指南.md` | 密钥发布脚本路径 → `scripts/dev/…` |
| `docs/channel/MOCASA催收系统升级_Phase1_策略迭代与测试操作手册.md` | 密钥发布脚本路径 → `scripts/dev/…` |
| `docs/channel/README_渠道文档索引.md` | 本地启动链不变；测试 SSOT 可外链本目录 |

未改 channel 正文规格，仅修正断链与脚本路径。
