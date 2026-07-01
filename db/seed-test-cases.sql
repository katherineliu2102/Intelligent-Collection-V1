-- =====================================================================
-- Phase 1 真实数据链路测试 —— t_collection 造数脚本
-- 目标库：ai_collection_db
-- 用途：覆盖各催收 Stage 的可控测试案件，统一发向少量测试号码/邮箱。
-- 用法：mysql -h<DB_HOST> -P<DB_PORT> -u<DB_USER> -p <DB_NAME> < db/seed-test-cases.sql
--
-- 设计要点：
--   1) caseId 取自 loan_id（数字串，可转 Long）；t_collection.id 是 hex 串，不作 caseId。
--   2) Stage 不存列，由 RealCaseService 按 dpd 经 Stage.fromDpd() 推导；
--      故 overdue_days 按各 Stage 区间定点设置（dpd 约定：正数=已逾期天数，与领域模型一致）。
--      Stage 区间（2026-06-15 对齐编排）：S0[-3,0] S1[1,3] S2[4,15] S3[16,30] S4[31+]
--      D91+ 完全停催（CASE_CEASED）：PlanFactory.shouldRejectPlan 拦截，不建计划。
--   3) overdue_days 尽量同时命中 Email 里程碑 DPD（0/1/4/31），使同一份 seed 既能测
--      SMS/Push 的 stage 文案，又能测 Email 里程碑模板；S3 无 Phase1 Email，仅测 SMS/Push。
--   4) phone/email 与 L4a 联调口径一致（测试文档 §L4a.2：126 邮箱 + 94101/94999 真号）。
--      Push 实际下发走 channel.notification.push-test-token（与 L4a 94200 同 token）；
--      payload/快照 jpushToken 见 db/seed-device-token.sql。
--   5) 幂等：按 id 前缀 'IC_TEST_' 先清理再插入，可反复执行。
-- =====================================================================

SET @TEST_PHONE = '+639451374358';   -- L4a 真号（94999/94101，E.164 +63）
SET @TEST_EMAIL = 'wzynju@126.com'; -- L4a Email 真发优先（126）

-- 幂等清理（仅清理本脚本造的测试行，不影响真实数据）
DELETE FROM t_collection WHERE id LIKE 'IC_TEST_%';

-- 每行一个 Stage：loan_id/user_id 用 9900000x 测试段。
-- dueDate(repayment_date) = 今天 - overdue_days，使 dpd 与到期日自洽。
INSERT INTO t_collection
    (id, loan_id, user_id, loan_no, colleciton_status,
     real_name, phone, email,
     app_name, platform_name,
     apply_amount, apply_time, disburse_amount, disburse_date, deadline,
     repayment_date, overdue_days,
     principal, interest, overdue, total_not_paid, pay_count,
     create_time)
VALUES
    -- S0（dpd=0，到期当日；命中 Email S0_DUE_TODAY；是否建计划由 PlanFactory 决定）
    ('IC_TEST_S0', '99000000', '99000000', 'LN99000000', '3',
     'Test Case S0', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     5000.00, NOW(), 5000.00, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 30,
     CURDATE(), 0,
     5000.00, 100.00, 0.00, 5100.00, 0,
     NOW()),

    -- S1（dpd=1，命中 Email S1_EMAIL_OVERDUE_NOTICE）
    ('IC_TEST_S1', '99000001', '99000001', 'LN99000001', '3',
     'Test Case S1', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     5000.00, NOW(), 5000.00, DATE_SUB(CURDATE(), INTERVAL 31 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 1 DAY), 1,
     5000.00, 150.00, 100.00, 5250.00, 0,
     NOW()),

    -- S2（dpd=4，区间[4,15]入口；命中 Email S2_EMAIL_ENTRY）
    ('IC_TEST_S2', '99000002', '99000002', 'LN99000002', '3',
     'Test Case S2', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'iOS',
     8000.00, NOW(), 8000.00, DATE_SUB(CURDATE(), INTERVAL 34 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 4 DAY), 4,
     8000.00, 240.00, 320.00, 8560.00, 1,
     NOW()),

    -- S3（dpd=20，区间[16,30]中值；Phase1 无 S3 Email，仅测 SMS/Push）
    ('IC_TEST_S3', '99000003', '99000003', 'LN99000003', '4',
     'Test Case S3', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     10000.00, NOW(), 10000.00, DATE_SUB(CURDATE(), INTERVAL 50 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 20 DAY), 20,
     10000.00, 300.00, 650.00, 10950.00, 0,
     NOW()),

    -- S4（dpd=31，区间[31+]入口；命中 Email S4_EMAIL_ENTRY）
    ('IC_TEST_S4', '99000004', '99000004', 'LN99000004', '4',
     'Test Case S4', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     6000.00, NOW(), 6000.00, DATE_SUB(CURDATE(), INTERVAL 61 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 31 DAY), 31,
     6000.00, 180.00, 880.00, 7060.00, 0,
     NOW()),

    -- D91+ 停催（dpd=95，仍属 S4 但 >91；验证 PlanFactory.shouldRejectPlan 拒建计划 / CASE_CEASED）
    ('IC_TEST_CEASED', '99000005', '99000005', 'LN99000005', '4',
     'Test Case Ceased', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'iOS',
     12000.00, NOW(), 12000.00, DATE_SUB(CURDATE(), INTERVAL 125 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 95 DAY), 95,
     12000.00, 360.00, 1800.00, 14160.00, 0,
     NOW());

-- 验证：应返回 6 行，stage 由 dpd 推导（S0/S1/S2/S3/S4/S4-但停催）
SELECT loan_id AS case_id, user_id, overdue_days AS dpd, phone, email, total_not_paid
FROM t_collection WHERE id LIKE 'IC_TEST_%' ORDER BY id;
