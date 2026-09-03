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
    cancel_reason       VARCHAR(64)     NULL     COMMENT 'REPAID/STAGE_UPGRADE/CEASED/CASE_NOT_FOUND/NO_DUE_BALANCE/ROUTED_TO_LEGACY/COMPLAINT/MANUAL/MANUAL_CLEANUP（PTP_EXPIRED 为 Phase 2 预留，Phase 1 不写入）',
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
    trigger_time        DATETIME        NULL     COMMENT '绝对触发时间（引擎计算写入）；被扫描拾取后清空',
    original_trigger_time DATETIME      NULL     COMMENT '建计划时的原始排期（只写一次，永不更新）：供计划 vs 实际偏差分析',
    timeout_time        DATETIME        NULL     COMMENT '异步回调超时时间',
    trigger_condition   VARCHAR(256)    NULL     COMMENT '前置条件表达式（Phase 1 未启用）',
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/EXECUTING/COMPLETED/SKIPPED/FAILED',
    observation_minutes INT             NOT NULL DEFAULT 0 COMMENT '观察期（分钟），0=无观察期',
    retry_count         INT             NOT NULL DEFAULT 0 COMMENT '已重试次数',
    result              VARCHAR(32)     NULL     COMMENT '步骤最终结果（ContactResult）',
    idempotency_key     VARCHAR(128)    NULL     COMMENT '幂等键 plan_id:step_order:retry_count',
    executed_at         DATETIME        NULL     COMMENT '步骤开始执行时间（引擎开始尝试）',
    dispatched_at       DATETIME        NULL     COMMENT '渠道受理时间（供应商已接单）：区分"卡在调用前"与"已发出未回写"',
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

-- 既有环境迁移：排期审计列。trigger_time 被扫描拾取后会被清空、重试/延后时会被改写，
-- 因此「原始排期」与「渠道受理时刻」必须独立成列，否则无法回答"计划几点打、实际几点发出"。
DROP PROCEDURE IF EXISTS sp_schema_add_plan_step_audit_columns;
DELIMITER //
CREATE PROCEDURE sp_schema_add_plan_step_audit_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_plan_step'
          AND COLUMN_NAME = 'original_trigger_time'
    ) THEN
        ALTER TABLE t_contact_plan_step
            ADD COLUMN original_trigger_time DATETIME NULL COMMENT '建计划时的原始排期（只写一次，永不更新）'
                AFTER trigger_time;
        UPDATE t_contact_plan_step SET original_trigger_time = trigger_time
        WHERE original_trigger_time IS NULL AND trigger_time IS NOT NULL;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_plan_step'
          AND COLUMN_NAME = 'dispatched_at'
    ) THEN
        ALTER TABLE t_contact_plan_step
            ADD COLUMN dispatched_at DATETIME NULL COMMENT '渠道受理时间（供应商已接单）' AFTER executed_at;
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_plan_step_audit_columns();
DROP PROCEDURE IF EXISTS sp_schema_add_plan_step_audit_columns;

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
    attempt_key         VARCHAR(128)    NULL COMMENT '单次触达尝试幂等键(planId:stepOrder:retryCount)',
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
    INDEX idx_user_time (user_id, created_at),
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
            ADD COLUMN attempt_key VARCHAR(128) NULL COMMENT '单次触达尝试幂等键(planId:stepOrder:retryCount)';
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
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_contact_timeline' AND INDEX_NAME = 'idx_user_time'
    ) THEN
        ALTER TABLE t_contact_timeline
            ADD INDEX idx_user_time (user_id, created_at);
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_timeline_columns();
DROP PROCEDURE IF EXISTS sp_schema_add_timeline_columns;

-- 7.2.2 供应商回调审计：原始回调证据，与 timeline 最终触达事实分层。
CREATE TABLE IF NOT EXISTS t_channel_callback_audit (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    plan_id             BIGINT          NULL,
    step_id             BIGINT          NULL,
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

-- Facade 入站在身份未解析时仍须留痕；plan/step 可空。
DROP PROCEDURE IF EXISTS sp_schema_relax_callback_audit_ids;
DELIMITER //
CREATE PROCEDURE sp_schema_relax_callback_audit_ids()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 't_channel_callback_audit'
          AND COLUMN_NAME = 'plan_id'
          AND IS_NULLABLE = 'NO'
    ) THEN
        ALTER TABLE t_channel_callback_audit
            MODIFY plan_id BIGINT NULL,
            MODIFY step_id BIGINT NULL;
    END IF;
