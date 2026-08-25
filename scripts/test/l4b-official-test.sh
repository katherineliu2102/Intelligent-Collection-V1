#!/usr/bin/env bash
# =============================================================================
# §L4b 官方闭环脚本（L4b-1 … L4b-14）
# =============================================================================
# 定位：真实 PubSub 入案 + 真实旧库 seed + 真实 MySQL 落库 + 渠道沙箱。
#       与 l4a-official-test.sh 的差别：入口不是 /mock/ingest，而是真实 topic；
#       裁决不看 REST 预览，而看 t_contact_plan / _step / _timeline 落库事实。
#
# 前置（缺一不可，均由 l4b-preflight.sh --strict 校验）：
#   1) PubSub 资源已开通且**独占消费**：python3 scripts/test/provision-l4-pubsub.py
#      （合成源 intelligent-collection-cases-test1(+sub)；真实源 intelligent-collection-cases-v1-l4b-sub）；
#   2) Nacos 已指向测试订阅、白名单 99000000–99000005、渠道沙箱开关已发布；
#   3) 本机 gcloud 有测试 topic publisher 权限，credentials.json 就位；
#   4) 本 shell 已注入 DB_HOST/DB_PORT/DB_USER/DB_PASS/DB_NAME（不入仓）；
#   5) collection-admin 已用 local profile 启动且 /actuator/health 为 UP。
#
# 用法：
#   source scripts/test/l4b-env.local.sh
#   export DB_HOST=... DB_PORT=3306 DB_USER=... DB_PASS=... DB_NAME=ai_collection_db
#   export GCP_PUBSUB_TEST_TOPIC=intelligent-collection-cases-test1
#   ./scripts/test/l4b-official-test.sh
#   L4B_ONLY=1,5,6 ./scripts/test/l4b-official-test.sh    # 只跑指定用例
#   L4B_RESET=0 ./scripts/test/l4b-official-test.sh       # 保留历史落库（默认清零后取绝对值断言）
#   L4B_SEED=0  ./scripts/test/l4b-official-test.sh       # 不重放 seed（默认随 L4B_RESET 一起重放）
#
# 重置除清 DB 行外，还会调 /mock/ingestion-dedup/clear 清掉进程内的「本周期已入催」标记，
# 因此**不再要求跑之前必须重启应用**。messageId 去重标记无需清：本脚本每条事件都用新 UUID。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

HOST="${HOST:-http://localhost:8888}"
MOCK="$HOST/mock"
PUBLISH="$ROOT/scripts/test/l4b-pubsub/publish-test-messages.sh"

DB_HOST="${DB_HOST:-}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-}"
DB_PASS="${DB_PASS:-}"
DB_NAME="${DB_NAME:-}"

TOPIC="${GCP_PUBSUB_TEST_TOPIC:-}"
ONLY="${L4B_ONLY:-}"
RUN_TS="$(date +%Y%m%d-%H%M%S)"
LOG_DIR="$ROOT/logs/run"
LOG_FILE="$LOG_DIR/l4b.last.log"

# 白名单合成案（与 db/seed-test-cases.sql、publish-test-messages.sh 对齐）
CASE_S0=99000000
CASE_S1=99000001   # L4b-2 还款取消 / L4b-3 升档
CASE_S2=99000002
CASE_S3=99000003   # L4b-4 停催
CASE_S4=99000004
CASE_CEASED=99000005
ALL_CASES=($CASE_S0 $CASE_S1 $CASE_S2 $CASE_S3 $CASE_S4 $CASE_CEASED)
CASE_CSV="$(IFS=,; echo "${ALL_CASES[*]}")"

PASS=0
FAIL=0
SKIP=0

mkdir -p "$LOG_DIR"

line() { printf '%.0s-' {1..78}; echo; }
hdr()  { echo; line; echo "### $1"; line; }
pass() { PASS=$((PASS + 1)); echo "   ✓ $1"; }
fail() { FAIL=$((FAIL + 1)); echo "   ✗ $1"; }
skip() { SKIP=$((SKIP + 1)); echo "   ⊘ $1"; }
die()  { echo "[l4b] ✗ $1" >&2; exit 1; }

selected() {
  [ -z "$ONLY" ] && return 0
  case ",$ONLY," in *",$1,"*) return 0 ;; *) return 1 ;; esac
}

md5_hex() {
  if command -v md5sum >/dev/null 2>&1; then md5sum | awk '{print $1}'; else md5 -q; fi
}

# 内容指纹，复刻数仓契约 §3：md5(concat(loan_id, maxDpd, overdueAmount, upcomingAmount,
# coalesce(nextDueDate,''), isFullCleared))。占位串会让投影层的「指纹相等即跳过」判断失真，
# 不可用于取证，详见测试 SSOT 附录 C。
case_version() { printf '%s' "$1$2$3$4$5$6" | md5_hex; }

new_event_id() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    python3 -c 'import uuid; print(uuid.uuid4())'
  fi
}

# ── 单值 SQL 查询：出错返回空串，调用方需判空 ──
sqlv() {
  MYSQL_PWD="$DB_PASS" mysql -N -B -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" \
    -e "$1" 2>/dev/null | head -1
}

sqltable() {
  MYSQL_PWD="$DB_PASS" mysql -t -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" -e "$1" 2>&1
}

sqlexec() {
  MYSQL_PWD="$DB_PASS" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" -e "$1" 2>&1
}

# 轮询直到 SQL 标量等于期望值
wait_sqlv() {
  local query="$1" want="$2" max="$3" label="$4"
  local elapsed=0 got=""
  while [ "$elapsed" -lt "$max" ]; do
    got="$(sqlv "$query")"
    [ "$got" = "$want" ] && return 0
    sleep 5; elapsed=$((elapsed + 5))
    echo "   [${elapsed}s] $label = '${got}' (want '${want}')"
  done
  return 1
}

# 轮询直到 SQL 标量 >= 期望值
wait_sqlv_ge() {
  local query="$1" want="$2" max="$3" label="$4"
  local elapsed=0 got=0
  while [ "$elapsed" -lt "$max" ]; do
    got="$(sqlv "$query")"
    [ -n "$got" ] && [ "$got" -ge "$want" ] 2>/dev/null && return 0
    sleep 5; elapsed=$((elapsed + 5))
    echo "   [${elapsed}s] $label = '${got}' (want >= ${want})"
  done
  return 1
}

APP_LOG="$ROOT/logs/run/admin.log"

# 注入器某个计数器的剩余次数。按字段名取，不拼数字：端点有两个注入点，拼串会串到一起。
injector_remaining() {
  curl -s "$MOCK/ingestion-fault" \
    | python3 -c "import json,sys; print(json.load(sys.stdin).get('$1'))" 2>/dev/null
}

# 应用日志里某个片段出现的次数。poison 的唯一可观测出口就是 WARN 日志（ack 后既不落 DLQ 表、
# 也不写投影），所以 L4b-13/14 只能靠日志取证；start-local.sh 已保证前台运行也落盘。
applog_count() {
  [ -f "$APP_LOG" ] || { echo 0; return; }
  local n
  n="$(grep -c -F -- "$1" "$APP_LOG" 2>/dev/null)"
  echo "${n:-0}"
}

