#!/bin/bash
set -euo pipefail
D="2026-09-02"
ENV="/opt/app/pilot.env"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== morning dispatched, no 1430 step ==="
q "SELECT COUNT(*) FROM (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL' AND s.status='COMPLETED'
) m WHERE case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.channel_type='AI_CALL'
)"

echo "=== those 32 sample: plan status ==="
q "SELECT c.case_id, c.stage, c.dpd, p.id, p.status, m.result am
FROM (
  SELECT p.case_id, s.result, s.plan_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL' AND s.status='COMPLETED'
) m
JOIN t_ai_collection c ON c.case_id=m.case_id
JOIN t_contact_plan p ON p.id=m.plan_id
WHERE m.case_id NOT IN (
  SELECT DISTINCT p2.case_id FROM t_contact_plan_step s JOIN t_contact_plan p2 ON p2.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.channel_type='AI_CALL'
) AND m.result!='ANSWERED' LIMIT 15"

echo "=== PLAN_COMPLETED cases with 0915 today ==="
q "SELECT COUNT(DISTINCT p.case_id) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL' AND p.status='PLAN_COMPLETED'"

echo "=== active plan missing 1430 step ==="
q "SELECT p.case_id, p.status, COUNT(*) steps_1430 FROM t_contact_plan p
LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id AND s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.channel_type='AI_CALL'
WHERE p.status IN ('STEP_SCHEDULED','ACTIVE','STEP_EXECUTING') AND p.case_id IN (
  SELECT p2.case_id FROM t_contact_plan_step s2 JOIN t_contact_plan p2 ON p2.id=s2.plan_id
  WHERE s2.original_trigger_time=CONCAT('$D',' 09:15:00') AND s2.status='COMPLETED'
) GROUP BY 1,2 HAVING steps_1430=0 LIMIT 10"

echo "=== 39 no SMS: breakdown ==="
q "SELECT
  SUM(p.status='PLAN_CANCELLED') cancelled,
  SUM(p.status='PLAN_COMPLETED') completed,
  SUM(p.id IS NULL) no_plan,
  SUM(c.dpd<0) neg_dpd,
  COUNT(*) total
FROM t_ai_collection c
LEFT JOIN t_contact_plan p ON p.case_id=c.case_id AND p.status NOT IN ('PLAN_CANCELLED')
WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p2.case_id FROM t_contact_plan_step s JOIN t_contact_plan p2 ON p2.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED'
)"
