-- =====================================================================
-- Phase 1.5 管理后台配置迁移 seed（Nacos/YAML -> DB）
-- 来源：application-local.yml channel.plan-templates / scripts / compliance
-- 依赖：先执行 db/schema.sql + db/schema-admin.sql
-- 用法：mysql -h<host> -P<port> -u<user> -p ai_collection_db < db/seed-admin-config.sql
-- 说明：幂等；按 template_code / script_slot / rule_code 清理后重建 seed 行
-- =====================================================================

SET @TENANT = 'mocasa-ph';
SET @CFG_VER = 1;
SET @OPERATOR = 'seed-admin-config';

-- 清理 seed 数据（不影响运行态 freeze/ops 表）
DELETE FROM t_strategy_rule WHERE tenant_id = @TENANT AND rule_name LIKE 'SEED_%';
DELETE FROM t_contact_plan_template WHERE tenant_id = @TENANT AND template_code LIKE 'SEED_%';
DELETE FROM t_script_template WHERE tenant_id = @TENANT AND script_slot IN (
    'S1_SMS_STANDARD', 'S1_PUSH_STANDARD', 'S2_SMS_FIRM',
    'S0_DUE_TODAY_EMAIL', 'S2_EMAIL_ENTRY', 'S1_EMAIL_OVERDUE_NOTICE', 'S4_EMAIL_ENTRY'
);
DELETE FROM t_compliance_rule WHERE tenant_id = @TENANT AND rule_code IN ('QUIET_HOURS', 'DAILY_CAP');
DELETE FROM t_channel_config WHERE tenant_id = @TENANT AND channel_type IN ('SMS', 'PUSH', 'EMAIL');

-- script templates（templateId 与 plan_json 对齐）
INSERT INTO t_script_template
    (id, tenant_id, script_slot, channel, locale, content_json, external_template_id, status, config_version, version, updated_by)