# 发一条完全受控的 caseEvent：dpd / occurredAt / eventType / eventId / 指纹全部由调用方决定。
# L4b-9…14 验的是载荷取值本身（乱序、越权 eventType、缺字段），不能走按旧库派生的 publish 模式。
# 回显 eventId，供调用方回查 t_ai_collection_inbox。
emit_case_event() {
  local cid="$1" dpd="$2" stage="$3" amount="$4" occurred="$5"
  local extra="${6:-}" eid="${7:-}" ver="${8:-}"
  [ -n "$eid" ] || eid="$(new_event_id)"
  [ -n "$ver" ] || ver="$(case_version "$cid" "$dpd" "$amount" "0.0" "0" "0")"
  local tmp
  tmp="$(mktemp)"
  cat > "$tmp" <<JSON
{
  "dataType": "caseEvent",
  "data": {
    "eventId": "$eid",
    ${extra}
    "caseVersion": "$ver",
    "caseId": ${cid},
    "userId": ${cid},
    "product": "3",
    "dpd": ${dpd},
    "occurredAt": "${occurred}",
    "stage": "${stage}",
    "collectionStatus": "IN_COLLECTION",
    "overduePenaltyAmount": 0.0,
    "overduePrincipal": ${amount},
    "overdueInterest": 0.0,
    "overdueAmount": ${amount},
    "upcomingAmount": 0.0,
    "nextDueDate": 0,
    "borrower": {
      "email": "l4b@example.com",
      "language": "en",
      "name": "L4b Case ${cid}",
      "phone": "9451374358"
    },
    "device": {
      "pushToken": "1a0018970bf0c19de04"
    }
  }
}
JSON
  "$PUBLISH" file "$tmp" caseEvent >/dev/null || { rm -f "$tmp"; return 1; }
  rm -f "$tmp"
  echo "$eid"
}

inbox_field() {
  local eid="$1" field="$2"
  sqlv "SELECT IFNULL($field,'') FROM t_ai_collection_inbox WHERE event_id='$eid'"
}

proj_field() {
  local cid="$1" field="$2"
  sqlv "SELECT IFNULL($field,'') FROM t_ai_collection WHERE case_id=$cid"
}

# 轮询直到 inbox 出现该 eventId（发布→消费有秒级延迟，直接查会假阴）
wait_inbox() {
  local eid="$1" max="${2:-120}" elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    [ "$(sqlv "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_id='$eid'")" = "1" ] && return 0
    sleep 5; elapsed=$((elapsed + 5))
  done
  return 1
}

latest_plan_field() {
  local cid="$1" field="$2"
  sqlv "SELECT IFNULL($field,'') FROM t_contact_plan WHERE case_id=$cid ORDER BY id DESC LIMIT 1"
}

# ─────────────────────────── 前置校验 ───────────────────────────

hdr "L4b 前置校验（run=${RUN_TS})"

[ -n "$TOPIC" ] || die "缺 GCP_PUBSUB_TEST_TOPIC"
[ "$TOPIC" != "collection-cases" ] && [ "$TOPIC" != "intelligent-collection-cases-v1" ] || die "拒绝：生产 topic（collection-cases / intelligent-collection-cases-v1）"
[ -n "$DB_HOST" ] && [ -n "$DB_USER" ] && [ -n "$DB_NAME" ] || \
  die "缺 DB_HOST/DB_USER/DB_NAME（连接信息不入仓，见 L4b 环境交接清单）"
command -v mysql >/dev/null 2>&1 || die "缺 mysql 客户端（Apple Silicon 需 arm64 版本）"
[ -x "$PUBLISH" ] || die "缺 $PUBLISH"

# Phase 1 eventbus/idempotency 均为内存实现；本地未部署 Redis 时 actuator 可能为 DOWN，
# 但业务 API 可正常提供 L4b 所需能力。与 l4b-preflight.sh 保持相同的可用性判定。
health_code="$(curl -s -o /dev/null -w '%{http_code}' "$HOST/actuator/health")"
api_code="$(curl -s -o /dev/null -w '%{http_code}' "$HOST/plans/active/by-case/0")"
if [ "$health_code" != "200" ] && [ "$api_code" != "200" ]; then
  die "应用 ${HOST} 未就绪（health=${health_code}, api=${api_code}），请先启动 collection-admin(local)"
fi
echo "   ✓ 应用可达：${HOST}（health=${health_code}, api=${api_code}）"

if [ "$(sqlv 'SELECT 1')" != "1" ]; then
  die "数据库不可达：$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
fi
echo "   ✓ 数据库可达：$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"

for col in script_slot template_version content_hmac content_key_id; do
  n="$(sqlv "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DB_NAME' AND table_name='t_contact_timeline' AND column_name='$col'")"
  [ "$n" = "1" ] || die "t_contact_timeline 缺列 ${col}，请先执行 db/schema.sql"
done
echo "   ✓ timeline 审计字段齐备"

# L4b-3/L4b-4 会改写 t_collection 的 overdue_days，故重置模式下默认重放 seed，
# 否则第二轮的 dpd 起点已被上一轮污染（S2 变 20、S3 变 95）。
seed_rows="$(sqlv "SELECT COUNT(*) FROM t_collection WHERE loan_id IN (${CASE_CSV})")"
if [ "${L4B_SEED:-${L4B_RESET:-1}}" = "1" ]; then
  echo "   … 重放 db/seed-test-cases.sql"
  MYSQL_PWD="$DB_PASS" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" "$DB_NAME" \
    < "$ROOT/db/seed-test-cases.sql" >/dev/null 2>&1 \
    || die "seed 失败（需要 t_collection 写权限）"
  seed_rows=6
fi
[ "$seed_rows" = "6" ] || die "旧库 seed 不完整（t_collection 命中 $seed_rows/6），请先跑 db/seed-test-cases.sql"
echo "   ✓ 旧库 seed 命中 6 行"

# ── 数据隔离：清空白名单案的历史落库，使本轮断言取绝对值 ──
# 不清则上轮遗留的 PLAN_COMPLETED / 旧快照会污染 L4b-1 增量判定与 L4b-5 溯源比对。
# 白名单固定在 9900000x 测试段，DELETE 前显式复核，避免误删真实案件。
case "$CASE_CSV" in
  99000000,99000001,99000002,99000003,99000004,99000005) ;;
  *) die "拒绝重置：案件集 ${CASE_CSV} 不是 9900000x 测试段" ;;
esac

