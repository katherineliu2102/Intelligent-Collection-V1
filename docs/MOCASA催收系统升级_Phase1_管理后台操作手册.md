# MOCASA 催收系统升级 Phase 1 — 管理后台操作手册

> **适用版本**：Phase 1 / Phase 1.5 切片（**2026-08-24**：案件目录口径见 §3.4）  
> **读者**：运营、测试、策略、研发联调同事  
> **数据源**：当前连接 **测试 MySQL**（Nacos 下发 JDBC，与 L4b 联调同一库）；正式跑通后再切生产库。  
> **关联文档**：[`管理后台设计文档`](./MOCASA催收系统升级_Phase1_管理后台设计文档.md) · [`P0 测试`](./testing/admin-p0-test.md) · [`L4b 触达核对清单`](./testing/MOCASA催收系统升级_Phase1_L4b触达内容核对清单.md) · [`e2e50 入案 JSONL`](./testing/e2e50_caseEvents.jsonl)

---

## 1. 系统概览

管理后台由 **后端**（`collection-admin`，Spring Boot，**8888**）和 **前端**（React + Vite，**5173**）组成。

| 地址 | 用途 | 浏览器能否当页面打开 |
|------|------|---------------------|
| **http://127.0.0.1:5173** | 管理后台 UI（看板、案件、配置） | ✅ **用这个** |
| http://localhost:8888 | REST API（程序调用） | ❌ 会 404 或 JSON 报错 |

后端读 **测试库** `ai_collection_db`（与 L4b、引擎落库同一库），Case Monitor / Dashboard 看到的数据即联调测试数据。

### 1.1 功能菜单

| 菜单 | 路由 | 说明 | 数据来源 |
|------|------|------|----------|
| **Data Analysis** | `/dashboard` | **触达效果看板**（渠道/Stage/模板、送达率） | `GET /dashboard/outreach/realtime` ← `t_contact_timeline` |
| Strategy Config | `/strategy` | 策略总览、Holdout、配置版本/回滚 | `/catalog/overview`、`/config/*` |
| Templates | `/templates` | SMS/Push 热更新、计划模板、Email 只读 | `/config/script-templates`、`/config/plan-templates` |
| Case Monitor | `/cases` | 案件检索 + 计划步骤 + 触达时间线 | `/cases/search`、`/plans/*`（**现行检索仍读 `t_collection`，见 §3.4**） |
| Ops Queue | `/ops` | 异常队列 ACK / Resolve | `/ops/exceptions` |
| Compliance | `/compliance` | 冻结 / 解冻 / 升级 | `/compliance/*` |
| System Admin | `/system` | 审计日志 | `/admin/audit-logs` |

---

## 2. 启动与登录（推荐一键）

### 2.1 一键启动（推荐）

