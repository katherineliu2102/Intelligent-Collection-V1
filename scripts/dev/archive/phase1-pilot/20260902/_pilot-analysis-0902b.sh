#!/bin/bash
set -euo pipefail
D="2026-09-02"
ENV="/opt/app/pilot.env"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== cases 0915 ok but 1430 skipped ==="
q "SELECT m.case_id, m.result am_result, a.status pm_status, a.result pm_result, c.stage, c.dpd
FROM (
  SELECT p.case_id, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL' AND s.status='COMPLETED'
) m
JOIN (
  SELECT p.case_id, s.status, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.channel_type='AI_CALL'
) a ON m.case_id=a.case_id
JOIN t_ai_collection c ON c.case_id=m.case_id
WHERE a.status='SKIPPED'
ORDER BY m.result LIMIT 40"

echo "=== count by am_result for 1430 skipped ==="
q "SELECT m.result, COUNT(*) n FROM (
  SELECT p.case_id, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL' AND s.status='COMPLETED'
) m JOIN (
  SELECT p.case_id, s.status FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.channel_type='AI_CALL' AND s.status='SKIPPED'
) a ON m.case_id=a.case_id GROUP BY 1"

echo "=== cases with 0915 step but NO 1430 step at all ==="
q "SELECT COUNT(*) FROM (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL'
) x WHERE case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.channel_type='AI_CALL'
)"

echo "=== 1430 skipped but 0915 also skipped ==="
q "SELECT COUNT(*) FROM (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.status='SKIPPED'
) a INNER JOIN (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 14:30:00') AND s.status='SKIPPED'
) b ON a.case_id=b.case_id"

echo "=== 40 SMS skip: plan status ==="
q "SELECT p.status, COUNT(*) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='SMS' AND s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.status='SKIPPED'
GROUP BY 1"

echo "=== 40 SMS skip: distinct case plan combo ==="
q "SELECT COUNT(DISTINCT p.case_id), COUNT(DISTINCT p.id) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='SMS' AND s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.status='SKIPPED'"

echo "=== IN_COLLECTION no SMS today ==="
q "SELECT COUNT(*) FROM t_ai_collection c WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED'
)"

echo "=== those without SMS why - sample ==="
q "SELECT c.case_id, c.stage, c.dpd, p.status plan_st, p.id
FROM t_ai_collection c
LEFT JOIN t_contact_plan p ON p.case_id=c.case_id AND p.status IN ('ACTIVE','STEP_SCHEDULED','STEP_EXECUTING','STEP_WAITING','PLAN_COMPLETED')
WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p2.case_id FROM t_contact_plan_step s JOIN t_contact_plan p2 ON p2.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED'
) LIMIT 20"