if [ "${L4B_RESET:-1}" = "1" ]; then
  echo "   … 重置白名单案落库（timeline → step → plan）"
  sqlexec "DELETE FROM t_contact_timeline WHERE case_id IN (${CASE_CSV});
           DELETE s FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id = s.plan_id
             WHERE p.case_id IN (${CASE_CSV});
           DELETE FROM t_contact_plan WHERE case_id IN (${CASE_CSV});
           DELETE FROM t_ai_collection WHERE case_id IN (${CASE_CSV});" >/dev/null \
    || die "重置失败（需要 t_contact_* / t_ai_collection 删除权限）"
  left="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV})")"
  [ "$left" = "0" ] || die "重置后仍残留 $left 个计划"
  # 清进程内的「本周期已入催」标记。不清则本轮的入案事件会被当成「已在催的每日刷新」而只刷投影、
  # 不建计划，L4b-1 报「90s 内未落 t_contact_plan」——2026-08-21 因漏重启应用实测烧掉一轮。
  # 有了这个入口就不必再靠「跑之前先重启」的纪律（messageId 去重标记无需清：本脚本每条事件都用新 UUID）。
  cleared="$(curl -s -X POST "$MOCK/ingestion-dedup/clear?caseIds=${CASE_CSV}" | tr -dc '0-9,')"
  if [ -n "$cleared" ]; then
    echo "   ✓ 已清进程内入催标记：${CASE_CSV}"
  else
    echo "   ⚠ 清入催标记失败（旧版本应用无该端点？）——请先重启应用再跑，否则 L4b-1 会假红"
  fi

  # 旧库 dpd 由上面的 seed 重放复位（seed 是 DELETE IC_TEST_% + INSERT 全量），此处不再重复。
  # 复位是必需的：L4b-3/L4b-4 会把 S2 改成 20、S3 改成 95 造升档/停催条件，不复位则下一轮
  # L4b-1 的合成载荷带着上轮改过的 dpd 出发，六案 stage 分布随运行次数漂移。
  echo "   ✓ 落库已清零、投影已清空（旧库 dpd 由 seed 重放复位）"
else
  echo "   ⚠ 跳过重置（L4B_RESET=0），增量断言可能被历史数据污染"
fi

# 进程内 dedup（messageId / ingested）为内存实现，须由重启清除；此处仅校验入案未被上轮标记挡住。
plans_now="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV})")"
echo "   · 起始计划数=${plans_now}（应用须为本次重置后新启动，否则 ingested 标记会跳过入案）"

# ─────────────────────────── L4b-1 / L4b-5 ───────────────────────────

if selected 1 || selected 5; then
  hdr "L4b-1 真实 case_push 入案 → 计划/步骤落库 ｜ L4b-5 快照字段溯源"

  "$PUBLISH" case || fail "publish case 失败"

  for cid in $CASE_S0 $CASE_S1 $CASE_S2 $CASE_S3 $CASE_S4; do
    if wait_sqlv_ge "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$cid" 1 90 "case=$cid plan 数"; then
      pass "L4b-1 case=$cid 已建计划（stage=$(latest_plan_field "$cid" stage)）"
    else
      fail "L4b-1 case=$cid 90s 内未落 t_contact_plan（查 [Ingestion] 日志与白名单）"
      continue
    fi

    steps="$(sqlv "SELECT COUNT(*) FROM t_contact_plan_step WHERE plan_id=(SELECT id FROM t_contact_plan WHERE case_id=$cid ORDER BY id DESC LIMIT 1)")"
    if [ -n "$steps" ] && [ "$steps" -ge 1 ]; then
      pass "L4b-1 case=$cid 步骤已落库（$steps 步）"
    else
      fail "L4b-1 case=$cid 无步骤行"
    fi
  done

  # L4b-4 前置事实：D91+ 案不建计划
  ceased_plans="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_CEASED")"
  if [ "$ceased_plans" = "0" ]; then
    pass "L4b-1 case=$CASE_CEASED (dpd=95) 未新建计划，符合 D91+ 拒建"
  else
    fail "L4b-1 case=$CASE_CEASED 不应建计划，却落了 $ceased_plans 行"
  fi

  echo
  echo "   —— L4b-5 快照 vs 旧库逐字段比对 ——"
  for cid in $CASE_S1 $CASE_S2 $CASE_S4; do
    snap_dpd="$(sqlv "SELECT JSON_UNQUOTE(JSON_EXTRACT(context_snapshot,'\$.caseContext.dpd')) FROM t_contact_plan WHERE case_id=$cid ORDER BY id DESC LIMIT 1")"
    db_dpd="$(sqlv "SELECT overdue_days FROM t_collection WHERE loan_id=$cid LIMIT 1")"
    if [ -n "$snap_dpd" ] && [ "$snap_dpd" = "$db_dpd" ]; then
      pass "L4b-5 case=${cid} dpd 溯源一致（${snap_dpd}）"
    else
      fail "L4b-5 case=$cid dpd 快照=$snap_dpd 旧库=$db_dpd"
    fi

    snap_phone="$(sqlv "SELECT JSON_UNQUOTE(JSON_EXTRACT(context_snapshot,'\$.userProfile.basic.primaryPhone')) FROM t_contact_plan WHERE case_id=$cid ORDER BY id DESC LIMIT 1")"
    if [ -n "$snap_phone" ] && [ "$snap_phone" != "null" ]; then
      pass "L4b-5 case=$cid 快照含手机号"
    else
      fail "L4b-5 case=$cid 快照缺 primaryPhone（payload 回填失败）"
    fi

    snap_stage="$(sqlv "SELECT JSON_UNQUOTE(JSON_EXTRACT(context_snapshot,'\$.caseContext.stage')) FROM t_contact_plan WHERE case_id=$cid ORDER BY id DESC LIMIT 1")"
    plan_stage="$(latest_plan_field "$cid" stage)"
    if [ "$snap_stage" = "$plan_stage" ]; then
      pass "L4b-5 case=${cid} 快照 stage 与计划 stage 一致（${plan_stage}）"
    else
      fail "L4b-5 case=$cid 快照 stage=$snap_stage 计划 stage=$plan_stage"
    fi
  done
fi

# ─────────────────────────── L4b-4 ───────────────────────────
# 紧跟 L4b-1：`onCaseCeased` 只取消 findActivePlansByCase 的结果，而测试环境步骤延迟被压缩，
# S3 案的 3 步计划约 90s 内即跑完转 PLAN_COMPLETED。放到 L4b-6 之后会无活跃计划可取消。

if selected 4; then
  hdr "L4b-4 日切停催：D91+ → PLAN_CANCELLED/CEASED 且不重建"

  active_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S3
                           AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')")"
  if [ "${active_before:-0}" -ge 1 ]; then
    echo "   · case=${CASE_S3} 有 ${active_before} 个活跃计划，满足停催前置"
  else
    fail "L4b-4 case=${CASE_S3} 无活跃计划，停催无对象（用例需在计划跑完前执行）"
  fi

  # 同 L4b-3：改旧库后必须重发该案，让 dpd 经接入进投影，日切才扫得到（日切只读投影表）。
  sqlexec "UPDATE t_collection SET overdue_days=95, repayment_date=DATE_SUB(CURDATE(), INTERVAL 95 DAY)
             WHERE loan_id=$CASE_S3 AND id LIKE 'IC_TEST_%'" >/dev/null \
    || fail "L4b-4 无法更新 t_collection（缺 seed 写权限）"
  "$PUBLISH" case1 "$CASE_S3" >/dev/null 2>&1 || fail "L4b-4 重发 case=$CASE_S3 失败"
  wait_sqlv_ge "SELECT dpd FROM t_ai_collection WHERE case_id=$CASE_S3" 95 60 "case=$CASE_S3 投影 dpd" \
    || fail "L4b-4 投影 dpd 未更新到 95，日切将扫到旧值"

  before_plans="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S3")"
  curl -s -X POST "$MOCK/daily-roll" >/dev/null

  if wait_sqlv_ge "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S3 AND status='PLAN_CANCELLED' AND cancel_reason='CEASED'" \
       1 120 "case=$CASE_S3 CEASED 取消数"; then
    pass "L4b-4 case=$CASE_S3 已停催（CEASED）"
  else
    fail "L4b-4 case=$CASE_S3 120s 内无 PLAN_CANCELLED/CEASED"
  fi

  sleep 20
  after_plans="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S3")"
  if [ "$after_plans" = "$before_plans" ]; then
    pass "L4b-4 case=$CASE_S3 停催后未重建计划"
  else
    fail "L4b-4 case=${CASE_S3} 停催后计划数 ${before_plans} → ${after_plans}，出现重建"
  fi

  ceased_timeline="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$CASE_S3")"
  echo "   · 停催时 case=${CASE_S3} 已触达 ${ceased_timeline} 条（取消后其余步骤不应再发）"
