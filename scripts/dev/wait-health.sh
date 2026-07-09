#!/usr/bin/env bash
# 等待 collection-admin HTTP 就绪
# 用法：HOST=http://localhost:8888 MAX_WAIT=180 ./scripts/dev/wait-health.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

HOST="${HOST:-http://localhost:8888}"
MAX_WAIT="${MAX_WAIT:-180}"
INTERVAL="${INTERVAL:-3}"
ADMIN_LOG="$ROOT/logs/run/admin.log"
ADMIN_ERR="$ROOT/logs/run/admin.err.log"

elapsed=0
while [ "$elapsed" -lt "$MAX_WAIT" ]; do
  code=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/plans/active/by-case/0" 2>/dev/null || echo "000")
  if [[ "$code" =~ ^[23] ]]; then
    echo "[wait-health] OK $HOST (${elapsed}s)"
    exit 0
  fi
  # 也认 Tomcat 刚起但 DB 还在连 — 看日志关键字
  if [ -f "$ADMIN_LOG" ] && grep -q "Tomcat started on port" "$ADMIN_LOG" 2>/dev/null; then
    sleep 2
    code=$(curl -s -o /dev/null -w "%{http_code}" "$HOST/plans/active/by-case/0" 2>/dev/null || echo "000")
    if [[ "$code" =~ ^[23] ]]; then
      echo "[wait-health] OK $HOST (${elapsed}s, after Tomcat log)"
      exit 0
    fi
  fi
  sleep "$INTERVAL"
  elapsed=$((elapsed + INTERVAL))
  echo "[wait-health] 等待中... ${elapsed}s (last HTTP $code)"
done

echo "[wait-health] 超时 ${MAX_WAIT}s — 查 $ADMIN_LOG / $ADMIN_ERR" >&2
exit 1
