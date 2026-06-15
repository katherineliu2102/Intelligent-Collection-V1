-- =====================================================================
-- Phase 1 Email E2E 联调案例注册表（ai_collection_db）
-- 用法：mysql -h<host> -u<user> -p ai_collection_db < db/seed/email-e2e-test-cases.sql
--
-- 说明：
--   - 本表为 QA/联调 SSOT；案件画像仍由 MockCaseService / MockProfileService 提供（Phase 1）
--   - ingest 后进 t_contact_plan / t_contact_timeline 验证全链路
--   - Phase 1 正式发信仅 phase1_active=1 的 5 行
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_email_e2e_registry (
    case_id         BIGINT          NOT NULL PRIMARY KEY COMMENT 'caseId = userId',
    alias           VARCHAR(64)     NOT NULL,
    script_slot     VARCHAR(64)     NOT NULL,
    stage           VARCHAR(8)      NOT NULL COMMENT 'S0/S1/S2/S3/S4',
    dpd             INT             NOT NULL,
    amount_due      DECIMAL(12,2)   NOT NULL,
    due_date        DATE            NULL     COMMENT 'NULL 表示 D0 当天',
    test_email      VARCHAR(128)    NOT NULL DEFAULT 'wzynju@126.com',
    phase1_active   TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '1=Phase1正式发信',
    notes           VARCHAR(256)    NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Email E2E 联调案例注册表';

-- 全量 14 case（与 MockEmailTestCases 对齐）
INSERT INTO t_email_e2e_registry (case_id, alias, script_slot, stage, dpd, amount_due, due_date, phase1_active, notes) VALUES
(92001, 'test_s0_user1', 'S0_DUE_TODAY_EMAIL',       'S0',  0, 5000.00, NULL,         0, '备用；推荐 92002 重复进案'),
(92002, 'test_s0_user2', 'S0_DUE_TODAY_EMAIL',       'S0',  0, 5000.00, NULL,         1, 'Phase1 D0'),
(93101, 'test_s1_user1', 'S1_EMAIL_OVERDUE_NOTICE',  'S1',  2, 2500.00, '2026-06-06', 1, 'Phase1 S1（显式 scriptSlot）'),
(93102, 'test_s1_user2', 'S1_EMAIL_STAGE_WARNING',   'S1',  3, 2500.00, '2026-06-05', 0, 'HTML备用；Phase1不发'),
(93201, 'test_s2_user1', 'S2_EMAIL_ENTRY',           'S2',  4, 4000.00, '2026-06-01', 1, 'Phase1 S2进段'),
(93202, 'test_s2_user2', 'S2_EMAIL_MID',             'S2',  7, 4000.00, '2026-06-01', 0, 'HTML备用'),
(93203, 'test_s2_user3', 'S2_EMAIL_PRE_S3',          'S2', 12, 4000.00, '2026-05-27', 0, 'HTML备用'),
(93301, 'test_s3_user1', 'S3_EMAIL_ENTRY',           'S3', 16, 5000.00, '2026-05-23', 0, 'S3 Phase1不发'),
(93302, 'test_s3_user2', 'S3_EMAIL_MID',             'S3', 23, 5000.00, '2026-05-16', 0, 'S3 Phase1不发'),
(93303, 'test_s3_user3', 'S3_EMAIL_PRE_S4',          'S3', 30, 5000.00, '2026-05-09', 0, 'S3 Phase1不发'),
(93401, 'test_s4_user1', 'S4_EMAIL_ENTRY',           'S4', 31, 5000.00, '2026-05-08', 1, 'Phase1 S4进段'),
(93402, 'test_s4_user2', 'S4_EMAIL_FINAL_REMINDER',  'S4', 45, 5000.00, '2026-04-24', 0, 'HTML备用'),
(93403, 'test_s4_user3', 'S4_EMAIL_MID',             'S4', 60, 5000.00, '2026-04-09', 0, 'HTML备用'),
(93404, 'test_s4_user4', 'S4_EMAIL_PRE_CLOSE',       'S4', 75, 5000.00, '2026-03-25', 1, 'Phase1 D+75；assignment_date=due+91')
ON DUPLICATE KEY UPDATE
    alias = VALUES(alias),
    script_slot = VALUES(script_slot),
    stage = VALUES(stage),
    dpd = VALUES(dpd),
    amount_due = VALUES(amount_due),
    due_date = VALUES(due_date),
    phase1_active = VALUES(phase1_active),
    notes = VALUES(notes),
    updated_at = CURRENT_TIMESTAMP;
