#!/usr/bin/env bash
# 本地启动 collection-admin（macOS / Linux / Git Bash）
# 用法：
#   ./scripts/dev/start-local.sh              # 前台启动（日志打终端）
#   ./scripts/dev/start-local.sh --detach     # 后台启动，日志写入 logs/run/admin.log
#   ./scripts/dev/start-local.sh --no-build   # 跳过 mvn（jar 已存在时）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

DETACH=0
DO_BUILD=1
for arg in "$@"; do
  case "$arg" in
    --detach|-d) DETACH=1 ;;
    --no-build) DO_BUILD=0 ;;
  esac
done

if [ ! -f .env ]; then
  echo "[start-local] 缺少 .env — 请 cp .env.example .env 并填写 NACOS_*" >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a && source .env && set +a

# Nacos server-addr 必须是 host:port
if [[ "${NACOS_SERVER_ADDR:-}" =~ ^https?:// ]]; then
  NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR#http://}"
  NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR#https://}"
  NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR%/nacos}"
  export NACOS_SERVER_ADDR
  echo "[start-local] 已修正 NACOS_SERVER_ADDR -> $NACOS_SERVER_ADDR"
fi

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

JAR="$ROOT/collection-admin/target/collection-admin.jar"
if [ "$DO_BUILD" -eq 1 ] || [ ! -f "$JAR" ]; then
  echo "[start-local] 编译 collection-admin..."
  mvn -pl collection-admin -am clean package -DskipTests -q
fi

# 从 Nacos 拉 JDBC（ConfigData 未生效时的 CLI 回退，同 start-local.ps1）
DB_ARGS=()
if command -v curl >/dev/null 2>&1 && [ -n "${NACOS_SERVER_ADDR:-}" ]; then
  yaml=$(curl -sf -G "http://${NACOS_SERVER_ADDR}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=intelligent-collection-local.yml" \
    --data-urlencode "group=${NACOS_GROUP}" \
    --data-urlencode "tenant=${NACOS_NAMESPACE}" \
    --data-urlencode "username=${NACOS_USERNAME}" \
    --data-urlencode "password=${NACOS_PASSWORD}" 2>/dev/null || true)
  if [ -n "$yaml" ]; then
    db_url=$(echo "$yaml" | grep -E 'url:[[:space:]]*jdbc:' | head -1 | sed -E 's/^[[:space:]]*url:[[:space:]]*//')
    db_user=$(echo "$yaml" | grep -E '^[[:space:]]*username:' | head -1 | sed -E 's/^[[:space:]]*username:[[:space:]]*//')
    db_pass=$(echo "$yaml" | grep -E '^[[:space:]]*password:' | head -1 | sed -E 's/^[[:space:]]*password:[[:space:]]*//')
    if [ -n "$db_url" ] && [ -n "$db_user" ] && [ -n "$db_pass" ]; then
      DB_ARGS=(
        "--spring.datasource.url=$db_url"
        "--spring.datasource.username=$db_user"
        "--spring.datasource.password=$db_pass"
      )
      echo "[start-local] JDBC from Nacos"
    fi
  else
    echo "[start-local] WARN: Nacos JDBC 拉取失败，依赖 ConfigData 自动注入" >&2
  fi
fi

PORT="${APP_PORT:-8888}"
RUN_LOG_DIR="$ROOT/logs/run"
mkdir -p "$RUN_LOG_DIR"
LOG="$RUN_LOG_DIR/admin.log"
ERR="$RUN_LOG_DIR/admin.err.log"

CMD=(java -jar "$JAR")
if [ "${#DB_ARGS[@]}" -gt 0 ]; then
  CMD+=("${DB_ARGS[@]}")
fi

echo "[start-local] http://localhost:${PORT}  profile=${SPRING_PROFILES_ACTIVE}"

if [ "$DETACH" -eq 1 ]; then
  nohup "${CMD[@]}" >"$LOG" 2>"$ERR" &
  pid=$!
  echo "$pid" >"$RUN_LOG_DIR/admin.pid"
  echo "[start-local] 后台 PID=$pid  日志: $LOG"
else
  exec "${CMD[@]}"
fi
