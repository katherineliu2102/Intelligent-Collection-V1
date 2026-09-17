#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-03}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a
# shellcheck disable=SC1090
source "$ENV"
set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")

q() { "${MY[@]}" -e "$1"; }

echo "PHT_DATE=$D"
echo "=== collection_total ==="
q "SELECT COUNT(*) FROM t_ai_collection"
echo "=== collection_status ==="
q "SELECT collection_status, COUNT(*) n FROM t_ai_collection GROUP BY 1"
echo "=== inbox_caseEvent ==="
q "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D'"
echo "=== inbox_time ==="
q "SELECT MIN(created_at), MAX(created_at), COUNT(*) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D'"
echo "=== projection ==="
q "SELECT IFNULL(stage,'null'), dpd, collection_status, COUNT(*) n FROM t_ai_collection GROUP BY 1,2,3 ORDER BY n DESC LIMIT 25"
echo "=== S0_cases ==="
q "SELECT case_id, dpd, collection_status FROM t_ai_collection WHERE stage='S0' ORDER BY case_id"
for spec in "08:00 SMS" "08:00 PUSH" "09:15 AI_CALL" "12:00 PUSH" "14:00 EMAIL" "14:30 AI_CALL"; do
  set -- $spec
  echo "=== slot_$1_$2 ==="
  q "SELECT status, IFNULL(result,'-'), COUNT(*) n, MIN(executed_at), MAX(executed_at) FROM t_contact_plan_step WHERE original_trigger_time=CONCAT('$D',' $1:00') AND channel_type='$2' GROUP BY 1,2 ORDER BY n DESC"
done
echo "=== timeline_delivered ==="
q "SELECT channel, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND status='DELIVERED' GROUP BY 1" || true
echo "=== email_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='EMAIL' AND status='DELIVERED' GROUP BY 1" || true
echo "=== sms_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND status='DELIVERED' GROUP BY 1"
echo "=== ai_answered ==="
q "SELECT p.case_id, s.plan_id, s.id, s.executed_at, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'"
echo "=== ai_executing ==="
q "SELECT COUNT(*) FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND status='EXECUTING' AND DATE(original_trigger_time)='$D'"
echo "=== new_plans_today ==="
q "SELECT COUNT(*) FROM t_contact_plan WHERE DATE(created_at)='$D'"
echo "=== new_plans_sample ==="
q "SELECT case_id, JSON_UNQUOTE(JSON_EXTRACT(context_snapshot,'$.stage')), status, created_at FROM t_contact_plan WHERE DATE(created_at)='$D' ORDER BY created_at LIMIT 20"
echo "=== repayment_inbox ==="
q "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='REPAYMENT' AND DATE(created_at)='$D'"
echo "=== S1_dpd1 ==="
q "SELECT case_id, dpd FROM t_ai_collection WHERE stage='S1' AND dpd=1 ORDER BY case_id"
echo "=== skip_513749_526109 ==="
q "SELECT case_id, collection_status, stage, dpd FROM t_ai_collection WHERE case_id IN (513749,526109)"
echo "=== old40_steps_today ==="
q "SELECT p.case_id, s.channel_type, s.status, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE p.case_id IN (513749,526109) AND DATE(s.original_trigger_time)='$D'"
echo "=== health ==="
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/actuator/health || echo fail
