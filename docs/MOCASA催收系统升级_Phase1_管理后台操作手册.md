# MOCASA 催收系统升级 Phase 1 — 管理后台操作手册

> **适用版本**：Phase 1 / Phase 1.5 切片（配置治理基础）
> **读者**：运营、测试、策略、研发联调同事
> **关联文档**：[`管理后台设计文档`](./MOCASA催收系统升级_Phase1_管理后台设计文档.md) · [`P0 测试`](./testing/admin-p0-test.md) · [`L4b 触达核对清单`](../../AI%20collection/MOCASA催收系统升级_Phase1_L4b触达内容核对清单.md)

---

## 1. 系统概览

管理后台由**后端**（`collection-admin`，Spring Boot，端口 `8888`）和**前端**（React + Ant Design + Vite，端口 `5173`）组成。前端通过 Vite 代理把 `/auth`、`/cases`、`/config`、`/catalog`、`/plans`、`/ops`、`/compliance`、`/admin`、`/mock` 转发到后端。

后端连接的是**测试 MySQL 数据库**（JDBC 连接信息由 Nacos 下发），与 L4b 联调、催收引擎写入的是**同一个库**。因此后台看到的案件、计划、时间线数据即真实测试数据。

### 1.1 功能菜单

| 菜单 | 路由 | 说明 | 数据来源 |
|------|------|------|----------|
| Data Analysis | `/dashboard` | 看板（**占位页**，Phase 1.5 待接 Grafana / 聚合接口） | — |
| Strategy Config | `/strategy` | 策略总览 + 阶段计划 + 渠道连通性 + Holdout 评估参数 + 配置版本/回滚 | `/catalog/overview`、`/config/*` |
| Templates | `/templates` | SMS / Push **可编辑热更新** + Email 只读；Plans 页可编辑计划模板 | `/catalog/overview`、`/config/script-templates`、`/config/plan-templates` |
| Case Monitor | `/cases` | 案件检索 + 按案件下钻计划（含已完成）步骤与触达时间线 | `/cases/search`、`/plans/by-case/{caseId}/history`、`/plans/{planId}/steps`、`/plans/timeline/{userId}` |
| Ops Queue | `/ops` | 异常队列（ACK / Resolve） | `/ops/exceptions` |
| Compliance | `/compliance` | 冻结 / 解冻 / 升级 | `/compliance/*` |
| System Admin | `/system` | 审计日志等 | `/admin/audit-logs` |

---

## 2. 启动与登录

### 2.1 启动后端

```powershell
# 项目根目录
powershell -ExecutionPolicy Bypass -File "scripts/dev/start-local.ps1"
```

启动成功后访问 `http://localhost:8888`，健康检查 `http://localhost:8888/actuator/health` 返回 `{"status":"UP"}`。

### 2.2 启动前端

```powershell
$env:Path = "C:\Program Files\nodejs;" + $env:Path
cd collection-admin/ui
npm install     # 首次
npm run dev -- --host 127.0.0.1 --port 5173
```

> **注意**：不能直接双击 `index.html`（`file://` 打开会白屏），必须走 `npm run dev`。
> 修改 `vite.config.ts`（如新增代理）后**必须重启** dev server 才生效。

### 2.3 登录

浏览器打开 `http://127.0.0.1:5173`，用户名 `admin`，角色 `SYSTEM_ADMIN`。会话基于 Cookie，前端所有请求带 `credentials: include`。

---

## 3. 各页面操作

### 3.1 Strategy Config（策略配置）

打开后由上到下：

1. **Strategy Overview**：范式（L1 Stage Plan + STANDARD/FIRM）、触达窗（08:00–21:00 PHT）、停催规则（D+91）、静默时段、日限额；下方标签显示阶段数、上线渠道数、各渠道模板数，以及 SendGrid / 通知中心等**连通性**（绿=OK / 红=OFF）。
2. **Stage Plan**：S0–S4 五个阶段的定位与 DPD 区间。
3. **Channels**：SMS / Push / Email / AI_CALL 等渠道的 Provider、Adapter、Phase 1 状态、是否已配置。
4. **Evaluation Settings**：编辑 **Holdout Ratio**（对照组比例，1%–20%）。填写变更原因 → Save。基于乐观锁（`version`），若他人已改会返回 409，刷新后重试。
5. **Config Versions**：配置变更流水。选中某历史版本 → 填回滚原因 → **Rollback To Selected Version**，即把 Holdout 恢复到该版本快照值，并写入新版本记录。

