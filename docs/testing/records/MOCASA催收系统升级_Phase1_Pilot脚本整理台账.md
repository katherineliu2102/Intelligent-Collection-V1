# Phase 1 Pilot 脚本整理台账

> **用途**：对照 Pilot 机脚本落点、仓库 `scripts/pilot` / `scripts/dev/archive` 归属与清理状态。  
> **维护**：每次新增取证/止血脚本、升格正式工具、或 Pilot 归档后，更新本文「变更记录」与对应表格。  
> **环境**：Pilot `ubuntu@34.87.136.20`，容器 `collection-admin`，配置 `/opt/app/pilot.env`，发版 [`deploy/pilot-run.sh`](../../../deploy/pilot-run.sh)。  
> **关联**：[自动跑 9/3 记录](./MOCASA催收系统升级_Phase1_自动跑记录_20260903.md) · [发版手册](../../channel/MOCASA催收系统升级_Phase1_发版手册.md) · [`scripts/README.md`](../../../scripts/README.md)

---

## 1. 结论（2026-09-03 已执行）

| 问题 | 答案 |
|------|------|
| `/tmp` 里临时脚本是否服务必需？ | **否**。容器只跑 jar；无 cron 引用 `/tmp`；`pilot-run.sh` 不读 `/tmp`。 |
| 删了会不会停催收？ | **不会**。已于 9/3 清 `/tmp/_*`，容器仍 `running`。 |
| 真正必需的是什么？ | `pilot.env`、jar、`pilot-run.sh`、GCP Scheduler/Pub/Sub、Redis、MySQL。 |
| `/tmp` 现状 | **0** 个 `_*.sh/_*.py`（清前 145 个，已 tar）。 |
| 运维脚本落点 | Pilot：`/opt/app/scripts/pilot/`（14）；仓库：`scripts/pilot/`（同源）。 |
| 一次性脚本 | 仓库已迁入 `scripts/dev/archive/phase1-pilot/`；MUTATING 已标头。 |

---

## 2. 脚本分层（维护口径）

```text
Tier 0  正式运维     scripts/pilot/ + /opt/app/scripts/pilot/ + deploy/pilot-run.sh
Tier 1  deprecated   scripts/dev/_pilot* 别名（指向 Tier 0，勿新增）
Tier 2  历史归档     scripts/dev/archive/phase1-pilot/{日期,cases,samples,...}
Tier 3  危险可写     archive/.../mutating/（⚠ MUTATING，禁止 cron）
Tier 4  （已清空）   Pilot `/tmp` 与机上 tar 均已删除；不再机上保留历史取证脚本
```

**命名约定**

| 前缀 / 路径 | 含义 |
|-------------|------|
| `scripts/pilot/*` | 正式 Pilot 运维工具（无下划线） |
| `/opt/app/scripts/pilot/*` | Pilot 机副本，与仓库同步 |
| `scripts/dev/_pilot-*` | deprecated 别名，仅过渡 |
| `scripts/dev/archive/phase1-pilot/` | 一次性 / 历史 / MUTATING |
| `/tmp/_*` | **禁止再堆**；用完即删或勿留 |

---

## 3. Tier 0 — 正式运维（必须保留）

### 3.1 运行时（Pilot 机）

| 路径 | 说明 |
|------|------|
| `/opt/app/pilot.env` | 环境变量真值 |
| `/opt/app/build/pilot-run.sh` | **唯一**合法 `docker run` 入口 |
| `/opt/app/build/collection-admin.jar` | 当前运行包 |
| `/opt/app/secrets/` | GCP 服务账号等 |
| `/opt/app/pilot.env.bak.*` | env 变更备份 |
| `/opt/app/scripts/pilot/` | 正式运维脚本（9/3 起） |

### 3.2 仓库 / Pilot `scripts/pilot/`（14）

