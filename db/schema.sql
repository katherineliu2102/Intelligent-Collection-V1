-- =====================================================================
-- MOCASA 智能催收升级 Phase 1 — 引擎核心表 DDL
-- 目标库：ai_collection_db（新测试库）
-- 来源：领域模型与数据定义 §7
-- 用法：mysql -h<DB_HOST> -u<DB_USER> -p -P<DB_PORT> <DB_NAME> < db/schema.sql （连接信息向主架构负责人获取）
-- =====================================================================

-- 7.1.1 触达计划主表
CREATE TABLE IF NOT EXISTS t_contact_plan (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT          NOT NULL COMMENT '关联案件ID',
    user_id             BIGINT          NOT NULL COMMENT '用户ID',
    stage               VARCHAR(16)     NOT NULL COMMENT '催收阶段: S0/S1/S2/S3/S4',
    plan_template_id    BIGINT          NULL     COMMENT '触达计划模板ID',
    status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/STEP_SCHEDULED/STEP_EXECUTING/STEP_WAITING/PLAN_COMPLETED/PLAN_CANCELLED',
    current_step        INT             NOT NULL DEFAULT 0 COMMENT '当前执行到第几步',
    total_steps         INT             NOT NULL COMMENT '总步数',
    cancel_reason       VARCHAR(64)     NULL     COMMENT 'REPAID/STAGE_UPGRADE/CEASED/CASE_NOT_FOUND/COMPLAINT/MANUAL（PTP_EXPIRED 为 Phase 2 预留，Phase 1 不写入）',
    context_snapshot    JSON            NULL     COMMENT '决策上下文快照（ContextSnapshot JSON）',
    idempotency_key     VARCHAR(128)    NULL     COMMENT '计划创建幂等键 case_id:stage:create_timestamp',
    renewal_pending     TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'REBUILD 事务内旧计划过渡标记，调度器不可执行',
    active_stage_key    VARCHAR(128) GENERATED ALWAYS AS (
        CASE
            WHEN renewal_pending = 0 AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')
            THEN CONCAT(case_id, ':', stage)
            ELSE NULL
        END
    ) STORED COMMENT '仅可执行活跃计划参与 case+stage 唯一约束',
    version             INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    started_at          DATETIME        NULL     COMMENT '首步进入EXECUTING时写入',
    completed_at        DATETIME        NULL     COMMENT '进入终态时写入',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_case (case_id),
    INDEX idx_status (status),
    INDEX idx_user_stage (user_id, stage),
    UNIQUE KEY uk_active_stage_key (active_stage_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达计划主表';

-- 既有环境迁移：通过 information_schema 判定，兼容不支持 ALTER ... IF NOT EXISTS 的 MySQL。
DROP PROCEDURE IF EXISTS sp_schema_add_plan_active_stage_key;
DELIMITER //
CREATE PROCEDURE sp_schema_add_plan_active_stage_key()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_contact_plan'
          AND COLUMN_NAME = 'active_stage_key'
    ) THEN
        ALTER TABLE t_contact_plan
            ADD COLUMN active_stage_key VARCHAR(128)
            GENERATED ALWAYS AS (
                CASE
                    WHEN renewal_pending = 0 AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')
                    THEN CONCAT(case_id, ':', stage)
                    ELSE NULL
                END
            ) STORED;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_contact_plan'
          AND INDEX_NAME = 'uk_active_stage_key'
    ) THEN
        ALTER TABLE t_contact_plan
            ADD UNIQUE INDEX uk_active_stage_key (active_stage_key);
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_plan_active_stage_key();
DROP PROCEDURE IF EXISTS sp_schema_add_plan_active_stage_key;

