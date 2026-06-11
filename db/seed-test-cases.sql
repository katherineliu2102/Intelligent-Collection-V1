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
--      Stage 区间：S0[-3,0] S1[1,3] S2[4,10] S3[11,15] S4[16,30] S4_PLUS[31+]
--   3) phone/email 统一填测试地址 —— 批量测不同 stage 但只投少量测试收件人。
--      ⚠ 把下面 @TEST_PHONE / @TEST_EMAIL 改成你们的真实测试号码/邮箱（E.164 +63 格式）。
--   4) 幂等：按 id 前缀 'IC_TEST_' 先清理再插入，可反复执行。
-- =====================================================================

SET @TEST_PHONE = '+639170000001';   -- TODO: 替换为你们的测试手机号（E.164）
SET @TEST_EMAIL = 'collection.test@mocasa.test';  -- TODO: 替换为你们的测试邮箱

-- 幂等清理（仅清理本脚本造的测试行，不影响真实 687 条）
DELETE FROM t_collection WHERE id LIKE 'IC_TEST_%';

-- 每行一个 Stage：loan_id/user_id 用 9900000x 测试段，overdue_days 落在对应区间中值。
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
    -- S0（D-3~D0，未逾期窗口；是否建计划由 PlanFactory 决定，纳入覆盖）
    ('IC_TEST_S0', '99000000', '99000000', 'LN99000000', '3',
     'Test Case S0', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     5000.00, NOW(), 5000.00, DATE_SUB(CURDATE(), INTERVAL 30 DAY), 30,
     CURDATE(), 0,
     5000.00, 100.00, 0.00, 5100.00, 0,
     NOW()),

    -- S1（D+1~D+3）
    ('IC_TEST_S1', '99000001', '99000001', 'LN99000001', '3',
     'Test Case S1', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     5000.00, NOW(), 5000.00, DATE_SUB(CURDATE(), INTERVAL 33 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 2 DAY), 2,
     5000.00, 150.00, 100.00, 5250.00, 0,
     NOW()),

    -- S2（D+4~D+10）
    ('IC_TEST_S2', '99000002', '99000002', 'LN99000002', '3',
     'Test Case S2', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'iOS',
     8000.00, NOW(), 8000.00, DATE_SUB(CURDATE(), INTERVAL 37 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 7 DAY), 7,
     8000.00, 240.00, 320.00, 8560.00, 1,
     NOW()),

    -- S3（D+11~D+15）
    ('IC_TEST_S3', '99000003', '99000003', 'LN99000003', '4',
     'Test Case S3', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     10000.00, NOW(), 10000.00, DATE_SUB(CURDATE(), INTERVAL 43 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 13 DAY), 13,
     10000.00, 300.00, 650.00, 10950.00, 0,
     NOW()),

    -- S4（D+16~D+30）
    ('IC_TEST_S4', '99000004', '99000004', 'LN99000004', '4',
     'Test Case S4', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'Android',
     6000.00, NOW(), 6000.00, DATE_SUB(CURDATE(), INTERVAL 52 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 22 DAY), 22,
     6000.00, 180.00, 880.00, 7060.00, 0,
     NOW()),

    -- S4_PLUS（D+31+）
    ('IC_TEST_S4P', '99000005', '99000005', 'LN99000005', '4',
     'Test Case S4PLUS', @TEST_PHONE, @TEST_EMAIL,
     'QuickLoan', 'iOS',
     12000.00, NOW(), 12000.00, DATE_SUB(CURDATE(), INTERVAL 75 DAY), 30,
     DATE_SUB(CURDATE(), INTERVAL 45 DAY), 45,
     12000.00, 360.00, 1800.00, 14160.00, 0,
     NOW());

-- 验证：应返回 6 行，stage 由 dpd 推导
SELECT loan_id AS case_id, user_id, overdue_days AS dpd, phone, email, total_not_paid
FROM t_collection WHERE id LIKE 'IC_TEST_%' ORDER BY id;