> Strategy 总览与模板为**只读**（来自 Nacos/YAML 运行时配置）；当前仅 Holdout 支持在后台读写落库。

### 3.2 Templates（文案模板 · 可编辑热更新）

按 SMS / Push / Email / Plans 分页。

**SMS / Push（可编辑）**：
- 每行显示 Slot、Stage、**Effective**（生效来源：`DB` 覆盖 > `YAML` 兜底 > `NONE`）、正文。
- **Edit**：修改正文/标题 → Save，写入 `t_script_template` 并 bump 全局配置版本；引擎在 **~10s** 内重载生效（无需重启）。
- **Reset**：把 DB 覆盖停用（`status=INACTIVE`），恢复用 YAML/Nacos。
- 占位符：`{name} {amount} {dpd} {repaymentUrl}`。

**Plans（可编辑计划模板）**：
- 列出 `t_contact_plan_template`，展示 stage、tone、步骤序列。
- **Edit**：增删步骤、改渠道/延迟/观察窗/templateId → Save；引擎建计划时按 stage 读 DB（DB 优先，未命中回落 YAML），~10s 生效。
- **Deactivate**：停用该 DB 计划模板，回落 YAML。

**Email**：SendGrid 托管，此处只读（显示 Subject、SendGrid 模板 ID）。

### 3.3 Case Monitor（案件监控）

1. 输入 Case ID 或 User ID → Search。
2. **展开某行**加载该案件下钻详情：
   - **Plans（含已完成）**：该案全部计划（含 `PLAN_COMPLETED`），显示 Stage、状态、进度、起止时间；**再展开某个计划**可看其步骤（序号、渠道、模板、状态、结果 `DELIVERED`/`SKIPPED`/`FAILED`、完成时间）。
   - **Contact Timeline**：实际触达记录（渠道、方向、模板、结果、供应商消息 ID、来源、时间）。

> 这就是查看"某次催收具体发了什么"的入口。例如 L4b 的 `99000002`，展开后可看到 3 个计划、S2 计划的 4 个步骤（SMS/PUSH/SMS/EMAIL）和 9 条 timeline（SMS `DELIVERED`、Email `SKIPPED` 等）。
>
> **注意**：时间线按 `userId` 查询（案件行已带 userId）；计划历史按 `caseId` 查询，包含终态计划，因此已完成的 L4b 催收也能看到。

### 3.4 Ops Queue / Compliance / System Admin

- **Ops Queue**：按状态（OPEN/ACK/RESOLVED/IGNORED）筛选异常，逐条 ACK 或 Resolve。
- **Compliance**：对案件执行冻结 / 解冻 / 升级（写审计日志）。
- **System Admin**：查看配置变更审计日志（`t_config_change_log`）。

---

## 4. 关键说明：为什么有些内容"看起来是空的"

| 现象 | 原因 | 现状 |
|------|------|------|
| Data Analysis 无数据 | 看板为占位页，聚合接口 / Grafana 尚未接入 | Phase 1.5 待做 |
| 策略/模板"以前看不到" | 后端 `/catalog/*` 一直有数据，是前端页面此前未调用 | **本次已接入**（Strategy + Templates 页） |
| 后台看不到 L4b 催收情况 | Case 页此前只做检索、未做时间线下钻 | **本次已接入**（Case Monitor 展开行） |
| Email 在 L4b "0 封" | Phase 1 Email 仅在精确 DPD 里程碑日发送，非里程碑 SKIPPED | 属预期，见 L4b 核对清单 §4 |

---

## 5. 后台热更新短信 / 计划模板（已支持）