fi

# ─────────────────────────── L4b-6 ───────────────────────────

if selected 6; then
  hdr "L4b-6 TriggerScanner 到期执行 → timeline 落库"

  for cid in $CASE_S1 $CASE_S2; do
    q="SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$cid"
    if wait_sqlv_ge "$q" 1 240 "case=$cid timeline 条数"; then
      pass "L4b-6 case=$cid 已落 timeline"
    else
      fail "L4b-6 case=$cid 240s 内无 timeline（检查 TriggerScanner 与渠道沙箱）"
      continue
    fi

    delivered="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$cid AND result='DELIVERED' AND provider_msg_id IS NOT NULL AND provider_msg_id<>''")"
    if [ -n "$delivered" ] && [ "$delivered" -ge 1 ]; then
      pass "L4b-6 case=$cid 有 $delivered 条 DELIVERED + provider_msg_id"
    else
      fail "L4b-6 case=$cid 无 DELIVERED+provider_msg_id（渠道未真实受理）"
    fi

    slot="$(sqlv "SELECT IFNULL(script_slot,'') FROM t_contact_timeline WHERE case_id=$cid ORDER BY created_at DESC LIMIT 1")"
    if [ -n "$slot" ]; then
      pass "L4b-6 case=$cid timeline 带 script_slot=$slot"
    else
      fail "L4b-6 case=$cid timeline 缺 script_slot（触达审计元数据未写入）"
    fi

    leaked="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$cid AND content_summary LIKE '%Test Case%'")"
    if [ "$leaked" = "0" ]; then
      pass "L4b-6 case=$cid content_summary 未泄露渲染正文"
    else
      fail "L4b-6 case=$cid content_summary 含姓名，违反隐私最小化"
    fi
  done

  echo
  sqltable "SELECT case_id, channel, result, provider_msg_id, script_slot, template_version, created_at
              FROM t_contact_timeline
                    WHERE case_id IN (${CASE_CSV})
             ORDER BY case_id, created_at"
fi

# ─────────────────────────── L4b-2 ───────────────────────────

if selected 2; then
  hdr "L4b-2 真实 repayment 消息 → 活跃计划 PLAN_CANCELLED/REPAID"

  before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S1 AND cancel_reason='REPAID'")"
  "$PUBLISH" repay "$CASE_S1" || fail "publish repay 失败"

  want=$(( before + 1 ))
  if wait_sqlv_ge "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S1 AND status='PLAN_CANCELLED' AND cancel_reason='REPAID'" \
       "$want" 120 "case=$CASE_S1 REPAID 取消数"; then
    pass "L4b-2 case=$CASE_S1 经真实 PubSub 还款消息取消（REPAID）"
  else
    fail "L4b-2 case=$CASE_S1 120s 内未出现 PLAN_CANCELLED/REPAID"
  fi

  active="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S1 AND status IN ('PENDING','STEP_SCHEDULED','STEP_EXECUTING','STEP_WAITING')")"
  if [ "$active" = "0" ]; then
    pass "L4b-2 case=$CASE_S1 还款后无活跃计划"
  else
    fail "L4b-2 case=$CASE_S1 仍有 $active 个活跃计划"
  fi
fi

# ─────────────────────────── L4b-3 ───────────────────────────

if selected 3; then
  hdr "L4b-3 日切升档：旧计划 STAGE_UPGRADE + 新阶段计划"

  # 造升档条件：S2 案 dpd 4 → 20（跨入 S3 区间[16,30]）。
  # 只改旧库不够——日切 DpdStageRollHandler 只扫投影表 t_ai_collection，改动必须先经接入进投影，
  # 否则日切扫到的还是旧 dpd（2026-08-21 实测本条与 L4b-4 都挂在这里）。重发一遍即生产口径：
  # 数仓每日校准发全量 caseEvent → 接入更新投影 → 日切扫投影产出阶段迁移。
  sqlexec "UPDATE t_collection SET overdue_days=20, repayment_date=DATE_SUB(CURDATE(), INTERVAL 20 DAY)
             WHERE loan_id=$CASE_S2 AND id LIKE 'IC_TEST_%'" >/dev/null \
    || fail "L4b-3 无法更新 t_collection（缺 seed 写权限）"
  "$PUBLISH" case1 "$CASE_S2" >/dev/null 2>&1 || fail "L4b-3 重发 case=$CASE_S2 失败"
  wait_sqlv_ge "SELECT dpd FROM t_ai_collection WHERE case_id=$CASE_S2" 20 60 "case=$CASE_S2 投影 dpd" \
    || fail "L4b-3 投影 dpd 未更新到 20，日切将扫到旧值"

  before_upg="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S2 AND cancel_reason='STAGE_UPGRADE'")"
  curl -s -X POST "$MOCK/daily-roll" >/dev/null

  if wait_sqlv_ge "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S2 AND cancel_reason='STAGE_UPGRADE'" \
       "$(( before_upg + 1 ))" 120 "case=$CASE_S2 STAGE_UPGRADE 取消数"; then
    pass "L4b-3 case=$CASE_S2 旧计划因升档取消"
  else
    fail "L4b-3 case=$CASE_S2 120s 内无 STAGE_UPGRADE 取消"
  fi

  new_stage="$(latest_plan_field "$CASE_S2" stage)"
  if [ "$new_stage" = "S3" ]; then
    pass "L4b-3 case=$CASE_S2 已建 S3 新计划"
  else
    fail "L4b-3 case=${CASE_S2} 最新计划 stage=${new_stage}（期望 S3）"
  fi
fi

# ─────────────────────────── L4b-8 ───────────────────────────

