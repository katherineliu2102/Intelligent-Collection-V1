#!/usr/bin/env bash
# L4b 方案 B：向【独立测试 topic】发布合成 case_push / repayment 消息。
#
# 设计前提（安全）：
#   - 绝不向生产共享 topic `collection-cases` 发消息（旧系统同 topic 会消费 → 污染生产）。
#     本脚本内置护栏：TOPIC=collection-cases 时直接拒绝。
#   - 合成 loan_id 99000000x 非真人；联系方式为测试地址（phone/email/jpushToken）。
#   - 上线无需回退代码：生产只是把 Nacos `collection.ingestion.subscription` 指回真实订阅。
#
# 前置：
#   1) 运维已建测试 topic + 订阅（见文末「运维一次性基建」）。
#   2) 应用侧 Nacos 把 collection.ingestion.subscription 指向测试订阅、project 指向测试 topic 所在项目。
#   3) 本机 credentials.json（服务账号，对测试 topic 有 publisher 权限）。
#
# 用法：
#   export GCP_PUBSUB_PROJECT=fintech-all
#   export GCP_PUBSUB_TEST_TOPIC=collection-cases-test1
#   export GOOGLE_APPLICATION_CREDENTIALS=$PWD/credentials.json
#   ./publish-test-messages.sh case                 # 发 case_push 6 案（99000000..99000005）
#   ./publish-test-messages.sh repay 99000001       # 发 repayment（默认 99000001）
#   ./publish-test-messages.sh file path/to.json case_push   # 发指定文件（attr dataType 由第3参数给）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$ROOT"

PROJECT="${GCP_PUBSUB_PROJECT:-fintech-all}"
TOPIC="${GCP_PUBSUB_TEST_TOPIC:-}"
CRED="${GOOGLE_APPLICATION_CREDENTIALS:-$ROOT/credentials.json}"

[ -n "$TOPIC" ] || { echo "[publish] 缺 GCP_PUBSUB_TEST_TOPIC（测试 topic 短名）" >&2; exit 1; }
[ -f "$CRED" ] || { echo "[publish] 缺凭证文件 $CRED" >&2; exit 1; }

# ── 安全护栏：拒绝向生产共享 topic 发消息 ──
if [ "$TOPIC" = "collection-cases" ]; then
  echo "[publish] ✗ 拒绝：collection-cases 是生产共享 topic，旧系统会消费到测试消息。请用独立测试 topic。" >&2
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
  echo "[publish] → topic=$TOPIC dataType=$dtype messageId=$mid file=$file"
  "${GCLOUD[@]}" pubsub topics publish "$TOPIC" \
    --message="$body" \
    --attribute="dataType=${dtype},messageId=${mid}"
}

MODE="${1:-case}"
case "$MODE" in
  case)
    # 6 案 case_push（结构同 case_push.sample.json，仅换 loanID/userID/realName/platform）
    #   dpd/金额不入 payload → 由旧库 t_collection（db/seed-test-cases.sql）经 RealCaseService 补。
    declare -a LOANS=(99000000 99000001 99000002 99000003 99000004 99000005)
    declare -a NAMES=("Test Case S0" "Test Case S1" "Test Case S2" "Test Case S3" "Test Case S4" "Test Case Ceased")
    tmp="$(mktemp)"
    for i in "${!LOANS[@]}"; do
      lid="${LOANS[$i]}"; nm="${NAMES[$i]}"
      cat > "$tmp" <<JSON
{
  "dataType": "case_push",
  "messageId": "L4B-CASE-${lid}-$(date +%s)",
  "loanID": "${lid}",
  "userID": "${lid}",
  "realName": "${nm}",
  "appName": "QuickLoan",
  "phone": "+639451374358",
  "email": "wzynju@126.com",
  "jpushToken": "1a0018970bf0c19de04"
}
JSON
      publish_one "$tmp" "case_push" "L4B-CASE-${lid}-$(date +%s)"
    done
    rm -f "$tmp"
    ;;
  repay)
    lid="${2:-99000001}"
    tmp="$(mktemp)"
    cat > "$tmp" <<JSON
{
  "dataType": "repayment_push_and_load",
  "messageId": "L4B-REPAY-${lid}-$(date +%s)",
  "loanId": "${lid}",
  "userId": "${lid}",
  "STATUS": 4,
  "fullRepayTime": "$(date '+%Y-%m-%d %H:%M:%S')"
}
JSON
    publish_one "$tmp" "repayment_push_and_load" "L4B-REPAY-${lid}-$(date +%s)"
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