| 文件 | 用途 | 典型命令 |
|------|------|----------|
| [`check-slot.py`](../../../scripts/pilot/check-slot.py) | 按触达槽核对 step + 悬挂 EXECUTING | `python3 scripts/pilot/check-slot.py 12:00 PUSH` |
| [`daily-report.sh`](../../../scripts/pilot/daily-report.sh) | 全日五槽 + inbox + 投影 | `PHT_DATE=2026-09-03 bash scripts/pilot/daily-report.sh` |
| [`noplan-rootcause.sh`](../../../scripts/pilot/noplan-rootcause.sh) | 在催但无活跃 plan 根因 | `bash scripts/pilot/noplan-rootcause.sh` |
| [`noplan-cancel.sh`](../../../scripts/pilot/noplan-cancel.sh) | noplan 取消分布 | 同上目录 |
| [`noplan-breakdown.sh`](../../../scripts/pilot/noplan-breakdown.sh) | noplan 拆解 | 同上目录 |
| [`ai-failed-audit.sh`](../../../scripts/pilot/ai-failed-audit.sh) | AI `MEDIA_NEGOTIATION_FAILED` | `bash scripts/pilot/ai-failed-audit.sh` |
| [`firm-check.py`](../../../scripts/pilot/firm-check.py) | FIRM 前置条件核验 | `python3 scripts/pilot/firm-check.py` |
| [`post-consume-check.py`](../../../scripts/pilot/post-consume-check.py) | 03:00 消费后基线 | `python3 scripts/pilot/post-consume-check.py` |
| [`schema-audit.sh`](../../../scripts/pilot/schema-audit.sh) | 表结构快查 | `bash scripts/pilot/schema-audit.sh` |
| [`s0-poison-check.py`](../../../scripts/pilot/s0-poison-check.py) | S0 毒丸 / 日志 | `python3 scripts/pilot/s0-poison-check.py` |
| [`pilot-env.py`](../../../scripts/pilot/pilot-env.py) | env：`clean` / `clear-whitelists` / `clear-redirects` | `python3 scripts/pilot/pilot-env.py clear-whitelists` |
| [`publish-daily-roll.py`](../../../scripts/pilot/publish-daily-roll.py) | 手动触发日切 Pub/Sub | 需 GCP 凭证 |
| [`strip-ai-call-slots.py`](../../../scripts/pilot/strip-ai-call-slots.py) | 生成摘 AI 槽 SQL | 配合 SQL 手工执行 |
| [`restore-ai-call-slots.sql`](../../../scripts/pilot/restore-ai-call-slots.sql) | 恢复 AI 槽 | 回滚用 |

### 3.3 发版

| 文件 | 说明 |
|------|------|
| [`deploy/pilot-run.sh`](../../deploy/pilot-run.sh) | 构建上下文内同名脚本会拷到 `/opt/app/build/` |

---

## 4. Tier 1 — deprecated 别名（`scripts/dev/`，11）

升格已完成；下列文件头含 `deprecated: use scripts/pilot/...`，**勿再 scp 到 `/tmp`**。

| 文件 | 指向 |
|------|------|
| `_pilot-daily-report.sh` | `scripts/pilot/daily-report.sh` |
| `_pilot-noplan-rootcause.sh` | `scripts/pilot/noplan-rootcause.sh` |
| `_pilot-noplan-cancel.sh` | `scripts/pilot/noplan-cancel.sh` |
| `_pilot-noplan-breakdown.sh` | `scripts/pilot/noplan-breakdown.sh` |
| `_pilot-ai-failed.sh` | `scripts/pilot/ai-failed-audit.sh` |
| `_pilot-firm-check.py` | `scripts/pilot/firm-check.py` |
| `_pilot-post-consume-check.py` | `scripts/pilot/post-consume-check.py` |
| `_pilot-schema.sh` | `scripts/pilot/schema-audit.sh` |
| `_pilot-s0-poison-check.py` | `scripts/pilot/s0-poison-check.py` |
| `_pilot-norm.py` | 工具：CRLF → LF |
| `_pilot-env-append.txt` | env 片段备忘，非脚本 |

**升格状态**

| 目标名 | 状态 | 完成日 |
|--------|------|--------|
| `daily-report.sh` | **完成** | 2026-09-03 |
| `noplan-*.sh` | **完成** | 2026-09-03 |
| `ai-failed-audit.sh` | **完成** | 2026-09-03 |
| `firm-check.py` 等 | **完成** | 2026-09-03 |

---

## 5. Tier 2 — 历史归档（仓库）

根目录：[`scripts/dev/archive/phase1-pilot/`](../../../scripts/dev/archive/phase1-pilot/)（含 [README](../../../scripts/dev/archive/phase1-pilot/README.md)）。