if selected 8; then
  hdr "L4b-8 日切幂等：同日重复 daily-roll 不重复升档/触达"

  # TriggerScanner 每 5s 独立到期扫描，若仍有未跑完的步骤，timeline 会在观察窗内自然增长，
  # 使"重复触达"断言产生假失败。故先等测试案件全部收敛到终态再取基线。
  if wait_sqlv "SELECT COUNT(*) FROM t_contact_plan
                  WHERE case_id IN (${CASE_CSV})
                    AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')" \
       0 180 "未收敛计划数"; then
    echo "   · 测试案件已全部收敛到终态，可测日切幂等"
  else
    fail "L4b-8 180s 内仍有活跃计划，基线不稳定，跳过幂等裁决"
  fi

  plans_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV})")"
  cancels_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV}) AND status='PLAN_CANCELLED'")"
  timeline_before="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id IN (${CASE_CSV})")"

  curl -s -X POST "$MOCK/daily-roll" >/dev/null
  sleep 30

  plans_after="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV})")"
  cancels_after="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV}) AND status='PLAN_CANCELLED'")"
  timeline_after="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id IN (${CASE_CSV})")"

  [ "$plans_after" = "$plans_before" ] \
    && pass "L4b-8 计划数不变（${plans_before}）" \
    || fail "L4b-8 计划数 ${plans_before} → ${plans_after}，日切非幂等"
  [ "$cancels_after" = "$cancels_before" ] \
    && pass "L4b-8 取消数不变（${cancels_before}）" \
    || fail "L4b-8 取消数 ${cancels_before} → ${cancels_after}，重复升档/停催"
  [ "$timeline_after" = "$timeline_before" ] \
    && pass "L4b-8 timeline 不变（${timeline_before}）" \
    || fail "L4b-8 timeline ${timeline_before} → ${timeline_after}，重复触达"
fi

# ─────────────────────────── L4b-7 ───────────────────────────

if selected 7; then
  hdr "L4b-7 NACK 重投与幂等（受控故障注入，不重启进程）"

  armed="$(curl -s -X POST "$MOCK/ingestion-fault/arm?count=1" | tr -dc '0-9')"
  if [ "${armed:-0}" != "1" ]; then
    skip "L4b-7 故障注入未启用（Nacos 需 collection.ingestion.fault-injection-enabled=true），不裁决"
  else
    # 先用还款消息清掉 CASE_S0 的 ingested 标记，使其可重新入案（fullySettled → clearIngested）
    "$PUBLISH" repay "$CASE_S0" >/dev/null || fail "L4b-7 前置 repay 发布失败"
    sleep 15

    before_plans="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S0")"
    before_timeline="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$CASE_S0")"

    tmp_case="$(mktemp)"
    cat > "$tmp_case" <<JSON
{
  "dataType": "caseEvent",
  "data": {
    "eventId": "$(new_event_id)",
    "caseVersion": "$(case_version "$CASE_S0" -1 "3000.0" "0.0" "0" "0")",
    "caseId": ${CASE_S0},
    "userId": ${CASE_S0},
    "product": "3",
    "dpd": -1,
    "occurredAt": "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')",
    "stage": "S0",
    "collectionStatus": "IN_COLLECTION",
    "overduePenaltyAmount": 0.0,
    "overduePrincipal": 3000.0,
    "overdueInterest": 0.0,
    "overdueAmount": 3000.0,
    "upcomingAmount": 0.0,
    "nextDueDate": 0,
    "borrower": {
      "email": "wzynju@126.com",
      "language": "en",
      "name": "Test Case S0",
      "phone": "9451374358"
    },
    "device": {
      "pushToken": "1a0018970bf0c19de04"
    }
  }
}
JSON
    "$PUBLISH" file "$tmp_case" caseEvent >/dev/null || fail "L4b-7 caseEvent 发布失败"
    rm -f "$tmp_case"

    # 注入命中后 remaining 归零；未归零说明消息没被消费到。
    # 必须按字段取值，不能 tr -dc '0-9' 拼全部数字：该端点后来多了 remainingPostProjection，
    # 拼出来是 "00" 永远不等于 "0"，注入其实已命中却报未命中（2026-08-21 实测）。
    fired=0
    for _ in $(seq 1 24); do
      [ "$(injector_remaining remaining)" = "0" ] && { fired=1; break; }
      sleep 5
    done
    if [ "$fired" = "1" ]; then
      pass "L4b-7 注入生效：首次处理抛异常 → 未 ack"
    else
      fail "L4b-7 注入未命中（消息未被本机消费？检查独占订阅与白名单）"
      curl -s -X POST "$MOCK/ingestion-fault/disarm" >/dev/null
    fi

    if wait_sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S0" \
         "$(( before_plans + 1 ))" 180 "case=$CASE_S0 plan 数"; then
      pass "L4b-7 PubSub 重投后成功入案，且只新增 1 个计划"
    else
      fail "L4b-7 重投后计划数异常：期望 $(( before_plans + 1 ))，实际 $(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S0")"
    fi

    sleep 30
    after_plans="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S0")"
    if [ "$after_plans" = "$(( before_plans + 1 ))" ]; then
      pass "L4b-7 静置 30s 后仍无重复计划，幂等收敛"
    else
      fail "L4b-7 出现重复入案：$(( before_plans + 1 )) → $after_plans"
    fi

    after_timeline="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$CASE_S0")"
    dup_provider="$(sqlv "SELECT IFNULL(MAX(c),0) FROM (SELECT COUNT(*) c FROM t_contact_timeline
                            WHERE case_id=$CASE_S0 AND provider_msg_id IS NOT NULL AND provider_msg_id<>''
                            GROUP BY provider_msg_id) t")"
    if [ "${dup_provider:-0}" -le 1 ]; then
      pass "L4b-7 无重复 provider_msg_id（timeline ${before_timeline} → ${after_timeline}）"
    else
      fail "L4b-7 出现重复触达：同一 provider_msg_id 命中 $dup_provider 次"
    fi
  fi
fi

# ─────────────────────────── L4b-9 ───────────────────────────
# 用例 9…14 都跑在前面用例留下的状态上（S4 已入案、S1 已还款清标记、S0 经 L4b-7 重新入案），
# 单独用 L4B_ONLY 跑会因前置不成立而 skip，这是有意的：假绿比不跑更贵。

if selected 9; then
  hdr "L4b-9 投影与收件箱状态一致"

  eid="$(sqlv "SELECT event_id FROM t_ai_collection_inbox
                 WHERE case_id=$CASE_S4 AND message_type='caseEvent'
                 ORDER BY id DESC LIMIT 1")"
  if [ -z "$eid" ]; then
    skip "L4b-9 case=$CASE_S4 无 caseEvent 收件箱行（L4b-1 未跑？），不裁决"
  else
    mismatch=""
    for pair in "dpd:dpd" "stage:stage" "collection_status:collectionStatus"; do
      col="${pair%%:*}"; key="${pair##*:}"
      got="$(proj_field "$CASE_S4" "$col")"
      want="$(sqlv "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload,'\$.data.$key'))
                      FROM t_ai_collection_inbox WHERE event_id='$eid'")"
      [ "$got" = "$want" ] || mismatch="$mismatch ${col}(投影=${got} 载荷=${want})"
    done
    proj_ver="$(proj_field "$CASE_S4" case_version)"
    inbox_ver="$(inbox_field "$eid" case_version)"
    [ "$proj_ver" = "$inbox_ver" ] || mismatch="$mismatch case_version(投影=${proj_ver} 收件箱=${inbox_ver})"
    if [ -z "$mismatch" ]; then
      pass "L4b-9 case=$CASE_S4 投影字段与收件箱载荷逐字段一致（指纹 ${proj_ver}）"
    else
      fail "L4b-9 投影与收件箱不一致：${mismatch}"
    fi

    applied="$(inbox_field "$eid" projection_applied)"
    status="$(inbox_field "$eid" publish_status)"
    pub_at="$(inbox_field "$eid" published_at)"
    if [ "$applied" = "1" ] && [ "$status" = "PUBLISHED" ] && [ -n "$pub_at" ]; then
      pass "L4b-9 该收件箱行终态正确：projection_applied=1 / PUBLISHED / published_at=$pub_at"
    else
      fail "L4b-9 收件箱终态异常：applied=$applied status=$status published_at='$pub_at'"
    fi

    # 全局收口：PENDING 意味着「投影已改、领域事件永远没发出去」，是最难在生产上发现的一类不一致。
    # 静置后仍为 PENDING 才算卡住，避免撞上正在处理中的行。
    sleep 15
    stuck="$(sqlv "SELECT COUNT(*) FROM t_ai_collection_inbox
                     WHERE publish_status='PENDING' AND projection_applied=1
                       AND created_at < DATE_SUB(NOW(), INTERVAL 10 SECOND)")"
    if [ "${stuck:-0}" = "0" ]; then
      pass "L4b-9 无卡在 PENDING 的收件箱行（投影已落但事件未发）"
    else
      fail "L4b-9 有 $stuck 行卡在 PENDING"
      sqltable "SELECT event_id, case_id, message_type, created_at FROM t_ai_collection_inbox
                  WHERE publish_status='PENDING' AND projection_applied=1" | tee -a "$LOG_FILE"
    fi
  fi
