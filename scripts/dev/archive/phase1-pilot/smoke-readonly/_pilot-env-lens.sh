#!/bin/bash
set -euo pipefail
f=/opt/app/pilot.env
echo "scheduler=$(grep -E '^COLLECTION_SCHEDULER_ENABLED=' "$f" | cut -d= -f2-)"
ing=$(grep -E '^COLLECTION_INGESTION_ENABLED=' "$f" | cut -d= -f2- || true)
echo "ingestion=${ing:-<unset>}"
lim=$(grep -E '^CHANNEL_DAILY_LIMIT_AI_CALL=' "$f" | cut -d= -f2- || true)
echo "ai_limit=${lim:-<unset>}"
for k in CHANNEL_FACADE_TEST_CALLEE NOTIFICATION_PUSH_TEST_TOKEN CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT CHANNEL_SENDGRID_TEST_RECIPIENT; do
  line=$(grep -E "^${k}=" "$f" || true)
  if [ -z "$line" ]; then
    echo "$k unset"
    continue
  fi
  v=${line#*=}
  v=${v%\"}
  v=${v#\"}
  if [ -z "$v" ]; then
    echo "$k empty"
  else
    echo "$k set len=${#v}"
  fi
done
ids=$(grep -E '^COLLECTION_PILOT_LOAN_IDS=' "$f" | cut -d= -f2- | tr -d '"' || true)
scan=$(grep -E '^COLLECTION_SCAN_CASE_IDS=' "$f" | cut -d= -f2- | tr -d '"' || true)
echo "pilot_ids_count=$(printf '%s' "$ids" | tr ',;' '\n' | grep -c . || true)"
echo "scan_ids_count=$(printf '%s' "$scan" | tr ',;' '\n' | grep -c . || true)"
echo "mvn=$(mvn -v | head -n 1)"
echo "java=$(java -version 2>&1 | head -n 1)"
