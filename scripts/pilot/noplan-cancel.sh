#!/bin/bash
set -euo pipefail
ENV="/opt/app/pilot.env"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME" <<'EOSQL'
SET @D = '2026-09-02';
DROP TEMPORARY TABLE IF EXISTS _nogap;
CREATE TEMPORARY TABLE _nogap AS
SELECT c.case_id, c.stage, c.dpd, c.overdue_amount
FROM t_ai_collection c
WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT(@D,' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED'
);

SELECT 'CANCEL_REASON', cr, COUNT(*) FROM (
SELECT IFNULL(p.cancel_reason,'null') cr
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id
 AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
) t GROUP BY cr ORDER BY 2 DESC;

SELECT 'CANCEL_DATE', cd, COUNT(*) FROM (
SELECT DATE(p.updated_at) cd
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id
 AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
) t GROUP BY cd ORDER BY cd;

SELECT 'SAMPLE', n.case_id, n.stage, n.dpd, p.cancel_reason, p.status, DATE(p.created_at), DATE(p.updated_at), p.id
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id
 AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
ORDER BY p.updated_at DESC LIMIT 15;

-- any ever had CASE_INGESTED / plan created after cancel?
SELECT 'PLAN_HISTORY', n.case_id, p.id, p.status, p.cancel_reason, DATE(p.created_at)
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id
WHERE n.case_id IN (513749,526109,531682,518041)
ORDER BY n.case_id, p.created_at;

EOSQL