fi

# ─────────────────────────── L4b-10 ───────────────────────────

if selected 10; then
  hdr "L4b-10 乱序不回退：陈旧 occurredAt 不覆盖新快照"

  now_pht="$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')"
  old_pht="$(TZ=Asia/Manila date -v-2d '+%Y-%m-%d %H:%M:%S' 2>/dev/null \
             || TZ=Asia/Manila date -d '2 days ago' '+%Y-%m-%d %H:%M:%S')"

  fresh_eid="$(emit_case_event "$CASE_S4" 45 S4 "9500.0" "$now_pht")" \
    || fail "L4b-10 新快照发布失败"
  if wait_sqlv "SELECT dpd FROM t_ai_collection WHERE case_id=$CASE_S4" 45 120 "case=$CASE_S4 投影 dpd"; then
    pass "L4b-10 较新快照已落投影（dpd=45）"
  else
    fail "L4b-10 较新快照 120s 内未落投影（dpd=$(proj_field "$CASE_S4" dpd)）"
  fi
  ver_after_fresh="$(proj_field "$CASE_S4" case_version)"
  plans_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S4")"

  # 指纹不同、occurredAt 更早：只比指纹会把旧数据盖回去，必须靠事实时间挡住
  stale_eid="$(emit_case_event "$CASE_S4" 31 S4 "8000.0" "$old_pht")" \
    || fail "L4b-10 陈旧快照发布失败"
  if wait_inbox "$stale_eid" 120; then
    pass "L4b-10 陈旧快照已被消费（收件箱留痕 ${stale_eid}）"
  else
    fail "L4b-10 陈旧快照 120s 内未进收件箱"
  fi

  sleep 10
  dpd_now="$(proj_field "$CASE_S4" dpd)"
  ver_now="$(proj_field "$CASE_S4" case_version)"
  if [ "$dpd_now" = "45" ] && [ "$ver_now" = "$ver_after_fresh" ]; then
    pass "L4b-10 投影未回退（dpd 仍 45，指纹未变）"
  else
    fail "L4b-10 投影被陈旧快照覆盖：dpd=$dpd_now 指纹=${ver_now}（期望 45 / ${ver_after_fresh}）"
  fi

  st="$(inbox_field "$stale_eid" publish_status)"
  ap="$(inbox_field "$stale_eid" projection_applied)"
  if [ "$st" = "SKIPPED" ] && [ "$ap" = "0" ]; then
    pass "L4b-10 陈旧快照记为 SKIPPED / projection_applied=0"
  else
    fail "L4b-10 陈旧快照收件箱状态异常：status=$st applied=${ap}（期望 SKIPPED / 0）"
  fi

  plans_after="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S4")"
  if [ "$plans_after" = "$plans_before" ]; then
    pass "L4b-10 乱序消息未产生额外计划（$plans_before 保持不变）"
  else
    fail "L4b-10 计划数被乱序消息改变：$plans_before → $plans_after"
  fi
fi

# ─────────────────────────── L4b-11 ───────────────────────────

if selected 11; then
  hdr "L4b-11 投影已落库但事件未发出：重投只补发事件"

  armed="$(curl -s -X POST "$MOCK/ingestion-fault/arm-post-projection?count=1" | tr -dc '0-9')"
  ingested_s1="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S1
                         AND status IN ('PENDING','STEP_SCHEDULED','STEP_EXECUTING','STEP_WAITING')")"
  if [ "${armed:-0}" != "1" ]; then
    skip "L4b-11 故障注入未启用（需 collection.ingestion.fault-injection-enabled=true），不裁决"
  elif [ "${ingested_s1:-0}" != "0" ]; then
    curl -s -X POST "$MOCK/ingestion-fault/disarm" >/dev/null
    skip "L4b-11 case=$CASE_S1 仍有活跃计划（L4b-2 未跑？入催标记未清），不裁决"
  else
    plans_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S1")"
    eid="$(emit_case_event "$CASE_S1" 1 S1 "3000.0" "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')")" \
      || fail "L4b-11 caseEvent 发布失败"

    fired=0
    for _ in $(seq 1 24); do
      [ "$(injector_remaining remainingPostProjection)" = "0" ] && { fired=1; break; }
      sleep 5
    done
    if [ "$fired" = "1" ]; then
      pass "L4b-11 注入生效：投影已提交、领域事件未发出即抛异常 → 未 ack"
    else
      fail "L4b-11 注入未命中（消息未被本机消费？检查独占订阅与白名单）"
      curl -s -X POST "$MOCK/ingestion-fault/disarm" >/dev/null
    fi

    if wait_sqlv "SELECT IFNULL(publish_status,'') FROM t_ai_collection_inbox WHERE event_id='$eid'" \
         PUBLISHED 180 "eventId=$eid publish_status"; then
      pass "L4b-11 重投后收件箱补发完成（PENDING → PUBLISHED）"
    else
      fail "L4b-11 收件箱未收敛：status=$(inbox_field "$eid" publish_status) applied=$(inbox_field "$eid" projection_applied)"
    fi

    # 收件箱按 eventId 唯一：重投命中 PENDING_PUBLISH 分支，不可能再写一行、也不可能重写投影
    rows="$(sqlv "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_id='$eid'")"
    applied="$(inbox_field "$eid" projection_applied)"
    if [ "$rows" = "1" ] && [ "$applied" = "1" ]; then
      pass "L4b-11 收件箱仅 1 行且 projection_applied=1（投影未被重复写）"
    else
      fail "L4b-11 收件箱行数/状态异常：rows=$rows applied=$applied"
    fi

    sleep 30
    plans_after="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S1")"
    if [ "$plans_after" = "$(( plans_before + 1 ))" ]; then
      pass "L4b-11 只新增 1 个计划，无重复入案（$plans_before → ${plans_after}）"
    else
      fail "L4b-11 计划数异常：期望 $(( plans_before + 1 ))，实际 $plans_after"
    fi

    dup_provider="$(sqlv "SELECT IFNULL(MAX(c),0) FROM (SELECT COUNT(*) c FROM t_contact_timeline
                            WHERE case_id=$CASE_S1 AND provider_msg_id IS NOT NULL AND provider_msg_id<>''
                            GROUP BY provider_msg_id) t")"
    if [ "${dup_provider:-0}" -le 1 ]; then
      pass "L4b-11 无重复 provider_msg_id（补发未造成重复触达）"
    else
      fail "L4b-11 出现重复触达：同一 provider_msg_id 命中 $dup_provider 次"
    fi
  fi
