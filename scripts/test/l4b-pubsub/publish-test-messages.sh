#!/usr/bin/env bash
# L4b：向【独立测试 topic】发布合成的单案 caseEvent / repaymentEvent。
#
# 设计前提（安全）：
#   - 绝不向生产 topic 发消息。护栏拒绝：`intelligent-collection-cases-v1`、`collection-cases`（旧）。
#   - 合成 loan_id 99000000x 非真人；联系方式为测试地址。
#
# 用法：
#   export GCP_PUBSUB_PROJECT=fintech-all
#   export GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1
#   export GOOGLE_APPLICATION_CREDENTIALS=$PWD/credentials.json
#   ./publish-test-messages.sh case                 # 发 caseEvent 6 案（99000000..99000005）
#   ./publish-test-messages.sh repay 99000001       # 发 repayment（默认 99000001）
#   ./publish-test-messages.sh file path/to.json caseEvent   # 发指定文件（attr dataType 由第3参数给）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

PROJECT="${GCP_PUBSUB_PROJECT:-fintech-all}"
TOPIC="${GCP_PUBSUB_TEST_TOPIC:-}"
CRED="${GOOGLE_APPLICATION_CREDENTIALS:-$ROOT/credentials.json}"

[ -n "$TOPIC" ] || { echo "[publish] 缺 GCP_PUBSUB_TEST_TOPIC（测试 topic 短名）" >&2; exit 1; }
[ -f "$CRED" ] || { echo "[publish] 缺凭证文件 $CRED" >&2; exit 1; }

# ── 安全护栏：拒绝向生产 topic 发消息 ──
if [ "$TOPIC" = "intelligent-collection-cases-v1" ] || [ "$TOPIC" = "collection-cases" ]; then
  echo "[publish] ✗ 拒绝：$TOPIC 是生产 topic。请用联调 topic intelligent-collection-cases-test1。" >&2
  exit 2
fi

# ── gcloud 鉴权 ──
# credentials.json 可能是两类：
#   1) service_account（有 client_email）→ 激活该 SA 后以 SA 身份发布；
#   2) authorized_user（gcloud ADC，无 client_email）→ 用 gcloud 当前活跃用户账号发布
#      （本仓当前即此类；应用侧 Subscriber 用 ADC 同凭证订阅，身份一致）。
CRED_TYPE="$(python3 -c "import json;print(json.load(open('$CRED')).get('type',''))" 2>/dev/null || true)"
SA_EMAIL="$(python3 -c "import json;print(json.load(open('$CRED')).get('client_email') or '')" 2>/dev/null || true)"
if [ "$CRED_TYPE" = "service_account" ] && [ -n "$SA_EMAIL" ]; then
  gcloud auth activate-service-account "$SA_EMAIL" --key-file="$CRED" --quiet >/dev/null 2>&1 || true
  GCLOUD=(gcloud --account="$SA_EMAIL" --project="$PROJECT")
  echo "[publish] auth=service_account $SA_EMAIL"
else
  ACTIVE_ACCT="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -1 || true)"
  if [ -z "$ACTIVE_ACCT" ]; then
    echo "[publish] 无 gcloud 活跃账号，请先 gcloud auth login" >&2
    exit 1
  fi
  GCLOUD=(gcloud --project="$PROJECT")
  echo "[publish] auth=authorized_user account=${ACTIVE_ACCT} (须对测试 topic 有 publisher 权限)"
fi

publish_one() {
  local file="$1" dtype="$2" mid="$3"
  local body; body="$(cat "$file")"
  echo "[publish] → topic=$TOPIC dataType=$dtype eventId=$mid file=$file"
  "${GCLOUD[@]}" pubsub topics publish "$TOPIC" \
    --message="$body" \
    --attribute="dataType=${dtype}"
}

MODE="${1:-case}"
case "$MODE" in
  case)
    # 6 案 v3 完整快照；不依赖旧库补字段。
    declare -a LOANS=(99000000 99000001 99000002 99000003 99000004 99000005)
    declare -a NAMES=("Test Case S0" "Test Case S1" "Test Case S2" "Test Case S3" "Test Case S4" "Test Case Ceased")
    tmp="$(mktemp)"
    for i in "${!LOANS[@]}"; do
      lid="${LOANS[$i]}"; nm="${NAMES[$i]}"
      cat > "$tmp" <<JSON
{
  "dataType": "caseEvent",
  "data": {
    "eventId": "l4b-case-${lid}-$(date +%s)",
    "occurredAt": "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')",
    "caseId": ${lid},
    "userId": ${lid},
    "caseVersion": "0000000000000000000000000000000${i}",
    "product": "3",
    "stage": "S1",
    "dpd": 2,
    "collectionStatus": "IN_COLLECTION",
    "overduePrincipal": 3000.00,
    "overdueInterest": 0.00,
    "overdueAmount": 3000.00,
    "overduePenaltyAmount": 0.00,
    "upcomingAmount": 0.00,
    "nextDueDate": 0,
    "borrower": {"name": "${nm}", "phone": "9451374358", "email": "l4b@example.com", "language": "en"},
    "device": {"pushToken": "1a0018970bf0c19de04"}
  }
}
JSON
      publish_one "$tmp" "caseEvent" "l4b-case-${lid}-$(date +%s)"
    done
    rm -f "$tmp"
    ;;
  repay)
    lid="${2:-99000001}"
    tmp="$(mktemp)"
    cat > "$tmp" <<JSON
{
  "dataType": "repaymentEvent",
  "data": {
    "eventId": "l4b-repay-${lid}-$(date +%s)",
    "eventType": "REPAYMENT",
    "occurredAt": "$(date '+%Y-%m-%dT%H:%M:%S+08:00')",
    "caseId": "${lid}",
    "userId": "${lid}",
    "repayTime": "$(date '+%Y-%m-%dT%H:%M:%S+08:00')",
    "paidAmount": 3000.00,
    "isFullCleared": true,
    "stage": "S0",
    "dpd": -1,
    "overdueAmount": 0.00,
    "overduePenaltyAmount": 0.00,
    "upcomingAmount": 3000.00,
    "nextDueDate": "2026-09-11"
  }
}
JSON
    publish_one "$tmp" "repaymentEvent" "l4b-repay-${lid}-$(date +%s)"
    rm -f "$tmp"
    ;;
  file)
    f="${2:?用法: file <path.json> <dataType>}"; dt="${3:?dataType}"
    publish_one "$f" "$dt" "L4B-FILE-$(date +%s)"
    ;;
  *)
    echo "[publish] 未知模式 '$MODE'（case|repay|file）" >&2; exit 1 ;;
esac

echo "[publish] 完成。查看应用日志 [Ingestion] 确认消费与白名单/落库。"
