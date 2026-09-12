# 迭代：AI Call 五波 + 日限放宽 + 全量重建

> **日期**: 2026-09-11（补丁 2026-09-12：夜间窗口已过，改为当日 catch-up）  
> **环境**: Pilot  
> **状态**: 代码 / 模板种子 / 告警窗口 / 关联文档已按本文落地。**9/12 catch-up 已完成**：发版 + 热加载模板 + `force=true` 重建（431/431，frozenMaxId=2259）。9/12 09:15 旧两波已打出，未追溯补发；当日剩余从 **11:30** 起。

---

## 0. 拍板

| 项 | 口径 |
|---|---|
| 波次 | **一次上五波**：09:15 / **11:30** / 14:30 / **16:15** / **18:40**（PHT） |
| S0 | **不拨** AI（模板不加 AI 槽） |
| S1 / S2 / S3 | 每个有外呼的日块 **5 波** |
| S4 D+31~60 | **5 波**（与 S1–S3 同） |
| S4 D+61~90 | **保持一天一通**：仅 **09:15** 一波（不加 11:30/14:30/16:15/18:40） |
| 日限 | `AI_CALL=10`，`daily-total-limit=15` |
| 存量计划 | **全量重建** S1–S4 活跃计划；S0 不重建 |
| 生效 | **首选**：最后一波之后（≥19:00 PHT）且次日日切 03:35 之前，新步骤从次日 08:00 起。**错过夜间窗口**：见 §4 catch-up，用 `force=true`，从**尚未过去**的槽起算，不追溯已过槽 |

未改：验签、出站 Facade API、结果映射（party / effective_conversation）、CONNECT_AND_STOP、Cloud Scheduler Job 数量（仍靠 `planStepDue` 扫 `trigger_time`）。

---

## 1. 为什么这样改

现网两波（09:15 / 14:30）覆盖一天两端，中间和傍晚空窗大。五波把未接通案件再铺开，不增加「通了还打」：真人有效沟通后引擎仍 **CONNECT_AND_STOP** 跳过同日剩余 AI。

日限从 `AI_CALL=2`、合计 `5` 放宽：里程碑日五波后触达约为 SMS+Push+Email+5×AI = **8**，必须同时抬合计，否则多出来的波次会被 Guard 静默跳过。`10` / `15` 是上限，不是每天打满。

S4 后段维持一天一通，避免晚期强度被五波带上去（编排规格原口径 D+61~90 ≤1 呼/日，靠 **dayBlocks 少铺槽**，不靠日限=1）。

---

## 2. 日槽（PHT）

| 时间 | 渠道 | S1–S3 与 S4 D+31~60 | S4 D+61~90 | S0 |
|---|---|---|---|---|
| 08:00 | SMS | 有 | 有 | 无 AI；Push/SMS 不变 |
| **09:15** | AI_CALL | 有 | **仅此 AI** | 无 |
| **11:30** | AI_CALL | 有 | 无 | 无 |
| 12:00 | PUSH | 有 | 有 | — |
| 14:00 | EMAIL | 仅里程碑日 | D+31 / D+75 | D0 |
| **14:30** | AI_CALL | 有 | 无 | 无 |
| **16:15** | AI_CALL | 有 | 无 | 无 |
| **18:40** | AI_CALL | 有 | 无 | 无 |

AI 话术 `templateId` 仍按档：S1=301 / S2=302 / S3=303 / S4=304。Facade `batch_id` 形如 `mocasa-YYYYMMDD-HHMM-N`（`1130` / `1615` / `1840` 为新槽键）。

触达窗仍 08:00–21:00，静默 21:00–08:00。18:40 波须在 21:00 前收口。

---

## 3. 日限