fi

# ─────────────────────────── L4b-12 ───────────────────────────

if selected 12; then
  hdr "L4b-12 每日快照静默刷新：刷投影不重复入催，日切据新投影升档"

  # 前置只要求「该案已在催收周期内」：有投影 + 有计划（含终态）。
  # 不要求「计划仍活跃」——S0 模板几秒就跑完，活跃态窗口小于轮询间隔，拿它当前置必然时而跳过时而假红
  # （2026-08-21 两种写法各踩一次：捡 L4b-7 剩余状态被整条跳过；自己 repay + 重新入案后，
  # 计划在首次轮询前就已 PLAN_COMPLETED）。
  proj_rows="$(sqlv "SELECT COUNT(*) FROM t_ai_collection WHERE case_id=$CASE_S0")"
  plan_rows="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S0")"
  if [ "${proj_rows:-0}" = "0" ] || [ "${plan_rows:-0}" = "0" ]; then
    skip "L4b-12 case=$CASE_S0 未入催（投影 ${proj_rows} 行 / 计划 ${plan_rows} 行，L4b-1/7 未跑？），不裁决"
  else
    plans_before="$plan_rows"
    stage_before="$(proj_field "$CASE_S0" stage)"

    # 在催案的每日全量校准：dpd 从 S0 段推到 S2 段，投影必须刷新，但不得再发一次 CASE_INGESTED
    eid="$(emit_case_event "$CASE_S0" 10 S2 "3000.0" "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')")" \
      || fail "L4b-12 刷新快照发布失败"
    if wait_sqlv "SELECT dpd FROM t_ai_collection WHERE case_id=$CASE_S0" 10 120 "case=$CASE_S0 投影 dpd"; then
      pass "L4b-12 投影已静默刷新（stage ${stage_before} → $(proj_field "$CASE_S0" stage), dpd=10）"
    else
      fail "L4b-12 投影 120s 内未刷新（dpd=$(proj_field "$CASE_S0" dpd)）"
    fi

    st="$(inbox_field "$eid" publish_status)"
    ap="$(inbox_field "$eid" projection_applied)"
    if [ "$st" = "SKIPPED" ] && [ "$ap" = "1" ]; then
      pass "L4b-12 收件箱记为 projection_applied=1 / SKIPPED（投影已刷、领域事件未发）"
    else
      fail "L4b-12 收件箱状态异常：status=$st applied=${ap}（期望 SKIPPED / 1）"
    fi

    # 「不重复入催」的判据是计划数不变。这里不断言 timeline 不变：已在执行中的旧步骤写 timeline 是
    # 正常推进，与本次刷新无关，断言它会随扫描节奏假红（L4b-14 首跑就这么红过一次）。
    # 「没有再发一次 CASE_INGESTED」已由上面的 inbox SKIPPED 直接证明，比数 timeline 更贴近不变式。
    sleep 20
    plans_mid="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$CASE_S0")"
    if [ "$plans_mid" = "$plans_before" ]; then
      pass "L4b-12 刷新未重复入催（计划数 $plans_before 保持不变）"
    else
      fail "L4b-12 刷新造成重复入催：$plans_before → $plans_mid"
    fi

    # 日切按新投影升档，验在 L4b-3 已落库的事实上，不在这里重造一遍。
    # 两者走的是同一条路径：L4b-3 用 `publish case1` 重发全量 caseEvent（= 数仓每日校准），
    # 该事件对在催案同样只刷投影、不再发 CASE_INGESTED，随后日切扫投影产出阶段迁移。
    # 重造的唯一差别是「谁触发的刷新」，而那一点上面已经验过了；重造反而要一个活跃态窗口够长的案件。
    upg_case="$CASE_S2"
    refresh_status="$(sqlv "SELECT publish_status FROM t_ai_collection_inbox
                              WHERE case_id=$upg_case AND message_type='caseEvent'
                              ORDER BY id DESC LIMIT 1")"
    refresh_applied="$(sqlv "SELECT projection_applied FROM t_ai_collection_inbox
                               WHERE case_id=$upg_case AND message_type='caseEvent'
                               ORDER BY id DESC LIMIT 1")"
    upg_cancels="$(sqlv "SELECT COUNT(*) FROM t_contact_plan
                           WHERE case_id=$upg_case AND cancel_reason='STAGE_UPGRADE'")"
    upg_new_stage="$(latest_plan_field "$upg_case" stage)"
    if [ "$refresh_status" = "SKIPPED" ] && [ "$refresh_applied" = "1" ] \
       && [ "${upg_cancels:-0}" -ge 1 ] && [ "$upg_new_stage" = "S3" ]; then
      pass "L4b-12 日切据静默刷新后的投影升档（case=$upg_case 刷新记 SKIPPED，旧计划 STAGE_UPGRADE，新计划 S3）"
    else
      fail "L4b-12 升档链路不成立：case=$upg_case 刷新状态=$refresh_status/applied=$refresh_applied，升档取消 ${upg_cancels} 个，最新计划 stage=$upg_new_stage（期望 SKIPPED/1/≥1/S3）"
    fi
  fi
fi

# ─────────────────────────── L4b-13 ───────────────────────────

