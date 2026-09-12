#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== EXECUTING all slots ==="
q "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING' AND DATE(original_trigger_time)='$D'"

echo "=== 1430 answered 520087 ==="
q "SELECT case_id, stage, dpd, overdue_amount FROM t_ai_collection WHERE case_id=520087"
q "SELECT case_id, step_id, result, LEFT(canonical_payload,250) FROM t_channel_callback_audit WHERE case_id=520087 AND DATE(received_at)='$D' AND result='ANSWERED'"

echo "=== email slots ==="
q "SELECT script_slot, COUNT(*) FROM t_contact_timeline WHERE channel='EMAIL' AND DATE(created_at)='$D' AND result='DELIVERED' GROUP BY 1"

echo "=== 1430 FAILED reasons count ==="
q "SELECT COUNT(*) FROM t_channel_callback_audit WHERE DATE(received_at)='$D' AND result='FAILED'"

echo "=== 0915 vs 1430 FAILED ==="
q "SELECT DATE_FORMAT(s.original_trigger_time,'%H:%i') slot, COUNT(*) n FROM t_contact_plan_step s WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='FAILED' GROUP BY 1"

echo "=== health check now ==="
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/actuator/health
