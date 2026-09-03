#!/bin/bash
set -euo pipefail
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }
# load e2e200 ids from a fixed list count
q "SELECT collection_status, COUNT(*) FROM t_ai_collection WHERE case_id BETWEEN 463000 AND 532000 GROUP BY 1"
q "SELECT COUNT(*) FROM t_ai_collection WHERE case_id BETWEEN 463000 AND 532000 AND collection_status='IN_COLLECTION'"
q "SELECT COUNT(DISTINCT case_id) FROM t_ai_collection WHERE collection_status='IN_COLLECTION'"
