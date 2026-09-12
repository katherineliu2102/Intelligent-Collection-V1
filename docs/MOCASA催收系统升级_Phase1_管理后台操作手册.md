# MOCASA 催收系统升级 Phase 1 — 管理后台操作手册

> **版本**: Phase 1 / 看板 v1.8  
> **日期**: 2026-09-11  
> **状态**: ✅ 操作说明（测试库 `ai_collection_db`）；设计 SSOT 见设计文档  
> **读者**: 运营、测试、策略、研发联调同事  
> **数据源**: 当前连接**测试 MySQL** `ai_collection_db`（JDBC 由 Nacos 下发），与 L4b 联调、催收引擎写入的是同一个库；正式跑通后再切生产库。  
> **关联文档**: [管理后台设计文档](./MOCASA催收系统升级_Phase1_管理后台设计文档.md) · [开发进度](./MOCASA催收系统升级_Phase1_管理后台开发进度.md) · [Pilot 前端上线方案](./channel/MOCASA催收系统升级_Phase1_管理后台前端上线方案.md) · [测试 SSOT](./testing/MOCASA催收系统升级_Phase1_测试文档.md) · Nacos / 进程启动见 [操作说明_Nacos本地启动](./操作说明_Nacos本地启动.md)

---

## 目录

- [1. 系统概览](#1-系统概览)
- [2. 启动与登录](#2-启动与登录)
  - [2.7 本机测通管理后台](#27-本机测通管理后台当前步骤)
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
- [9. 钉钉告警（A1–A3 / A7–A9）](#9-钉钉告警a1a3--a7a9)

---

## 1. 系统概览

管理后台由**后端**（`collection-admin`，Spring Boot，端口 `8888`）和**前端**（React + Ant Design + Vite，端口 `5173`）组成。前端通过 Vite 代理把 `/auth`、`/cases`、`/config`、`/catalog`、`/plans`、`/ops`、`/compliance`、`/admin`、`/mock` 转发到后端。

| 地址 | 用途 | 能否当页面打开 |
|------|------|---------------|
| `http://127.0.0.1:5173` | 管理后台 UI（看板、案件、配置） | ✅ **用这个** |
| `http://localhost:8888` | REST API（程序调用） | ❌ 会 404 或返回 JSON 报错 |

把 `8888` 存成书签是最常见的"打不开"原因——它是 API 不是页面。

**Pilot 域名** `https://collection-admin.mocasa.com` 的打开方式、白名单和上线步骤见 [前端上线方案](./channel/MOCASA催收系统升级_Phase1_管理后台前端上线方案.md)。在那份方案落地之前，公网除 `/webhook/` 外仍是 403；本文 §2 只讲本机 `5173`。

### 1.1 功能菜单

| 菜单 | 路由 | 说明 | 数据来源 |
|------|------|------|----------|
| Data Analysis | `/dashboard` | **今日执行 / 复盘** 两视图。默认「今日执行」：触达时间线、分渠道触达、AI 波次、日切断言、风险。复盘：昨日作业集（T+1、dpd>0）/ 渠道×Stage 矩阵 / 分渠道趋势。打开即查 + 手动刷新，无自动刷。 | `GET /dashboard/today`、`/dashboard/daily-by-channel`、`/dashboard/outreach/realtime` 等 |
| Strategy Config | `/strategy` | 策略总览 + 阶段计划 + 渠道连通性 + Holdout 评估参数 + 配置版本/回滚 | `/catalog/overview`、`/config/*` |
| Templates | `/templates` | SMS / Push **可编辑热更新** + Email 只读；Plans 页可编辑计划模板 | `/catalog/overview`、`/config/script-templates`、`/config/plan-templates` |
| Case Monitor | `/cases` | 案件检索 + 按案件下钻计划（含已完成）步骤与触达时间线 | `/cases/search`（读 `t_ai_collection` 投影，见 §3.4）、`/plans/by-case/{caseId}/history`、`/plans/{planId}/steps`、`/plans/timeline/{userId}` |
| Ops Queue | `/ops` | 异常队列只读浏览；ACK/Resolve 未接引擎，按钮禁用 | `/ops/exceptions` |
| Compliance | `/compliance` | 冻结 / 解冻 / 升级 | `/compliance/*` |
| System Admin | `/system` | 账号管理（仅超管）：禁用 / 改角色 / 重置口令 / 新建 | `/admin/accounts` |

---

## 2. 启动与登录

进程、Nacos 与 `.env` 的通用说明见 [操作说明_Nacos本地启动](./操作说明_Nacos本地启动.md)。本节只覆盖后台 UI。

### 2.1 一键启动（推荐，Windows）

在项目根目录执行，脚本会按需拉起后端(8888)与前端(5173)并打开浏览器：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/start-admin.ps1
```

关掉这两个 PowerShell 窗口（或 Ctrl+C）即停服务。仓库目前没有 `start-admin.sh`。

**本次改了代码之后，只点一键启动可能仍是旧程序。** 脚本逻辑是：8888 已经在听 → **不重启后端**；`collection-admin.jar` 已经存在 → **不重新编译**；5173 已经在听 → **不重启前端**。正确顺序：

1. 关掉旧的后端 / 前端两个窗口（或结束占用 8888、5173 的进程）。
2. 删掉或覆盖旧包后再启动（在项目根目录）：

```powershell
$env:JAVA_HOME = "C:\Users\voghion\java\jdk8u502-b07"
$env:Path = "$env:JAVA_HOME\bin;C:\Users\voghion\apache-maven-3.9.11\bin;" + $env:Path
mvn -pl collection-admin -am package -DskipTests
powershell -ExecutionPolicy Bypass -File scripts/dev/start-admin.ps1
```

3. 浏览器打开 `http://127.0.0.1:5173`，登录后左侧 **Data Analysis**。默认应是「今日执行」，没有「经营 / 催收 / 策略」三个 Tab。

**和「等 30 分钟才会触达」无关。** 后台页面在登录后立刻可用。催收引擎和后台 API 是**同一个 Java 进程**（8888）。会不会对外发短信/外呼，只取决于白名单，不取决于等半小时：

| `.env` 的 `COLLECTION_SCAN_CASE_IDS` | 一键启动后会发生什么 |
|---|---|
| `999999999`（只看后台） | 引擎在跑，但扫描名单是假 id → **零触达**。看板仍显示测试库里已有的历史/Pilot 数据 |
| 本轮批准的真实 case_id | 到期步骤会按 **PHT 槽点**（08:00 / 09:15 / 11:30 / 12:00 / 14:00 / 14:30 / 16:15 / 18:40）触发，不是启动后 30 分钟。已经过点的到期步骤会在扫描周期内很快补打 |

local 与共享测试库上的 Pilot **不要同时用真实白名单扫库**，会互抢步骤。只看新看板时用占位白名单。

钉钉告警：local 默认 **不发送**（见 §9）。看新看板不需要先配机器人。

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

> **扫描白名单（`COLLECTION_SCAN_CASE_IDS`，2026-09 新增守卫）**：local 模式必须非空，否则 `ScanIsolationGuard` 拒绝启动——空名单等于对共享库全量案件发起触达、并与其他实例互抢步骤。该值从 `.env` 读取（`start-local.ps1` 会自动逐行注入）。仅「看后台」不跑扫描时用占位值（如 `999999999`）零触达；真实联调填入本轮批准的 case_id（逗号分隔）。确需全量扫描才置 `collection.scan.allow-full-scan=true`（勿在共享库使用）。
>
> 脚本会在启动日志打印两行，用来确认配置是否真的生效：
> ```
> [start-local] 已加载 .env 变量 8 个
> [start-local] scan whitelist = 999999999
> ```
> 若 `scan whitelist` 为空，说明 `.env` 未被正确解析——不要直接改 `application-local.yml` 绕过，先按 §7 排查。

> **端口被 Nacos 覆盖（2026-09 修复）**：Nacos `intelligent-collection-common.yml` 下发了 `server.port=56384`，且 Nacos ConfigData 优先级**高于** `application-local.yml`（后者写的 8888 会被静默覆盖）。56384 与 WorkBuddy / Cursor 等 IDE 的服务代理端口冲突，本机必然 `PortInUseException`。`start-local.ps1` / `start-local.sh` 已在命令行强制下发 `--server.port=8888`（命令行参数优先级最高）。换端口用环境变量 `LOCAL_ADMIN_PORT`，**不要复用 `APP_PORT`**（那是容器端口口径，值为 8080）。

启动成功后健康检查 `http://localhost:8888/actuator/health`：

- 返回 `{"status":"UP"}` 为正常；
- 本机未装 Redis 时返回 `{"status":"DOWN"}`（HTTP 503）——这是 Redis 健康指标报警，**不影响后台登录与使用**（登录会话走 Tomcat 内存、事件总线走内存，不依赖 Redis）。

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

**Pilot 账号与权限口径（上线方案 v1.3）**：

- 三个独立测试账号 `mocasa-admin` / `mocasa-viewer` / `mocasa-operator`，禁止多人共享。角色分别为 `SYSTEM_ADMIN` / `VIEWER` / `OPERATOR`。明文和 Pilot env 片段见已 gitignore 的 `docs/ops/管理后台测试账号_20260909.local.md`。
- 后端按角色拒绝写接口（VIEWER 只读；高危、活计划、目录写仅超管）。前端隐藏高危回滚入口、VIEWER 写按钮禁用，**安全边界仍是后端 403**。
- DLQ 重放、配置版本回滚、故障注入仅 `SYSTEM_ADMIN`；二次确认 + 必填原因。紧急停催第一版仍走 runbook，后台无全局停催按钮。
- 登录后以库表 `t_admin_account` 为准（启动时空表会从 env 种子一次）。超管在 System Admin 禁用/改角色/重置口令；不能禁用或降级最后一个 SYSTEM_ADMIN。
- 配置变更日志只在 Strategy → Config Versions（含回滚）。
- 催收系统已有钉钉业务告警，本轮不新建管理后台专属钉钉告警。

### 2.6 30 秒自检

```bash
curl -sS http://localhost:8888/actuator/health    # 应 200
curl -sS -o /dev/null -w "%{http_code}" http://127.0.0.1:5173/dashboard
```

Windows 可用 `Invoke-WebRequest` 替代。第三条若返回 JSON，说明 Vite 把整段 `/dashboard` 代理到了后端——只应代理 `/dashboard/outreach` 等 API 子路径。

### 2.7 本机测通管理后台（当前步骤）

目标：在 **local + 零触达** 下把看板和各页走一遍。**不要**为了测钉钉打开 `collection.scheduler.enabled`（会在共享库上跑扫描，且可能往群里打告警）。钉钉 Pilot 写入是上线闸门，见开发进度「上线闸门 G1」。

1. `.env` 保持 `SPRING_PROFILES_ACTIVE=local`、`COLLECTION_SCAN_CASE_IDS=999999999`。
2. 若刚改过 Nacos：关掉旧的 8888 窗口再 `start-admin.ps1`（已占用端口时脚本**不会**重启，Nacos 新键进不了进程）。
3. 打开 `http://127.0.0.1:5173` 登录。
4. 按下面清单点一遍（详细口径见 §3）：

| 页 | 过关标准 |
|---|---|
| Data Analysis → 今日执行 | 默认就是这一页；**没有**「经营 / 催收 / 策略」三 Tab。触达时间线、分渠道、AI 接通、日切、风险能出数或合理空态（分母 0 为 `—`，接通时间空为「未回传」，未到点为「尚未到时间」） |
| Data Analysis → 复盘 | 能切过去；昨日作业集 / 渠道×Stage 矩阵 / 分渠道趋势。矩阵格是单渠道×Stage；AI 格是线路接通率 |
| 右上角刷新 | 整页重拉，没有自动刷 |
| Case Monitor | 能搜到测试库案件；点进计划/时间线不 500 |
| Ops Queue | 能打开；A3 悬挂若有会在此，本机无扫描器时可能为空 |
| Strategy / Templates / Compliance / System | 能打开、不白屏即可（本期看板为主，这些页本期未改） |

本机看板读的是测试库里**已有**的历史/Pilot 写入，不是等触达。群里暂时不应出现 A1/A2/A3（local 扫描器未装配）。

---

## 3. 各页面操作

### 3.1 Data Analysis（触达看板）

**入口**：登录后左侧 **Data Analysis**，或 `/dashboard`。默认打开 **今日执行**（PHT 当日）；可切到 **复盘**（昨日作业集 T+1 + 近 7 日质量）。点右上角 **刷新** 会重拉当前页全部接口，**没有**定时自动刷或 WebSocket。

日常观测以本页「今日执行」为准，不再按日新写自动跑 Markdown（历史 `docs/testing/records/` 保留）。钉钉 A1–A3 / A7–A9 是叫醒通道，不替代看板。

**今日执行**

| 模块 | 看什么 | 注意 |
|------|--------|------|
| 今日触达时间线 | 08:00 SMS/PUSH、09:15 / 11:30 / 14:30 / 16:15 / 18:40 AI、12:00 PUSH、14:00 EMAIL（条数不固定；S4 后段当天可能只有 09:15 一通 AI） | 未到点显示「尚未到时间」。Email 发送=0 显示「正常零发送」，不标红。AI 看线路接通/真人/有效沟通；FAILED 仅线路与我方；下钻为 RPC 后的 disposition。 |
| 分渠道触达 | SMS / PUSH / EMAIL 各一张卡 | **禁止**把多渠道合成一条送达率 |
| AI 接通 | 时间线：线路接通/真人/有效沟通；明细默认线路接通；FAILED = network+our_system | 线路接通=`was_answered`；真人=`party=human`。明细列 `party` / `effective_conversation` / `right_party`。不展示时长。`disposition` 只在 `right_party=yes` 时有值。 |
| 日切断言 | 迁出后再 DELIVERED、升档、inbox、新建 plan | 迁出后再打必须为 0，>0 标红 |
| 风险 | 悬挂、Guard、到期仍 PENDING | 悬挂 >0 去 Ops Queue / Case Monitor |

**复盘**：昨日作业集（昨天有催收动作且动作时 dpd>0 的去重案件；OS 为作业集 T+1 现值；结清/回收只算作业集内当日全清；结清时刻缺失显示「未就绪」）、渠道×Stage 矩阵（AI 格=线路接通率，不是真人）、分渠道趋势、AI 近 7 日漏斗。不展示日终在催全量、近 7 天触达、48h 结清、Aging。

**口径（SMS/PUSH/EMAIL）**（分母只算真正发出去的）：

| 指标 | 含义 | 是否计入发送率分母 |
|------|------|-------------------|
| **Records** | timeline 全部 OUT 行 | — |
| **Attempted** | 实际发起发送（SENT/DELIVERED/FAILED 等） | ✅ 分母 |
| **Sent** | 催收系统成功发出（SENT / DELIVERED / ACCEPTED），**不是**供应商「已送达用户」回执 | 分子 |
| **Skipped** | **未发送**（Guard 拦截、非里程碑 Email 等） | ❌ |
| **未归类** | `result` 不在标准枚举（渠道质量表）；矩阵里「未归类」= Stage 仍 UNKNOWN | — |
| **发送率** | Sent ÷ Attempted；分母为 0 显示 `—` | Skipped **不进**分母 |

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

- **Ops Queue**：按状态筛选异常。ACK/Resolve **尚未接入引擎**（不会重拨/关通话），OPEN 行按钮禁用，非 OPEN 不显示 ACK。
- **Compliance**：对案件执行冻结 / 解冻 / 升级（写审计日志）。
- **System Admin**：仅超管。管理登录账号（`t_admin_account`）。配置变更日志在 Strategy → Config Versions。

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
  "http://localhost:8888/dashboard/today"                                  # 今日执行（触达时间线 / 日切 / AI 波次）
curl -s -b cookies.txt \
  "http://localhost:8888/dashboard/outreach/realtime?days=30"              # 触达复盘（分渠道，无跨渠道合并率）
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

-- 告警去重表（2026-09-07 已在测试库创建；没有此表时 A1/A2/A3 扫描会 SQL 失败）
SHOW TABLES LIKE 't_alert_dedup';
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
| 启动报 `Port 56384 was already in use` | Nacos 下发的端口覆盖了本地配置。确认用的是修复后的 `start-local.ps1`（已强制 `--server.port=8888`）；手工启动时必须自己带上该参数 |
| 启动报 `拒绝启动：profile=local 未配置 collection.scan.case-id-whitelist` | `.env` 缺 `COLLECTION_SCAN_CASE_IDS`。用占位值 `999999999` 即可零触达启动；见 §2.2 |
| `.env` 明明配了却不生效 | Windows PowerShell 5.1 的 `Get-Content` 默认按 ANSI/GBK 解码，UTF-8 中文注释会导致其后的配置行被**合并进注释行而静默丢失**（实测 13 行读成 11 行）。三个 `.ps1` 脚本已统一改为 `-Encoding UTF8`；自查时看启动日志的「已加载 .env 变量 N 个」是否与 `.env` 里的键值数一致 |
| 看板仍是「经营 / 催收 / 策略」三 Tab | 前端还是旧代码：关掉 5173 窗口后重新 `start-admin.ps1`；改过 `vite.config.ts` 必须重启 Vite |
| 今日执行接口 404 | 后端还是旧 jar：按 §2.1 先 `mvn package` 再启动；确认 Vite 代理含 `/dashboard/today`（勿代理整段 `/dashboard`） |
| 一键启动后没有发短信 | 先看 `.env` 白名单是不是 `999999999`（零触达是故意的）；真触达还要等 **PHT 槽点**，不是等 30 分钟 |
| Catalog 接口报错 | 确认 `catalog/catalog-metadata.json`、`script-drafts.json` 存在于 classpath |

**为什么经常"打不开"**：前后端是两个进程、必须同时跑，关窗口即停；`8888` 是 API 不是页面，容易被误存书签；`npm run dev` 不开机自启。日常直接用 §2.1 的一键脚本。页面与权限设计见 [管理后台设计文档 §4 / §5](./MOCASA催收系统升级_Phase1_管理后台设计文档.md#4-信息架构总览)。

---

## 8. 附录：默认账号与测试地址

以下均为**测试 / L4b 占位**，不是生产凭据；不得写入生产密钥。

- 登录：`admin` / `local-dev`（角色 `SYSTEM_ADMIN`，仅 local profile；与 `.env.example` 一致）
- 前端：`http://127.0.0.1:5173`
- 后端：`http://localhost:8888`
- Pilot（上线方案落地后）：`https://collection-admin.mocasa.com`，仅白名单出口（办公室 / VPN / 已登记补充 IP，清单见上线方案）；网页账号见本机 `docs/ops/管理后台测试账号_*.local.md`，不要把口令写进本手册。SSH 发版主机是 `ubuntu@34.87.136.20`（`bdp01`），见 [发版手册](./channel/MOCASA催收系统升级_Phase1_发版手册.md)
- L4b 统一触达地址：手机 `+639451374358` / 邮箱 `wzynju@126.com` / Push token `1a0018970bf0c19de04`
- L4b 主流程案件：`99000000`（S0）～ `99000005`（S4）

---

## 9. 钉钉告警（A1–A3 / A7–A9）

本批钉钉 CRITICAL：AI Call 三类（FAILED 率过高 A1，>35% 且 n≥20；到期步骤漏打 A2；EXECUTING 悬挂 A3）+ 消息渠道 FAILED 率（A7 SMS / A8 PUSH / A9 EMAIL，各自 **>15%** 且 attempted ≥20）。A1 的 FAILED = `failure_class` 为 `network` 或 `our_system`（DECLINE/空号等 callee **不告**），与看板同一口径。A3 还会写入后台 **Ops Queue**。**A3 不在 dispatch 后 15 分钟就告**：波次聚合下排队超过 15 分钟仍可能正常。有 `timeout_time` 时与超时哨兵对齐（到期仍 EXECUTING 且无 `session.completed`）；没有 `timeout_time` 才用 dispatch+15 分钟兜底。异常队列**不自动结单**，session 回来后仍须人工关闭。群消息带前缀 `【催收告警】`，不含明文手机号。n&lt;20 不告；同日同槽只发一次。正好等于阈值不告（`>`）。

渠道 FAILED 口径与看板「今日执行」一致：分子 = `FAILED`/`REJECTED`/`BOUNCED`，分母 = ATTEMPTED（`DELIVERED`/`SENT`/`ACCEPTED` + 失败三种），**SKIPPED 不计分母**。SMS / PUSH / EMAIL **分渠道、禁止合并**。SMS 全日一槽 `0800`；PUSH 分 `0800`（12 点前）与 `1200`（12 点后）；EMAIL 全日 `1400`（无里程碑日发送=0 属正常，n&lt;20 不告）。

**2026-09-09 现状**：测试库已有 `t_alert_dedup`；Pilot 库也有该表。本机 Nacos `intelligent-collection-local.yml` 已有 webhook，机器人通道已用关键词消息验过。**local 默认不发告警**（扫描器未装配）。Pilot `/opt/app/pilot.env` 已写入 webhook，jar 含扫描器（A1>**35%**；A7/A8/A9>**15%**）。公网 nginx 未改，`/` 仍 403。真告警时群里应出现 `【催收告警】 A1/A2/A3/A7/A8/A9`。

### 9.1 小白版：以后要怎么配

目标：给催收群加一个「只会收系统消息、不会聊天」的机器人，系统出大事时群里多一条字。

1. 打开要用的钉钉群 → 群设置 → **智能群助手**（或「机器人」）→ 添加机器人 → **自定义**。
2. 起名例如「催收看板告警」。安全设置选 **自定义关键词**，关键词填 **`催收告警`**（必须这四个字，和程序发出去的前缀一致）。  
   **不要选「加签」**：当前程序还不会算钉钉签名，选了加签会发送失败。
3. 完成后复制那一长串网址（以 `https://oapi.dingtalk.com/robot/send?access_token=` 开头）。这就是 webhook。**当密码看**：不要发到群里、不要提交 git、不要贴进聊天记录。
4. **正式上线前**把这串写进 Pilot：Nacos Data ID **`intelligent-collection-pilot.yml`** 的 `collection.alert.dingtalk.webhook`，或 Pilot 机 `/opt/app/pilot.env` 的 `COLLECTION_ALERT_DINGTALK_WEBHOOK`。不要写 `*-common.yml`。本机 `.env.example` 里有注释占位，**不要把真值写进会入库的文件**。
5. 写完**重启 Pilot 容器**。local 一键启动默认 `collection.scheduler.enabled=false`，即使本机 Nacos 有 webhook 也不会每分钟扫描。Pilot 默认会扫。
6. 自检：看日志里出现 `[alert]`；真告警时群里应有 `【催收告警】 A1 ...` 或 `A7 SMS FAILED ...`。连续三天同一条会改成只打日志、不再刷屏。

只看本地后台、不配机器人：完全没问题，看板照常。

### 9.2 专业版：注入、生效条件、运维边界

| 项 | 约定 |
|---|---|
| 配置键 | 环境变量 `COLLECTION_ALERT_DINGTALK_WEBHOOK` → `collection.alert.dingtalk.webhook` |
| 通道 | 自定义机器人 `POST` JSON，`msgtype=text`；失败只打日志，不抛、不断触达 |
| 扫描器 | `AiCallAlertScanner`，`@Scheduled(cron = 每分钟)`，`@ConditionalOnProperty(collection.scheduler.enabled=true)` |
| local | `scheduler.enabled` 默认 false（到期扫描走 `TriggerScanner`，与 Cloud Scheduler 互斥）→ **不装配扫描器** |
| Pilot | `scheduler.enabled` 默认 true → 装配扫描器；webhook 为空则 log-only |
| 去重表 | `t_alert_dedup`（alert_id + object_key + PHT 日历日）；连续 3 个日历日 SENT 后改 SUPPRESSED |
| 安全 | webhook URL = 密钥。机器人侧用自定义关键词 `催收告警`。客户端**未实现**钉钉 HMAC 加签 |
| 文案 | 固定前缀 `【催收告警】`；含波次/渠道/分子分母/SIP Top/stepId；禁止手机号 |

换群或轮换机器人：只换环境变量并重启 Pilot，不必发版。删表行可解除当日抑制（一般不需要）。
