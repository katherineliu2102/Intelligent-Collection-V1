#!/usr/bin/env bash
# 催收库查询helper。连接参数取自 .env.pilot（gitignore，真值不入仓）。
# 用法：scripts/db/psql.sh "SELECT 1"        —— 表格输出
#      scripts/db/psql.sh -B "SELECT 1"     —— tab 分隔，便于脚本消费
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${COLLECTION_ENV_FILE:-$ROOT/.env.pilot}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "missing $ENV_FILE" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

exec mysql \
  -h"$COLLECTION_DB_HOST" \
  -P"${COLLECTION_DB_PORT:-3306}" \
  -u"$COLLECTION_DB_USERNAME" \
  -p"$COLLECTION_DB_PASSWORD" \
  -D"$COLLECTION_DB_NAME" \
  --connect-timeout=10 \
  "$@"