if selected 13; then
  hdr "L4b-13 外部阶段事件被拒：poison + ack，投影与计划不变"

  # 探针打在停催案上：它按 D91+ 规则从不建计划，因此没有在执行中的步骤会在断言窗口里写 timeline。
  # 打在有活跃计划的案件上会撞到扫描器正常执行的到期步骤，把「零副作用」误判成有副作用
  # （2026-08-21 实测 L4b-14 就这么假红了一次：timeline 11 → 12，增的那条来自 L4b-6 排的步骤）。
  PROBE="$CASE_CEASED"

  if [ ! -f "$APP_LOG" ]; then
    skip "L4b-13 无 ${APP_LOG}（应用未用 start-local.sh 启动？），poison 只能靠日志取证，不裁决"
  else
    poison_before="$(applog_count 'poison message ack+skip')"
    ver_before="$(proj_field "$PROBE" case_version)"
    dpd_before="$(proj_field "$PROBE" dpd)"
    plans_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$PROBE")"
    tl_before="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$PROBE")"

    # 阶段变更与 D+91 停催由日切独占产出；外部 topic 送阶段事件属违约，必须拒收而不是照做
    eid="$(emit_case_event "$PROBE" 70 S4 "9900.0" "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')" \
             '"eventType": "CASE_STAGE_CHANGED",')" || fail "L4b-13 发布失败"

    hit=0
    for _ in $(seq 1 24); do
      [ "$(applog_count 'poison message ack+skip')" -gt "$poison_before" ] && { hit=1; break; }
      sleep 5
    done
    if [ "$hit" = "1" ]; then
      pass "L4b-13 外部阶段事件被判 poison 并 ack（日志留痕）"
    else
      fail "L4b-13 120s 内未见 poison 日志（外部阶段事件可能被照单执行）"
    fi

    if [ "$(applog_count '仅受理 CASE_INGESTED')" -gt 0 ]; then
      pass "L4b-13 拒因明确：外部 caseEvent 仅受理 CASE_INGESTED"
    else
      fail "L4b-13 日志无「仅受理 CASE_INGESTED」拒因，可能被别的原因拦下"
    fi

    if [ "$(sqlv "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_id='$eid'")" = "0" ]; then
      pass "L4b-13 未写收件箱（在落库之前就被拒）"
    else
      fail "L4b-13 违约事件被写入收件箱：$(sqltable "SELECT publish_status, projection_applied FROM t_ai_collection_inbox WHERE event_id='$eid'")"
    fi

    # ack 而非 nack：静置后 poison 计数不再增长才说明没被无限重投
    count_a="$(applog_count 'poison message ack+skip')"
    sleep 40
    count_b="$(applog_count 'poison message ack+skip')"
    if [ "$count_a" = "$count_b" ]; then
      pass "L4b-13 静置 40s 无重投（ack 生效，poison 计数停在 ${count_a}）"
    else
      fail "L4b-13 poison 被重投：$count_a → $count_b"
    fi

    if [ "$(proj_field "$PROBE" case_version)" = "$ver_before" ] \
       && [ "$(proj_field "$PROBE" dpd)" = "$dpd_before" ]; then
      pass "L4b-13 投影未变（指纹 ${ver_before} / dpd ${dpd_before}）"
    else
      fail "L4b-13 投影被违约事件改写：dpd $dpd_before → $(proj_field "$PROBE" dpd)"
    fi
    plans_after="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$PROBE")"
    tl_after="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$PROBE")"
    if [ "$plans_after" = "$plans_before" ] && [ "$tl_after" = "$tl_before" ]; then
      pass "L4b-13 计划与触达均未变（plan=$plans_before timeline=${tl_before}）"
    else
      fail "L4b-13 产生副作用：plan $plans_before → $plans_after, timeline $tl_before → $tl_after"
    fi
  fi
fi

# ─────────────────────────── L4b-14 ───────────────────────────

if selected 14; then
  hdr "L4b-14 poison 消息处置：契约错误 ack + 告警，零副作用"

  # 同 L4b-13：探针打在从不建计划的停催案上，副作用断言才不会被正常执行中的到期步骤污染。
  # 「零副作用」只按该案计数，不按全部白名单案 —— 后者会把别的案件的正常触达算成本用例的副作用。
  PROBE="$CASE_CEASED"

  if [ ! -f "$APP_LOG" ]; then
    skip "L4b-14 无 ${APP_LOG}（应用未用 start-local.sh 启动？），不裁决"
  else
    inbox_before="$(sqlv "SELECT COUNT(*) FROM t_ai_collection_inbox")"
    plans_before="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$PROBE")"
    tl_before="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$PROBE")"

    # 探针 1：缺 eventId —— 去重与收件箱都以它为主键，缺了重投永远补不出来，只能 ack
    tmp="$(mktemp)"
    cat > "$tmp" <<JSON
{
  "dataType": "caseEvent",
  "data": {
    "caseId": ${PROBE},
    "userId": ${PROBE},
    "product": "3",
    "dpd": 45,
    "stage": "S4",
    "occurredAt": "$(TZ=Asia/Manila date '+%Y-%m-%d %H:%M:%S')",
    "collectionStatus": "IN_COLLECTION",
    "overdueAmount": 9500.0
  }
}
JSON
    before1="$(applog_count '缺 eventId')"
    "$PUBLISH" file "$tmp" caseEvent >/dev/null || fail "L4b-14 探针 1 发布失败"
    rm -f "$tmp"

    # 探针 2：data 不是 JSON object —— 解析期就该判死，不进业务逻辑
    tmp="$(mktemp)"
    printf '{"dataType":"caseEvent","data":"not-an-object"}' > "$tmp"
    before2="$(applog_count '必须为 JSON object')"
    "$PUBLISH" file "$tmp" caseEvent >/dev/null || fail "L4b-14 探针 2 发布失败"
    rm -f "$tmp"

    hit1=0 hit2=0
    for _ in $(seq 1 24); do
      [ "$(applog_count '缺 eventId')" -gt "$before1" ] && hit1=1
      [ "$(applog_count '必须为 JSON object')" -gt "$before2" ] && hit2=1
      [ "$hit1" = "1" ] && [ "$hit2" = "1" ] && break
      sleep 5
    done
    [ "$hit1" = "1" ] && pass "L4b-14 缺 eventId 被判 poison 并 ack" \
                      || fail "L4b-14 120s 内未见「缺 eventId」告警"
    [ "$hit2" = "1" ] && pass "L4b-14 data 非 JSON object 被判 poison 并 ack" \
                      || fail "L4b-14 120s 内未见「必须为 JSON object」告警"

    poison_a="$(applog_count 'poison message ack+skip')"
    sleep 40
    poison_b="$(applog_count 'poison message ack+skip')"
    if [ "$poison_a" = "$poison_b" ]; then
      pass "L4b-14 静置 40s 无重投（poison 计数停在 ${poison_a}）"
    else
      fail "L4b-14 poison 被重投：$poison_a → $poison_b"
    fi

    inbox_after="$(sqlv "SELECT COUNT(*) FROM t_ai_collection_inbox")"
    plans_after="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id=$PROBE")"
    tl_after="$(sqlv "SELECT COUNT(*) FROM t_contact_timeline WHERE case_id=$PROBE")"
    if [ "$inbox_after" = "$inbox_before" ]; then
      pass "L4b-14 收件箱未增行（$inbox_before 保持不变）"
    else
      fail "L4b-14 收件箱增了 $(( inbox_after - inbox_before )) 行"
    fi
    if [ "$plans_after" = "$plans_before" ] && [ "$tl_after" = "$tl_before" ]; then
      pass "L4b-14 计划与触达均未变（plan=$plans_before timeline=${tl_before}）"
    else
      fail "L4b-14 产生副作用：plan $plans_before → $plans_after, timeline $tl_before → $tl_after"
    fi
  fi
fi

# ─────────────────────────── 汇总 ───────────────────────────

hdr "L4b 汇总"
{
  echo "run=$RUN_TS topic=$TOPIC db=$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
  sqltable "SELECT case_id, stage, status, cancel_reason, current_step, total_steps, created_at
              FROM t_contact_plan WHERE case_id IN (${CASE_CSV}) ORDER BY case_id, id"
} | tee -a "$LOG_FILE"

echo
echo "PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
echo "日志：$LOG_FILE ；应用日志：logs/run/admin.log"
[ "$FAIL" -eq 0 ] || exit 1
[ "$SKIP" -eq 0 ] || exit 3
