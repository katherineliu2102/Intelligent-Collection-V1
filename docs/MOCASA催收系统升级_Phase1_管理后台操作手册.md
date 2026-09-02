# MOCASA 催收系统升级 Phase 1 — 管理后台操作手册

> **版本**: Phase 1 / Phase 1.5 切片（配置治理基础）  
> **日期**: 2026-09-01  
> **状态**: ✅ 操作说明（测试库）；设计 SSOT 见设计文档  
> **读者**: 运营、测试、策略、研发联调同事  
> **数据源**: 当前连接**测试 MySQL** `ai_collection_db`（JDBC 由 Nacos 下发），与 L4b 联调、催收引擎写入的是同一个库；正式跑通后再切生产库。  
> **关联文档**: [管理后台设计文档](./MOCASA催收系统升级_Phase1_管理后台设计文档.md) · [测试 SSOT](./testing/MOCASA催收系统升级_Phase1_测试文档.md) · Nacos / 进程启动见 [操作说明_Nacos本地启动](./操作说明_Nacos本地启动.md)

---

## 目录

- [1. 系统概览](#1-系统概览)
- [2. 启动与登录](#2-启动与登录)
- [3. 各页面操作](#3-各页面操作)
  - [3.1 Data Analysis](#31-data-analysis触达看板)
  - [3.2 Strategy Config](#32-strategy-config策略配置)
  - [3.3 Templates](#33-templates文案模板--可编辑热更新)
  - [3.4 Case Monitor](#34-case-monitor案件监控)
  - [3.5 Ops / Compliance / System](#35-ops-queue--compliance--system-admin)
- [4. 常见「看起来没数据」说明](#4-常见看起来没数据说明)
- [5. 后台热更新短信 / 计划模板（已支持）](#5-后台热更新短信--计划模板已支持)
- [6. 数据链路自查（REST / SQL）](#6-数据链路自查rest--sql)
- [7. 故障排查](#7-故障排查)
- [8. 附录：默认账号与测试地址](#8-附录默认账号与测试地址)

---

## 1. 系统概览

管理后台由**后端**（`collection-admin`，Spring Boot，端口 `8888`）和**前端**（React + Ant Design + Vite，端口 `5173`）组成。前端通过 Vite 代理把 `/auth`、`/cases`、`/config`、`/catalog`、`/plans`、`/ops`、`/compliance`、`/admin`、`/mock` 转发到后端。

| 地址 | 用途 | 能否当页面打开 |
|------|------|---------------|
| `http://127.0.0.1:5173` | 管理后台 UI（看板、案件、配置） | ✅ **用这个** |
| `http://localhost:8888` | REST API（程序调用） | ❌ 会 404 或返回 JSON 报错 |

把 `8888` 存成书签是最常见的"打不开"原因——它是 API 不是页面。

### 1.1 功能菜单

| 菜单 | 路由 | 说明 | 数据来源 |
|------|------|------|----------|
| Data Analysis | `/dashboard` | **触达效果看板**：按渠道 / Stage / 模板看送达率与 result 分布 | `GET /dashboard/outreach/realtime` ← `t_contact_timeline` |
| Strategy Config | `/strategy` | 策略总览 + 阶段计划 + 渠道连通性 + Holdout 评估参数 + 配置版本/回滚 | `/catalog/overview`、`/config/*` |
| Templates | `/templates` | SMS / Push **可编辑热更新** + Email 只读；Plans 页可编辑计划模板 | `/catalog/overview`、`/config/script-templates`、`/config/plan-templates` |
| Case Monitor | `/cases` | 案件检索 + 按案件下钻计划（含已完成）步骤与触达时间线 | `/cases/search`（读 `t_ai_collection` 投影，见 §3.4）、`/plans/by-case/{caseId}/history`、`/plans/{planId}/steps`、`/plans/timeline/{userId}` |
| Ops Queue | `/ops` | 异常队列（ACK / Resolve） | `/ops/exceptions` |
| Compliance | `/compliance` | 冻结 / 解冻 / 升级 | `/compliance/*` |
| System Admin | `/system` | 审计日志等 | `/admin/audit-logs` |

---

## 2. 启动与登录

进程、Nacos 与 `.env` 的通用说明见 [操作说明_Nacos本地启动](./操作说明_Nacos本地启动.md)。本节只覆盖后台 UI。

### 2.1 一键启动（推荐，Windows）

在项目根目录执行，脚本会按需拉起后端(8888)与前端(5173)并打开浏览器：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/start-admin.ps1
```

关掉这两个 PowerShell 窗口（或 Ctrl+C）即停服务。仓库目前没有 `start-admin.sh`。

### 2.2 macOS / Linux（两步）

```bash
# 项目根目录 — 后端
./scripts/dev/start-local.sh

# 另开终端 — 前端
cd collection-admin/ui
npm install     # 首次
npm run dev -- --host 127.0.0.1 --port 5173
```

浏览器打开 `http://127.0.0.1:5173`。

### 2.3 手动启动后端（Windows）

```powershell
powershell -ExecutionPolicy Bypass -File "scripts/dev/start-local.ps1"
```

启动成功后健康检查 `http://localhost:8888/actuator/health` 返回 `{"status":"UP"}`。

### 2.4 手动启动前端（Windows）

```powershell
$env:Path = "C:\Program Files\nodejs;" + $env:Path
cd collection-admin/ui
npm install     # 首次
npm run dev -- --host 127.0.0.1 --port 5173
```

> **注意**：不能直接双击 `index.html`（`file://` 打开会白屏），必须走 `npm run dev`。
> 修改 `vite.config.ts`（如新增代理）后**必须重启** dev server 才生效。
> `npm run dev` 不会开机自启，重启机器后要重新拉起。

### 2.5 登录

浏览器打开 `http://127.0.0.1:5173`。本地账号只存在于 **local profile**（不连生产库、不对外暴露端口）；用户名/口令见 `.env.example` 与登录页提示，pilot/生产账号只经环境变量注入，`PilotReadinessValidator` 会强制至少配一个可用账号，否则拒绝启动。会话基于 Cookie，前端所有请求带 `credentials: include`。

### 2.6 30 秒自检

```bash
curl -sS http://localhost:8888/actuator/health    # 应 200
curl -sS -o /dev/null -w "%{http_code}" http://127.0.0.1:5173/dashboard
```

Windows 可用 `Invoke-WebRequest` 替代。第三条若返回 JSON，说明 Vite 把整段 `/dashboard` 代理到了后端——只应代理 `/dashboard/outreach` 等 API 子路径。

---

## 3. 各页面操作

### 3.1 Data Analysis（触达看板）

**入口**：登录后左侧 **Data Analysis**，或 `/dashboard`。

**指标口径**（分母只算真正发出去的）：

| 指标 | 含义 | 是否计入送达率分母 |
|------|------|-------------------|
| **Records** | timeline 全部 OUT 行 | — |
| **Attempted** | 实际发起发送（DELIVERED + FAILED 等） | ✅ 分母 |
| **Delivered** | 供应商受理 / 送达 | 分子 |
| **Skipped** | **未发送**（Guard 拦截、非里程碑 Email 等） | ❌ 归入 Other |
| **Other** | 其他未归类 result | ❌ |
| **送达率** | Delivered ÷ Attempted | Skipped **不进**分母 |

**时间窗口**：默认近 30 天。测试数据若超过 7 天没跑批，选 7 天会看起来是空的——这是正常现象，改 30 / 90 天即可。

**维度**：按渠道、Stage、scriptSlot 下钻；右侧为 result 分布与计划状态（全量，不受时间窗限制）。

### 3.2 Strategy Config（策略配置）

打开后由上到下：

1. **Strategy Overview**：范式（L1 Stage Plan + STANDARD/FIRM）、触达窗（08:00–21:00 PHT）、停催规则（D+91）、静默时段、日限额；下方标签显示阶段数、上线渠道数、各渠道模板数，以及 SendGrid / 通知中心等**连通性**（绿=OK / 红=OFF）。
2. **Stage Plan**：S0–S4 五个阶段的定位与 DPD 区间。
3. **Channels**：SMS / Push / Email / AI_CALL 等渠道的 Provider、Adapter、Phase 1 状态、是否已配置。
4. **Evaluation Settings**：编辑 **Holdout Ratio**（对照组比例，1%–20%）。填写变更原因 → Save。基于乐观锁（`version`），若他人已改会返回 409，刷新后重试。
5. **Config Versions**：配置变更流水。选中某历史版本 → 填回滚原因 → **Rollback To Selected Version**，即把 Holdout 恢复到该版本快照值，并写入新版本记录。

> Strategy 总览与模板为**只读**（来自 Nacos/YAML 运行时配置）；当前仅 Holdout 支持在后台读写落库。

### 3.3 Templates（文案模板 · 可编辑热更新）

按 SMS / Push / Email / Plans 分页。

**SMS / Push（可编辑）**：
- 每行显示 Slot、Stage、**Effective**（生效来源：`DB` 覆盖 > `YAML` 兜底 > `NONE`）、正文。
- **Edit**：修改正文/标题 → Save，写入 `t_script_template` 并 bump 全局配置版本；引擎在 **~10s** 内重载生效（无需重启）。保存前的校验规则见 §5.1。
- **Reset**：把 DB 覆盖停用（`status=INACTIVE`），恢复用 YAML/Nacos。
- 占位符：`{name} {amount} {dpd} {repaymentUrl}`。

**Plans（可编辑计划模板）**：
- 列出 `t_contact_plan_template`，展示 stage、tone、步骤序列。
- **Edit**：增删步骤、改渠道/延迟/观察窗/templateId → Save；引擎建计划时按 stage 读 DB（DB 优先，未命中回落 YAML），~10s 生效。
- **Deactivate**：停用该 DB 计划模板，回落 YAML。

**Email**：SendGrid 托管，此处只读（显示 Subject、SendGrid 模板 ID）。

### 3.4 Case Monitor（案件监控）

1. 输入 Case ID 或 User ID → Search。
2. **展开某行**加载该案件下钻详情：
   - **Plans（含已完成）**：该案全部计划（含 `PLAN_COMPLETED`），显示 Stage、状态、进度、起止时间；**再展开某个计划**可看其步骤（序号、渠道、模板、状态、结果 `DELIVERED`/`SKIPPED`/`FAILED`、完成时间）。
   - **Contact Timeline**：实际触达记录（渠道、方向、模板、结果、供应商消息 ID、来源、时间）。

> 这就是查看"某次催收具体发了什么"的入口。例如 L4b 的 `99000002`，展开后可看到 3 个计划、S2 计划的 4 个步骤（SMS/PUSH/SMS/EMAIL）和 9 条 timeline（SMS `DELIVERED`、Email `SKIPPED` 等）。
>
> **注意**：时间线按 `userId` 查询（案件行已带 userId）；计划历史按 `caseId` 查询，包含终态计划，因此已完成的 L4b 催收也能看到。

**列表隐私口径**（[PRD §8.2](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md#82-隐私与数据安全) / [§9](./MOCASA催收系统升级_Phase1_产品需求文档_PRD.md#9-产品层已拍板决策)）：

| 列 | 是否展示 | 说明 |
|----|----------|------|
| Case ID / User ID | 明文 | 内部键 |
| Phone | 脱敏 | 如 `+63****358`，完整号不进列表 |
| Email | 脱敏 | 如 `w***@126.com` |
| 姓名 | **不展示** | 话术渲染用 `{name}`，后台列表不展示 |

**AI Call 的已知缺口**（升级前必须知道，否则会误判成故障）：

- 时间线能看到 `AI_CALL` 的 `result` 与 `providerMsgId`；但接通原因、Facade 原始 `line_outcome` 存在 `t_channel_callback_audit`，**页面还没接**，只能走 §6.2 的 SQL。

> 2026-08-25 起 Search 已读 `t_ai_collection`（ingestion 投影），与入案主路径同源。此前「只投新 `caseEvent` 就搜不到」的缺口已闭合；若仍搜不到，那就是真的没入案，按 §6.2 查 `t_ai_collection_inbox` 定位。

### 3.5 Ops Queue / Compliance / System Admin

- **Ops Queue**：按状态（OPEN/ACK/RESOLVED/IGNORED）筛选异常，逐条 ACK 或 Resolve。
- **Compliance**：对案件执行冻结 / 解冻 / 升级（写审计日志）。
- **System Admin**：查看配置变更审计日志（`t_config_change_log`）。

---

## 4. 常见「看起来没数据」说明

| 现象 | 原因 | 处理 / 现状 |
|------|------|------------|
| Dashboard 全 0 | 默认时间窗内没有 timeline（测试数据最后写入可能已超过 7 天） | 选近 30 / 90 天，或跑新一轮 L4b |
| 投了新 `caseEvent`，Case Monitor 搜不到 | Search 已与入案同源（`t_ai_collection`），搜不到即投影未落库 | 用 §6.2 SQL 查 `t_ai_collection_inbox` 的 `projection_applied` / `publish_status` |
| 有 AI_CALL 计划但看不出接通原因 | UI 未接回调审计表 | 查 `t_channel_callback_audit`（§6.2） |
| 浏览器返回 JSON `UNAUTHORIZED` | 误开 8888，或 Vite 把整段 `/dashboard` 代理到了后端 | 只开 5173；改 vite 配置后重启前端 |
| `ERR_CONNECTION_REFUSED` | 前后端没同时运行 | `scripts\dev\start-admin.ps1` |
| Email 送达率低但 Case 里是 DELIVERED | `SKIPPED` 是**未发**，不应进分母 | 看板已按 Attempted 计算 |
| Case 有数据、看板没有 | 看板按时间窗聚合，Case 按案件查全量 | 放大看板天数 |
| Email 在 L4b "0 封" | Phase 1 Email 仅在精确 DPD 里程碑日发送，非里程碑 `SKIPPED` | 属预期 |
| 策略/模板"以前看不到" | 后端 `/catalog/*` 一直有数据，是前端页面此前未调用 | 已接入（Strategy + Templates 页） |

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

### 5.1 SMS / Push 文案保存护栏

保存前前后端双校验，**后端是唯一拦截门禁**（`ScriptTemplateValidator`）：

| 规则 | SMS | Push |
|------|-----|------|
| 允许变量 | 仅 `{name}` `{amount}` `{dpd}` `{repaymentUrl}` | 同左 |
| 必填变量 | body 必须含 `{amount}` 与 `{repaymentUrl}` | title / body 不可同时为空 |
| 模板字数硬上限 | body ≤ 300 | title ≤ 40，body ≤ 120 |
| 样例渲染上限 | ≤ 400（超 160 / 320 仅提示分段成本） | title ≤ 60，body ≤ 180 |

- 编辑弹窗提供变量 chip 一键插入、字数计数、样例渲染预览、非法变量红字提示。
- Dry-run：`POST /config/script-templates/validate`，不落库，返回 errors / warnings / preview。

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
curl -s -b cookies.txt \
  "http://localhost:8888/dashboard/outreach/realtime?days=30"              # 触达看板聚合
```

### 6.2 SQL

```sql
SET @caseId = 99000002;

-- 该案触达明细
SELECT channel, direction, result, provider_msg_id, source, created_at
  FROM t_contact_timeline WHERE case_id = @caseId ORDER BY created_at;

-- 该案最近一个计划的步骤
SELECT step_order, channel_type, status, result
  FROM t_contact_plan_step
 WHERE plan_id = (SELECT id FROM t_contact_plan WHERE case_id = @caseId ORDER BY id DESC LIMIT 1)
 ORDER BY step_order;

-- 看板对不上时，先确认时间窗内有没有数据
SELECT channel, result, COUNT(*) FROM t_contact_timeline
 WHERE direction = 'OUT' AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
 GROUP BY channel, result;

-- 新入案投影：Case Monitor 搜不到但引擎在催时查这里
SELECT case_id, user_id, dpd, stage, collection_status, overdue_amount, upcoming_amount,
       borrower_phone, borrower_email
  FROM t_ai_collection WHERE case_id = @caseId;

-- AI Call 回调审计：result 为映射后枚举，Facade 原始 line_outcome 在 canonical_payload
SELECT plan_id, step_id, result, disposition, provider_msg_id, signature_valid, received_at
  FROM t_channel_callback_audit
 WHERE case_id = @caseId
 ORDER BY received_at;
```

| result | 含义 | 看板归类 |
|--------|------|----------|
| `DELIVERED` | 供应商已受理（SMS/Push 真实下发） | Attempted + Delivered |
| `SKIPPED` | **未发出**（如非里程碑 Email、Guard 拦截） | 不进送达率分母 |
| `FAILED` | 发送失败 | Attempted |

---

## 7. 故障排查

| 问题 | 处理 |
|------|------|
| 前端白屏 | 确认用 `npm run dev` 启动、且已登录；直接 `file://` 打开无效 |
| 页面数据空 / 接口 404 | 确认 `vite.config.ts` 含对应代理；**勿**代理整段 `/dashboard`，只代理 `/dashboard/outreach` 等 API 子路径；改代理后重启 dev server |
| 接口 401 | 未登录或会话过期，重新登录 |
| Holdout 保存报 409 | 他人已修改，点 Refresh 后基于最新 version 重试 |
| 后端起不来 / 端口占用 | 结束占用 8888 的 Java 进程后重启；jar 被占用无法 rebuild 同理 |
| 看板接口 500 | 看后端日志，确认 MySQL / Nacos 连通 |
| Catalog 接口报错 | 确认 `catalog/catalog-metadata.json`、`script-drafts.json` 存在于 classpath |

**为什么经常"打不开"**：前后端是两个进程、必须同时跑，关窗口即停；`8888` 是 API 不是页面，容易被误存书签；`npm run dev` 不开机自启。日常直接用 §2.1 的一键脚本。页面与权限设计见 [管理后台设计文档 §4 / §5](./MOCASA催收系统升级_Phase1_管理后台设计文档.md#4-信息架构总览)。

---

## 8. 附录：默认账号与测试地址

以下均为**测试 / L4b 占位**，不是生产凭据；不得写入生产密钥。

- 登录：`admin` / `local-dev`（角色 `SYSTEM_ADMIN`，仅 local profile；与 `.env.example` 一致）
- 前端：`http://127.0.0.1:5173`
- 后端：`http://localhost:8888`
- L4b 统一触达地址：手机 `+639451374358` / 邮箱 `wzynju@126.com` / Push token `1a0018970bf0c19de04`
- L4b 主流程案件：`99000000`（S0）～ `99000005`（S4）
