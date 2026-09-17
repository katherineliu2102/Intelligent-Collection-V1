#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== Q1: FAILED overlap 0915 vs 1430 ==="
q "SELECT COUNT(DISTINCT p.case_id) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='FAILED'
AND p.case_id IN (
  SELECT p2.case_id FROM t_contact_plan_step s2 JOIN t_contact_plan p2 ON p2.id=s2.plan_id
  WHERE s2.channel_type='AI_CALL' AND s2.original_trigger_time=CONCAT('$D',' 09:15:00') AND s2.result='FAILED'
) AND s.original_trigger_time=CONCAT('$D',' 14:30:00')"

echo "=== Q1b: FAILED only morning ==="
q "SELECT COUNT(*) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.result='FAILED'"

echo "=== Q1c: FAILED only afternoon ==="
q "SELECT COUNT(*) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.result='FAILED'"

echo "=== Q1d: overlap case_ids sample ==="
q "SELECT a.case_id FROM (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.result='FAILED'
) a INNER JOIN (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.result='FAILED'
) b ON a.case_id=b.case_id LIMIT 15"

echo "=== Q2: 0915 vs 1430 step counts by status ==="
q "SELECT DATE_FORMAT(original_trigger_time,'%H:%i') slot, status, result, COUNT(*) n
FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND DATE(original_trigger_time)='$D'
GROUP BY 1,2,3 ORDER BY 1,n DESC"

echo "=== Q2b: 1430 SKIPPED reasons - sample case_ids ==="
q "SELECT p.case_id, JSON_UNQUOTE(JSON_EXTRACT(p.context_snapshot,'$.stage')) st, s.status, s.result
FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.status='SKIPPED'
LIMIT 20"

echo "=== Q2c: morning ANSWERED case 1430 status ==="
q "SELECT p.case_id, s.status, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND s.original_trigger_time=CONCAT('$D',' 14:30:00')
AND p.case_id IN (513849,529588,531516,507347)"

echo "=== Q2d: 0915 dispatched vs 1430 dispatched (non-SKIPPED) ==="
q "SELECT DATE_FORMAT(original_trigger_time,'%H:%i') slot,
  SUM(status!='SKIPPED') dispatched,
  SUM(status='SKIPPED') skipped
FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND DATE(original_trigger_time)='$D'
GROUP BY 1"

echo "=== Q3: collection status breakdown ==="
q "SELECT collection_status, COUNT(*) FROM t_ai_collection GROUP BY 1"

echo "=== Q3b: IN_COLLECTION with active plan SMS step today ==="
q "SELECT COUNT(DISTINCT p.case_id) FROM t_contact_plan p
JOIN t_contact_plan_step s ON s.plan_id=p.id
WHERE s.channel_type='SMS' AND s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.status!='SKIPPED'"

echo "=== Q3c: SMS step breakdown ==="
q "SELECT status, result, COUNT(*) FROM t_contact_plan_step
WHERE channel_type='SMS' AND original_trigger_time=CONCAT('$D',' 08:00:00') GROUP BY 1,2"

echo "=== Q3d: PUSH 1200 breakdown ==="
q "SELECT status, result, COUNT(*) FROM t_contact_plan_step
WHERE channel_type='PUSH' AND original_trigger_time=CONCAT('$D',' 12:00:00') GROUP BY 1,2"

echo "=== Q3e: why 40 SMS skipped ==="
q "SELECT p.case_id, c.collection_status, c.stage, c.dpd FROM t_contact_plan_step s
JOIN t_contact_plan p ON p.id=s.plan_id
JOIN t_ai_collection c ON c.case_id=p.case_id
WHERE s.channel_type='SMS' AND s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.status='SKIPPED'
LIMIT 15"

echo "=== Q3f: inbox 168 vs projection ==="
q "SELECT COUNT(*) total, SUM(collection_status='IN_COLLECTION') in_col, SUM(collection_status='SETTLED') settled, SUM(collection_status='CEASED') ceased FROM t_ai_collection"
