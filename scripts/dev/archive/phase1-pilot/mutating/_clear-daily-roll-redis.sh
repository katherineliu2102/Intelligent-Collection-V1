#!/bin/bash
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
set -euo pipefail
set -a
. /opt/app/pilot.env
set +a
export REDISCLI_AUTH="${COLLECTION_REDIS_PASSWORD:-}"
HOST="${COLLECTION_REDIS_HOST}"
PORT="${COLLECTION_REDIS_PORT:-6379}"
DB="${COLLECTION_REDIS_DB:-0}"
echo "redis ${HOST}:${PORT} db=${DB}"
echo "=== keys before ==="
redis-cli -h "$HOST" -p "$PORT" -n "$DB" --no-auth-warning KEYS 'collection:ingestion:daily-roll:*'
echo "=== values ==="
for k in $(redis-cli -h "$HOST" -p "$PORT" -n "$DB" --no-auth-warning KEYS 'collection:ingestion:daily-roll:*'); do
  echo "$k = $(redis-cli -h "$HOST" -p "$PORT" -n "$DB" --no-auth-warning GET "$k")"
done
echo "=== delete 2026-08-31 completed+cursor ==="
redis-cli -h "$HOST" -p "$PORT" -n "$DB" --no-auth-warning DEL \
  'collection:ingestion:daily-roll:2026-08-31:completed' \
  'collection:ingestion:daily-roll:2026-08-31:cursor'
echo "=== keys after ==="
redis-cli -h "$HOST" -p "$PORT" -n "$DB" --no-auth-warning KEYS 'collection:ingestion:daily-roll:*'
