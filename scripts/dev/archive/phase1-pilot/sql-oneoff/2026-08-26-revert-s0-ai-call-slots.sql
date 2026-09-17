-- 把 S0 模板回退到 2026-08-24 摘除前的原文，去掉被误注入的 8 个 AI_CALL 槽位。
--
-- 背景：2026-08-24 用 strip-ai-call-slots.py 从 S1~S4 摘掉 150 个 AI_CALL 槽位（S0 本就没有），
-- 快照存进 t_contact_plan_template_bak_20260824_aicall。2026-08-25 03:19 由 `aicall-e2e-restore`
-- 还原时，S1~S4 的槽位构成与备份逐项一致（正确），但 S0 从 5 槽变成 13 槽，多出
-- 每日 09:15 与 14:30 两个 AI_CALL × 4 个日块 = 8 个。
--
-- 为什么必须回退：S0 是 D-3~D0 到期前阶段，渠道编排规格 §7.4 与 §5 的渠道矩阵都写明
-- 「到期前提醒，无语音 / 不拨」。留着它，真实案件一旦落进 S0，系统就会给**尚未逾期**的
-- 借款人拨催收电话。Pilot 已在消费数仓真实案件且调度已开，属于随时会触发的合规回归。
--
-- 只动 S0：S1~S4 与备份的字节差异来自还原脚本的 JSON 键序/空白重排，槽位构成相同，不动。

SET @TENANT = 'mocasa-ph';

-- 1. 回退前留一份当前 S0，便于事后比对（回退本身可由备份表重放，这份只为审计）。
CREATE TABLE IF NOT EXISTS t_contact_plan_template_bak_20260826_s0 LIKE t_contact_plan_template;
INSERT INTO t_contact_plan_template_bak_20260826_s0
SELECT * FROM t_contact_plan_template
WHERE tenant_id = @TENANT AND stage = 'S0' AND template_code LIKE 'PH1%';

-- 2. 从摘除前快照还原 S0 原文。
UPDATE t_contact_plan_template t
JOIN t_contact_plan_template_bak_20260824_aicall b
  ON b.id = t.id
SET t.plan_json = b.plan_json,
    t.version = t.version + 1,
    t.updated_by = 'pilot-s0-aicall-revert'
WHERE t.tenant_id = @TENANT
  AND t.stage = 'S0'
  AND t.template_code LIKE 'PH1%';

-- 3. 抬全局 epoch 让 ConfigTemplateProvider 的 TTL 轮询失效缓存。
--    必须自增而不是 GREATEST(x, n)：seq 常已高于模板自身的 config_version，
--    取最大值会原地不动，缓存不失效，应用继续用旧模板。
UPDATE t_config_version_seq
SET current_version = current_version + 1,
    updated_at = NOW()
WHERE id = 1;

-- 4. 校验：S0 应为 4 个日块 / 5 槽 / 0 个 AI_CALL；S1~S4 的 AI_CALL 数不受影响。
SELECT stage,
       JSON_LENGTH(plan_json -> '$.dayBlocks') AS day_blocks,
       (LENGTH(plan_json) - LENGTH(REPLACE(plan_json, '"channel": "AI_CALL"', ''))) / 20 AS ai_call_slots,
       (LENGTH(plan_json) - LENGTH(REPLACE(plan_json, '"time"', ''))) / 6 AS total_slots
FROM t_contact_plan_template
WHERE tenant_id = @TENANT AND template_code LIKE 'PH1%'
ORDER BY stage;
