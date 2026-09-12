#!/bin/bash
set -euo pipefail
D="2026-09-02"
ENV="/opt/app/pilot.env"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME" <<'EOSQL'
SET @D = '2026-09-02';

-- cohort: IN_COLLECTION, no SMS delivered today
DROP TEMPORARY TABLE IF EXISTS _nogap;
CREATE TEMPORARY TABLE _nogap AS
SELECT c.case_id, c.stage, c.dpd, c.collection_status, c.overdue_amount, c.updated_at
FROM t_ai_collection c
WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s
  JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT(@D,' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED'
);

SELECT 'COUNT', COUNT(*) FROM _nogap;

SELECT 'STATUS_DIST', st, n FROM (
  SELECT (SELECT p.status FROM t_contact_plan p WHERE p.case_id=n.case_id ORDER BY p.created_at DESC LIMIT 1) st, COUNT(*) n
  FROM _nogap n GROUP BY 1
) x ORDER BY n DESC;

SELECT 'DETAIL', n.case_id, IFNULL(n.stage,'null'), n.dpd, n.overdue_amount,
  IFNULL((SELECT p.status FROM t_contact_plan p WHERE p.case_id=n.case_id ORDER BY p.created_at DESC LIMIT 1),'NO_PLAN') last_st,
  IFNULL((SELECT DATE(p.created_at) FROM t_contact_plan p WHERE p.case_id=n.case_id ORDER BY p.created_at DESC LIMIT 1),'') last_dt,
  (SELECT COUNT(*) FROM t_contact_plan p WHERE p.case_id=n.case_id AND p.status IN ('ACTIVE','STEP_SCHEDULED','STEP_EXECUTING','STEP_WAITING')) act
FROM _nogap n ORDER BY last_st, n.dpd DESC, n.case_id;

SELECT 'NO_PLAN_AT_ALL', n.case_id, IFNULL(n.stage,'null'), n.dpd FROM _nogap n
WHERE NOT EXISTS (SELECT 1 FROM t_contact_plan p WHERE p.case_id=n.case_id);

SELECT 'NEG_DPD', n.case_id, IFNULL(n.stage,'null'), n.dpd,
  (SELECT p.status FROM t_contact_plan p WHERE p.case_id=n.case_id ORDER BY p.created_at DESC LIMIT 1) st
FROM _nogap n WHERE n.dpd<=0 OR n.stage IS NULL ORDER BY n.dpd;

SELECT 'COMPLETED_NOT_REBUILT', n.case_id, n.stage, n.dpd,
  p.id, p.status, DATE(p.created_at), DATE(p.updated_at)
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id AND p.status='PLAN_COMPLETED'
AND p.id = (SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
ORDER BY n.case_id;

EOSQL
