#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== answered_cases_projection ==="
q "SELECT case_id, stage, dpd, overdue_amount, collection_status FROM t_ai_collection WHERE case_id IN (513849,529588,531516,507347)"

echo "=== answered_step_params ==="
q "SELECT p.case_id, LEFT(s.resolved_params,300) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.id IN (10809,11221,13079,13644)"

echo "=== 507209 ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id=507209"
q "SELECT id, status, created_at FROM t_contact_plan WHERE case_id=507209 ORDER BY created_at DESC LIMIT 3"

echo "=== old40 plans ==="
q "SELECT case_id, id, status FROM t_contact_plan WHERE case_id IN (513749,526109) ORDER BY case_id, created_at DESC LIMIT 4"

echo "=== inbox_time ==="
q "SELECT MIN(created_at), MAX(created_at), COUNT(*) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D'"

echo "=== repayment ==="
q "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='REPAYMENT' AND DATE(created_at)='$D'"

echo "=== wave_log ==="
q "SELECT 1" 
# use docker below
