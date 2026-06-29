#!/usr/bin/env bash
# L4b 跑前自动检查 —— 对齐 测试文档 §L4b.1 + 接入 §2.1
#
# 用法：
#   ./scripts/test/l4b-preflight.sh           # 尽力检查，缺项 WARN
#   ./scripts/test/l4b-preflight.sh --strict  # 关键项缺失则 exit 1
#
# 前置：项目根 .env（DB_* / NACOS_* / GCP_*）；collection-admin 已启动（L4b 联调环境）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
STRICT=0
[[ "${1:-}" == "--strict" ]] && STRICT=1

BASE="${L4B_BASE_URL:-http://localhost:8888}"
EXPECTED_SUB="${GCP_PUBSUB_SUBSCRIPTION:-collection-cases-ai-v1-sub}"
NACOS_DATA_ID="${NACOS_DATA_ID:-intelligent-collection-local.yml}"
PASS=0
WARN=0
FAIL=0

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
grn()   { printf '\033[32m%s\033[0m\n' "$*"; }
ylw()   { printf '\033[33m%s\033[0m\n' "$*"; }
hdr()   { echo; printf '=== %s ===\n' "$1"; }

pass() { PASS=$((PASS + 1)); grn "PASS: $1"; }
warn() { WARN=$((WARN + 1)); ylw "WARN: $1"; }
fail() { FAIL=$((FAIL + 1)); red "FAIL: $1"; [[ "$STRICT" -eq 1 ]] && exit 1 || true; }

