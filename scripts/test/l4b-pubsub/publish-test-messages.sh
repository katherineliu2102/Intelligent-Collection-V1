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
#   ./publish-test-messages.sh ownerfeed 名单.txt [dpd偏移] ["occurredAt"]  # Owner 路由 E2E（T1-7）
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

md5_hex() {
  if command -v md5sum >/dev/null 2>&1; then
    md5sum | awk '{print $1}'
  else
    md5 -q
  fi
}

# 内容指纹，复刻数仓契约 §3 的公式：md5(concat(loan_id, maxDpd, overdueAmount,
# upcomingAmount, coalesce(nextDueDate,''), isFullCleared))。
#
# 不能用占位串：投影层按 caseVersion 是否相等决定「刷新还是跳过」，占位串变了但内容没变会造成假刷新
# （白跑一遍入催），内容变了但占位串没变会漏掉余额更新。L4b-10 / L4b-12 验的正是这两件事。
# 消费侧把该值当不透明字符串，因此与数仓 MySQL 的十进制渲染逐字节一致并非必要，
# 「同内容同值、异内容异值」才是取证前提。
case_version() {
  printf '%s' "$1$2$3$4$5$6" | md5_hex
}

new_event_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    python3 -c 'import uuid; print(uuid.uuid4())'
  fi
}

# DPD → Stage，与 Stage.fromDpd 的边界一致（2026-06-15 与渠道编排对齐）：
# S0 ∈[-3,0]、S1 ∈[1,3]、S2 ∈[4,15]、S3 ∈[16,30]、S4 ∈[31,∞)。
# D91+ 停催不是 Stage 概念，由 PlanFactory.shouldRejectPlan 按 dpd 拦截，所以这里照常给 S4。
stage_from_dpd() {
  local d="$1"
  if   [ "$d" -le 0  ]; then echo S0
  elif [ "$d" -le 3  ]; then echo S1
  elif [ "$d" -le 15 ]; then echo S2
  elif [ "$d" -le 30 ]; then echo S3
  else                       echo S4
  fi
}

# 从旧库 IC_TEST_% 行合成并发布单案 caseEvent。
# 供 L4b-3 / L4b-4 用：它们改 t_collection 造升档 / 停催条件，但日切只扫投影表 t_ai_collection，
# 不重发事件的话改动到不了投影，日切无对象可扫（2026-08-21 实测两条都挂在这上面）。
# 重发一遍正是生产口径：数仓每日校准发全量 caseEvent → 接入更新投影 → 日切扫投影产出阶段迁移。
publish_case_from_legacy() {
  local lid="$1" nm="${2:-L4b Case ${1}}"
  local row dpd principal interest penalty amount stg eid cv tmp
  row="$(MYSQL_PWD="${DB_PASS:-}" mysql -N -B -h"${DB_HOST:?缺 DB_HOST}" -P"${DB_PORT:-3306}" \
    -u"${DB_USER}" "${DB_NAME}" -e \
    "SELECT overdue_days, principal, interest, overdue, total_not_paid
       FROM t_collection WHERE loan_id='${lid}' LIMIT 1" 2>/dev/null)"
  [ -n "$row" ] || { echo "[publish] ✗ 旧库无 loan_id=${lid} 的行" >&2; return 1; }
  dpd="$(echo "$row" | awk '{print $1}')"
  principal="$(echo "$row" | awk '{print $2}')"
  interest="$(echo "$row" | awk '{print $3}')"
  penalty="$(echo "$row" | awk '{print $4}')"
  amount="$(echo "$row" | awk '{print $5}')"
  stg="$(stage_from_dpd "$dpd")"
  eid="$(new_event_id)"
  cv="$(case_version "$lid" "$dpd" "$amount" "0.00" "0" "0")"
  tmp="$(mktemp)"
  cat > "$tmp" <<JSON
{
  "dataType": "caseEvent",
  "data": {
    "eventId": "${eid}",
    "occurredAt": "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')",
    "caseId": ${lid},
    "userId": ${lid},
    "caseVersion": "${cv}",
    "product": "3",
    "stage": "${stg}",
    "dpd": ${dpd},
    "collectionStatus": "IN_COLLECTION",
    "overduePrincipal": ${principal},
    "overdueInterest": ${interest},
    "overdueAmount": ${amount},
    "overduePenaltyAmount": ${penalty},
    "upcomingAmount": 0.00,
    "nextDueDate": 0,
    "borrower": {"name": "${nm}", "phone": "9451374358", "email": "l4b@example.com", "language": "en"},
    "device": {"pushToken": "1a0018970bf0c19de04"}
  }
}
JSON
  echo "[publish] case=${lid} dpd=${dpd} stage=${stg} overdueAmount=${amount}（取自旧库）"
  publish_one "$tmp" "caseEvent" "$eid"
  rm -f "$tmp"
}