| 子目录 | 数量 | 内容 |
|--------|------|------|
| `20260827/` | 4 | 8/27 日报 |
| `20260830/` | 4 | 8/30 日报 |
| `20260831/` | 9 | 8/31 日报 |
| `20260901/` | 5 | 9/1 日报（不含 fix） |
| `20260902/` | 4 | 9/2 analysis |
| `20260903/` | 7 | 9/3 am/pm + cleanup 脚本 |
| `cases/` | 8 | 单案深挖 |
| `samples/` | 22 | 抽样 / 导出 / 渠道 |
| `smoke-readonly/` | 20 | 发版冒烟只读 |
| `superseded/` | 6 | daily-extra* / daily-final / ai-failed2 等 |
| `sql-oneoff/` | 5 | 原 `scripts/pilot/` 一次性 SQL |
| `mutating/` | 16 | 见 §6 |
| **合计（含 README）** | **~111** | |

对应测试文档（均在本 `records/`）： [8/27](./MOCASA催收系统升级_Phase1_自动跑记录_20260827.md) · [8/30](./MOCASA催收系统升级_Phase1_自动跑记录_20260830.md) · [8/31](./MOCASA催收系统升级_Phase1_自动跑记录_20260831.md) · [9/1](./MOCASA催收系统升级_Phase1_自动跑记录_20260901.md) · [9/2](./MOCASA催收系统升级_Phase1_自动跑记录_20260902.md) · [9/3](./MOCASA催收系统升级_Phase1_自动跑记录_20260903.md) · [9/4](./MOCASA催收系统升级_Phase1_自动跑记录_20260904.md) · [目录](./README.md)

---

## 6. Tier 3 — 危险脚本（MUTATING，禁止 cron）

位置：`scripts/dev/archive/phase1-pilot/mutating/`。文件头已标 `⚠ MUTATING`。

| 文件 | 操作 | 风险 | 正式替代 |
|------|------|------|----------|
| `_pilot-stop-old40.py` | 非 e2e200 → `MANUAL_CLEANUP` | **高** | 勿重跑；救活走 `STAGE_CHANGED` |
| `_pilot-sep01-fix.py` | 同上（9/1） | **高** | 同上 |
| `_pilot-pull-slots.py` / `_pilot-pull-b-slots.py` | UPDATE `trigger_time` | 中 | 仅紧急改槽 |
| `_pilot-hikari-env.py` | 写 Hikari=25 | 中 | 已写入 env |
| `_pilot-set-e2e200.py` | 写 200 案名单 | 中 | 发版 / 手册 |
| `_pilot-clear-whitelists.py` 等 | 改 env | 中 | `pilot-env.py` |
| `_pilot-enable-wave.sh` | sed env | 中 | 手工 + 重启 |
| `_pilot-smoke-env.py` | 缩圈 / 限流 | **高** | 仅冒烟 |
| `_pilot-restart.sh` | 重启容器 | 中 | 发版手册 |
| `_clear-daily-roll-redis.*` | DEL Redis 键 | 中 | 仅日切卡死 |
| `_pilot-fix-2477.py` | 执行挂步 SQL | 中 | `archive/.../sql-oneoff/` |
| `_pilot-build-engine-patch.sh` | 机内 mvn | 低 | 发版手册 |

---

## 7. 重复项对照

| 保留（正式） | 废弃位置 |
|--------------|----------|
| `scripts/pilot/check-slot.py` | `archive/.../smoke-readonly/_pilot-check-slot.py` |
| `scripts/pilot/pilot-env.py` | `archive/.../mutating/_pilot-clear-*.py` 等 |
| `scripts/pilot/publish-daily-roll.py` | `archive/.../smoke-readonly/_publish-daily-roll-pilot.py` |
| `scripts/pilot/daily-report.sh` | `scripts/dev/_pilot-daily-report.sh`（deprecated）+ `archive/.../superseded/` |
| `scripts/pilot/noplan-*.sh` | `scripts/dev/_pilot-noplan-*.sh`（deprecated） |
| `scripts/pilot/ai-failed-audit.sh` | `scripts/dev/_pilot-ai-failed.sh`（deprecated） |

---

## 8. Pilot `/tmp` 清理记录（2026-09-03）

### 8.1 执行摘要

