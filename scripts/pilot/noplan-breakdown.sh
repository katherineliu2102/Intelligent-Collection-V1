#!/bin/bash
set -euo pipefail
ENV="/opt/app/pilot.env"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME" <<'EOSQL'
SET @D='2026-09-02';
DROP TEMPORARY TABLE IF EXISTS _nogap;
CREATE TEMPORARY TABLE _nogap AS
SELECT c.case_id, c.stage, c.dpd, c.overdue_amount
FROM t_ai_collection c WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT(@D,' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED');

SELECT 'MANUAL_with_overdue', COUNT(*) FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE p.cancel_reason='MANUAL_CLEANUP' AND n.overdue_amount>0 AND n.dpd>0;

SELECT 'MANUAL_should_collect', n.case_id, n.stage, n.dpd, n.overdue_amount
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE p.cancel_reason='MANUAL_CLEANUP' AND n.overdue_amount>0 AND n.dpd>0
ORDER BY n.dpd DESC LIMIT 20;

SELECT 'NO_DUE_all', n.case_id, n.stage, n.dpd, n.overdue_amount, p.cancel_reason
FROM _nogap n
JOIN t_contact_plan p ON p.case_id=n.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE p.cancel_reason='NO_DUE_BALANCE';

SELECT 'in_e2e200_missing', COUNT(*) FROM _nogap n
WHERE n.case_id IN (507188,507250,504801,506532);

EOSQL
