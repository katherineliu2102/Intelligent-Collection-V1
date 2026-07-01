-- =====================================================================
-- MOCASA 智能催收升级 Phase 1 — L4b 落库断言 SQL（可跑副本）
-- 目标库：ai_collection_db
-- 来源：测试文档 §L4b.6（落库断言口径，与之保持一致）
-- 用法：mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" < db/l4b-assert.sql
--       （连接信息走环境变量，不入仓）
-- 说明：把 @caseId 改为白名单测试 caseId（= loan_id，数字串可转 Long）后整段执行；
--       各段对应 §L4b 用例清单 L4b-1…8 的落库证据。
-- =====================================================================

SET @caseId = 99000000;   -- TODO: 替换为白名单测试 caseId（对齐 seed-test-cases.sql 的 9900000x）

-- ---------------------------------------------------------------------
-- L4b-1 / L4b-6：计划 + 步骤 + timeline 落库
-- ---------------------------------------------------------------------
SELECT id, stage, status, cancel_reason, current_step, total_steps,
       started_at, completed_at, idempotency_key, version
  FROM t_contact_plan
 WHERE case_id = @caseId
 ORDER BY id DESC;

SELECT step_order, channel_type, status, trigger_time, timeout_time,
       result, retry_count, observation_minutes, executed_at, completed_at
  FROM t_contact_plan_step
 WHERE plan_id = (SELECT id FROM t_contact_plan WHERE case_id = @caseId ORDER BY id DESC LIMIT 1)
 ORDER BY step_order;

SELECT channel, direction, result, provider_msg_id, source, content_summary, created_at
  FROM t_contact_timeline
 WHERE case_id = @caseId
 ORDER BY created_at;

-- ---------------------------------------------------------------------
-- L4b-2：还款 → 取消活跃计划（期望 PLAN_CANCELLED / REPAID）
-- ---------------------------------------------------------------------
SELECT status, cancel_reason, completed_at
  FROM t_contact_plan
 WHERE case_id = @caseId
 ORDER BY id DESC LIMIT 1;

-- ---------------------------------------------------------------------
-- L4b-3：DPD 日切升档（旧计划取消 + 新阶段计划）
--   期望 旧:PLAN_CANCELLED/STAGE_UPGRADE，新:stage=新阶段 + PENDING|执行
-- ---------------------------------------------------------------------
SELECT id, stage, status, cancel_reason, created_at
  FROM t_contact_plan
 WHERE case_id = @caseId
 ORDER BY id;

-- ---------------------------------------------------------------------
-- L4b-4：DPD≥91 CEASED 停催（活跃计划取消 + 不再建；期望 PLAN_CANCELLED/CEASED）
-- ---------------------------------------------------------------------
SELECT id, stage, status, cancel_reason
  FROM t_contact_plan
 WHERE case_id = @caseId;

-- ---------------------------------------------------------------------
-- L4b-5：快照字段溯源（决策 B：payload → context_snapshot，逐字段比对）
-- ---------------------------------------------------------------------
SELECT JSON_EXTRACT(context_snapshot, '$.caseContext.dpd')               AS dpd,
       JSON_EXTRACT(context_snapshot, '$.caseContext.stage')             AS stage,
       JSON_EXTRACT(context_snapshot, '$.caseContext.totalOutstanding')  AS total_out,
       JSON_EXTRACT(context_snapshot, '$.caseContext.penaltyAmount')     AS penalty,
       JSON_EXTRACT(context_snapshot, '$.caseContext.collectionStatus')  AS coll_status,
       JSON_EXTRACT(context_snapshot, '$.caseContext.repaid')            AS repaid,
       JSON_EXTRACT(context_snapshot, '$.userProfile.basic.primaryPhone') AS phone,
       JSON_EXTRACT(context_snapshot, '$.userProfile.basic.email')        AS email,
       JSON_EXTRACT(context_snapshot, '$.userProfile.device.jpushToken')  AS jpush_token,
       JSON_EXTRACT(context_snapshot, '$.strategyTone')                   AS strategy_tone
  FROM t_contact_plan
 WHERE case_id = @caseId
 ORDER BY id DESC LIMIT 1;
-- 旧库对账（ai_collection_db 已含 t_collection 全结构镜像）：
SELECT loan_id, overdue_days, total_not_paid, overdue, colleciton_status, phone, email, full_repay_time
  FROM t_collection WHERE loan_id = @caseId;

-- ---------------------------------------------------------------------
-- L4b-7 / L4b-8：重投 / 日切幂等（同 case+stage 仅一个活跃计划）
--   期望：活跃计划数 = 1（PENDING/STEP_*），无重复 STAGE_UPGRADE/REPAID 取消行
-- ---------------------------------------------------------------------
SELECT stage,
       SUM(status IN ('PENDING','STEP_SCHEDULED','STEP_EXECUTING','STEP_WAITING')) AS active_cnt,
       COUNT(*) AS total_cnt
  FROM t_contact_plan
 WHERE case_id = @caseId
 GROUP BY stage;