load_env() {
  if [[ -f "$ROOT/.env" ]]; then
    # shellcheck disable=SC1091
    set -a
    source "$ROOT/.env"
    set +a
    pass ".env 已加载"
  else
    warn "未找到 $ROOT/.env — 部分检查跳过"
  fi
  if [[ -n "${NACOS_SERVER_ADDR:-}" && "$NACOS_SERVER_ADDR" =~ ^https?:// ]]; then
    NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR#http://}"
    NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR#https://}"
    NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR%/nacos}"
    NACOS_SERVER_ADDR="${NACOS_SERVER_ADDR%/}"
  fi
}

check_service() {
  hdr "服务健康"
  if curl -sf --connect-timeout 5 "$BASE/actuator/health" >/dev/null 2>&1; then
    pass "actuator/health 可达 ($BASE)"
  elif curl -sf --connect-timeout 5 "$BASE/plans/active/by-case/0" >/dev/null 2>&1; then
    pass "API 可达 ($BASE)"
  else
    fail "服务不可达: $BASE — 请先启动 collection-admin"
  fi
}

check_gcp_env() {
  hdr "PubSub 环境变量"
  [[ -n "${GCP_PUBSUB_PROJECT:-}" ]] && pass "GCP_PUBSUB_PROJECT 已设" || fail "GCP_PUBSUB_PROJECT 未设"
  if [[ -n "${GCP_PUBSUB_SUBSCRIPTION:-}" ]]; then
    if [[ "$GCP_PUBSUB_SUBSCRIPTION" == "$EXPECTED_SUB" ]]; then
      pass "GCP_PUBSUB_SUBSCRIPTION=$GCP_PUBSUB_SUBSCRIPTION"
    else
      warn "GCP_PUBSUB_SUBSCRIPTION=$GCP_PUBSUB_SUBSCRIPTION（文档定稿名: $EXPECTED_SUB）"
    fi
  else
    warn "GCP_PUBSUB_SUBSCRIPTION 未设（默认定稿名: $EXPECTED_SUB）"
  fi
  if [[ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]]; then
    if [[ -f "$GOOGLE_APPLICATION_CREDENTIALS" ]]; then
      pass "GOOGLE_APPLICATION_CREDENTIALS 文件存在"
    else
      fail "GOOGLE_APPLICATION_CREDENTIALS 指向的文件不存在: $GOOGLE_APPLICATION_CREDENTIALS"
    fi
  else
    fail "GOOGLE_APPLICATION_CREDENTIALS 未设"
  fi
  if command -v gcloud >/dev/null 2>&1 && [[ -n "${GCP_PUBSUB_PROJECT:-}" ]]; then
    sub="${GCP_PUBSUB_SUBSCRIPTION:-$EXPECTED_SUB}"
    if gcloud pubsub subscriptions describe "$sub" --project="$GCP_PUBSUB_PROJECT" >/dev/null 2>&1; then
      pass "GCP 订阅存在: $sub"
    else
      warn "gcloud 无法 describe 订阅 $sub（权限或尚未创建）"
    fi
  else
    warn "跳过 gcloud 订阅 describe（无 gcloud 或未设 project）"
  fi
}

fetch_nacos_yaml() {
  if [[ -z "${NACOS_SERVER_ADDR:-}" || -z "${NACOS_NAMESPACE:-}" ]]; then
    warn "NACOS_SERVER_ADDR / NACOS_NAMESPACE 未设 — 跳过 Nacos 配置检查"
    return 1
  fi
  local url="http://${NACOS_SERVER_ADDR}/nacos/v1/cs/configs"
  local qs="dataId=${NACOS_DATA_ID}&group=${NACOS_GROUP:-DEFAULT_GROUP}&tenant=${NACOS_NAMESPACE}"
  if [[ -n "${NACOS_USERNAME:-}" ]]; then
    qs="${qs}&username=${NACOS_USERNAME}&password=${NACOS_PASSWORD:-}"
  fi
  NACOS_YAML="$(curl -sf --connect-timeout 10 "${url}?${qs}" 2>/dev/null || true)"
  if [[ -n "$NACOS_YAML" ]]; then
    pass "Nacos 配置已拉取 ($NACOS_DATA_ID)"
    return 0
  fi
  warn "无法拉取 Nacos 配置 ($NACOS_DATA_ID)"
  return 1
}

check_nacos_ingestion() {
  hdr "接入 / 渠道沙箱（Nacos）"
  fetch_nacos_yaml || return 0
  if echo "$NACOS_YAML" | grep -qE 'collection:[[:space:]]*$|ingestion:'; then
    if echo "$NACOS_YAML" | grep -qE 'enabled:[[:space:]]*true'; then
      pass "collection.ingestion.enabled=true（或 YAML 含 enabled: true）"
    else
      if [[ "$STRICT" -eq 1 ]]; then
        fail "未在 Nacos 中发现 collection.ingestion.enabled=true（L4b 必须 true）"
      else
        warn "未确认 collection.ingestion.enabled=true — L4b 须手动核对"
      fi
    fi
  else
    warn "Nacos YAML 未含 collection.ingestion 段 — 可能在 application-local.yml"
  fi
  if echo "$NACOS_YAML" | grep -q 'push-test-token'; then
    pass "channel.notification.push-test-token 已配置"
  else
    warn "未在 Nacos 发现 push-test-token — Push 可能触达真实用户"
  fi
  if echo "$NACOS_YAML" | grep -qE 'sms-test-mode:[[:space:]]*true'; then
    pass "channel.notification.sms-test-mode=true"
  else
    warn "未确认 sms-test-mode=true"
  fi
  if echo "$NACOS_YAML" | grep -q 'loan-id-whitelist'; then
    pass "collection.ingestion.loan-id-whitelist 已配置（可选 blast radius）"
  else
    ylw "INFO: loan-id-whitelist 未配置（非必须；建议 L4b 配置白名单）"
  fi
}

check_db() {
  hdr "新库 DDL"
  if [[ -z "${DB_HOST:-}" || -z "${DB_USER:-}" ]]; then
    warn "DB_HOST / DB_USER 未设 — 跳过 MySQL 检查"
    return 0
  fi
  export DB_PASS="${DB_PASS:-${DB_PASSWORD:-}}"
  if [[ -z "${DB_NAME:-}" ]]; then
    warn "DB_NAME 未设 — 跳过 MySQL 检查"
    return 0
  fi
  if python3 "$ROOT/scripts/dev/db-ping.py" 2>/dev/null | grep -q CONNECT_OK; then
    pass "MySQL 连通 ($DB_NAME)"
  else
    fail "MySQL 连接失败 — 运行 scripts/dev/db-ping.py 排查"
    return 0
  fi
  local tables=(t_contact_plan t_contact_plan_step t_contact_timeline t_user_device_token t_decision_log)
  for t in "${tables[@]}"; do
    if mysql -h"$DB_HOST" -P"${DB_PORT:-3306}" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" \
      -N -e "SHOW TABLES LIKE '$t'" 2>/dev/null | grep -q "$t"; then
      pass "表存在: $t"
    else
      fail "表缺失: $t — 请先执行 db/schema.sql"
    fi
  done
}

check_manual_reminders() {
  hdr "须人工确认（preflight 无法自动验）"
  ylw "  • L4a 8 条已通过（见 logs/run/l4a.last.log）"
  ylw "  • 运维已建 PubSub 订阅 collection-cases-ai-v1-sub（挂 topic collection-cases）"
  ylw "  • loan_id 白名单清单（不入仓）"
  ylw "  • XXL-Job dailyRoll 已注册（B2）"
  ylw "  • /actuator/beans 核对 A1–A6 薄/全"
  ylw "  • SendGrid 测试收件人 / 内部邮箱"
}

main() {
  echo "L4b preflight — $ROOT"
  load_env
  check_service
  check_gcp_env
  check_nacos_ingestion
  check_db
  check_manual_reminders
  echo
  hdr "汇总"
  echo "PASS=$PASS WARN=$WARN FAIL=$FAIL (strict=$STRICT)"
  if [[ "$FAIL" -gt 0 && "$STRICT" -eq 1 ]]; then
    exit 1
  fi
  if [[ "$FAIL" -gt 0 ]]; then
    ylw "存在 FAIL 项；加 --strict 可在首 FAIL 时立即退出"
    exit 1
  fi
  grn "Preflight 完成 — 可继续 §L4b 用例（仍须完成人工项）"
}

main
