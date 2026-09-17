-- 订正冒烟挤压槽位后挂住的 AI 步：plan 862 step 2477。
--
-- 事实链：
--   16:08  step 2477 → EXECUTING，Facade start batchId=a55f6ee9-79f4-48d1-ac5c-bb7968c1c581
--   16:10  同计划 PUSH 2480 到期（日限拦截）后计划被推进
--   16:15  webhook 入审计 id=76，signature_valid=1，result=FAILED，
--          reason=MEDIA_NEGOTIATION_FAILED，session=bf55465e-58cb-4e08-9796-eb3358aac79b
--          Facade 已 publish CHANNEL_CALLBACK
--   随后 onChannelCallback 因 plan.status=STEP_SCHEDULED 静默吞掉，步骤仍 EXECUTING / result NULL
--
-- 订正口径：以审计 76 为准，步骤补回 COMPLETED/FAILED（与 recordTerminal 一致），
-- 清 timeout_time；不改计划 current_step、不新增触达。
-- timeline 若已有该 attempt 行，把 result 写成 FAILED（与回调 upsert 同口径）。

SELECT id, plan_id, step_order, status, result, retry_count,
       executed_at, completed_at, timeout_time
FROM t_contact_plan_step WHERE id = 2477;

SELECT id, status, current_step FROM t_contact_plan WHERE id = 862;

SELECT id, attempt_key, result, provider_msg_id
FROM t_contact_timeline WHERE step_id = 2477 ORDER BY id;

UPDATE t_contact_plan_step
SET status = 'COMPLETED',
    result = 'FAILED',
    completed_at = COALESCE(
        completed_at,
        (SELECT received_at FROM t_channel_callback_audit WHERE id = 76)),
    timeout_time = NULL,
    updated_at = NOW()
WHERE id = 2477
  AND status = 'EXECUTING';

UPDATE t_contact_timeline
SET result = 'FAILED'
WHERE step_id = 2477
  AND (result IS NULL OR result <> 'FAILED');

SELECT id, plan_id, step_order, status, result, completed_at, timeout_time
FROM t_contact_plan_step WHERE id = 2477;

SELECT id, status, current_step FROM t_contact_plan WHERE id = 862;