在 **项目根目录** `Intelligent-Collection-V1` 执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\dev\start-admin.ps1
```

脚本会：未运行时启动后端(8888) → 未运行时启动前端(5173) → 打开浏览器。  
**关闭**：关掉两个 PowerShell 窗口（或 Ctrl+C）即停服务。

### 2.2 手动启动

**终端 1 — 后端（必须先起）**

```powershell
cd d:\AI\Intelligent-Collection-V1
powershell -ExecutionPolicy Bypass -File scripts\dev\start-local.ps1
```

**终端 2 — 前端**

```powershell
cd d:\AI\Intelligent-Collection-V1\collection-admin\ui
& "C:\Program Files\nodejs\npm.cmd" install    # 仅首次
& "C:\Program Files\nodejs\npm.cmd" run dev -- --host 127.0.0.1
```

> 不能直接双击 `index.html`（会白屏）。修改 `vite.config.ts` 后须 **重启** `npm run dev`。

### 2.3 登录

打开 **http://127.0.0.1:5173/**，用户名 `admin`，角色 `SYSTEM_ADMIN`。会话 Cookie 鉴权。

### 2.4 30 秒自检

```powershell
Invoke-WebRequest http://localhost:8888/actuator/health -UseBasicParsing   # 应 200
Invoke-WebRequest http://127.0.0.1:5173 -UseBasicParsing                   # 应 200
Invoke-WebRequest http://127.0.0.1:5173/dashboard -UseBasicParsing         # 应 200 且为 HTML
```

---

## 3. 各页面操作

### 3.1 Data Analysis（触达看板）

**入口**：登录后左侧 **Data Analysis**，或 `/dashboard`。

**指标口径（重要）**

| 指标 | 含义 | 是否计入送达率分母 |
|------|------|-------------------|
| **Records** | timeline 全部 OUT 行 | — |
| **Attempted** | 实际发起发送（DELIVERED + FAILED 等） | ✅ 分母 |
| **Delivered** | 供应商受理/送达 | 分子 |
| **Skipped** | **未发送**（Guard、非里程碑 Email 等） | ❌ 算 Other 类 |
| **Other** | 其他未归类 result | ❌ |
| **送达率** | Delivered ÷ Attempted | Skipped **不算**分母 |

**时间窗口**：默认 **近 30 天**（测试数据若超过 7 天未跑批，选 7 天会**看起来为空**——这是正常现象，改 30/90 天即可）。

**维度**：按渠道、Stage、scriptSlot 下钻；右侧为 result 分布与计划状态（全量）。

**REST 自查**

```bash
curl -s -b cookies.txt "http://localhost:8888/dashboard/outreach/realtime?days=30"
```

### 3.2 Strategy Config

1. **Strategy Overview**：阶段、触达窗、渠道连通性（绿/红）。
2. **Evaluation Settings**：Holdout 比例，Save 后乐观锁 version。
3. **Config Versions**：选中历史版本 → Rollback。

### 3.3 Templates

- **SMS / Push**：Edit 写 DB，~10s 热更新；Reset 回 YAML。
- **Plans**：按 Stage 编辑步骤序列。
- **Email**：SendGrid 托管，只读。

### 3.4 Case Monitor

1. 输入 Case ID / User ID → Search。
2. **展开行**：Plans（含已完成）+ Contact Timeline。
3. L4b 合成案示例：`99000002` — 可见多计划、SMS/PUSH/EMAIL 步骤与 `DELIVERED`/`SKIPPED`。

**隐私（现在就会看到）**

| 列 | 是否展示 | 说明 |
|----|----------|------|
| Phone | 是，**脱敏** | 如 `+63****358`，完整号不在列表 |
| Email | 是，**脱敏** | 如 `w***@126.com` |
| 姓名 | **否** | 话术里会用 `{name}`，后台列表不展示 |

**真实案 / AI Call 注意（升级前）**

- 新入案写在 **`t_ai_collection`**。当前 Search 查的是旧表 **`t_collection`**。只投了 `e2e50_caseEvents.jsonl`、旧库没有该 `loan_id` 时，**Case Monitor 会是空的**，引擎却可能已建计划。这是已知缺口，不是「没入案」。
- 时间线能看到 `AI_CALL` 的 `result` / `providerMsgId`。接通原因、Facade `line_outcome` 在 `t_channel_callback_audit`，**页面上还没有**，SQL 自查见 §6.2。
- 升级完成后：Search 以 `t_ai_collection` 为准；可用真实 `caseId`（如 e2e50）代替 `9900000x`。

### 3.5 Ops / Compliance / System

- **Ops Queue**：OPEN/ACK/RESOLVED 筛选，ACK / Resolve。
- **Compliance**：冻结 / 解冻 / 升级。
- **System Admin**：`t_config_change_log` 审计。

---

## 4. 常见「看起来没数据」说明

| 现象 | 原因 | 处理 |
|------|------|------|
| **Dashboard 全 0** | 默认时间窗内无 timeline（测试数据最后写入可能 >7 天前） | 选 **近 30 天 / 90 天** 或跑新一轮 L4b |
| 浏览器 JSON `UNAUTHORIZED` | 误开 **8888** 或旧代理把 `/dashboard` 转到 API | 只开 **5173**；改 vite 后重启前端 |
| `ERR_CONNECTION_REFUSED` | 前后端未同时运行 | `scripts\dev\start-admin.ps1` |
| Email 送达率低但 Case 里 DELIVERED | Skipped 是**未发**，不应进分母 | 看板已按 Attempted 算率；Case 看 timeline 逐条 |
| Case 有数据、看板没有 | 看板按**时间窗**聚合，Case 按案件查全量 | 放大看板天数 |
| **投了 JSONL / 真实案，Case Monitor 为空** | Search 仍读 `t_collection`，新案在 `t_ai_collection` | 先 SQL 查投影（§6.2）；后台改查询源后重试 |
| **有 AI_CALL 计划，页面看不出接通原因** | UI 未接回调审计 | 查 `t_channel_callback_audit`（§6.2） |

---

## 5. 热更新（SMS / Push / 计划模板）

`Templates 页 Edit → PUT /config/* → t_script_template / t_contact_plan_template → 引擎 ~10s 重载`。

- 优先级：**DB(ACTIVE) > Nacos/YAML**
- 初始 seed：`db/seed-admin-config.sql`
- 开关：`channel.config.db-source-enabled`（默认 true）

### 5.1 SMS / Push 文案保存护栏

保存前前后端双校验（后端为唯一拦截门禁）：

| 规则 | SMS | Push |
|------|-----|------|
| 允许变量 | 仅 `{name}` `{amount}` `{dpd}` `{repaymentUrl}` | 同左 |
| 必填变量 | body 必须含 `{amount}`、`{repaymentUrl}` | title/body 不可同时为空 |
| 模板字数硬上限 | body ≤ 300 | title ≤ 40，body ≤ 120 |
| 样例渲染上限 | ≤ 400（>160 / >320 仅警告分段成本） | title ≤ 60，body ≤ 180 |

- 编辑弹窗：变量 chip 一键插入、字数计数、样例渲染预览、非法变量红字提示。
- Dry-run：`POST /config/script-templates/validate`（不落库，返回 errors/warnings/preview）。
- 非法保存返回 `TEMPLATE_VALIDATION_FAILED`（HTTP 400）。

---

## 6. 数据链路自查

### 6.1 REST

```bash
curl -s "http://localhost:8888/plans/by-case/99000002/history?limit=10"
curl -s "http://localhost:8888/plans/timeline/99000002?limit=50"
curl -s "http://localhost:8888/dashboard/outreach/realtime?days=30"
curl -s "http://localhost:8888/catalog/overview"
```

### 6.2 SQL（测试库）

```sql
-- 看板数据量（按窗口）
SELECT COUNT(*) FROM t_contact_timeline
 WHERE direction='OUT' AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY);

SELECT channel, result, COUNT(*) FROM t_contact_timeline
 WHERE direction='OUT' AND created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
 GROUP BY channel, result;

SET @caseId = 99000002;
SELECT channel, result, created_at FROM t_contact_timeline
 WHERE case_id=@caseId ORDER BY created_at;

-- 新入案投影（真实 caseEvent / e2e50）
SELECT case_id, user_id, dpd, stage, collection_status, overdue_amount, upcoming_amount,
       borrower_phone, borrower_email
  FROM t_ai_collection WHERE case_id = @caseId;

-- AI Call 回调审计（disposition 为映射后枚举；原生 line_outcome 在 canonical_payload）
SELECT plan_id, step_id, result, disposition, provider_msg_id, received_at
  FROM t_channel_callback_audit
 WHERE case_id = @caseId
 ORDER BY received_at;
```

| result | 含义 | 看板归类 |
|--------|------|----------|
| `DELIVERED` | 已发送/受理 | Attempted + Delivered |
| `SKIPPED` | **未发送** | Skipped（不进送达率分母） |
| `FAILED` | 发送失败 | Attempted |

---

## 7. 故障排查

| 问题 | 处理 |
|------|------|
| 前端白屏 | `npm run dev` 启动；勿 `file://` 打开 |
| 接口 404 | 检查 `vite.config.ts` 代理；**勿**代理整段 `/dashboard`（只代理 `/dashboard/outreach` 等 API 子路径） |
| 401 | 重新登录 |
| Holdout 409 | Refresh 后带最新 version 重试 |
| 8888 端口占用 | 结束 Java 进程后重启 |
| 看板接口 500 | 看后端日志；MySQL/Nacos 连通 |

### 为什么经常「打不开」？

1. **两个进程**必须同时跑（5173 + 8888），关窗口即停。  
2. 容易书签成 **8888**（API 不是页面）。  
3. `npm run dev` **不会开机自启**。  

→ 日常用 **`start-admin.ps1` 一键脚本**。

---

## 8. 附录

| 项 | 值 |
|----|-----|
| 登录 | `admin` / `SYSTEM_ADMIN` |
| 前端 | http://127.0.0.1:5173 |
| 后端 | http://localhost:8888 |
| 一键启动 | `scripts/dev/start-admin.ps1` |
| L4b 触达 | 手机 `+639451374358` / 邮箱 `wzynju@126.com` |
| L4b 案件 | `99000000`～`99000005` |
| 真实入案样例 | `docs/testing/e2e50_caseEvents.jsonl`（Search 改投影表后才进 Case Monitor） |
| 相关脚本 | `scripts/dev/start-local.ps1`、`scripts/dev/refresh-test-db.py` |
