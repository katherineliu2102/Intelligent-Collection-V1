-- 订正 F12 复活的步骤：plan 829 step 1416（AI_CALL，step_order=2）。
--
-- 事实链（取自 /opt/app/logs/collection/collection.log）：
--   09:55:53  step 1416 → STEP_EXECUTING，挂 60min 回调超时
--   10:02:12  [callback] plan 829 step 1416 result FAILED   ← 已按回调正常终结
--   10:02:13  [advance] plan 829 → 下一步 1417 at 12:00      ← 推进正确
--   10:04:56  [advance] plan 829 → 下一步 1416 at 10:04:56   ← step 1415 退避重试后才落地，
--                                                             按 step_order+1 取到已终结的 1416
--   10:05:04  [execStep] duplicate event, key=829:2:0 skipped ← 幂等锁挡住了真实重复外呼
--
-- 于是该行呈撕裂态：status=EXECUTING，而 result=FAILED / completed_at=10:02:12 是上一次终态的残留。
-- 且 onCallbackTimeout 要求 plan.status=STEP_EXECUTING，而计划已推进到 STEP_SCHEDULED，
-- 所以 callbackTimeout 每分钟扫到它却永远处理不掉（scan_rows{callbackTimeout} 每 tick +1 即此条）。
--
-- 订正口径：以 10:02:12 那次回调为准（callbackAudit id=11，signature_valid=1，result=FAILED），
-- 把状态补回它本该停留的终态，不新增触达、不改 timeline、不改 result 与 completed_at。
-- 清空 timeout_time 让超时扫描不再摸到它。
--
-- 根因已在代码侧修掉（selectByPlanAndOrder 跳过终态 + updateTriggerTime/updateTimeoutTime 加终态谓词），
-- 本脚本只清理修复前已产生的这一行。

SELECT id, status, result, retry_count, executed_at, dispatched_at, completed_at, timeout_time
FROM t_contact_plan_step WHERE id = 1416;

UPDATE t_contact_plan_step
SET status = 'COMPLETED',
    timeout_time = NULL,
    updated_at = NOW()
WHERE id = 1416
  AND status = 'EXECUTING'
  AND result = 'FAILED'
  AND completed_at IS NOT NULL;

-- 校验：该步骤应为 COMPLETED/FAILED 且 timeout_time 为空；全库不应再有「已写 completed_at 却仍非终态」的行。
SELECT id, status, result, completed_at, timeout_time
FROM t_contact_plan_step WHERE id = 1416;

SELECT id, plan_id, step_order, status, result, completed_at
FROM t_contact_plan_step
WHERE completed_at IS NOT NULL
  AND status NOT IN ('COMPLETED', 'SKIPPED', 'FAILED');
