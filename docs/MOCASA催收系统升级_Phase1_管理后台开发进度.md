# MOCASA 催收系统升级 — Phase 1 管理后台开发进度

> **日期**: 2026-09-11（原文 2026-09-01，按日追加）
> **设计基线**: 管理后台设计文档 **v1.8**
> **跟踪方式**: 本文件是管理后台开发进度的跟踪入口（testing 目录无副本）。完成任务后更新状态列并追加进度日志。

---

## 当前状态（2026-09-07 · v1.6 交付）

本期已按设计文档 v1.6 落地，**不是**旧六模块人格 UI。

| 项 | 状态 | 说明 |
|---|---|---|
| 看板两视图 | ✅ | 默认「今日执行」：触达时间线、分渠道触达、AI 波次、日切断言、风险；「复盘」：昨日作业集 / 渠道×Stage 矩阵 / 分渠道趋势。无经营·催收·策略三 Tab |
| 口径 | ✅ | 禁止跨渠道合并送达率；AI FAILED = `network`+`our_system`（callee 含 DECLINE/空号不计）；真人=`party=human`；disposition 分布仅 `right_party=yes`；分母 0 显示 `—` |
| API | ✅ | `GET /dashboard/today`、`GET /dashboard/daily-by-channel`；打开即查 + 手动全量刷新；无 WebSocket |
| 钉钉 A1/A2/A3 + A7–A9 | ✅ 代码 | 扫描器在 `collection.scheduler.enabled=true` 时每分钟跑（**Pilot 默认开，local 一键启动默认关**）。未配 webhook 只打日志。文案前缀 `【催收告警】`，不含手机号。A1>35%，FAILED 与看板同为 network+our_system；A7 SMS / A8 PUSH / A9 EMAIL 各自 >15% 且 n≥20 |
| `t_alert_dedup` | ✅ | DDL 已写入 `db/schema.sql` / `schema-admin.sql`；**2026-09-07 已在测试库 `ai_collection_db` 建表**（空表） |
| Webhook 快照 | ✅ | `writeAiCallSession` 补 `stage_snapshot`/`dpd_snapshot`；ON DUPLICATE 不覆盖已有快照 |
| 日常观测 | ✅ 约定 | 「今日执行」替代按日自动跑 Markdown；停写前提见设计 §5.1.7 |
| 录音/转写代理 | ⬜ 后置 | 设计有，本批不做 |
| 钉钉机器人（本机） | ✅ 通道 | 自定义机器人已建；webhook 已写入 Nacos `intelligent-collection-local.yml`（真值不入库）。关键词直发已通。**local 默认不装配扫描器**，本机看板测试不会往群里打 A1–A3 / A7–A9 |
| 钉钉机器人（Pilot） | ✅ 2026-09-09 | A1 阈值 **35%**；A7/A8/A9 渠道 FAILED **>15%** 已随 jar 生效。webhook 在容器内；公网 `/` 仍 403。未改 nginx、未传 dist |

### 上线闸门（Pilot 发版前，不可跳过）

当前先把**本机管理后台测通**，Pilot 发版工程后做。下列项在**正式上线 / 切真实监控**前必须闭合：

| # | 项 | 状态 | 做什么 |
|---|---|---|---|
| G1 | Pilot 钉钉 webhook | ✅ | 2026-09-08 写入 `/opt/app/pilot.env`；发版后容器内变量已生效（不打印真值） |
| G2 | Pilot 扫描器 | ✅ | 2026-09-09 发版：A1>**35%**；A7 SMS / A8 PUSH / A9 EMAIL 各自 >**15%**（正好等于不告）。profile=pilot、scheduler=true。公网 `/` 仍 403 |

单测：`collection-admin` 及依赖模块 `mvn test` 通过（2026-09-07）。

### 管理后台前端首次开放闸门（2026-09-09 新增）

催收系统已有钉钉业务告警；**本轮不新建管理后台专属钉钉告警**。前端上线另开窗口，以[前端上线方案](./channel/MOCASA催收系统升级_Phase1_管理后台前端上线方案.md)为 SSOT。

