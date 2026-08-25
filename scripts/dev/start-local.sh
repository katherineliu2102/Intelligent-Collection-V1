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

# JDBC 来源：显式覆盖优先，否则从 Nacos 拉（ConfigData 未生效时的 CLI 回退，同 start-local.ps1）
DB_ARGS=()
db_url=""; db_user=""; db_pass=""
if [ -n "${COLLECTION_DB_URL:-}" ]; then
  # 共享库 ai_collection_db 上有外来实例无过滤地抢走全库到期步骤（2026-08-21 实测：步骤建好 7 秒即被抢走
  # 并扔在地上，我方一次都没抢到过），在其上 L4a 不可能跑通；账号又无 CREATE DATABASE 权限。
  # 故留一个显式覆盖，把联调指向本机 MySQL。设了它就完全不碰 Nacos 的 datasource。
  db_url="$COLLECTION_DB_URL"
  db_user="${COLLECTION_DB_USER:-}"
  db_pass="${COLLECTION_DB_PASS:-}"
  echo "[start-local] JDBC from COLLECTION_DB_URL（覆盖 Nacos）: ${db_url%%\?*}"
elif command -v curl >/dev/null 2>&1 && [ -n "${NACOS_SERVER_ADDR:-}" ]; then
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
  else
    echo "[start-local] WARN: Nacos JDBC 拉取失败，依赖 ConfigData 自动注入" >&2
  fi
fi

if [ -n "$db_url" ]; then
  # MySQL 服务端 time_zone=SYSTEM(UTC)，NOW() 因此写出 UTC，而引擎按 Asia/Manila 比较时间。
  # ⚠ 必须用数值偏移：远端实例未加载时区表，下发命名时区会以
  #   "Unknown or incorrect time zone: 'Asia/Shanghai'" 让每条连接都建不起来。
  #   Manila 全年 +08:00 且无夏令时，数值偏移与命名时区等价。
  # ⚠ 本项只是缓解、不是修复：加上之后绝大多数 NOW() 列已落 Manila，但 2026-08-21 实测
  #   step 1173/1174 的 executed_at 仍落 UTC（同一行 dispatched_at/updated_at 却是 Manila）。
  #   彻底修复已改为应用侧传参（见 HANDOFF「时区口径」），本项只服务于仍用 NOW() 的审计表。
  if [[ "$db_url" != *forceConnectionTimeZoneToSession* ]]; then
    if [[ "$db_url" == *serverTimezone=* ]]; then
      db_url=$(printf '%s' "$db_url" | sed -E 's#serverTimezone=[^&]*#connectionTimeZone=%2B08:00#')
    elif [[ "$db_url" == *connectionTimeZone=* ]]; then
      db_url=$(printf '%s' "$db_url" | sed -E 's#connectionTimeZone=[^&]*#connectionTimeZone=%2B08:00#')
    else
      case "$db_url" in
        *\?*) db_url="${db_url}&connectionTimeZone=%2B08:00" ;;
        *)    db_url="${db_url}?connectionTimeZone=%2B08:00" ;;
      esac
    fi
    db_url="${db_url}&forceConnectionTimeZoneToSession=true"
    echo "[start-local] 会话时区对齐 +08:00（connectionTimeZone + forceConnectionTimeZoneToSession）"
  fi
  DB_ARGS=(
    "--spring.datasource.url=$db_url"
    "--spring.datasource.username=$db_user"
    "--spring.datasource.password=$db_pass"
  )
fi

PORT="${APP_PORT:-8888}"
RUN_LOG_DIR="$ROOT/logs/run"
mkdir -p "$RUN_LOG_DIR"
LOG="$RUN_LOG_DIR/admin.log"
ERR="$RUN_LOG_DIR/admin.err.log"

# 从副本启动，不直接跑 target 下的 jar。Spring Boot fat jar 的类是**按需**从 jar 里读的，
# 联调期间任何一次 mvn package 都会把正在被读的 jar 覆盖掉，此后所有尚未加载的类都报
# NoClassDefFoundError——2026-08-21 实测：一轮 L4b 跑到一半被重新打包，L4b-1/2 无计划、
# 注入端点 500，失败长得像产品缺陷，实际是构建把运行中的 jar 抽走了。
RUN_JAR="$RUN_LOG_DIR/collection-admin.running.jar"
cp "$JAR" "$RUN_JAR"
CMD=(java -jar "$RUN_JAR")
if [ "${#DB_ARGS[@]}" -gt 0 ]; then
  CMD+=("${DB_ARGS[@]}")
fi

echo "[start-local] http://localhost:${PORT}  profile=${SPRING_PROFILES_ACTIVE}"

# 启动前转存上一轮日志，不再覆盖：L4a/L4b 的取证（时区、零真实出站、poison 告警）事后才回读 admin.log，
# 一旦重启就把上一轮证据冲掉，失败现场无法复原。只留最近 10 份，避免联调把磁盘吃满。
rotate_log() {
  local f="$1"
  [ -s "$f" ] || return 0
  mv "$f" "${f%.log}.$(date +%Y%m%d-%H%M%S).log"
  ls -1t "${f%.log}."*.log 2>/dev/null | tail -n +11 | while read -r old; do rm -f "$old"; done
}
rotate_log "$LOG"
rotate_log "$ERR"

if [ "$DETACH" -eq 1 ]; then
  nohup "${CMD[@]}" >"$LOG" 2>"$ERR" &
  pid=$!
  echo "$pid" >"$RUN_LOG_DIR/admin.pid"
  echo "[start-local] 后台 PID=$pid  日志: $LOG"
else
  # 前台运行也落盘。终端文件是 1MB 环形缓冲，长跑一轮 L4b 足以把前半段冲掉，
  # 而 poison / 回落 Mock 的告警行恰好都在前半段。process substitution 保留 exec 语义。
  echo "[start-local] 前台运行，日志同时写入: $LOG"
  exec > >(tee -a "$LOG") 2> >(tee -a "$ERR" >&2)
  exec "${CMD[@]}"
fi
