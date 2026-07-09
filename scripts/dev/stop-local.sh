#!/usr/bin/env bash
# 停止本地 collection-admin（默认端口 8888 / APP_PORT）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

PORT="${APP_PORT:-8888}"
if [ -f .env ]; then
  # shellcheck disable=SC1091
  set -a && source .env && set +a
  PORT="${APP_PORT:-8888}"
fi

stopped=0

if command -v lsof >/dev/null 2>&1; then
  pids=$(lsof -ti:"$PORT" 2>/dev/null || true)
  if [ -n "$pids" ]; then
    echo "[stop-local] 终止占用 :$PORT 的进程: $pids"
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 2
    # shellcheck disable=SC2086
    kill -9 $pids 2>/dev/null || true
    stopped=1
  fi
fi

pids_jar=$(pgrep -f 'collection-admin/target/collection-admin.jar' 2>/dev/null || true)
if [ -n "$pids_jar" ]; then
  echo "[stop-local] 终止 collection-admin.jar: $pids_jar"
  # shellcheck disable=SC2086
  kill $pids_jar 2>/dev/null || true
  sleep 1
  stopped=1
fi

if [ "$stopped" -eq 0 ]; then
  echo "[stop-local] 未发现运行中的 collection-admin (port ${PORT})"
else
  echo "[stop-local] 已停止"
fi
