#!/bin/bash
set -euo pipefail
D=2026-09-03
ENV=/opt/app/pilot.env
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== answered_detail ==="
q "SELECT p.case_id, s.plan_id, s.id, s.executed_at FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.id=11279 OR (s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED')"

echo "=== answered_audit ==="
q "SHOW TABLES LIKE '%ai_call%'"
q "SHOW COLUMNS FROM t_ai_call_session" 2>/dev/null | awk '{print $1}' || true
q "SELECT * FROM t_ai_call_session WHERE case_id=529588 AND DATE(created_at)='$D' LIMIT 3" 2>/dev/null || true
q "SELECT case_id, LEFT(canonical_payload,350) FROM t_channel_callback_audit WHERE case_id=529588 AND DATE(received_at)='$D' AND result='ANSWERED' LIMIT 1"

echo "=== 1430_snr ==="
q "SELECT p.case_id, c.stage, c.dpd FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id JOIN t_ai_collection c ON c.case_id=p.case_id WHERE s.original_trigger_time='2026-09-03 14:30:00' AND s.result='SENT_NO_RESPONSE'"

echo "=== 0915_vs_1430_failed ==="
q "SELECT DATE_FORMAT(original_trigger_time,'%H:%i') slot, COUNT(*) n FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND DATE(original_trigger_time)='$D' AND result='FAILED' GROUP BY 1"

echo "=== push_time ==="
q "SELECT MIN(executed_at), MAX(executed_at) FROM t_contact_plan_step WHERE original_trigger_time='2026-09-03 12:00:00' AND channel_type='PUSH' AND result='DELIVERED'"

echo "=== inbox_case ==="
q "SELECT MIN(created_at), MAX(created_at), COUNT(*) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D'"