END //
DELIMITER ;
CALL sp_schema_relax_callback_audit_ids();
DROP PROCEDURE IF EXISTS sp_schema_relax_callback_audit_ids;

-- AI Call 会话底座（v1.3 设计，v1.6 首次落库，对应设计文档 §6.2 / §5.1.4）：
-- 结构化提取 Facade `session.completed` 回调，供看板 AI Call 分区（业务结果首屏 + 渠道卫生层）按原生词聚合。
-- 与 t_channel_callback_audit 分工：audit 存完整 canonical_payload（审计/重放 SSOT），本表存结构化列（查询/聚合 SSOT）。
-- 两套口径（§6.2）：本表存供应商原生词（was_answered/was_ai_connected/line_reason/sip_code/final_failure_reason/result_label），
-- 映射后 ContactResult 仍在 step/timeline，互不覆盖。字段名以《FACADE客户接入手册》§9.3/§9.4 为准。
-- stage/dpd 快照由写路径从 plan/step 上下文补（回调不带）；is_synthetic 由 mock 触发通道标记。
CREATE TABLE IF NOT EXISTS t_ai_call_session (
    id                   BIGINT          AUTO_INCREMENT PRIMARY KEY,
    session_id           VARCHAR(128)    NOT NULL COMMENT 'Facade session_id',
    batch_id             VARCHAR(128)    NULL COMMENT 'Facade batch_id / external_batch_id',
    case_id              BIGINT          NULL COMMENT 'loan_id；identity 未解析时可空',
    plan_id              BIGINT          NULL,
    step_id              BIGINT          NULL,
    event                VARCHAR(32)     NULL COMMENT 'session.completed / batch.completed',
    -- 电信层（原生词）
    was_ringing          TINYINT(1)      NULL COMMENT 'line_outcome.was_ringing（线路/号码质量）',
    was_answered         TINYINT(1)      NULL COMMENT 'line_outcome.was_answered（客户接起，含信箱/筛选）',
    was_ai_connected     TINYINT(1)      NULL COMMENT 'line_outcome.was_ai_connected（真人多轮 = 结果链 L1）',
    line_reason          VARCHAR(32)     NULL COMMENT 'line_outcome.reason：NORMAL/VOICEMAIL/CALL_SCREENING',
    sip_code             VARCHAR(32)     NULL COMMENT 'line_outcome.sip_code（406/486/487/603...；未接通时常见）',
    final_failure_reason VARCHAR(64)     NULL COMMENT 'BUSY/NO_ANSWER/FORBIDDEN/DECLINE/TEMP_UNAVAILABLE/REQUEST_TIMEOUT/INVALID_NUMBER/MEDIA_NEGOTIATION_FAILED/SIP_SERVER_ERROR',
    -- 业务层（ai_result，9/1 实证真实接通会回传）
    result_label         VARCHAR(64)     NULL COMMENT 'ai_result.result_label（开放标签集：promise_to_pay/follow_up_required/dispute/...，不冻结枚举）',
    summary              TEXT            NULL COMMENT 'ai_result.summary；非空可作 needs_review 辅助清除信号',
    promises_json        JSON            NULL COMMENT 'ai_result.promises[] 原始数组（amount/currency/promised_date）；现恒空',
    -- 观测辅助
    caller_cli           VARCHAR(32)     NULL COMMENT 'parties.caller_cli 实际外显主叫（当前 6310001）',
    dialed_at            DATETIME        NULL COMMENT 'dial_timeline.dialed_at',
    answered_at          DATETIME        NULL COMMENT 'dial_timeline.answered_at；未回传为 NULL，禁止记 0',
    ended_at             DATETIME        NULL COMMENT 'dial_timeline.ended_at；时长由 ended_at-answered_at 派生',
    needs_review         TINYINT(1)      NULL COMMENT 'was_answered=1 且无借款人发言（转写判定，未定前退化人工）',
    is_synthetic         TINYINT(1)      NOT NULL DEFAULT 0 COMMENT 'mock/测试会话；看板默认过滤',
    stage_snapshot       VARCHAR(16)     NULL COMMENT '会话发生时 stage 快照（S0-S4），写路径从 plan/step 补',
    dpd_snapshot         INT             NULL COMMENT '会话发生时 dpd 快照，写路径从 plan/step 补',
    received_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_call_session_id (session_id),
    INDEX idx_ai_call_session_received (received_at),
    INDEX idx_ai_call_session_case (case_id, received_at),
    INDEX idx_ai_call_session_batch (batch_id),
    INDEX idx_ai_call_session_label (result_label, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Call 会话底座（原生词，看板聚合用）';

-- 7.2.3 事件死信长期审计（Redis :dlq 为即时缓冲，MySQL 为处置 SSOT）。
CREATE TABLE IF NOT EXISTS t_event_dlq (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    event_id            VARCHAR(64)     NOT NULL,
    event_type          VARCHAR(64)     NOT NULL,
    payload             JSON            NOT NULL,
    failure_reason      VARCHAR(256)    NOT NULL,
    delivery_count      INT             NOT NULL DEFAULT 1,
    redrive_count       INT             NOT NULL DEFAULT 0,
    first_failed_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failed_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    redrive_reason      VARCHAR(256)    NULL,
    redriven_at         DATETIME        NULL,
    terminated_at       DATETIME        NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_dlq_event_id (event_id),
    INDEX idx_event_dlq_status_last (status, last_failed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事件死信队列';

-- 既有环境迁移：受控 redrive 所需的计数、原因与状态时间列。
DROP PROCEDURE IF EXISTS sp_schema_add_event_dlq_redrive_columns;
DELIMITER //
CREATE PROCEDURE sp_schema_add_event_dlq_redrive_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_event_dlq' AND COLUMN_NAME = 'redrive_count'
    ) THEN
        ALTER TABLE t_event_dlq
            ADD COLUMN redrive_count INT NOT NULL DEFAULT 0 COMMENT '受控重放次数，上限 3';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_event_dlq' AND COLUMN_NAME = 'redrive_reason'
    ) THEN
        ALTER TABLE t_event_dlq
            ADD COLUMN redrive_reason VARCHAR(256) NULL COMMENT '操作者填写的重放原因';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_event_dlq' AND COLUMN_NAME = 'redriven_at'
    ) THEN
        ALTER TABLE t_event_dlq
            ADD COLUMN redriven_at DATETIME NULL COMMENT '最近一次重放发布时间';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_event_dlq' AND COLUMN_NAME = 'terminated_at'
    ) THEN
        ALTER TABLE t_event_dlq
            ADD COLUMN terminated_at DATETIME NULL COMMENT '终止处置时间';
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_event_dlq_redrive_columns();
DROP PROCEDURE IF EXISTS sp_schema_add_event_dlq_redrive_columns;

