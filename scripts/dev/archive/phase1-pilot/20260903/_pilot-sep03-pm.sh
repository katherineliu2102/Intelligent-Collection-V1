#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-03}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "PHT_DATE=$D"
echo "=== collection_status ==="
q "SELECT collection_status, COUNT(*) n FROM t_ai_collection GROUP BY 1"
for spec in "08:00 SMS" "08:00 PUSH" "09:15 AI_CALL" "12:00 PUSH" "14:00 EMAIL" "14:30 AI_CALL"; do
  set -- $spec
  echo "=== slot_$1_$2 ==="
  q "SELECT status, IFNULL(result,'-'), COUNT(*) n, MIN(executed_at), MAX(executed_at) FROM t_contact_plan_step WHERE original_trigger_time=CONCAT('$D',' $1:00') AND channel_type='$2' GROUP BY 1,2 ORDER BY n DESC"
done
echo "=== timeline_delivered ==="
q "SELECT channel, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND result='DELIVERED' GROUP BY 1"
echo "=== email_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='EMAIL' AND result='DELIVERED' GROUP BY 1"
echo "=== sms_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND result='DELIVERED' GROUP BY 1"
echo "=== push_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='PUSH' AND result='DELIVERED' GROUP BY 1"
echo "=== ai_answered ==="
q "SELECT p.case_id, s.plan_id, s.id, s.executed_at, DATE_FORMAT(s.original_trigger_time,'%H:%i') slot
FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'"
echo "=== ai_executing ==="
q "SELECT COUNT(*) FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND status='EXECUTING' AND DATE(original_trigger_time)='$D'"
echo "=== ai_0915 ==="
q "SELECT status, IFNULL(result,'-'), COUNT(*) n FROM t_contact_plan_step WHERE original_trigger_time=CONCAT('$D',' 09:15:00') AND channel_type='AI_CALL' GROUP BY 1,2 ORDER BY n DESC"
echo "=== ai_1430 ==="
q "SELECT status, IFNULL(result,'-'), COUNT(*) n FROM t_contact_plan_step WHERE original_trigger_time=CONCAT('$D',' 14:30:00') AND channel_type='AI_CALL' GROUP BY 1,2 ORDER BY n DESC"
echo "=== failed_reason ==="
q "SELECT JSON_UNQUOTE(JSON_EXTRACT(canonical_payload,'$.final_failure_reason')) r, COUNT(*) n FROM t_channel_callback_audit WHERE DATE(received_at)='$D' AND result='FAILED' GROUP BY 1"
echo "=== repayment ==="
q "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='REPAYMENT' AND DATE(created_at)='$D'"
echo "=== health ==="
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/actuator/health
echo "=== hikari ==="
docker exec collection-admin printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
echo "=== wave_1430 ==="
docker logs --since 3h collection-admin 2>&1 | grep -E 'mocasa-20260903-1430|started batch|wave mocasa|14:30' | grep -v enrolled | tail -20
echo "=== hikari_pool ==="
docker logs --since 12h collection-admin 2>&1 | grep -E 'Connection is not available|HikariPool-1 - Timeout' | tail -5 || echo none
echo "=== daily_roll ==="
docker logs --since 24h collection-admin 2>&1 | grep 'daily roll completed' | grep '2026-09-03' | tail -3
echo "=== old40 ==="
q "SELECT p.case_id, s.channel_type, s.status, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE p.case_id IN (513749,526109) AND DATE(s.original_trigger_time)='$D'"
echo "=== answered_proj ==="
q "SELECT c.case_id, c.stage, c.dpd, c.overdue_amount FROM t_ai_collection c WHERE c.case_id IN (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED')"
echo "=== firm ==="
q "SELECT script_slot, COUNT(*) FROM t_contact_timeline WHERE DATE(created_at)='$D' AND script_slot LIKE '%FIRM%' GROUP BY 1"
echo "=== noplan ==="
q "SELECT IFNULL(p.cancel_reason,'NULL') r, COUNT(*) n
FROM t_ai_collection c
JOIN t_contact_plan p ON p.case_id=c.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=c.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE c.collection_status='IN_COLLECTION'
AND NOT EXISTS (SELECT 1 FROM t_contact_plan x WHERE x.case_id=c.case_id AND x.status NOT IN ('PLAN_CANCELLED','PLAN_COMPLETED'))
GROUP BY 1"