**已支持从后台编辑 SMS/Push 文案与计划模板并热更新生效，无需重启。**

链路：`Templates 页 Edit → PUT /config/script-templates|plan-templates → 落库 t_script_template / t_contact_plan_template + bump t_config_version_seq → 引擎(collection-channel ConfigTemplateProvider)按版本号 TTL(默认 10s)轮询失效缓存 → ScriptLibrary / DefaultPlanFactory 读 DB`。

生效优先级：**DB(status=ACTIVE) 覆盖 > Nacos/YAML 兜底**。未在 DB 配置的槽/阶段仍走 YAML。

关键行为：
- 保存后**约 10s 内**引擎生效（`channel.config.cache-ttl-ms` 可调）。
- **Reset/Deactivate** 会把 DB 行置 `INACTIVE`，回落 YAML。
- 开关：`channel.config.db-source-enabled`（默认 true）；设为 false 则完全走 YAML。
- SMS 文案 scriptSlot 仍由引擎按 stage/dpd/tone 推导（如 S2+STANDARD→`S2_SMS_STANDARD`），编辑对应 slot 即可。

数据来源与迁移：
- 全量把 Nacos/YAML 迁入 DB：执行 `db/seed-admin-config.sql`（或本地 `POST /mock/admin/seed-config` 做最小 seed）。
- 未迁移时 DB 为空 → 引擎全走 YAML；在 Templates 页首次 Edit 某槽即创建 DB 覆盖。

> 说明：Phase 1.5 已实现 SMS/Push/计划模板；rule/compliance/channel 的后台编辑仍为后续切片。

---

## 6. 数据链路自查（REST / SQL）

前端不方便时，可直接查后端或数据库确认某案件触达情况。

### 6.1 REST

```bash
curl -s "http://localhost:8888/plans/by-case/99000002/history?limit=10"   # 计划历史(含终态)
curl -s "http://localhost:8888/plans/141/steps"                            # 某计划步骤
curl -s "http://localhost:8888/plans/timeline/99000002?limit=50"           # 按 userId 时间线
curl -s "http://localhost:8888/catalog/overview"                           # 策略/模板目录
```

### 6.2 SQL

```sql
SET @caseId = 99000002;
SELECT channel, direction, result, provider_msg_id, source, created_at
  FROM t_contact_timeline WHERE case_id = @caseId ORDER BY created_at;

SELECT step_order, channel_type, status, result
  FROM t_contact_plan_step
 WHERE plan_id = (SELECT id FROM t_contact_plan WHERE case_id = @caseId ORDER BY id DESC LIMIT 1)
 ORDER BY step_order;
```

| result | 含义 |
|--------|------|
| `DELIVERED` | 供应商已受理（SMS/Push 真实下发） |
| `SKIPPED` | 未发出（如非里程碑 Email） |
| `FAILED` | 发送失败 |

---

## 7. 故障排查

| 问题 | 处理 |
|------|------|
| 前端白屏 | 确认用 `npm run dev` 启动、且已登录；直接 `file://` 打开无效 |
| 页面数据空 / 接口 404 | 确认 `vite.config.ts` 含对应代理；改代理后重启 dev server |
| 接口 401 | 未登录或会话过期，重新登录 |
| Holdout 保存报 409 | 他人已修改，点 Refresh 后基于最新 version 重试 |
| 后端起不来 / 端口占用 | 结束占用 8888 的 Java 进程后重启；jar 被占用无法 rebuild 同理 |
| Catalog 接口报错 | 确认 `catalog/catalog-metadata.json`、`script-drafts.json` 存在于 classpath |

---

## 8. 附录：默认账号与测试地址

- 登录：`admin` / `SYSTEM_ADMIN`
- 前端：`http://127.0.0.1:5173`
- 后端：`http://localhost:8888`
- L4b 统一触达地址：手机 `+639451374358` / 邮箱 `wzynju@126.com` / Push token `1a0018970bf0c19de04`
- L4b 主流程案件：`99000000`（S0）～ `99000005`（S4）
