#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== FAILED sample steps ==="
q "SELECT p.case_id, s.id, s.status, s.result, LEFT(IFNULL(s.resolved_params,''),200), s.executed_at
FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.original_trigger_time LIKE '%09:15%'
AND s.result='FAILED' LIMIT 8"

echo "=== FAILED distinct resolved_params keys ==="
q "SELECT LEFT(IFNULL(s.resolved_params,''),120), COUNT(*) n
FROM t_contact_plan_step s
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.original_trigger_time LIKE '%09:15%'
AND s.result='FAILED' GROUP BY 1 ORDER BY n DESC LIMIT 10"

echo "=== callback audit FAILED ==="
q "SELECT case_id, result, disposition, LEFT(canonical_payload,180)
FROM t_channel_callback_audit
WHERE DATE(received_at)='$D' AND result='FAILED' LIMIT 5" 2>/dev/null || echo none

echo "=== timeline AI FAILED ==="
q "SELECT case_id, result, LEFT(content_summary,120), created_at
FROM t_contact_timeline
WHERE channel='AI_CALL' AND DATE(created_at)='$D' AND result='FAILED' LIMIT 8" 2>/dev/null || echo none