| 项 | 值 |
|----|-----|
| 清前 `/tmp/_*.{sh,py}` | **145**（约 696KB） |
| 中间态归档包 | 曾打 `pilot-tmp-20260903.tgz`；确认无用后 **已删除**（连同 manifest / `script-archive/` 目录） |
| 清后 `/tmp/_*.{sh,py}` | **0** |
| 运维落点 | `/opt/app/scripts/pilot/`（14，与仓库一致） |
| 容器 | `collection-admin` 仍 `running` |
| cron | 无 `/tmp/_` 引用 |

### 8.2 清前前缀分布（归档在 manifest）

| 前缀 | 约数 | 处置 |
|------|------|------|
| `_pilot-*` | ~95 | tar 后清；可复用已升格到 `/opt/app/scripts/pilot/` |
| `_e2e-*` | 23 | tar 后清（8/25 联调） |
| `_clear-daily-roll-*` | 2 | tar 后清（MUTATING 仅留仓库 archive） |
| 其他 orphan | ~25 | tar 后清（含 `_nacos-*` `_pick-*` `_redis-*` `_slot-ingest.py` 等） |

### 8.3 曾仅存在于 `/tmp` 的孤儿（已随 tar 删除，不再保留）

`_pilot-deploy-jar.sh`, `_e2e-*`, `_nacos-*`, `_pick-dpd30*`, `_redis-*`, `_slot-ingest.py` 等联调残留；结论数字以各日「自动跑记录」为准，无需机上还原。

### 8.4 切勿删除

```
/opt/app/pilot.env
/opt/app/pilot.env.bak.*
/opt/app/build/pilot-run.sh
/opt/app/build/collection-admin.jar
/opt/app/secrets/
/opt/app/scripts/pilot/
```

---

## 9. 常用操作速查

### 9.1 推荐（正式路径）

```bash
# 仓库或 Pilot 机均可
PHT_DATE=2026-09-03 PILOT_ENV=/opt/app/pilot.env bash scripts/pilot/daily-report.sh
python3 scripts/pilot/check-slot.py 14:30 AI_CALL
bash scripts/pilot/noplan-rootcause.sh
```

Pilot 机上等价：

```bash
PHT_DATE=$(TZ=Asia/Manila date +%F) bash /opt/app/scripts/pilot/daily-report.sh
```

### 9.2 禁止再做

```bash
# 不要再把一次性脚本堆进 /tmp 长期留存
scp scripts/dev/archive/... ubuntu@...:/tmp/   # 即用即删，或根本不解压到 /tmp
```

### 9.3 上机确认

```bash
ls /tmp/_*.sh /tmp/_*.py 2>/dev/null | wc -l   # 期望 0
ls /opt/app/scripts/pilot | wc -l                # 期望 14
docker inspect collection-admin --format '{{.State.Status}}'
```

---

## 10. 仓库现状清单（2026-09-03）

### 10.1 `scripts/pilot/`（14）— 见 §3.2

### 10.2 `scripts/dev/_pilot*`（11 deprecated）— 见 §4

### 10.3 `scripts/dev/archive/phase1-pilot/` — 见 §5 / §6

---

## 11. 变更记录

| 日期 | 变更 | 操作人 |
|------|------|--------|
| 2026-09-02 | 初版台账：138 `/tmp` + 105 `dev/_pilot*` 分级；未执行 Pilot 清理 | Agent |
| 2026-09-03 | **已执行**：Pilot `/tmp` 145→0；运维脚本落 `/opt/app/scripts/pilot/`（14）；仓库升格正式工具；T2/MUTATING 迁 `scripts/dev/archive/`；随后确认自动跑/抽样无需机上留存，**删除** Pilot `script-archive/` tar | Agent |

**更新模板**：

```text
| YYYY-MM-DD | 简述：升格 / 归档 / Pilot tar | 姓名 |
```

---

## 12. 待办

- [x] Pilot `/tmp` 打 tar 后清理
- [x] 创建 `scripts/dev/archive/phase1-pilot/` 并挪 T2 / MUTATING
- [x] 升格 `daily-report` / `noplan` / `ai-failed` 等到 `scripts/pilot/`
- [x] MUTATING 文件头加 `⚠ MUTATING`
- [ ] 删除或最终移除 `scripts/dev/_pilot*` deprecated 别名（确认无人引用后）
- [ ] 32/40 户 `MANUAL_CLEANUP` 救活（`STAGE_CHANGED`，与脚本整理独立）
