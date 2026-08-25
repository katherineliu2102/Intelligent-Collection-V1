#!/usr/bin/env bash
# 在 Pilot 机（bdp01；地址见 docs/ops/生产访问凭据.local.md，不入库）上拉起应用，消费 Pub/Sub 积压。
#
# 前置：本机需有 deploy/pilot.env（由 .env.pilot 传上来，不入仓）与 GCP 服务账号 json。
# 用法：deploy/pilot-run.sh [镜像 tar 路径]
#
# 与 docker-compose 的区别：Pilot 机按约定用 docker run，不装 compose。
set -euo pipefail

IMAGE="intelligent-collection-admin:pilot"
NAME="collection-admin"
ENV_FILE="${PILOT_ENV_FILE:-/opt/app/pilot.env}"
SECRETS_DIR="${PILOT_SECRETS_DIR:-/opt/app/secrets}"
LOG_DIR="${PILOT_LOG_DIR:-/opt/app/logs}"
PORT="${APP_PORT:-8080}"
# 只绑回环。管理面（/ops、/cases、/compliance、/admin、/config）背后的登录目前是开发态实现，
# 不校验口令、任意用户名即得 SYSTEM_ADMIN；此前绑 0.0.0.0 时该端口在公网可直接访问，
# 等于把债务人 PII 与 DLQ 重放（可驱动真实触达）敞开给互联网（见台账 F8）。
# 运维经 SSH 隧道访问：ssh -L 8080:127.0.0.1:8080 ubuntu@<host>
# 待 AI_CALL 公网回调上线时，用反向代理只放通回调路径，不要把这里改回 0.0.0.0。
BIND_ADDR="${APP_BIND_ADDR:-127.0.0.1}"

die() { echo "ERROR: $*" >&2; exit 1; }

[[ -f "$ENV_FILE" ]] || die "缺少 $ENV_FILE"

# docker --env-file 不做 shell 解析：引号会被当成值的一部分，`COLLECTION_DB_PASSWORD="<含$的口令>"`
# 会连引号一起传进容器，表现为连不上库。这里先用 shell 正确解析一遍，再导出成 docker 认的裸值格式。
DOCKER_ENV_FILE="$(mktemp)"
trap 'rm -f "$DOCKER_ENV_FILE"' EXIT
chmod 600 "$DOCKER_ENV_FILE"
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
while IFS= read -r key; do
  printf '%s=%s\n' "$key" "${!key-}" >> "$DOCKER_ENV_FILE"
done < <(sed -nE 's/^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=.*/\1/p' "$ENV_FILE")

# 应用侧对缺配置的报错离根因很远（少了 JDBC URL 只会报 "Failed to determine suitable jdbc url"，
# 不会告诉你是 env 文件里被 & 截断了），所以在这里按名字点出来。
REQUIRED_KEYS=(
  SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
  COLLECTION_REDIS_HOST COLLECTION_REPAYMENT_URL_TEMPLATE
  COLLECTION_PILOT_LOAN_IDS COLLECTION_SCAN_CASE_IDS
  CHANNEL_CALLBACK_HMAC_SECRET
  # PilotReadinessValidator 要求管理面至少一个可用账号；缺这两项只会得到一句
  # 「管理面无可用账号」的 IllegalStateException，看不出是 env 没配。
  COLLECTION_ADMIN_USER COLLECTION_ADMIN_PASSWORD_HASH
)
# 只接入不触达时（调度关闭）没有任何步骤会执行，Facade 配不配都到不了客户，故不做必填。
if [[ "${COLLECTION_SCHEDULER_ENABLED:-true}" == "true" ]]; then
  # callback-secret 缺失时外呼照打、回调全被判验签失败回 401，结果只能挂到 callbackTimeout。
  # PilotReadinessValidator 同样拒启，在这里先点名以免只看到一句 IllegalStateException。
  REQUIRED_KEYS+=(CHANNEL_FACADE_BASE_URL CHANNEL_FACADE_API_KEY CHANNEL_FACADE_CALLBACK_SECRET)
  # pilot 下 TriggerScanner 不装配（@Profile local/test），步骤执行的唯一入口是调度订阅：
  # 这两项为空时应用侧看不到任何 tick，而 Cloud Scheduler 的 Job 仍显示成功——两侧都「正常」、
  # 链路静默停摆（台账 T3o-3）。application-pilot.yml 给的是空缺省，不点名只会得到一句
  # 离根因很远的报错。
  REQUIRED_KEYS+=(GCP_PUBSUB_PROJECT GCP_SCHEDULER_SUBSCRIPTION)