-- 7.1.2 触达计划步骤表
CREATE TABLE IF NOT EXISTS t_contact_plan_step (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    plan_id             BIGINT          NOT NULL COMMENT '关联触达计划ID',
    step_order          INT             NOT NULL COMMENT '步骤序号（从1开始）',
    channel_type        VARCHAR(32)     NOT NULL COMMENT 'PUSH/SMS/AI_CALL/TTS/EMAIL/VIBER/WHATSAPP/HUMAN_CALL',
    template_id         BIGINT          NULL     COMMENT '话术模板ID',
    delay_minutes       INT             NOT NULL DEFAULT 0 COMMENT '相对上一步的延迟（分钟）',
    trigger_time        DATETIME        NULL     COMMENT '绝对触发时间（引擎计算写入）',
    timeout_time        DATETIME        NULL     COMMENT '异步回调超时时间',
    trigger_condition   VARCHAR(256)    NULL     COMMENT '前置条件表达式（Phase 1 未启用）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EXECUTING/COMPLETED/SKIPPED/FAILED',
    observation_minutes INT             NOT NULL DEFAULT 0 COMMENT '观察期（分钟），0=无观察期',
    retry_count         INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    result              VARCHAR(32)     NULL     COMMENT '步骤最终结果（ContactResult）',
    idempotency_key     VARCHAR(128)    NULL     COMMENT '幂等键 plan_id:step_order:attempt',
    executed_at         DATETIME        NULL     COMMENT '步骤开始执行时间',
    completed_at        DATETIME        NULL     COMMENT '步骤完成时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_step_order (plan_id, step_order),
    INDEX idx_trigger (trigger_time, status),
    INDEX idx_timeout (timeout_time, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='触达计划步骤表';

DROP PROCEDURE IF EXISTS sp_schema_add_plan_step_index;
DELIMITER //
CREATE PROCEDURE sp_schema_add_plan_step_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_contact_plan_step'
          AND INDEX_NAME = 'uk_plan_step_order'
    ) THEN
        ALTER TABLE t_contact_plan_step
            ADD UNIQUE INDEX uk_plan_step_order (plan_id, step_order);
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_plan_step_index();
DROP PROCEDURE IF EXISTS sp_schema_add_plan_step_index;

-- 7.1.3 决策日志
CREATE TABLE IF NOT EXISTS t_decision_log (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT          NOT NULL,
    plan_id             BIGINT          NULL     COMMENT '关联触达计划ID',
    step_id             BIGINT          NULL     COMMENT '关联步骤ID',
    decision_type       VARCHAR(32)     NOT NULL COMMENT 'ASSIGNMENT/CHANNEL_SELECT/SCRIPT_SELECT/TIMING/CHANNEL_MODE_SELECT',
    engine_type         VARCHAR(16)     NOT NULL COMMENT 'RULE/LLM',
    engine_version      VARCHAR(32)     NULL,
    input_snapshot      JSON            NOT NULL COMMENT '决策输入快照',
    output_decision     JSON            NOT NULL COMMENT '决策结果',
    reasoning           TEXT            NULL,
    confidence          DECIMAL(5,4)    NOT NULL DEFAULT 1.0000,
    latency_ms          INT             NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_case_type (case_id, decision_type),
    INDEX idx_plan_step (plan_id, step_id),
    INDEX idx_engine (engine_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策日志';

-- 7.2.1 统一触达时间线
CREATE TABLE IF NOT EXISTS t_contact_timeline (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    case_id             BIGINT          NOT NULL,
    user_id             BIGINT          NOT NULL,
    plan_id             BIGINT          NULL,
    step_id             BIGINT          NULL,
    attempt_key         VARCHAR(128)    NULL COMMENT '单次触达尝试幂等键(planId:stepId:retryCount)',
    channel             VARCHAR(32)     NOT NULL COMMENT 'PUSH/SMS/AI_CALL/TTS/EMAIL/VIBER/WHATSAPP/HUMAN_CALL',
    direction           VARCHAR(8)      NOT NULL DEFAULT 'OUT' COMMENT 'OUT/IN',
    template_id         BIGINT          NULL,
    config_version      BIGINT          NULL COMMENT '发送时配置版本',
    rendered_ref        VARCHAR(256)    NULL COMMENT '渲染内容无 PII 引用',
    content_summary     VARCHAR(500)    NULL,
    script_slot         VARCHAR(64)     NULL COMMENT '解析后话术槽位（无 PII）',
    template_version    VARCHAR(128)    NULL COMMENT '模板来源及发布版本',
    content_hmac        CHAR(64)        NULL COMMENT '渲染正文 HMAC-SHA-256',
    content_key_id      VARCHAR(64)     NULL COMMENT 'content_hmac 密钥版本标识',
    result              VARCHAR(32)     NULL,
    provider_msg_id     VARCHAR(128)    NULL,
    provider_callback   JSON            NULL,
    cost                DECIMAL(10,4)   NULL,
    source              VARCHAR(16)     NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM/ETL_SYNC/PUBSUB_SYNC',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_case_time (case_id, created_at),
    INDEX idx_user_channel (user_id, channel),
    INDEX idx_plan (plan_id),
    UNIQUE KEY uk_attempt_key (attempt_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一触达时间线';

-- 既有环境迁移：使用 information_schema 判定，避免依赖 ALTER ... IF NOT EXISTS。
DROP PROCEDURE IF EXISTS sp_schema_add_timeline_columns;
DELIMITER //
CREATE PROCEDURE sp_schema_add_timeline_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'attempt_key'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN attempt_key VARCHAR(128) NULL COMMENT '单次触达尝试幂等键(planId:stepId:retryCount)';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'config_version'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN config_version BIGINT NULL COMMENT '发送时配置版本';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'rendered_ref'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN rendered_ref VARCHAR(256) NULL COMMENT '渲染内容无 PII 引用';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'script_slot'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN script_slot VARCHAR(64) NULL COMMENT '解析后话术槽位（无 PII）';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'template_version'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN template_version VARCHAR(128) NULL COMMENT '模板来源及发布版本';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'content_hmac'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN content_hmac CHAR(64) NULL COMMENT '渲染正文 HMAC-SHA-256';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND COLUMN_NAME = 'content_key_id'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD COLUMN content_key_id VARCHAR(64) NULL COMMENT 'content_hmac 密钥版本标识';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND INDEX_NAME = 'uk_attempt_key'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD UNIQUE INDEX uk_attempt_key (attempt_key);
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_timeline_columns();
DROP PROCEDURE IF EXISTS sp_schema_add_timeline_columns;

-- 7.2.2 供应商回调审计：原始回调证据，与 timeline 最终触达事实分层。
CREATE TABLE IF NOT EXISTS t_channel_callback_audit (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    plan_id             BIGINT          NOT NULL,
    step_id             BIGINT          NOT NULL,
    case_id             BIGINT          NULL,
    provider_msg_id     VARCHAR(128)    NULL,
    result              VARCHAR(32)     NULL,
    disposition         VARCHAR(64)     NULL,
    canonical_payload   TEXT            NOT NULL,
    signature           VARCHAR(512)    NULL,
    signature_valid     TINYINT(1)      NOT NULL,
    received_at         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_plan_step_received (plan_id, step_id, received_at),
    INDEX idx_provider_msg_id (provider_msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应商渠道回调原始审计';

-- 7.2.3 事件死信长期审计（Redis :dlq 为即时缓冲，MySQL 为处置 SSOT）。
CREATE TABLE IF NOT EXISTS t_event_dlq (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    event_id            VARCHAR(64)     NOT NULL,
    event_type          VARCHAR(64)     NOT NULL,
    payload             JSON            NOT NULL,
    failure_reason      VARCHAR(256)    NOT NULL,
    delivery_count      INT             NOT NULL DEFAULT 1,
    first_failed_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failed_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_dlq_event_id (event_id),
    INDEX idx_event_dlq_status_last (status, last_failed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件死信队列';

-- 7.2.4 用户 Push Token 镜像（数仓日同步，供 ingestion enrichment）
CREATE TABLE IF NOT EXISTS t_user_device_token (
    user_id             BIGINT          NOT NULL PRIMARY KEY COMMENT '用户ID',
    jpush_token         VARCHAR(256)    NULL     COMMENT 'JPush Registration ID（源：旧库 t_user_extend.ji_guang_token）',
    synced_at           DATETIME        NOT NULL COMMENT '数仓同步批次时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_synced_at (synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 Push Token 镜像（数仓日同步）';

-- 7.2.2 用户画像扩展表 t_user_profile_ext：Phase 1 不建表，押后 Phase 2
--   原因：Phase 1 无代码消费 / 无 mapper（MockProfileService 仅填 basic + device.jpushToken）。
--   待数仓/号码检测供应商就绪或坐席标记上线再建，届时同步领域模型 §7.2.2。
