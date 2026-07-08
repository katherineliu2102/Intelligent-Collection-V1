-- =====================================================================
-- MOCASA 智能催收 Phase 1 — 管理后台扩展 DDL
-- 目标库：ai_collection_db（与 db/schema.sql 同库）
-- 来源：管理后台设计文档 v1.3 附录 C + §6
-- 依赖：先执行 db/schema.sql（引擎核心表）
-- 用法：mysql -h<host> -P<port> -u<user> -p ai_collection_db < db/schema-admin.sql
-- 对齐：领域模型 §1.2 B 区（配置表 NEW ⚠️ → 本文件为首版 DDL，需编排层 review）
-- =====================================================================

-- ---------------------------------------------------------------------
-- A. 配置表（Phase 1.5 后台写入；Phase 1 引擎仍可读 Nacos/Java 常量）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_contact_plan_template (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph' COMMENT '租户ID，Phase 1 单租户',
    template_code       VARCHAR(64)     NOT NULL COMMENT '模板业务编码，如 S2_STANDARD_V1',
    stage               VARCHAR(16)     NOT NULL COMMENT 'S0/S1/S2/S3/S4',
    product_code        VARCHAR(64)     NULL     COMMENT '产品 code；NULL=全产品',
    risk_tier           VARCHAR(16)     NULL     COMMENT '风险分层预留；Phase 1 不填充、不参与匹配',
    tone                VARCHAR(32)     NULL     COMMENT 'STANDARD/FIRM 等',
    plan_json           JSON            NOT NULL COMMENT '步骤序列：channel/delayMin/observeMin/templateId 等',
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
    config_version      BIGINT          NOT NULL COMMENT '全局递增配置版本号（发布时写入）',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_by          VARCHAR(64)     NULL,
    updated_by          VARCHAR(64)     NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_code (tenant_id, template_code),
    INDEX idx_stage_product (stage, product_code),
    INDEX idx_config_version (config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达计划模板';

CREATE TABLE IF NOT EXISTS t_script_template (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    script_slot         VARCHAR(64)     NOT NULL COMMENT '如 S2_SMS_STANDARD',
    channel             VARCHAR(32)     NOT NULL COMMENT 'SMS/PUSH/EMAIL/AI_CALL/TTS',
    locale              VARCHAR(16)     NOT NULL DEFAULT 'en' COMMENT 'Phase 1 英文',
    content_json        JSON            NOT NULL COMMENT 'title/body/variables 或 vendor 参数',
    external_template_id VARCHAR(128)   NULL     COMMENT 'SendGrid d-xxx 等',
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    config_version      BIGINT          NOT NULL,
    version             INT             NOT NULL DEFAULT 0,
    created_by          VARCHAR(64)     NULL,
    updated_by          VARCHAR(64)     NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_slot_channel_locale (tenant_id, script_slot, channel, locale),
    INDEX idx_config_version (config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='话术/scriptSlot 模板';

CREATE TABLE IF NOT EXISTS t_strategy_rule (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    rule_name           VARCHAR(128)    NOT NULL,
    priority            INT             NOT NULL DEFAULT 100 COMMENT '越小越优先',
    match_dpd_min       INT             NULL,
    match_dpd_max       INT             NULL,
    match_product       VARCHAR(64)     NULL,
    match_tags          JSON            NULL     COMMENT '用户标签 JSON 数组，如 ["HARD_COLLECT"]',
    match_risk_tier     VARCHAR(16)     NULL     COMMENT '预留；Phase 1 不参与匹配',
    output_template_id  BIGINT          NULL     COMMENT '→ t_contact_plan_template.id',
    output_tone         VARCHAR(32)     NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    config_version      BIGINT          NOT NULL,
    version             INT             NOT NULL DEFAULT 0,
    created_by          VARCHAR(64)     NULL,
    updated_by          VARCHAR(64)     NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_priority (priority),
    INDEX idx_config_version (config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略规则矩阵';

CREATE TABLE IF NOT EXISTS t_compliance_rule (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    rule_code           VARCHAR(64)     NOT NULL COMMENT '如 QUIET_HOURS / DAILY_CAP',
    channel             VARCHAR(32)     NULL     COMMENT 'NULL=全渠道',
    rule_json           JSON            NOT NULL COMMENT '时段/频率/呼损率等参数',
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    config_version      BIGINT          NOT NULL,
    version             INT             NOT NULL DEFAULT 0,
    created_by          VARCHAR(64)     NULL,
    updated_by          VARCHAR(64)     NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_rule (tenant_id, rule_code, channel),
    INDEX idx_config_version (config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='合规阈值配置';

CREATE TABLE IF NOT EXISTS t_channel_config (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    channel_type        VARCHAR(32)     NOT NULL COMMENT 'PUSH/SMS/EMAIL/AI_CALL/...',
    enabled             TINYINT(1)      NOT NULL DEFAULT 1,
    credentials_ref     VARCHAR(128)    NULL     COMMENT '密钥引用，不明文存库',
    route_json          JSON            NULL     COMMENT '供应商路由/降级策略',
    circuit_state       VARCHAR(16)     NOT NULL DEFAULT 'CLOSED' COMMENT 'CLOSED/OPEN/HALF_OPEN',
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    config_version      BIGINT          NOT NULL,
    version             INT             NOT NULL DEFAULT 0,
    updated_by          VARCHAR(64)     NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_channel (tenant_id, channel_type),
    INDEX idx_config_version (config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道开关与路由';

CREATE TABLE IF NOT EXISTS t_evaluation_setting (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    setting_key         VARCHAR(64)     NOT NULL COMMENT '如 HOLDOUT_RATIO',
    setting_value       JSON            NOT NULL COMMENT '策略评估参数 JSON',
    status              VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    config_version      BIGINT          NOT NULL,
    version             INT             NOT NULL DEFAULT 0,
    updated_by          VARCHAR(64)     NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_setting (tenant_id, setting_key),
    INDEX idx_config_version (config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略评估参数配置';

CREATE TABLE IF NOT EXISTS t_evaluation_setting_history (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    setting_key         VARCHAR(64)     NOT NULL,
    setting_value       JSON            NOT NULL,
    config_version      BIGINT          NOT NULL,
    operator            VARCHAR(64)     NOT NULL,
    reason              VARCHAR(512)    NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_setting_version (tenant_id, setting_key, config_version),
    INDEX idx_setting_version (setting_key, config_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略评估参数历史快照（支持回滚）';

CREATE TABLE IF NOT EXISTS t_config_change_log (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(32)     NOT NULL DEFAULT 'mocasa-ph',
    config_type         VARCHAR(32)     NOT NULL COMMENT 'plan_template/script/strategy/compliance/channel',
    config_key          VARCHAR(128)    NOT NULL COMMENT '业务键，如 template_code 或 script_slot',
    from_version        BIGINT          NULL,
    to_version          BIGINT          NOT NULL,
    diff_summary        JSON            NULL,
    operator            VARCHAR(64)     NOT NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_type_time (config_type, created_at),
    INDEX idx_to_version (to_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置变更审计日志';

CREATE TABLE IF NOT EXISTS t_config_version_seq (
    id                  TINYINT         NOT NULL PRIMARY KEY DEFAULT 1,
    current_version     BIGINT          NOT NULL DEFAULT 0,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局 config_version 序列表';

INSERT IGNORE INTO t_config_version_seq (id, current_version) VALUES (1, 0);

-- ---------------------------------------------------------------------
-- B. 管理后台运行辅助表（Phase 1）
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_admin_case_freeze (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT          NOT NULL,
    user_id             BIGINT          NULL,
    freeze_type         VARCHAR(32)     NOT NULL DEFAULT 'COMPLAINT' COMMENT 'COMPLAINT/OTHER',
    status              VARCHAR(16)     NOT NULL DEFAULT 'FROZEN' COMMENT 'FROZEN/RELEASED/ESCALATED',
    reason              VARCHAR(512)    NULL,
    operator            VARCHAR(64)     NOT NULL,
    escalated_at        DATETIME        NULL     COMMENT '升级为 COMPLAINT 终态的时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_case_id (case_id),
    INDEX idx_user (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='案件投诉冻结（PreFlight 实时读取）';

CREATE TABLE IF NOT EXISTS t_ops_exception (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    exception_type      VARCHAR(32)     NOT NULL COMMENT 'CALLBACK_TIMEOUT/PLAN_STUCK/...',
    channel             VARCHAR(32)     NULL,
    error_code          VARCHAR(64)     NULL,
    case_id             BIGINT          NULL,
    plan_id             BIGINT          NULL,
    step_id             BIGINT          NULL,
    severity            VARCHAR(16)     NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
    message             VARCHAR(1024)   NULL,
    detail_json         JSON            NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACK/RESOLVED/IGNORED',
    cluster_key         VARCHAR(256)    NULL     COMMENT '折叠聚合键 type:channel:errorCode',
    operator            VARCHAR(64)     NULL,
    resolved_at         DATETIME        NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status_time (status, created_at),
    INDEX idx_cluster (cluster_key, status),
    INDEX idx_plan_step (plan_id, step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维异常队列';

-- ---------------------------------------------------------------------
-- C. 运行表扩展（历史快照，§6.5）
-- 注：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，用存储过程安全追加
-- ---------------------------------------------------------------------

DROP PROCEDURE IF EXISTS sp_admin_add_snapshot_columns;

DELIMITER //
CREATE PROCEDURE sp_admin_add_snapshot_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_plan_step' AND COLUMN_NAME = 'config_version'
    ) THEN
        ALTER TABLE t_contact_plan_step
            ADD COLUMN config_version BIGINT NULL COMMENT '步骤生成时的配置版本' AFTER template_id,
            ADD COLUMN resolved_params JSON NULL COMMENT '解析后关键参数快照' AFTER config_version;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'config_version'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN config_version BIGINT NULL COMMENT '发送时配置版本' AFTER template_id,
            ADD COLUMN rendered_ref VARCHAR(256) NULL COMMENT '渲染内容引用/摘要键' AFTER config_version;
    END IF;
END //
DELIMITER ;

CALL sp_admin_add_snapshot_columns();
DROP PROCEDURE IF EXISTS sp_admin_add_snapshot_columns;

DROP PROCEDURE IF EXISTS sp_admin_add_change_log_columns;

DELIMITER //
CREATE PROCEDURE sp_admin_add_change_log_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_config_change_log' AND COLUMN_NAME = 'rollback_ref'
    ) THEN
        ALTER TABLE t_config_change_log
            ADD COLUMN rollback_ref BIGINT NULL COMMENT '回滚来源配置版本' AFTER diff_summary;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_config_change_log' AND COLUMN_NAME = 'reason'
    ) THEN
        ALTER TABLE t_config_change_log
            ADD COLUMN reason VARCHAR(512) NULL COMMENT '操作原因' AFTER rollback_ref;
    END IF;
END //
DELIMITER ;

CALL sp_admin_add_change_log_columns();
DROP PROCEDURE IF EXISTS sp_admin_add_change_log_columns;