| 键 | 旧（Pilot 缺省） | 新 |
|---|---|---|
| `channel.compliance.daily-limit.AI_CALL` / `CHANNEL_DAILY_LIMIT_AI_CALL` | 2 | **10** |
| `channel.compliance.daily-total-limit` / `CHANNEL_DAILY_TOTAL_LIMIT` | 5 | **15** |
| SMS / PUSH / EMAIL 单渠道 | 1 | **不变** |

环境变量优先于 `application-pilot.yml` 优先于 Nacos。三处不得再写 `AI_CALL=2` 或合计 `5` 当生产口径。local/L4a 缺省合计 3 **不改**（联调脚本自己抬 env）。

Guard 对 step `retryCount>0` 不重复占配额。CONNECT_AND_STOP 的 SKIPPED 不占新配额。

---

## 4. 引擎与计划实例

- **不改** Plan 表结构、推进状态机、CONNECT_AND_STOP。
- PlanFactory 继续读 `t_contact_plan_template.plan_json` 的 `dayBlocks`，用 `PhtSlotScheduleCalculator.futureSlots(now)` 只排 **尚未过去** 的槽。
- **只改模板不会改已经生成的步骤。** 因此必须 **取消 S1–S4 活跃计划并按当前投影 stage/dpd 重建**，否则明天仍是两波。
- 取消原因：`MANUAL`（运维策略刷新）。取消后 **同一事务立即建新计划**。禁止只取消不建（日切对 `MANUAL`/`MANUAL_CLEANUP` **不会**补建）。
- **不做** owner_date 门控（否则非当日 NEW 的在催案会被跳过）。
- **跳过** S0、已终态、投影 CEASED/结清、当前有 `AI_CALL` 且 `EXECUTING` 的计划（避免打断在途呼叫；扫完后补跑）。
- 重建窗口（默认）：**≥19:00 PHT 且次日 03:35 之前**。若在 18:40 前重建，`futureSlots` 会排出当晚 18:40 并可能立即外呼。
- **Catch-up（错过夜间窗口）**：日切已过、当日早波已按旧计划打出时，禁止再等当晚 19:00（会再空一天新波次）。发版并热加载模板后，用 `force=true` 重建。`futureSlots(now)` 自然丢掉已过槽：例如 9/12 09:45 重建，当日不再补 08:00/09:15，从 **11:30** 起铺新五波剩余槽。已接通案件仍走 CONNECT_AND_STOP，不会「通了还打」。接口默认仍拒绝 03:35–19:00，避免误点；catch-up 必须显式 `force=true`。
- **分页冻结**：取消后新计划 id 更大。接口先取当前活跃 S1–S4 的 `MAX(id)`（或沿用上次返回的 `frozenMaxId`），只处理 `id <= frozenMaxId` 的存量，续跑须带 `maxId=` 该值。

---

## 5. 告警与看板

| 面 | 改什么 |
|---|---|
| A1 | 已按 `batch_id` 的 `YYYYMMDD-HHMM` 分波，新槽会自然分桶；阈值仍 FAILED>35% 且 n≥20 |
| A2 漏催 | `WaveKey.slotHhmmFromTrigger` **必须**识别 1130 / 1615 / 1840，否则新波次到期未拨 **不会告** |
| A3 悬挂 | 与波次数无关。口径：有 `timeout_time` 则对齐超时哨兵；无则 dispatch+15min。看板「AI 悬挂」同口径。**9/12 五波包已带上，不必再单独发版。** |
| 今日执行时间线 | 在 09:15 与 14:30 之间/之后插入 11:30、16:15、18:40 三张 AI 卡 |
| 波次表 | 继续从 `batch_id` 解析，无需写死两波 |

`slotHhmmFromTrigger` 窗口（互不重叠，约 ±15 分钟）：

| 槽 | 命中 |
|---|---|
| 0915 | 09:00–09:29 |
| 1130 | 11:15–11:44 |
| 1430 | 14:15–14:44 |
| 1615 | 16:00–16:29 |
| 1840 | 18:25–18:54 |