| # | 项 | 状态 | 验收 |
|---|---|---|---|
| G3 | 前端生产 `/api` 前缀 | ✅ 本地 | `.env.development` / `.env.production` + dev/preview `/api` 代理；production build 通过 |
| G4 | 管理 API 登录拦截闭合 | ✅ 本地 | `/plans/**`、`/catalog/**` 已补；13 条定向认证测试及全 reactor 测试通过 |
| G5 | Pilot 独立账号 | 🟡 待部署 | 三个独立测试账号已生成，首批统一 SYSTEM_ADMIN；凭据文件已 gitignore，尚未写 Pilot env |
| G6 | 原子静态发布 | ⬜ | `releases/<id>` + `current` 软链；新旧两版可瞬时切换 |
| G7 | nginx 目标配置 | 🟡 仓库已完成 | 四条 ACL、HTTP/HTTPS 分离、mock deny、ACME、Cookie 已入仓；本机无 Nginx/Docker，待 Pilot 实机 `nginx -t` |
| G8 | Webhook 与三层回滚 | ⬜ | jar / nginx 变更前后 POST 对照；jar / nginx / 静态资源可分别回滚 |
| G9 | 紧急停催 runbook | ⬜ | 至少演练一次全局停催、恢复、状态确认与责任人记录 |

**不上线阻断**：G3–G8。G9 最迟在管理后台投入真实运营前闭合。

### 上线稳定后的权限与运营任务

| # | 任务 | 状态 | 约定 |
|---|---|---|---|
| P1-1 | 三角色 RBAC | 🟡 | 后端按方法授权；账号以 `t_admin_account` 为准（env 仅空表种子） |
| P1-2 | 高危操作保护 | 🟡 | DLQ / 配置回滚 / 故障注入仅 SYSTEM_ADMIN；紧急停催仍无按钮，跟 runbook |
| P1-3 | 二次确认与审计 | 🟡 | 回滚/DLQ/注入要求 confirm+reason；DLQ 与配置变更原有操作人审计。不做双人审批 |
| P1-4 | 主链路对账 | ⬜ | 应入案 → 实际入案 → 应触达 → 实际触达/回调 |
| P1-5 | 配置安全 | ⬜ | diff、静态校验、乐观锁、版本和回滚 |
| P1-6 | 告警处理闭环 | ⬜ | 复用催收系统既有告警，做认领、备注、解决、关闭；不建独立机器人 |
| P2-1 | 渠道运维台 | ⬜ | 渠道状态、失败率、暂停/恢复、备用渠道 |
| P2-2 | 案件 360 增强 | ⬜ | 还款、录音、转写和完整事件时间轴 |

---

## 目录