# Owner 路由 E2E（T1-7）：按名单文件逐案发布 owner=NEW 的 caseEvent，
# 模拟数仓每日「当日 NEW 名单全量重发」的 Publisher 行为。
#
# 迁出/再入不由本模式表达——名单本身就是当日 NEW 集：
#   模拟迁出（LEAVE）= 当日名单去掉该案（缺席 → 03:35 对账取消其活跃计划）
#   模拟再入（ENTER）= 次日名单加回该案（对账重发 CASE_INGESTED 建新计划）
#
# dpd 偏移默认 0 = 同内容重发（caseVersion 不变，验证「指纹相同仍刷新归属日」的
# updateOwnerDate 路径，§7.1 场景 4）；传 N 模拟真实每日 dpd 递增（内容变 →
# caseVersion 变 → 全量刷新路径）。两条路径都是 §7.1 必测。
#
# occurredAt 覆盖参数用于构造乱序/跨日迟到消息（§7.1 场景 1）：传早于当前的
# PHT 时间戳即可；默认当前 PHT 时间。
#
# 不支持发 owner=LEGACY：契约要求数仓只向新系统 topic 发 NEW 案件，消费侧收到
# 非 NEW owner 按 PoisonMessage 进 DLQ。需要验证毒消息路径时用 file 模式手工构造。
ownerfeed_publish() {
  local lid="$1" dpd_offset="$2" occurred_at="${3:-}"
  local row dpd principal interest penalty amount stg eid cv tmp
  row="$(MYSQL_PWD="${DB_PASS:-}" mysql -N -B -h"${DB_HOST:?缺 DB_HOST}" -P"${DB_PORT:-3306}" \
    -u"${DB_USER}" "${DB_NAME}" -e \
    "SELECT overdue_days, principal, interest, overdue, total_not_paid
       FROM t_collection WHERE loan_id='${lid}' LIMIT 1" 2>/dev/null)"
  [ -n "$row" ] || { echo "[ownerfeed] ✗ 旧库无 loan_id=${lid} 的行" >&2; return 1; }
  dpd="$(( $(echo "$row" | awk '{print $1}') + dpd_offset ))"
  principal="$(echo "$row" | awk '{print $2}')"
  interest="$(echo "$row" | awk '{print $3}')"
  penalty="$(echo "$row" | awk '{print $4}')"
  amount="$(echo "$row" | awk '{print $5}')"
  stg="$(stage_from_dpd "$dpd")"
  eid="$(new_event_id)"
  cv="$(case_version "$lid" "$dpd" "$amount" "0.00" "0" "0")"
  [ -n "$occurred_at" ] || occurred_at="$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')"
  tmp="$(mktemp)"
  cat > "$tmp" <<JSON
{
  "dataType": "caseEvent",
  "data": {
    "eventId": "${eid}",
    "occurredAt": "${occurred_at}",
    "caseId": ${lid},
    "userId": ${lid},
    "owner": "NEW",
    "caseVersion": "${cv}",
    "product": "3",
    "stage": "${stg}",
    "dpd": ${dpd},
    "collectionStatus": "IN_COLLECTION",
    "overduePrincipal": ${principal},
    "overdueInterest": ${interest},
    "overdueAmount": ${amount},
    "overduePenaltyAmount": ${penalty},
    "upcomingAmount": 0.00,
    "nextDueDate": 0,
    "borrower": {"name": "Owner Feed ${lid}", "phone": "9451374358", "email": "l4b@example.com", "language": "en"},
    "device": {"pushToken": "1a0018970bf0c19de04"}
  }
}
JSON
  echo "[ownerfeed] case=${lid} dpd=${dpd} stage=${stg} occurredAt=${occurred_at} owner=NEW"
  publish_one "$tmp" "caseEvent" "$eid"
  rm -f "$tmp"
}