fi
missing=()
for k in "${REQUIRED_KEYS[@]}"; do
  [[ -n "${!k-}" ]] || missing+=("$k")
done
[[ ${#missing[@]} -eq 0 ]] || die "$ENV_FILE 缺少必填项：${missing[*]}"

# 值含 & 时若在 env 文件里没加引号，source 会在此处截断并把后半段当命令执行。
[[ "$SPRING_DATASOURCE_URL" == *"?"* && "$SPRING_DATASOURCE_URL" != *"&"* ]] \
  && die "SPRING_DATASOURCE_URL 疑似被 & 截断（$ENV_FILE 里该值需用双引号包裹）"

# 无 Pub/Sub 凭证时可显式降级：接入与调度关掉，其余（库/Redis/文案闸门/时区）照常验证。
# 默认仍是硬失败——静默跑一个不消费的实例，在监控上和「没有积压」无法区分。
if [[ ! -f "$SECRETS_DIR/credentials.json" ]]; then
  [[ "${PILOT_NO_PUBSUB:-0}" == "1" ]] \
    || die "缺少 $SECRETS_DIR/credentials.json（Pub/Sub 服务账号）；仅验证非接入链路请显式 PILOT_NO_PUBSUB=1"
  echo "!! PILOT_NO_PUBSUB=1：本次不消费 Pub/Sub，接入与调度均关闭"
  cat >> "$DOCKER_ENV_FILE" <<'EOF'
COLLECTION_INGESTION_ENABLED=false
COLLECTION_SCHEDULER_ENABLED=false
GOOGLE_APPLICATION_CREDENTIALS=
EOF
fi

# 传了 tar 就先导入；否则假定镜像已在本机
if [[ $# -ge 1 ]]; then
  echo "==> 导入镜像 $1"
  docker load -i "$1"
fi
docker image inspect "$IMAGE" >/dev/null 2>&1 || die "本机没有镜像 $IMAGE"

mkdir -p "$LOG_DIR"

# 联调期曾按别的名字起过容器（如真拨演练的 collection-admin-aicall）。这里只按 $NAME 清理，
# 管不到那些；两个实例同时挂在同一个 Redis 消费组和同一条调度订阅上，tick 会被随机分走一半、
# 事件被重复消费，现象很像「调度不稳定」，排查会绕远路（T5-S8 独占性直接失败）。
STRAY="$(docker ps --format '{{.Names}}' \
  | grep -E '^collection-admin' | grep -vx "$NAME" || true)"
if [[ -n "$STRAY" ]]; then
  die "检测到并存的应用容器：$(echo "$STRAY" | tr '\n' ' ')
同一消费组/调度订阅上有第二个消费者会静默分流 tick。确认无用后先 docker rm -f 再重跑本脚本。"
fi

echo "==> 停掉旧容器"
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "==> 启动 $NAME"
docker run -d \
  --name "$NAME" \
  --restart unless-stopped \
  -p "${BIND_ADDR}:${PORT}:8080" \
  --env-file "$DOCKER_ENV_FILE" \
  -e TZ=Asia/Manila \
  -v "$SECRETS_DIR:/opt/app/secrets:ro" \
  -v "$LOG_DIR:/opt/app/logs" \
  "$IMAGE"

echo "==> 等待就绪（Pilot 启动闸门任一不满足都会直接退出）"
for i in $(seq 1 60); do
  if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    echo "!! 容器已退出，启动闸门日志："
    docker logs --tail 60 "$NAME"
    exit 1
  fi
  code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/health" || true)
  if [[ "$code" == "200" ]]; then
    echo "==> 就绪"
    curl -s "http://127.0.0.1:${PORT}/actuator/health"; echo
    echo "==> 容器时区自检（必须是 PHT/+08）"
    docker exec "$NAME" date
    exit 0
  fi
  sleep 5
done

echo "!! 5 分钟未就绪，最近日志："
docker logs --tail 80 "$NAME"
exit 1
