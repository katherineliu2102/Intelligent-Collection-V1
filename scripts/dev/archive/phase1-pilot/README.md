# Phase1 Pilot 脚本归档

> 一次性取证 / 改库脚本归档区。**不要**从这里直接 cron。  
> 正式运维请用 [`scripts/pilot/`](../../pilot/)。  
> 盘点见 [Pilot 脚本整理台账](../../../docs/testing/MOCASA催收系统升级_Phase1_Pilot脚本整理台账.md)。

| 目录 | 内容 |
|------|------|
| `20260827/` … `20260903/` | 按日自动跑日报脚本 |
| `cases/` | 单案深挖 |
| `samples/` | 抽样 / 导出 / 渠道内容 |
| `smoke-readonly/` | 发版冒烟、只读核验（含重复的 check-slot 副本） |
| `mutating/` | ⚠ 改库 / 改 env / 重启；文件头已标 MUTATING |
| `superseded/` | 已被 `scripts/pilot/daily-report.sh` 等替代的旧变体 |
| `sql-oneoff/` | 历史一次性 SQL（从 `scripts/pilot/` 迁出） |

Pilot 机归档包：`/opt/app/logs/script-archive/pilot-tmp-20260903.tgz`（清 `/tmp/_*` 前全量 tar）。
