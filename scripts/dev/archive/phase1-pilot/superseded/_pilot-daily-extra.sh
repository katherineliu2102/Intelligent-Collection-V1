#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== ai_answered ==="
q "SELECT p.case_id, s.plan_id, s.id step_id, s.executed_at, s.result
FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'"

echo "=== ai_resolved ==="
q "SELECT p.case_id, JSON_UNQUOTE(JSON_EXTRACT(s.resolved_params,'$.result_label')) lbl,
LEFT(JSON_UNQUOTE(JSON_EXTRACT(s.resolved_params,'$.summary')),120) sm
FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'"

echo "=== timeline_delivered ==="
q "SELECT channel, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND result='DELIVERED' GROUP BY 1"

echo "=== sms_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND result='DELIVERED' GROUP BY 1 LIMIT 5"

echo "=== S1_dpd1 ==="
q "SELECT case_id, dpd FROM t_ai_collection WHERE stage='S1' AND dpd=1 ORDER BY case_id"

echo "=== stage_roll_plans ==="
q "SELECT p.case_id, JSON_UNQUOTE(JSON_EXTRACT(p.context_snapshot,'$.stage')) st, p.status, p.created_at
FROM t_contact_plan p WHERE DATE(p.created_at)='$D' ORDER BY p.created_at LIMIT 12"

echo "=== skip_cases ==="
q "SELECT case_id, collection_status FROM t_ai_collection WHERE case_id IN (513749,526109)"

echo "=== 200ring ==="
q "SELECT COUNT(*) FROM t_ai_collection WHERE case_id BETWEEN 463000 AND 532000 AND collection_status='IN_COLLECTION'"
