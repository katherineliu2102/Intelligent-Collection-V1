#!/bin/bash
set -euo pipefail
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }
echo "=== callback_audit ==="
q "DESCRIBE t_channel_callback_audit" | head -20
q "SELECT case_id, step_id, result, disposition, LEFT(canonical_payload,200) FROM t_channel_callback_audit WHERE case_id IN (513849,529588,531516,507347) AND DATE(received_at)='2026-09-02'"
