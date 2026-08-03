#!/usr/bin/env bash
# =============================================================================
# §L4b 官方闭环脚本（L4b-1 … L4b-8）
# =============================================================================
# 定位：真实 PubSub 入案 + 真实旧库 seed + 真实 MySQL 落库 + 渠道沙箱。
#       与 l4a-official-test.sh 的差别：入口不是 /mock/ingest，而是真实 topic；
#       裁决不看 REST 预览，而看 t_contact_plan / _step / _timeline 落库事实。
#
# 前置（缺一不可，均由 l4b-preflight.sh --strict 校验）：
#   1) 运维已建 collection-cases-test1 + collection-cases-test1-sub，且**独占消费**；
#   2) Nacos 已指向测试订阅、白名单 99000000–99000005、渠道沙箱开关已发布；
#   3) 本机 gcloud 有测试 topic publisher 权限，credentials.json 就位；
#   4) 本 shell 已注入 DB_HOST/DB_PORT/DB_USER/DB_PASS/DB_NAME（不入仓）；
#   5) collection-admin 已用 local profile 启动且 /actuator/health 为 UP。
#
# 用法：
#   source scripts/test/l4b-env.local.sh
#   export DB_HOST=... DB_PORT=3306 DB_USER=... DB_PASS=... DB_NAME=ai_collection_db
#   export GCP_PUBSUB_TEST_TOPIC=collection-cases-test1
#   ./scripts/test/l4b-official-test.sh
#   L4B_ONLY=1,5,6 ./scripts/test/l4b-official-test.sh    # 只跑指定用例
#   L4B_RESET=0 ./scripts/test/l4b-official-test.sh       # 保留历史落库（默认清零后取绝对值断言）
#   L4B_SEED=0  ./scripts/test/l4b-official-test.sh       # 不重放 seed（默认随 L4B_RESET 一起重放）
#
# ⚠ 重置只清进程外的 DB 行；messageId / ingested 幂等标记是进程内内存态，
#   必须让应用在本轮之前重启（scripts/dev/restart-local.sh），否则入案会被上轮标记跳过。
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

latest_plan_field() {
  local cid="$1" field="$2"
  sqlv "SELECT IFNULL($field,'') FROM t_contact_plan WHERE case_id=$cid ORDER BY id DESC LIMIT 1"
}

# ─────────────────────────── 前置校验 ───────────────────────────

hdr "L4b 前置校验（run=${RUN_TS})"

[ -n "$TOPIC" ] || die "缺 GCP_PUBSUB_TEST_TOPIC"
[ "$TOPIC" != "collection-cases" ] || die "拒绝：collection-cases 是生产共享 topic"
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
           DELETE FROM t_contact_plan WHERE case_id IN (${CASE_CSV});" >/dev/null \
    || die "重置失败（需要 t_contact_* 删除权限）"
  left="$(sqlv "SELECT COUNT(*) FROM t_contact_plan WHERE case_id IN (${CASE_CSV})")"
  [ "$left" = "0" ] || die "重置后仍残留 $left 个计划"
  echo "   ✓ 落库已清零"
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

  sqlexec "UPDATE t_collection SET overdue_days=95, repayment_date=DATE_SUB(CURDATE(), INTERVAL 95 DAY)
             WHERE loan_id=$CASE_S3 AND id LIKE 'IC_TEST_%'" >/dev/null \
    || fail "L4b-4 无法更新 t_collection（缺 seed 写权限）"

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

  # 造升档条件：S2 案 dpd 4 → 20（跨入 S3 区间[16,30]）
  sqlexec "UPDATE t_collection SET overdue_days=20, repayment_date=DATE_SUB(CURDATE(), INTERVAL 20 DAY)
             WHERE loan_id=$CASE_S2 AND id LIKE 'IC_TEST_%'" >/dev/null \
    || fail "L4b-3 无法更新 t_collection（缺 seed 写权限）"

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
  "dataType": "case_push",
  "messageId": "L4B7-${CASE_S0}-${RUN_TS}",
  "loanID": "${CASE_S0}",
  "userID": "${CASE_S0}",
  "realName": "Test Case S0",
  "appName": "QuickLoan",
  "phone": "+639451374358",
  "email": "wzynju@126.com",
  "jpushToken": "1a0018970bf0c19de04"
}
JSON
    "$PUBLISH" file "$tmp_case" case_push >/dev/null || fail "L4b-7 case_push 发布失败"
    rm -f "$tmp_case"

    # 注入命中后 remaining 归零；未归零说明消息没被消费到
    fired=0
    for _ in $(seq 1 24); do
      [ "$(curl -s "$MOCK/ingestion-fault" | tr -dc '0-9')" = "0" ] && { fired=1; break; }
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
