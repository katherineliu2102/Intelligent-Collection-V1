-- 2026-09-21 AI Call 录音夜间进 GCS。热库可重复执行。不要跑 seed DELETE。
DROP PROCEDURE IF EXISTS sp_schema_add_ai_call_recording_ingest;
DELIMITER //
CREATE PROCEDURE sp_schema_add_ai_call_recording_ingest()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_call_media'
          AND COLUMN_NAME = 'recording_ingest'
    ) THEN
        ALTER TABLE t_ai_call_media
            ADD COLUMN recording_ingest VARCHAR(16) NULL
                COMMENT 'PENDING/OK/NO_MEDIA/FAILED；与 fetch_status 独立'
                AFTER recording_object_uri,
            ADD COLUMN recording_ingest_attempts INT NOT NULL DEFAULT 0
                AFTER recording_ingest,
            ADD COLUMN recording_ingest_error VARCHAR(256) NULL
                AFTER recording_ingest_attempts,
            ADD COLUMN recording_ingested_at DATETIME NULL
                AFTER recording_ingest_error;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_call_media'
          AND INDEX_NAME = 'idx_ai_call_media_recording'
    ) THEN
        ALTER TABLE t_ai_call_media
            ADD INDEX idx_ai_call_media_recording
                (recording_ingest, recording_ingest_attempts, id);
    END IF;
    UPDATE t_ai_call_media
        SET recording_ingest = 'PENDING'
        WHERE recording_url IS NOT NULL AND recording_url <> ''
          AND (recording_object_uri IS NULL OR recording_object_uri = '')
          AND (recording_ingest IS NULL OR recording_ingest = '');
    UPDATE t_ai_call_media
        SET recording_ingest = 'NO_MEDIA'
        WHERE (recording_url IS NULL OR recording_url = '')
          AND recording_ingest IS NULL;
END //
DELIMITER ;
CALL sp_schema_add_ai_call_recording_ingest();
DROP PROCEDURE IF EXISTS sp_schema_add_ai_call_recording_ingest;