- [1. 本期范围与目标](#1-本期范围与目标)
- [2. 现状基线（代码审计结论）](#2-现状基线代码审计结论)
- [3. 任务拆解](#3-任务拆解)
- [4. 依赖与阻塞项](#4-依赖与阻塞项)
- [5. 验收口径](#5-验收口径)
- [6. 进度日志](#6-进度日志)

---

## 1. 本期范围与目标

- **范围**：§5.1 数据分析看板六模块——回收效果（置顶）/ 案件迁徙与老化 / 触达与结果链 / AI Call 分区 / 风险信号 / 渠道 ROI，外加口径规范（§5.1.7）。
- **目标**：把看板从「渠道运营视角」切到「催收管理视角」——回收置顶、四层结果链（触达→result_label→PTP→还款）、AI Call 业务结果首屏。
- **边界**：策略配置、案件监控、异常队列等其他模块**本期不动**（已实现，见 §2.2）。

## 2. 现状基线（代码审计结论）

### 2.1 看板现状

| 项           | 现状                                                                                                                         |
| ----------- | -------------------------------------------------------------------------------------------------------------------------- |
| 后端 API      | 仅 `GET /dashboard/outreach/realtime`（JdbcTemplate 聚合 `t_contact_timeline`）                                                 |
| AI Call API | ❌ 无 `/dashboard/aicall/realtime`；无录音/转写代理 `AiCallMediaController`                                                          |
| 前端          | `DashboardPage.tsx` 卡片+表格（Timeline 记录/送达率/结果分布/按渠道·Stage·模板），**无图表库**                                                      |
| 数据底座        | ❌ `t_ai_call_session` 不存在；`result_label`/`promises` 零落库（AI Call 会话仅存 `t_channel_callback_audit.canonical_payload` 原始 JSON） |

### 2.2 其他模块实现验证（对照设计文档 §5.2–§5.8）

| 模块 | 状态 | 是否需要修改 | 证据 |
|---|---|---|---|
| 策略配置 §5.2 | ✅ 已实现 | 否 | `ConfigController` script/plan-templates CRUD + 校验 |
| 案件与计划监控 §5.3 | ✅ 已实现（AI Call 增强除外） | 录音/转写代理属 AI Call 观测栈，随 §5.1 一起补 | `CaseQueryController` + `PlanQueryController` |
| 渠道与系统运维 §5.4 | ❌ 未实现 | 是（P1，本期不做） | 无渠道运维页面/熔断/切流实现 |
| 异常队列 §5.5 | ✅ 已实现 | 需补 `HIGH_SENSITIVITY_LABEL` 类型；PLAN_STUCK 与钉钉通知待确认 | `OpsQueueController` + `DlqController` + `FaultInjectionController` + CALLBACK_TIMEOUT 哨兵 |
| 基础合规操作 §5.6 | 🟡 部分（phase2 默认关闭） | 否 | `ComplianceOpsController` 完整 |
| 策略评估 §5.7 | 🟡 部分 | 否 | 仅 evaluation-settings 读写 |
| 系统管理 §5.8 | ✅ 已实现 | 否 | `AdminController` me/audit-logs |

> **核心结论**：Phase 1 基础（案件/计划/配置/异常队列/合规/系统）已实现；**整个 v1.3「AI Call 观测迭代」（`t_ai_call_session`、AI Call 看板分区、录音/转写代理、钉钉告警）是「已设计未实现」**——这正是 §5.1 本期要补的核心。

## 3. 任务拆解（按依赖排序）

状态图例：⬜ 待办 / 🔵 进行中 / ✅ 完成 / 🔴 阻塞

### 3.1 数据底座

| # | 任务 | 状态 | 依赖 | 验收 |
|---|---|---|---|---|
| T1 | `t_ai_call_session` DDL（结构化提取 Facade 回调原生词） | ✅ | 无 | 表可建，字段与手册 §9.3/§9.4 对齐（已落 `db/schema.sql`） |
| T2 | FacadeWebhookService 写路径：从 `session.completed` 提取 result_label/summary/promises/sip_code/line_reason 落 `t_ai_call_session` | ✅ | T1 | 编译通过；验证需真实回调或回填历史 canonical_payload |
| T3 | `promises[]` 落库（promises_json 列填充） | 🔴 | 真实 PTP 案例 | promises_json 非空可查（测试样本少、暂无案例） |

### 3.2 后端 API

| # | 任务 | 状态 | 依赖 | 验收 |
|---|---|---|---|---|
| T4 | `GET /dashboard/aicall/realtime`（业务结果首屏 + 渠道卫生层） | ✅ | T1/T2 | 接口已建 + 编译通过；验证需 `t_ai_call_session` 建表 + 数据 |
| T5 | `GET /dashboard/recovery`（当日回收 + 分 Stage 回收率 + 治愈率） | ⬜ | 还款口径（§4） | 分 Stage 回收率对照 PRD 基线 |
| T6 | `GET /dashboard/migration`（Stage 迁徙 + 滞留 + S4 待核销） | ⬜ | 无（读 `t_ai_collection`） | 迁徙率期初 cohort 口径 |
| T7 | `GET /dashboard/risk`（高敏标签清单 + 断联 + 悬挂 + Guard 拦截） | ✅ | T1/T2 + §5.5.1 新类型 | 四项全落：高敏标签 + 断联（INVALID_NUMBER）+ 悬挂（超 15 分钟）+ Guard 拦截（SKIPPED+COMPLIANCE_BLOCKED） |

### 3.3 前端

| #   | 任务                                        | 状态  | 依赖    | 验收                                                           |
| --- | ----------------------------------------- | --- | ----- | ------------------------------------------------------------ |
| T8  | 图表库选型（recharts / antd-charts，与 AntD 生态一致） | ✅   | 无     | 暂用零依赖纯 CSS `BarList` 满足分布可视化（SIP/Stage/DPD）；重型图表库留待需要复杂图表再引入 |
| T9  | DashboardPage 重构为六模块结构（回收置顶）              | ✅   | T4–T7 | **v1.6 纠正**：改为今日执行 / 复盘两视图，不再做六模块人格页 |
| T10 | AI Call 分区双屏（业务结果首屏 + 卫生层第二屏）             | ✅   | T4/T8 | 前端已落 + 端到端验证（建表 + 回填 1146 行，接口返回真实漏斗/标签/SIP 分布）              |

### 3.4 口径规范落地

| # | 任务 | 状态 | 依赖 | 验收 |
|---|---|---|---|---|
| T11 | 48h 归因窗口（触达→还款转化） | 🔵 | 还款台账 | 触达→48h 还款转化已落（touched/converted48h，读 timeline + settled_at）；冷层精确归因待 BigQuery |
| T12 | 案件级分母统一 + 双口径纪律（§5.1.7） | ⬜ | T5/T6 | 率指标分母=案件数 |

## 4. 依赖与阻塞项

| 依赖                     | 阻塞什么                                                                                                                                       | 状态                                      |                                 |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------- | ------------------------------- |
| PTP 案例样本少（测试期暂无真实承诺案例） | L3 PTP 率/兑现率                                                                                                                               | 🔴 占位展示，待产生真实 PTP 案例                    |                                 |
| 还款事实源                  | 冷层用 BigQuery `detail.t_loan_repayment_plan`（还款计划事实表，键 `plan_id`=催收 loan_id，T+1）；热层用 repaymentEvent + 投影补 `settled_at`/`last_paid_amount` 列 | 当日回收金额热层、分 Stage 回收率、治愈率、48h 归因         | 🟡 冷层已明确；热层待落 settled_at/paid 列 |
| SIP/接通质量（406/BUSY）     | 渠道卫生层数据                                                                                                                                    | 随供应商修复（验收出口 406<5% / BUSY<25% / 接通≥15%） |                                 |
| 图表库选型                  | 前端可视化                                                                                                                                      | ⬜                                       |                                 |
| Pilot 钉钉 webhook 未写入   | 正式上线后群里收不到 A1/A2/A3（扫描器 log-only）                                                                                                 | ⬜ **上线闸门 G1**                        | 本机测通后再做；见「当前状态 · 上线闸门」 |

## 5. 验收口径

- **回收看板**：分 Stage 回收率对照 PRD §2.3 基线（S1 ~36% / S2 ~17% / S3 ~5% / S4 ~4%），画目标线；金额 + 笔数双口径。
- **AI Call 分区**：result_label 三桶分布（业务结果 / 合规风险 / 未分类），未知标签不丢弃；第一屏业务结果、第二屏渠道卫生层。
- **归因窗口**：统一 48h（§5.1.7），全渠道同窗。
- **高敏标签**（dispute 等）：仅展示 + 异常队列 `HIGH_SENSITIVITY_LABEL` 提醒，**不自动暂停**（v1.6 决策 D4）。

## 6. 进度日志

- 2026-09-01：设计文档升 v1.6（§5.1 重构 + 四项决策）；完成代码审计（现状基线 §2）；落地 `t_ai_call_session` DDL（T1 ✅）。
- 2026-09-01（下午）：落地 `GET /dashboard/portfolio`（读 `t_ai_collection` 投影：在催案件 / OS 余额 / 逾期 / 结清 + Stage / DPD 分布，对应 T5/T6 热层雏形）；前端 `DashboardPage` 加「催收组合概况 · 回收与迁徙」区置顶（T9 部分）；澄清 promises 恒空 = 测试样本少（非供应商未实现），修正文档与阻塞项归因；还款数据源定调（冷层 BigQuery repayment_plan + 热层 repaymentEvent）。
- 2026-09-01（傍晚）：**本地端到端跑通**——后端编译部署（下载 Maven 3.9.11 + JDK8；修 ONLY_FULL_GROUP_BY 的 SQL、start-local.sh 把 Unix 路径 `/d/AI` 传给 Windows java 的 bug），8899 后端返回真实投影（200 案件 / OS ₱120 万 / Stage·DPD·status 分布）；前端 `vite.config.local.ts` 补 `/dashboard/portfolio` 代理，5173 刷新即见「催收组合概况」置顶。**全程本地（本机 Windows 工作区），未动 pilot**。
- 2026-09-02：AI Call 分区数据链路落地——T2（`FacadeWebhookService` 写 `t_ai_call_session`，提取 result_label/summary/promises_json/sip_code/line_reason/was_* 三层布尔，JdbcTemplate 直写 + ON DUPLICATE KEY UPDATE 兜底）+ T4（`GET /dashboard/aicall/realtime`：四层漏斗 + result_label 三桶 + SIP 分布）+ T10 前端（AI Call 分区卡，业务结果首屏 + 渠道卫生层）；全模块编译通过。热层还款 paidAmount/repayTime 落库代码已改并编译通过，但用户要求先理清需求，暂不部署。
- 2026-09-02（下午）：**AI Call 分区端到端验证通过**——本地库建 `t_ai_call_session`（pymysql 建表 + executemany 回填 1146 行历史会话）；`GET /dashboard/aicall/realtime` 返回真实数据：漏斗 dispatched 1146 / ringing 1143 / answered 14 / aiConnected 12 / invalid 2；result_label 5 个非空标签（dispute=合规风险 + vague_commitment/incomplete/refused_to_discuss=未分类，**开放标签集实证**）；SIP 486 515 / 406 391 印证供应商质量问题。portfolio 的 todayRecovered 已回退（等热层还款讨论）。
- 2026-09-02（下午续）：labelBucket 补全（`vague_commitment`=模糊承诺 → 业务结果，手册 prior_contacts 有定义）；发现 **result_label 无完整枚举契约**——手册只有 `prior_contacts.result` 旧枚举（promise_obtained/no_answer/refused/vague_commitment/callback_requested），`incomplete`/`refused_to_discuss` 语义不明，建议向供应商索取 result_label 完整枚举。
- 2026-09-02（下午再续）：**热层还款部署**——建 `last_paid_amount`/`settled_at` 两列 + 恢复 portfolio 的 `todayRecovered`（后端 + 前端），编译通过 + 验证通过（`todayRecovered` 返回 0.00，等真实还款事件流入即出数）。澄清：热层（实时当日回收）与冷层（T+1 分 Stage 回收率）**非二选一**，热层已落，冷层后续走 BigQuery。
- 2026-09-02（下午三续）：T7 补 Guard 拦截（`status=SKIPPED` + `result=COMPLIANCE_BLOCKED`，引擎守卫标记）+ 结果链 L4「触达→48h 还款转化」（portfolio 加 `touchConversion`：touched/converted48h，读 timeline + settled_at，48h 归因窗口）；编译通过。至此 §5.1 三大板块 + 热层还款 + 结果链 L4 全部落地。
- 2026-09-02（下午四续）：图表可视化——引入零依赖纯 CSS `BarList` 组件（横向条形图），SIP 分布从 Tag 改为条形图；决策：暂不引入重型图表库（recharts/antd-charts），待需要复杂图表（折线/饼图）再装。前端 tsc 无新错误。
- 2026-09-02（下午五续）：**看板简化（按用户反馈）**——删 DPD 分桶（与 Stage 语义重复）、删「逾期金额」（与 OS 余额语义重复，逾期催收本就一个意思）、Stage 表只留 OS 余额一列金额；AI Call 漏斗删「拨通/振铃」（BUSY/FAILED 不该算拨通，口径误导）+ 加时间范围 Select（今天/今天昨天/近7天/近30天）；触达与结果链剔除 AI Call（`channel <> 'AI_CALL'`，AI Call 已单列分区、delivered 口径不适用）+ 删按模板表。编译通过。
- 2026-09-02（下午六续）：顶部加「近 7 天触达」Statistic（复用 touchConversion.touched，零后端改动）。**「触达/回收/归因」三概念分开展示**：触达=近7天触达案件数、回收=今日回收+结清数、归因=触达→48h还款转化（分母=触达案件，48h 窗口是近似非精确因果，精确靠 holdout §5.7）。前端 tsc 无新错误。
- 2026-09-07：按 v1.6 交付看板两视图 + A1/A2/A3 扫描器 + `t_alert_dedup`（测试库已建表）+ webhook stage/dpd 快照；去掉跨渠道 `queryByStage`；操作手册同步。钉钉机器人尚未创建，local 一键启动默认不发告警。
- 2026-09-08（续）：接通明细改真人接通（排除 SNR）；时长只算 `ended_at − answered_at`，缺一则 —；去掉 CONNECT_AND_STOP 告警条（属正常策略，跳过原因表仍可见）。
- 2026-09-08（DPD）：接通明细不再用案件当前 dpd 回填（535728 09:18 接通后 10:26 还清，现值 −29 不是通话时 DPD）。Owner 抽样去掉 45 天 due_date 窗口（498789 类三期旧账单被裁掉）。
- 2026-09-08（Owner 粘性补丁）：出队 DPD>30 176 + 结清 27 + DPD<−3 12，补新人 208+12，`status=1` 仍 605（不砍到 600，不中午补发）；`cal_dt` 未改。Strategy Config Stage Plan 展开为每天各渠道（仍 5 行 S0–S4，S4 桶内写明 D+61~90 降频）。
- 2026-09-08（钉钉）：A1 FAILED 阈值 15%→30%→**35%**。Pilot 已换 jar；未改 nginx、未传 dist。公网 `/` 仍 403。
- 2026-09-09（渠道 FAILED）：扫描器加 A7 SMS / A8 PUSH / A9 EMAIL，FAILED 率 **>15%** 且 attempted ≥20（SKIPPED 不计分母；PUSH 分 0800/1200）。A1 仍 35%。Pilot 已换 jar；未改 nginx、未传 dist。公网 `/` 仍 403。
- 2026-09-09（看板）：「五槽收口」改为「今日触达时间线」（不固定条数）；列改为时段/结果；未到点显示「尚未到时间」；AI 只看实拨、下钻接通标签；接通明细标签/Stage/波次可筛选。
- 2026-09-09（前端上线评审）：新增 G3–G9。首次开放要求 `/api`、登录拦截闭合、每人独立账号（先统一 SYSTEM_ADMIN）、release + current 原子发布、nginx 实机验收、Webhook 对照与三层回滚；稳定后做 VIEWER / OPERATOR / SYSTEM_ADMIN 三角色 RBAC，高危操作仅 SYSTEM_ADMIN。催收系统已有钉钉告警，本轮不建管理后台专属钉钉告警。
- 2026-09-10（看板六层）：时间线/波次改为线路接通/真人/有效沟通；FAILED 与 A1 改为 `network`+`our_system`；callee 其他单独展示；接通明细补 party/有效沟通/right_party；disposition 分布仅 `right_party=yes`。前端仍须另传 dist，不进 jar。
- 2026-09-11（复盘作业集）：资产卡改为昨日作业集（T+1、动作时 dpd>0、不含 SKIPPED）；结清/回收只在作业集当日；去掉近 7 天触达/48h；接通明细去掉时长与接通类型，列名 `effective_conversation`；矩阵标注 AI=线路接通。
- 2026-09-11（RBAC 第一刀）：后端按角色+HTTP 方法授权；VIEWER 只读；OPERATOR 可写模板/冻结/异常；DLQ/回滚/故障注入仅超管且要 confirm+reason。前端藏回滚、禁用 VIEWER 写按钮。账号仍走 env ROLE。
- 2026-09-11（账号管理 + 去重）：System 去掉与 Strategy 重复的配置日志；超管在 System 管理 `t_admin_account`（env 仅空表种子）。Ops ACK/Resolve 未接引擎，OPEN 外不显示 ACK、按钮不可点。
