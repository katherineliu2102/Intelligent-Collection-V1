-- 2026-09-14 AI Call 对话 script 落库。录音只记 URL，不下载。
-- 热库可重复执行。不要跑 seed DELETE。
CREATE TABLE IF NOT EXISTS t_ai_call_media (
    id                      BIGINT          AUTO_INCREMENT PRIMARY KEY,
    session_id              VARCHAR(128)    NOT NULL COMMENT '对齐 t_ai_call_session.session_id',
    script_url              VARCHAR(1024)   NULL,
    recording_url           VARCHAR(1024)   NULL COMMENT '供应商地址；本期不下载',
    recording_status        VARCHAR(32)     NULL,
    recording_object_uri    VARCHAR(512)    NULL COMMENT 'GCS 对象路径预留',
    script_json             MEDIUMTEXT      NULL COMMENT 'script_url 响应原文',
    transcript_text         MEDIUMTEXT      NULL COMMENT '扁平对话文本',
    turn_count              INT             NULL,
    has_borrower_turn       TINYINT(1)      NULL COMMENT '拉到 script 后：role/speaker/from 非 assistant/agent/bot/system/ai/tool/operator 且有文本则为 1；EMPTY/仅助手为 0；未拉取为 NULL',
    fetch_status            VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/OK/EMPTY/NO_MEDIA/FAILED',
    fetch_attempts          INT             NOT NULL DEFAULT 0,
    fetch_error             VARCHAR(256)    NULL,
    fetched_at              DATETIME        NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_call_media_session (session_id),
    INDEX idx_ai_call_media_fetch (fetch_status, fetch_attempts, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Call 对话 script 与录音指针';