-- 7.2.5 领域事件发件箱（Transactional Outbox）。
-- 状态迁移与派生事件写在同一事务：提交后的即时发布若失败（总线抖动、进程被杀），
-- 原事件重投时状态已是终态、派生事件不会被重新推导，计划就此静默停摆。
-- 发件箱把"事件已产生"这个事实和状态一起落盘，由 OutboxPublisher 兜底重发。

-- 7.2.5a AI 催收案件投影（collection-ingestion 消费数仓 Pub/Sub 事实流后写入）。
-- t_ai_collection 是新系统运行时唯一案件来源；不得在引擎/日切路径回读旧 t_collection。
-- 单写者约束：只有 ingestion 投影管道可以写本表，数仓不得直连业务库 SQL 写入，
-- 否则独立写入路径会互相覆盖、case_version 内容指纹失去一致性。
CREATE TABLE IF NOT EXISTS t_ai_collection (
    case_id                 BIGINT          NOT NULL PRIMARY KEY COMMENT 'loan_id，规范数字案件键',
    user_id                 BIGINT          NOT NULL,
    case_version            CHAR(32)        NOT NULL COMMENT '数仓快照内容指纹；相同略过、不同刷新',
    dpd                     INT             NOT NULL,
    stage                   VARCHAR(16)     NULL COMMENT 'S0/S1/S2/S3/S4；D+91 可为空',
    collection_status       VARCHAR(32)     NOT NULL COMMENT 'IN_COLLECTION/SETTLED/CEASED',
    product                 VARCHAR(64)     NOT NULL,
    overdue_amount          DECIMAL(18,2)   NOT NULL DEFAULT 0 COMMENT '已到期未结清总额，含罚息',
    total_outstanding       DECIMAL(18,2)   NOT NULL COMMENT '已到期且未结清，对客金额',
    penalty_amount          DECIMAL(18,2)   NOT NULL DEFAULT 0,
    last_paid_amount        DECIMAL(18,2)   NULL COMMENT '最近一次还款金额（repaymentEvent.paidAmount）',
    settled_at              DATETIME        NULL COMMENT '最近一次还款时间（repaymentEvent.repayTime，PHT）',
    remaining_amount        DECIMAL(18,2)   NOT NULL DEFAULT 0 COMMENT '废弃历史字段，不再表示全部未结清',
    upcoming_amount         DECIMAL(18,2)   NULL COMMENT '三期下一期 D-3～D0 待还金额',
    due_date                DATE            NULL,
    next_due_date           DATE            NULL COMMENT '三期下一期 D-3～D0 提醒日期',
    borrower_name           VARCHAR(256)    NOT NULL,
    borrower_phone          VARCHAR(64)     NOT NULL COMMENT 'E.164',
    borrower_email          VARCHAR(256)    NULL,
    borrower_language       VARCHAR(16)     NOT NULL DEFAULT 'en',
    push_token              VARCHAR(512)    NULL,
    owner                   VARCHAR(16)     NOT NULL DEFAULT 'NEW' COMMENT '发给本系统的案件固定 NEW',
    owner_date              DATE            NULL COMMENT 'PHT 归属日，date(occurredAt)；还款不得刷新',
    updated_at              DATETIME        NOT NULL COMMENT '数仓快照业务更新时间',
    synced_at               DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接入层投影落库时间',
    INDEX idx_ai_collection_active (collection_status, dpd, case_id),
    INDEX idx_ai_collection_updated (updated_at, case_id),
    INDEX idx_ai_collection_owner_date (owner_date, collection_status, case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新系统 AI 催收案件当前态';

-- 7.2.5b 入催消息收件箱。event_id 为数仓生成的业务幂等键，重试/重发/重放必须复用同一值。
-- 投影更新（MySQL）与内部领域事件发布（Redis Stream）无法原子提交：本表在投影事务内落盘，
-- 记录"这条事实已入库、领域事件是否已发出"。消息重投时据 publish_status 判断是补发事件还是整条跳过，
-- 避免"投影已写入 → 进程被杀 → 重投被版本判定为陈旧 → 领域事件永久丢失"。
-- 与 t_event_outbox（引擎派生事件发件箱）分属两层，互不替代。
CREATE TABLE IF NOT EXISTS t_ai_collection_inbox (
    id                      BIGINT          AUTO_INCREMENT PRIMARY KEY,
    event_id                VARCHAR(64)     NOT NULL COMMENT '数仓 publish 时生成，重投复用',
    case_id                 BIGINT          NOT NULL,
    case_version            CHAR(32)        NULL COMMENT 'caseEvent 内容指纹；repaymentEvent 增量可为空',
    message_type            VARCHAR(32)     NOT NULL COMMENT 'caseEvent/repaymentEvent',
    event_type              VARCHAR(64)     NOT NULL,
    payload                 JSON            NOT NULL COMMENT '完整外部 Pub/Sub payload，供审计与重放',
    projection_applied      TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否已更新 t_ai_collection；陈旧版本为 0',
    publish_status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/SKIPPED',
    published_at            DATETIME        NULL COMMENT '内部领域事件确认投递时间',
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_inbox_event_id (event_id),
    INDEX idx_ai_inbox_pending (publish_status, created_at),
    INDEX idx_ai_inbox_case_version (case_id, case_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 催收入站事实收件箱（投影与内部事件的可靠桥接）';

-- 既有环境迁移：case_version 从单调整数版本改为数仓内容指纹。
DROP PROCEDURE IF EXISTS sp_schema_case_version_to_fingerprint;
DELIMITER //
CREATE PROCEDURE sp_schema_case_version_to_fingerprint()
BEGIN
    ALTER TABLE t_ai_collection
        MODIFY COLUMN case_version CHAR(32) NOT NULL COMMENT '数仓快照内容指纹；相同略过、不同刷新';
    ALTER TABLE t_ai_collection_inbox
        MODIFY COLUMN case_version CHAR(32) NULL COMMENT 'caseEvent 内容指纹；repaymentEvent 增量可为空';
END //
DELIMITER ;
CALL sp_schema_case_version_to_fingerprint();
DROP PROCEDURE IF EXISTS sp_schema_case_version_to_fingerprint;

-- 既有联调环境迁移：还款增量的运行态字段。
DROP PROCEDURE IF EXISTS sp_schema_add_ai_collection_repayment_fields;
DELIMITER //
CREATE PROCEDURE sp_schema_add_ai_collection_repayment_fields()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'overdue_amount'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN overdue_amount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已到期未结清总额，含罚息'
            AFTER product;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'upcoming_amount'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN upcoming_amount DECIMAL(18,2) NULL COMMENT '三期下一期 D-3～D0 待还金额'
            AFTER remaining_amount;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'next_due_date'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN next_due_date DATE NULL COMMENT '三期下一期 D-3～D0 提醒日期'
            AFTER due_date;
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_ai_collection_repayment_fields();
DROP PROCEDURE IF EXISTS sp_schema_add_ai_collection_repayment_fields;

-- 既有环境迁移：按日 owner 路由。
DROP PROCEDURE IF EXISTS sp_schema_add_ai_collection_owner_fields;
DELIMITER //
CREATE PROCEDURE sp_schema_add_ai_collection_owner_fields()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'owner'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN owner VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT '发给本系统的案件固定 NEW'
            AFTER push_token;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'owner_date'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN owner_date DATE NULL COMMENT 'PHT 归属日，date(occurredAt)；还款不得刷新'
            AFTER owner;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND INDEX_NAME = 'idx_ai_collection_owner_date'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD INDEX idx_ai_collection_owner_date (owner_date, collection_status, case_id);
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_ai_collection_owner_fields();
DROP PROCEDURE IF EXISTS sp_schema_add_ai_collection_owner_fields;

CREATE TABLE IF NOT EXISTS t_ai_owner_reconcile (
    reconcile_date          DATE            NOT NULL PRIMARY KEY COMMENT 'PHT 日历日',
    completed_at            DATETIME        NOT NULL,
    owner_case_count        INT             NOT NULL DEFAULT 0 COMMENT '写入水位时当日 owner_date=当日的案件数（当日收到 caseEvent 的案件数，按案件去重）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当日 owner 对账水位；引擎与扫描只读';

-- 既有环境迁移：零收检测从 inbox JSON 扫描改为投影 owner_date 计数，水位计数列随之改名换义。
DROP PROCEDURE IF EXISTS sp_schema_rename_owner_reconcile_count;
DELIMITER //
CREATE PROCEDURE sp_schema_rename_owner_reconcile_count()
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_owner_reconcile'
          AND COLUMN_NAME = 'inbox_case_event_count'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_owner_reconcile'
          AND COLUMN_NAME = 'owner_case_count'
    ) THEN
        ALTER TABLE t_ai_owner_reconcile
            CHANGE COLUMN inbox_case_event_count owner_case_count
            INT NOT NULL DEFAULT 0 COMMENT '写入水位时当日 owner_date=当日的案件数（当日收到 caseEvent 的案件数，按案件去重）';
    END IF;
END //
DELIMITER ;
CALL sp_schema_rename_owner_reconcile_count();
DROP PROCEDURE IF EXISTS sp_schema_rename_owner_reconcile_count;

-- 既有环境迁移：还款金额与时间字段（「当日回收金额」热层数据底座）。
DROP PROCEDURE IF EXISTS sp_schema_add_ai_collection_paid_fields;
DELIMITER //
CREATE PROCEDURE sp_schema_add_ai_collection_paid_fields()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'last_paid_amount'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN last_paid_amount DECIMAL(18,2) NULL COMMENT '最近一次还款金额（repaymentEvent.paidAmount）'
            AFTER penalty_amount;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_collection' AND COLUMN_NAME = 'settled_at'
    ) THEN
        ALTER TABLE t_ai_collection
            ADD COLUMN settled_at DATETIME NULL COMMENT '最近一次还款时间（repaymentEvent.repayTime，PHT）'
            AFTER last_paid_amount;
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_ai_collection_paid_fields();
DROP PROCEDURE IF EXISTS sp_schema_add_ai_collection_paid_fields;

-- 既有环境迁移：数仓不再写业务库，t_ai_collection_outbox 无发布器也无消费者。
-- 归档需求由 t_ai_collection_inbox.payload 承接；确认数仓侧发布器已下线、无 PENDING 记录后再执行下一行。
-- DROP TABLE IF EXISTS t_ai_collection_outbox;

CREATE TABLE IF NOT EXISTS t_event_outbox (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    event_id            VARCHAR(64)     NOT NULL COMMENT '与 CollectionEvent.eventId 一致，消费侧据此去重',
    event_type          VARCHAR(64)     NOT NULL,
    plan_id             BIGINT          NULL     COMMENT '来源计划（排障用）',
    case_id             BIGINT          NULL     COMMENT '来源案件（排障用）',
    payload             JSON            NOT NULL COMMENT '完整 CollectionEvent 信封 JSON',
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/PUBLISHED/FAILED',
    retry_count         INT             NOT NULL DEFAULT 0 COMMENT '兜底重发次数',
    next_retry_at       DATETIME        NOT NULL COMMENT '兜底重发时间；入库时 = now + 宽限期，让即时发布先赢',
    lease_until         DATETIME        NULL COMMENT 'PROCESSING 认领租约；到期后允许其他实例重新认领',
    published_at        DATETIME        NULL     COMMENT '确认已投递到总线的时间',
    last_error          VARCHAR(512)    NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_event_outbox_event_id (event_id),
    INDEX idx_event_outbox_due (status, next_retry_at),
    INDEX idx_event_outbox_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域事件发件箱（状态与事件同事务）';

-- 既有环境迁移：多实例发布器认领租约。
DROP PROCEDURE IF EXISTS sp_schema_add_event_outbox_lease;
DELIMITER //
CREATE PROCEDURE sp_schema_add_event_outbox_lease()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_event_outbox' AND COLUMN_NAME = 'lease_until'
    ) THEN
        ALTER TABLE t_event_outbox
            ADD COLUMN lease_until DATETIME NULL COMMENT 'PROCESSING 认领租约；到期后允许重新认领';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_event_outbox' AND INDEX_NAME = 'idx_event_outbox_lease'
    ) THEN
        ALTER TABLE t_event_outbox
            ADD INDEX idx_event_outbox_lease (status, lease_until);
    END IF;
END //
DELIMITER ;
CALL sp_schema_add_event_outbox_lease();
DROP PROCEDURE IF EXISTS sp_schema_add_event_outbox_lease;

-- 7.2.4 用户 Push Token 镜像（数仓日同步，供 ingestion enrichment）
CREATE TABLE IF NOT EXISTS t_user_device_token (
    user_id             BIGINT          NOT NULL PRIMARY KEY COMMENT '用户ID',
    jpush_token         VARCHAR(256)    NULL     COMMENT 'JPush Registration ID（源：旧库 t_user_extend.ji_guang_token）',
    synced_at           DATETIME        NOT NULL COMMENT '数仓同步批次时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_synced_at (synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户 Push Token 镜像（数仓日同步）';

-- 7.2.6 Email 抑制名单（SendGrid Event Webhook 写入，ExecutionGuard 读取）。
-- 按地址而非按案件：hard bounce 与投诉都是地址级事实，同一地址换个案件再发仍会退信，
-- 并持续损耗发信域信誉。SendGrid 自家 suppression list 会在供应商侧拦掉发信，但本地不知情，
-- 于是每个里程碑照旧建 EMAIL 步骤、调一次 API、拿一个 dropped，step 记成渠道失败——
-- 本地留一份才能在 Guard 就 BLOCK，把「地址已废」表达成合规拦截。
CREATE TABLE IF NOT EXISTS t_email_suppression (
    id                  BIGINT          AUTO_INCREMENT PRIMARY KEY,
    email               VARCHAR(256)    NOT NULL COMMENT '收件地址，写入前统一小写去空白',
    reason              VARCHAR(32)     NOT NULL COMMENT 'HARD_BOUNCE/DROPPED/SPAM_REPORT/UNSUBSCRIBE',
    detail              VARCHAR(512)    NULL     COMMENT '供应商原因原文，截断 512',
    case_id             BIGINT          NULL     COMMENT '首次触发抑制的案件，仅作溯源',
    created_at          DATETIME        NOT NULL COMMENT '抑制发生时间（PHT，应用侧传入）',
    UNIQUE KEY uk_email_suppression_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Email 抑制名单（退信/投诉/退订）';

-- 7.2.2 用户画像扩展表 t_user_profile_ext：Phase 1 不建表，押后 Phase 2
--   原因：Phase 1 无代码消费 / 无 mapper（MockProfileService 仅填 basic + device.jpushToken）。
--   待数仓/号码检测供应商就绪或坐席标记上线再建，届时同步领域模型 §7.2.2。
