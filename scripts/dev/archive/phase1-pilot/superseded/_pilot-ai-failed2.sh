#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== AI result breakdown 0915 ==="
q "SELECT result, COUNT(*) n FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND original_trigger_time=CONCAT('$D',' 09:15:00') GROUP BY 1 ORDER BY n DESC"

echo "=== callback audit result breakdown ==="
q "SELECT result, COUNT(*) n FROM t_channel_callback_audit WHERE DATE(received_at)='$D' GROUP BY 1 ORDER BY n DESC"

echo "=== FAILED attempt_count ==="
q "SELECT JSON_EXTRACT(canonical_payload,'$.attempt_count') ac, COUNT(*) n
FROM t_channel_callback_audit
WHERE DATE(received_at)='$D' AND result='FAILED'
GROUP BY 1 ORDER BY n DESC"

echo "=== enroll vs failed - sample enrolled case ==="
q "SELECT p.case_id, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.id=3940"
