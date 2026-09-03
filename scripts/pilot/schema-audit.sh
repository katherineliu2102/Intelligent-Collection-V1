#!/bin/bash
set -euo pipefail
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }
echo "=== DESCRIBE step ==="; q "DESCRIBE t_contact_plan_step" | head -25
echo "=== DESCRIBE timeline ==="; q "DESCRIBE t_contact_timeline" | head -25
echo "=== DESCRIBE audit ==="; q "DESCRIBE t_ai_call_audit" 2>/dev/null | head -20 || echo no audit table