旧实现把 08:45–10:15 都算 0915、14:15–15:15 算 1430，五波后必须收窄，避免 11:30 被吞进 0915。

---

## 6. 实施清单（按序）

1. 落本文。  
2. 更新 `db/seed-phase1-config.sql` 与 Pilot `t_contact_plan_template`（S1–S4；S0 不动）并 bump `config_version`。  
3. `application-pilot.yml` 日限缺省 10 / 15；Pilot `/opt/app/pilot.env` 同步；核 Nacos 未写死旧值。  
4. `WaveKey` + 今日槽位 + 单测。  
5. 重建入口：`POST /ops/plans/rebuild-strategy`（仅 SYSTEM_ADMIN），取消+重建 S1–S4。  
6. 发版（jar：告警/日限/重建入口）→ 热加载模板（10s）→ 窗口内跑重建（夜间 ≥19:00；错过则 catch-up `force=true`）→ 抽检下一波 `trigger_time`。  
7. 同步关联文档（§7）。

9/12 catch-up 顺序：改 `pilot.env` 日限 → 打含新 jar 的镜像 → 执行 `scripts/pilot/20260911-ai-call-five-waves.sql`（勿跑 seed DELETE）→ 等模板热加载 → dry-run → `force=true` 正式重建 → 抽 S2 / S4 D+65。

---

## 7. 关联文档（必须改口径）

| 文档 | 改什么 |
|---|---|
| 本文 | SSOT |
| [渠道编排规格](./MOCASA催收系统升级_Phase1_渠道编排规格.md) §7.11 / S4 子区间 | S1–S3 与 S4 D+31~60：≤5 呼/日；D+61~90 仍 ≤1 |
| [管理后台设计文档](../MOCASA催收系统升级_Phase1_管理后台设计文档.md) 今日槽 / 两波 / 日限 2 | 五波与 10/15 |
| [管理后台操作手册](../MOCASA催收系统升级_Phase1_管理后台操作手册.md) | 时间线钟点 |
| [catalog-metadata.json](../../collection-admin/src/main/resources/catalog/catalog-metadata.json) | aiCall / schedule |
| [T5 Pilot 手册](../testing/runbooks/MOCASA催收系统升级_Phase1_T5Pilot准备与演练手册.md) | 日限 2/5 → 10/15 |
| [功能测试指南](./MOCASA催收系统升级_Phase1_collection-channel功能测试指南.md) | S4 D+61 仍 ≤1/日；S1–S3 槽位数 |
| [发版手册](./MOCASA催收系统升级_Phase1_发版手册.md) §5 | 日限口径一句 |

不改 VALUBO 接入手册（供应商契约未变）。

---

## 8. 验收

1. 模板：S0 的 `AI_CALL` 槽 = 0；S1 任一日块 AI 时间 = 五波；S4 `dpdDay>=61` 仅 09:15 一通。  
2. 重建后抽 1 个 S2、1 个 S4 D+65：S2 次日有 5 个 AI `PENDING`；S4 后段次日只有 1 个 AI。  
3. 夜间窗口重建：无「当晚 18:40 新计划外呼」。catch-up：无「已过槽被追溯补发」（如 09:45 重建后不应再有当日 08:00/09:15 新步骤）。  
4. 容器 `CHANNEL_DAILY_LIMIT_AI_CALL=10`、`CHANNEL_DAILY_TOTAL_LIMIT=15`（或未设而 yml 缺省已是 10/15）。  
5. A2 对 `trigger_time=11:30` 能归到 `1130`。  
6. 新波次 Facade `batch_id` 出现 `-1130-` / `-1615-` / `-1840-`（S4 后段没有）。

## 9. 回滚

- 模板：从备份表 / 本迭代前 `plan_json` 还原并 bump version。  
- 日限：env/yml 改回 2 与 5 后发版。  
- 计划：不能自动回到「旧两波步骤」；需再重建一次（用旧模板）。jar 回滚见发版手册 §3。