VALUES
    (101, @TENANT, 'S1_SMS_STANDARD', 'SMS', 'en',
     JSON_OBJECT('body', 'MOCASA Collections: {name}, your account is {dpd} day(s) overdue. Please settle PHP {amount} promptly. Pay: {repaymentUrl}'),
     NULL, 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (102, @TENANT, 'S1_PUSH_STANDARD', 'PUSH', 'en',
     JSON_OBJECT('title', 'Overdue: PHP {amount}', 'body', '{name}, your payment is past due. Tap to settle in the app.'),
     NULL, 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (103, @TENANT, 'S2_SMS_FIRM', 'SMS', 'en',
     JSON_OBJECT('body', 'MOCASA Collections: {name}, {dpd} days overdue; your account may be reported as delinquent. See your options for PHP {amount}. Pay: {repaymentUrl}'),
     NULL, 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (201, @TENANT, 'S0_DUE_TODAY_EMAIL', 'EMAIL', 'en',
     JSON_OBJECT('preview', 'Payment due today'),
     'd-9b485bfd24e14950a7811faf33c2b22f', 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (202, @TENANT, 'S2_EMAIL_ENTRY', 'EMAIL', 'en',
     JSON_OBJECT('preview', 'Overdue notice'),
     'd-86ed8faae3b24489ad7db8a11067b8c4', 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (203, @TENANT, 'S1_EMAIL_OVERDUE_NOTICE', 'EMAIL', 'en',
     JSON_OBJECT('preview', 'Overdue reminder'),
     'd-bc7f5aee7e304caf93ca4d435a73a1d7', 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (204, @TENANT, 'S4_EMAIL_ENTRY', 'EMAIL', 'en',
     JSON_OBJECT('preview', 'Final notice'),
     'd-658d5be184ab4710a19c8419ed66bca9', 'ACTIVE', @CFG_VER, 0, @OPERATOR);

-- plan templates（来自 channel.plan-templates）
INSERT INTO t_contact_plan_template
    (tenant_id, template_code, stage, product_code, tone, plan_json, status, config_version, version, created_by, updated_by)
VALUES
    (@TENANT, 'SEED_S0_STANDARD', 'S0', NULL, 'STANDARD',
     JSON_OBJECT('steps', JSON_ARRAY(JSON_OBJECT('channel', 'SMS', 'delayMin', 0, 'observeMin', 0, 'templateId', 101))),
     'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR),
    (@TENANT, 'SEED_S1_STANDARD', 'S1', NULL, 'STANDARD',
     JSON_OBJECT('steps', JSON_ARRAY(
         JSON_OBJECT('channel', 'SMS', 'delayMin', 0, 'observeMin', 0, 'templateId', 101),
         JSON_OBJECT('channel', 'PUSH', 'delayMin', 1, 'observeMin', 0, 'templateId', 102),
         JSON_OBJECT('channel', 'EMAIL', 'delayMin', 1, 'observeMin', 0, 'templateId', 201)
     )),
     'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR),
    (@TENANT, 'SEED_S2_STANDARD', 'S2', NULL, 'STANDARD',
     JSON_OBJECT('steps', JSON_ARRAY(
         JSON_OBJECT('channel', 'SMS', 'delayMin', 0, 'observeMin', 0, 'templateId', 101),
         JSON_OBJECT('channel', 'PUSH', 'delayMin', 1, 'observeMin', 0, 'templateId', 102),
         JSON_OBJECT('channel', 'SMS', 'delayMin', 1, 'observeMin', 0, 'templateId', 103),
         JSON_OBJECT('channel', 'EMAIL', 'delayMin', 1, 'observeMin', 0, 'templateId', 202)
     )),
     'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR),
    (@TENANT, 'SEED_S3_STANDARD', 'S3', NULL, 'STANDARD',
     JSON_OBJECT('steps', JSON_ARRAY(
         JSON_OBJECT('channel', 'SMS', 'delayMin', 0, 'observeMin', 0, 'templateId', 101),
         JSON_OBJECT('channel', 'PUSH', 'delayMin', 1, 'observeMin', 0, 'templateId', 102),
         JSON_OBJECT('channel', 'EMAIL', 'delayMin', 1, 'observeMin', 0, 'templateId', 203)
     )),
     'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR),
    (@TENANT, 'SEED_S4_STANDARD', 'S4', NULL, 'STANDARD',
     JSON_OBJECT('steps', JSON_ARRAY(
         JSON_OBJECT('channel', 'SMS', 'delayMin', 0, 'observeMin', 0, 'templateId', 101),
         JSON_OBJECT('channel', 'EMAIL', 'delayMin', 1, 'observeMin', 0, 'templateId', 204)
     )),
     'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR);

-- strategy rules（Stage -> plan template）
INSERT INTO t_strategy_rule
    (tenant_id, rule_name, priority, match_dpd_min, match_dpd_max, output_template_id, output_tone, status, config_version, version, created_by, updated_by)
SELECT @TENANT, CONCAT('SEED_', stage, '_RULE'), 100,
       CASE stage WHEN 'S0' THEN -3 WHEN 'S1' THEN 1 WHEN 'S2' THEN 4 WHEN 'S3' THEN 16 WHEN 'S4' THEN 31 END,
       CASE stage WHEN 'S0' THEN 0 WHEN 'S1' THEN 3 WHEN 'S2' THEN 15 WHEN 'S3' THEN 30 WHEN 'S4' THEN 999 END,
       id, 'STANDARD', 'ACTIVE', @CFG_VER, 0, @OPERATOR, @OPERATOR
FROM t_contact_plan_template
WHERE tenant_id = @TENANT AND template_code LIKE 'SEED_%';

-- compliance
INSERT INTO t_compliance_rule
    (tenant_id, rule_code, channel, rule_json, status, config_version, version, updated_by)
VALUES
    (@TENANT, 'QUIET_HOURS', NULL,
     JSON_OBJECT('timezone', 'Asia/Manila', 'quietHoursStart', '21:00', 'quietHoursEnd', '08:00'),
     'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (@TENANT, 'DAILY_CAP', NULL,
     JSON_OBJECT('dailyLimit', 3),
     'ACTIVE', @CFG_VER, 0, @OPERATOR);

-- channel config
INSERT INTO t_channel_config
    (tenant_id, channel_type, enabled, route_json, circuit_state, status, config_version, version, updated_by)
VALUES
    (@TENANT, 'SMS', 1, JSON_OBJECT('provider', 'notification', 'smsTestMode', true), 'CLOSED', 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (@TENANT, 'PUSH', 1, JSON_OBJECT('provider', 'notification', 'pushSyncMode', true), 'CLOSED', 'ACTIVE', @CFG_VER, 0, @OPERATOR),
    (@TENANT, 'EMAIL', 1, JSON_OBJECT('provider', 'sendgrid'), 'CLOSED', 'ACTIVE', @CFG_VER, 0, @OPERATOR);

-- evaluation settings（holdout 默认 10%）
INSERT INTO t_evaluation_setting
    (tenant_id, setting_key, setting_value, status, config_version, version, updated_by)
VALUES
    (@TENANT, 'HOLDOUT_RATIO', JSON_OBJECT('holdoutRatio', 0.10), 'ACTIVE', @CFG_VER, 1, @OPERATOR)
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    config_version = VALUES(config_version),
    version = version + 1,
    updated_by = VALUES(updated_by),
    updated_at = NOW();

INSERT INTO t_evaluation_setting_history
    (tenant_id, setting_key, setting_value, config_version, operator, reason)
VALUES
    (@TENANT, 'HOLDOUT_RATIO', JSON_OBJECT('holdoutRatio', 0.10), @CFG_VER, @OPERATOR, 'seed-admin-config initial import')
ON DUPLICATE KEY UPDATE
    setting_value = VALUES(setting_value),
    operator = VALUES(operator),
    reason = VALUES(reason);

UPDATE t_config_version_seq SET current_version = GREATEST(current_version, @CFG_VER), updated_at = NOW() WHERE id = 1;

INSERT INTO t_config_change_log
    (tenant_id, config_type, config_key, from_version, to_version, diff_summary, operator, reason, created_at)
VALUES
    (@TENANT, 'seed_import', 'nacos-yaml', 0, @CFG_VER,
     JSON_OBJECT('summary', 'Import plan/script/compliance/channel config from application-local.yml'),
     @OPERATOR, 'seed-admin-config.sql', NOW());

SELECT 'SEED_ADMIN_CONFIG_OK' AS result,
       (SELECT COUNT(*) FROM t_script_template WHERE tenant_id = @TENANT) AS script_templates,
       (SELECT COUNT(*) FROM t_contact_plan_template WHERE tenant_id = @TENANT AND template_code LIKE 'SEED_%') AS plan_templates,
       (SELECT current_version FROM t_config_version_seq WHERE id = 1) AS config_version;
