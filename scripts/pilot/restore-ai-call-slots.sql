-- 回滚 2026-08-24 的 AI_CALL 槽位摘除，把 5 个 PH1 计划模板还原为含 AI_CALL 的原文。
--
-- 触发条件：Facade 回调的公网入口就绪、L2-CB 通过之后。
-- 还原源是摘除前一刻的整行快照 t_contact_plan_template_bak_20260824_aicall，
-- 因此还原的是原文而非"再插一遍 seed"——期间若有人改过话术/模板，本脚本会一并回退，
-- 执行前请先比对备份表与当前表的差异。

SET @TENANT = 'mocasa-ph';

-- 1. 确认备份存在且完整（应为 5 行、94 个日块）。
SELECT COUNT(*) AS backup_rows,
       SUM(JSON_LENGTH(plan_json -> '$.dayBlocks')) AS backup_day_blocks
FROM t_contact_plan_template_bak_20260824_aicall;

-- 2. 还原 plan_json，并抬 version 以便审计能看出这是一次新的写入而非回到旧状态。
UPDATE t_contact_plan_template t
JOIN t_contact_plan_template_bak_20260824_aicall b
  ON b.id = t.id
SET t.plan_json = b.plan_json,
    t.version = t.version + 1,
    t.updated_by = 'pilot-ai-call-restore'
WHERE t.tenant_id = @TENANT
  AND t.template_code LIKE 'PH1%';

-- 3. 抬全局 epoch，让 ConfigTemplateProvider 的 TTL 轮询失效缓存。
--    必须是自增而不是 GREATEST(x, n)：seq 常已高于模板自身的 config_version，
--    取最大值会原地不动，缓存不失效，应用继续用旧模板。
UPDATE t_config_version_seq
SET current_version = current_version + 1,
    updated_at = NOW()
WHERE id = 1;

-- 4. 校验：AI_CALL 应重新出现在 S1~S4，S0 本就没有。
SELECT template_code,
       JSON_LENGTH(plan_json -> '$.dayBlocks') AS day_blocks,
       JSON_LENGTH(JSON_SEARCH(plan_json, 'all', 'AI_CALL')) AS ai_call_slots
FROM t_contact_plan_template
WHERE tenant_id = @TENANT AND template_code LIKE 'PH1%'
ORDER BY template_code;
