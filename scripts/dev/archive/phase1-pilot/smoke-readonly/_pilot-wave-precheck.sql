-- 波次聚合上线前置核对：14:30 那批 AI 步骤的 original_trigger_time 是否齐全且落在同一分钟。
-- 波次键取自 original_trigger_time；若为 NULL 会退化成「按当前分钟聚合」，跨分钟到期就会被拆成两批。
SELECT s.status,
       COUNT(*)                                AS n,
       MIN(s.original_trigger_time)            AS min_ott,
       MAX(s.original_trigger_time)            AS max_ott,
       SUM(s.original_trigger_time IS NULL)    AS null_ott
  FROM t_contact_plan_step s
  JOIN t_contact_plan p ON p.id = s.plan_id
 WHERE s.channel_type = 'AI_CALL'
   AND DATE(s.original_trigger_time) = CURDATE()
 GROUP BY s.status;

SELECT DATE_FORMAT(s.original_trigger_time, '%H:%i') AS slot,
       s.status,
       COUNT(*) AS n
  FROM t_contact_plan_step s
  JOIN t_contact_plan p ON p.id = s.plan_id
 WHERE s.channel_type = 'AI_CALL'
   AND DATE(s.original_trigger_time) = CURDATE()
   AND p.status NOT IN ('PLAN_CANCELLED', 'PLAN_COMPLETED')
 GROUP BY slot, s.status
 ORDER BY slot;
