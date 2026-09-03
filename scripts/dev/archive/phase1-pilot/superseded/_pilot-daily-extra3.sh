#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-02}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }
echo "=== old40_steps_today ==="
q "SELECT p.case_id, s.channel_type, s.status, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE p.case_id IN (513749,526109) AND DATE(s.original_trigger_time)='$D'"
echo "=== S0_yesterday_settled ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id IN (507209,507188,507250,507265,507272,507296,507323,507379,531817)"
echo "=== callback_summary ==="
q "SELECT p.case_id, LEFT(s.provider_callback,200) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.id IN (10809,11221,13079,13644)" 2>/dev/null || echo no provider_callback on step
echo "=== outbox_callback ==="
q "SHOW TABLES LIKE '%callback%'"
q "SHOW TABLES LIKE '%ai%'" | head -10
