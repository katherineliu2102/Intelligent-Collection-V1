-- =====================================================================
-- Phase 1 链路验证 mock 数据（可选）
-- =====================================================================
-- 说明：链路跑通不依赖预置案件数据 —— Phase 1 的 MockCaseService / MockProfileService
-- 会按 caseId 合成 CaseInfo / ContextSnapshot / UserProfile，因此你只需：
--   1) 执行 db/schema.sql 建好新表
--   2) 调 POST /mock/ingest?caseId=1001 启动链路
-- 计划 / 步骤 / 时间线会由引擎运行时写入下列表，可直接查询观测。
--
-- 当服务层开发者用真实 CaseService 替换 MockCaseService（映射 t_collection 等）后，
-- 才需要在对应业务库灌入真实案件 mock 数据。
-- =====================================================================

-- 可选：为 userId=1001 预置一条画像扩展，演示 t_user_profile_ext 读写
INSERT INTO t_user_profile_ext (user_id, best_contact_hour, preferred_channel, phone_validity, sensitivity_tag)
VALUES (1001, 10, 'SMS', 'VALID', 'COOPERATIVE')
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- 观测查询参考：
-- SELECT id, case_id, stage, status, current_step, total_steps, started_at, completed_at FROM t_contact_plan ORDER BY id DESC;
-- SELECT id, plan_id, step_order, channel_type, status, result, trigger_time FROM t_contact_plan_step ORDER BY plan_id DESC, step_order;
-- SELECT id, case_id, channel, direction, result, provider_msg_id, created_at FROM t_contact_timeline ORDER BY id DESC;