MODE="${1:-case}"
case "$MODE" in
  case1)
    # 单案重发，dpd/stage/金额取旧库当前值
    publish_case_from_legacy "${2:?用法: case1 <loanId>}" "${3:-}"
    ;;
  case)
    # 6 案 v3 完整快照。案件状态字段（dpd / stage / 金额）逐案取自旧库 t_collection 的 IC_TEST_% 行，
    # **不能写死**：数仓直发改造后事件载荷成了唯一真相来源，写死一个 dpd 会让六案塌缩成同一个 stage，
    # 而 L4b-1（停催案不建计划）、L4b-3（升档）、L4b-4（日切停催）、L4b-5（快照 vs 旧库逐字段）
    # 验的正是案件之间的差异。2026-08-21 实测写死 dpd=2 时这三条必挂，且失败长得像产品缺陷。
    # 联系方式仍用脚本自带的测试地址，不取旧库真值——零真实触达优先于字段保真。
    declare -a LOANS=(99000000 99000001 99000002 99000003 99000004 99000005)
    declare -a NAMES=("Test Case S0" "Test Case S1" "Test Case S2" "Test Case S3" "Test Case S4" "Test Case Ceased")
    : "${DB_HOST:?缺 DB_HOST —— 合成载荷需按旧库 IC_TEST_% 行取 dpd/金额，见 l4b-env.local.sh}"
    command -v mysql >/dev/null 2>&1 || { echo "[publish] 缺 mysql 客户端" >&2; exit 1; }
    for i in "${!LOANS[@]}"; do
      publish_case_from_legacy "${LOANS[$i]}" "${NAMES[$i]}" || exit 1
    done
    ;;
  repay)
    lid="${2:-99000001}"
    tmp="$(mktemp)"
    eid="$(new_event_id)"
    cat > "$tmp" <<JSON
{
  "dataType": "repaymentEvent",
  "data": {
    "eventId": "${eid}",
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
    publish_one "$tmp" "repaymentEvent" "$eid"
    rm -f "$tmp"
    ;;
  ownerfeed)
    # T1-7 Owner 路由 E2E：按名单发当日 NEW 集（名单文件每行一个 loan_id，# 注释/空行忽略）
    list="${2:?用法: ownerfeed <loan_id名单文件> [dpd偏移] [occurredAt覆盖]}"
    dpd_offset="${3:-0}"
    occurred_at="${4:-}"
    : "${DB_HOST:?缺 DB_HOST —— 合成载荷需按旧库 IC_TEST_% 行取 dpd/金额，见 l4b-env.local.sh}"
    command -v mysql >/dev/null 2>&1 || { echo "[ownerfeed] 缺 mysql 客户端" >&2; exit 1; }
    [ -f "$list" ] || { echo "[ownerfeed] ✗ 名单文件不存在: $list" >&2; exit 1; }
    n=0
    while IFS= read -r lid || [ -n "$lid" ]; do
      lid="$(echo "$lid" | tr -d '[:space:]')"
      case "$lid" in ""|'#'*) continue ;; esac
      ownerfeed_publish "$lid" "$dpd_offset" "$occurred_at" || exit 1
      n=$((n+1))
    done < "$list"
    echo "[ownerfeed] 名单发布完成：${n} 案 owner=NEW（topic=$TOPIC dpd偏移=${dpd_offset}）"
    ;;
  file)
    f="${2:?用法: file <path.json> <dataType>}"; dt="${3:?dataType}"
    publish_one "$f" "$dt" "L4B-FILE-$(date +%s)"
    ;;
  *)
    echo "[publish] 未知模式 '$MODE'（case|case1|repay|ownerfeed|file）" >&2; exit 1 ;;
esac

echo "[publish] 完成。查看应用日志 [Ingestion] 确认消费与白名单/落库。"
