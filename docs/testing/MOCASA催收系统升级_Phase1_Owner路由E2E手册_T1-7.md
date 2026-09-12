# Phase 1 Owner 路由 E2E 手册（T1-7）

> **用途**：隔离环境（L4b）用 mock owner feed 跑 Owner 路由必测场景，作为 T4 影子验证前的开发侧出口。
> **判定口径**：场景是否通过的裁决以[测试 SSOT](./MOCASA催收系统升级_Phase1_测试文档.md)为准；本手册只提供注入手法、命令与断言字段。
> **环境**：L4b 隔离环境（测试 topic + 合成案 99000000–99000005，凭证与连接见[ L4b 环境交接清单](./runbooks/MOCASA催收系统升级_Phase1_L4b环境交接清单.md)）。
> **关联**：[按日 Owner 路由开发计划](./MOCASA催收系统升级_Phase1_按日Owner路由开发计划.md) T1-7 · [DPD30 样本](./MOCASA催收系统升级_Phase1_Owner路由DPD30样本_20260903.md)（Pilot/影子期用）

---

## 目录

- [1. 环境与前置检查](#1-环境与前置检查)
- [2. mock owner feed 用法](#2-mock-owner-feed-用法)
- [3. 对账触发与隔离模式语义](#3-对账触发与隔离模式语义)
- [4. 场景矩阵](#4-场景矩阵)
- [5. 断言 SQL 速查](#5-断言-sql-速查)
- [6. 记录要求](#6-记录要求)

---

## 1. 环境与前置检查

| 项 | 要求 | 检查方式 |
|---|---|---|
| 测试 topic | `intelligent-collection-cases-test1`（发布脚本护栏拒绝生产 topic） | `echo $GCP_PUBSUB_TEST_TOPIC` |
| 水位表列 | `t_ai_owner_reconcile.owner_case_count`（2026-09-03 更名迁移后） | `SHOW COLUMNS FROM t_ai_owner_reconcile LIKE 'owner_case_count';` |
| 投影归属字段 | `t_ai_collection.owner` / `owner_date`，索引 `idx_ai_collection_owner_date` | `SHOW COLUMNS FROM t_ai_collection LIKE 'owner%';` |
| 白名单 | 含 99000000–99000005（隔离模式；见 §3 语义差异） | 应用日志 `[Ingestion]` 白名单条目 |
| 调度链 | 案件订阅消费正常、`dailyRoll` tick 可达 | [L4b 环境交接清单](./runbooks/MOCASA催收系统升级_Phase1_L4b环境交接清单.md) |
| 基线清理 | 起跑前清当日水位与测试案残留活跃计划 | `DELETE FROM t_ai_owner_reconcile WHERE reconcile_date = CURDATE();` |

## 2. mock owner feed 用法

```bash
export GCP_PUBSUB_PROJECT=fintech-all
export GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1
export GOOGLE_APPLICATION_CREDENTIALS=$PWD/credentials.json
# DB_HOST / DB_USER / DB_NAME 等 l4b-env.local.sh

# 名单文件：每行一个 loan_id，# 注释与空行忽略。名单即「当日 NEW 集」。
cat > /tmp/new_list_day1.txt <<'EOF'
99000000
99000001
99000002
EOF

scripts/test/l4b-pubsub/publish-test-messages.sh ownerfeed /tmp/new_list_day1.txt        # 当日名单，dpd 取旧库
scripts/test/l4b-pubsub/publish-test-messages.sh ownerfeed /tmp/new_list_day1.txt 1      # dpd+1（内容变 → caseVersion 变）
scripts/test/l4b-pubsub/publish-test-messages.sh ownerfeed /tmp/new_list_day1.txt 0 "2026-09-03 02:50:00"  # occurredAt 覆盖（构造迟到/乱序）
```

要点：

- **迁出（LEAVE）= 名单缺席**：当日名单去掉该案，03:35 后对账取消其活跃计划。
- **再入（ENTER）= 名单加回**：次日名单重新含该案，对账重发 `CASE_INGESTED` 建新计划。
- **dpd 偏移两条路径都要测**：偏移 0 = 同内容重发（`caseVersion` 不变，走「指纹相同仍刷新归属日」路径，场景 4）；偏移 1 = 内容变化（全量刷新路径）。
- **不发 owner=LEGACY**：契约要求数仓只向新系统 topic 发 NEW；消费侧收到非 NEW 按 PoisonMessage 进 DLQ。毒消息路径验证用 `file` 模式手工构造（改样例的 `owner` 值）。

## 3. 对账触发与隔离模式语义

- **触发**：`dailyRoll` tick（调度 topic → 应用，03:35–05:55 PHT 每 5 分钟）。E2E 不等真实窗口，用 `scripts/test/publish-schedule-tick.py dailyRoll` 手动触发（每 tick 推进一页，写完水位后幂等跳过）。
- **隔离模式（白名单开启，L4b 即此形态）**：`OwnerReconcileHandler` 对白名单逐案对账——`owner_date ≠ 当日` → LEAVE；`owner_date = 当日` 且未结清有 stage → ENTER；完即写水位。**零收推迟不生效**（那是全量模式的保护）。
- **归属日单调保护**：`owner_date` 只进不退（早 `occurredAt` 不回退）。因此**不能靠倒拨 occurredAt 模拟「昨日名单」**——跨日场景按 §4 场景 4/5 的两种路径构造。
- **水位门控**：`t_ai_owner_reconcile` 无当日行时，Stage 日切、到期扫描、回调推进、渠道 dispatch 均被门控；对账完成仅执行一次（重复 tick 幂等）。

## 4. 场景矩阵

> 命名 E2E-N 对应改造计划 §7.1 逐条。每条含：目的 / 前置 / 注入 / 断言。SQL 见 §5。

### E2E-1 乱序、重复、跨日迟到与 replay

- **前置**：案 99000000 已入投影（`owner_date = 当日`）。
- **注入**：① `ownerfeed` 发案后，再用 `file` 模式连发 3 条同案消息：`occurredAt` 早于当日（迟到）、与首条相同（重复）、晚于首条但同日（乱序）。② 同一 `eventId` 重发一条（消息级 replay 等价路径；subscription seek 级 replay 可选）。
- **断言**：投影 `owner_date` 仍为当日且未被早消息回退；计划数不变（无重复 `CASE_INGESTED`）；重复 `eventId` 被 inbox 去重丢弃。收到 `owner=LEGACY` 的构造消息时进 `t_event_dlq` 且不落投影。
- **通过**：全部断言成立，应用日志无 WARN 以外的异常。

### E2E-2 缺席迁出五状态（§7.1-2 核心）

- **前置**：Day1 `ownerfeed` 全名单 → 触发 dailyRoll 建计划 → 推进步骤，使 5 案分别处于：`PENDING`（未开始）/ `STEP_SCHEDULED`（待执行）/ `STEP_EXECUTING`（执行中）/ `STEP_WAITING`（AI Call 等回调，手法见[引擎真拨 Webhook 测试记录](./records/MOCASA催收系统升级_Phase1_AI_Call_引擎真拨Webhook测试记录_20260825.md)）/ `PLAN_COMPLETED`（穷尽）。
- **注入**：Day2 名单仅保留 1 案保底（其余 5 案缺席）→ 触发 dailyRoll 直至写水位。
- **断言**：5 案活跃计划（未终态者）全部 `PLAN_CANCELLED` + `cancel_reason=ROUTED_TO_LEGACY`；`t_contact_timeline` 出现 `CASE_OWNER_RECONCILED`；此后 tick 到期扫描，5 案无任何新触达/新步骤；水位 `owner_case_count = 1`（保底案）。
- **通过**：五状态全覆盖且零新触达。

### E2E-3 对账未完成时门控

- **前置**：发当日名单后**不触发** dailyRoll（水位无当日行）。
- **注入**：先触发 `planStepDue` tick（到期扫描），再触发 `dailyRoll` 一次，最后再重复触发一次。
- **断言**：水位缺失期间 Stage 日切不推进（`t_ai_collection.stage` 不变）、到期扫描无动作；dailyRoll 后水位写入、日切与扫描仅执行一次；第二次 dailyRoll tick 幂等跳过（水位已存在）。
- **通过**：门控生效且恢复后恰执行一次。

### E2E-4 连续多日 NEW

- **前置**：案 99000001 Day1 `ownerfeed`（偏移 0）→ 对账 → 建计划，记录 `plan.id` 与 `caseVersion`。
- **注入**：Day2 同案再发（偏移 0）→ 对账。**单日快速路径**：直改库 `UPDATE t_ai_collection SET owner_date = CURDATE() - INTERVAL 1 DAY WHERE case_id = 99000001;` 后重发同案消息即可在当日完成「跨日」构造；终验建议真实跨日跑一次。
- **断言**：`owner_date` 刷新为当日；`caseVersion` 不变（同内容）；`plan.id` 不变、状态不被取消/重建；无第二条 `CASE_INGESTED`。变体：偏移 1 重发，`caseVersion` 变化、计划仍不重建（内容刷新不等于重建）。
- **通过**：两条路径均「只刷新、不重建」。

### E2E-5 迁出后再入

- **前置**：接 E2E-2，案 99000000 已迁出（计划已取消）。
- **注入**：名单加回该案 → 发 `ownerfeed` → 触发对账；随后**同日再发一次**同案名单（模拟重放）并再触发对账。
- **断言**：首次再入只创建一条新计划（`active_stage_key` 唯一约束生效）；第二次重放不重复建计划（`owner_date = 当日` 且已有活跃计划 → ENTER 名单不含它）；新计划步骤正常调度。
- **通过**：恰好一条新计划、重放零重复。

### E2E-6 同一 userId 多案件单日唯一 owner —— **已拍板跳过**

2026-09-03 决策：一个 user 仅一个借据，本场景无业务实例，不投入 E2E。保留编号占位防止后续误读为遗漏。

### E2E-7 旁路门控抽查

- **前置**：接 E2E-2（案已迁出、计划已取消）。
- **注入**：对迁出案分别触发：到期扫描 tick、回调注入（Facade webhook 手法同 E2E-2 前置）、Outbox 重投（直改 `t_event_outbox` 状态触发兜底扫描）、DLQ redrive。
- **断言**：四条路径全部被 owner 门控拦下——无新触达、无步骤推进；执行日志出现 gated 跳过记录；`t_channel_callback_audit` 回调被 PreFlight gated 吞掉且不推进状态机。
- **通过**：任一路径均无法绕过门控。

### E2E-8 归属日一致关联

- **前置**：完成一轮完整对账（含迁出与再入）。
- **注入**：无（纯查库断言）。
- **断言**：对每个在册案，投影 `owner_date` = 当日；迁出案的时间线 `CASE_OWNER_RECONCILED` 时间落在对账窗口内且晚于其计划 `completed_at`；再入案新计划 `created_at` 晚于 `CASE_OWNER_RECONCILED`；`t_decision_log` 当日决策的 `case_id` 与投影一致。
- **通过**：四表（投影/计划/timeline/决策日志）按 `case_id` + 时间轴对齐无矛盾。

## 5. 断言 SQL 速查

```sql
-- 归属与水位
SELECT case_id, owner, owner_date, stage FROM t_ai_collection WHERE case_id IN (99000000,99000001);
SELECT * FROM t_ai_owner_reconcile WHERE reconcile_date = CURDATE();

-- 计划状态（迁出五状态 / 再入重建）
SELECT id, case_id, status, current_step, cancel_reason, created_at, completed_at
  FROM t_contact_plan WHERE case_id = <caseId> ORDER BY id;

-- 对账事件与决策
SELECT case_id, event_type, created_at FROM t_contact_timeline
  WHERE case_id = <caseId> AND event_type = 'CASE_OWNER_RECONCILED';
SELECT case_id, decision, created_at FROM t_decision_log WHERE case_id = <caseId> ORDER BY id DESC LIMIT 10;

-- 旁路门控 / 毒消息
SELECT message_id, error_message FROM t_event_dlq ORDER BY created_at DESC LIMIT 5;
SELECT event_id, status, retry_count FROM t_event_outbox WHERE case_id = <caseId>;
```

## 6. 记录要求

- 每场景执行后在本文追加执行记录（日期、结果、偏差），证据（日志片段/查询输出）留档于 Pilot 机约定目录。
- 场景失败时先区分「注入手法问题」与「产品缺陷」再报障——L4b 历史经验（写死 dpd 塌缩 stage）表明多数失败长得像产品缺陷但实为注入错误。
- E2E 全绿是 T4 影子验证（G1 数仓测试 topic 验收后）的前置条件之一。

---

> 本手册不定义测试准入/出口（以测试 SSOT 为准），不替代[渠道编排规格](../channel/MOCASA催收系统升级_Phase1_渠道编排规格.md)的渠道侧行为断言。
