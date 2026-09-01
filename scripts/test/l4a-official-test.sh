#!/usr/bin/env bash
# =============================================================================
# §L4a 官方 8 条 + L4a-全 补充（Guard block / REBUILD·ESCALATE）
# =============================================================================
# 前置：App(local:8888) + Nacos + 渠道密钥；08:00–21:00 PHT 运行（静默时段会 TIME_WINDOW block）
# 用法：
#   ./scripts/test/l4a-official-test.sh
#   ./scripts/test/restart-and-l4a.sh          # 停服→编译→后台起→本脚本（推荐一键）
#   HOST=http://localhost:8888 ./scripts/test/l4a-official-test.sh
#   L4A_ONLY=3,guard ./scripts/test/l4a-official-test.sh   # 只跑 L4a-3 + Guard 段
# =============================================================================
set -uo pipefail

HOST="${HOST:-http://localhost:8888}"
MOCK="$HOST/mock"
PLANS="$HOST/plans"
CASE_THREE=94999
CASE_OBS=94102
CASE_REPAY=94101
CASE_STAGE=94101
CASE_CEASE=94101
CASE_IDEM=92002
CASE_GMAIL=95001
CASE_GUARD=94801
CASE_GUARD_FREQ=94805
CASE_REBUILD=94804
FAIL=0
PASS=0

# Intel Homebrew Python cannot run on Apple Silicon without Rosetta. Prefer the
# system runtime when the inherited python3 command is not executable.
if ! python3 -c 'import sys' >/dev/null 2>&1 && /usr/bin/python3 -c 'import sys' >/dev/null 2>&1; then
  export PATH="/usr/bin:$PATH"
fi

pp() { if command -v jq >/dev/null 2>&1; then jq .; elif command -v python3 >/dev/null 2>&1; then python3 -m json.tool; else cat; fi; }
line() { printf '%.0s-' {1..78}; echo; }
hdr()  { echo; line; echo "### $1"; line; }
pass() { PASS=$((PASS + 1)); echo "   ✓ $1"; }
fail() { FAIL=$((FAIL + 1)); echo "   ✗ $1"; }

post() { curl -s -X POST "$1"; }
get()  { curl -s "$1"; }

# 最近计划中查找指定 cancelReason
assert_cancel() {
  local cid="$1" want="$2" label="$3"
  local raw
  raw=$(get "$PLANS/by-case/$cid/history?limit=5")
  if command -v python3 >/dev/null 2>&1; then
    if echo "$raw" | python3 -c "
import json,sys
want=sys.argv[1]
plans=json.load(sys.stdin)
for p in plans:
    if p.get('status')=='PLAN_CANCELLED' and p.get('cancelReason')==want:
        sys.exit(0)
sys.exit(1)
" "$want" 2>/dev/null; then
      pass "$label cancelReason=$want"
    else
      fail "$label 未找到 PLAN_CANCELLED/$want — history=$(echo "$raw" | python3 -c 'import json,sys; print([(p.get(\"status\"),p.get(\"cancelReason\")) for p in json.load(sys.stdin)][:3])' 2>/dev/null || echo '?')"
    fi
  else
    if echo "$raw" | grep -q "\"cancelReason\": \"$want\""; then
      pass "$label cancelReason=$want"
    else
      fail "$label 未找到 cancelReason=$want"
    fi
  fi
}

assert_active_count() {
  local cid="$1" want="$2" label="$3"
  local n
  n=$(get "$PLANS/active/by-case/$cid" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo -1)
  if [ "$n" = "$want" ]; then pass "$label 活跃计划数=$want"; else fail "$label 活跃计划数=${n} (expect ${want})"; fi
}

wait_active_count() {
  local cid="$1" want="$2" max="$3"
  local elapsed=0 n
  while [ "$elapsed" -lt "$max" ]; do
    n=$(get "$PLANS/active/by-case/$cid" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo -1)
    [ "$n" = "$want" ] && return 0
    sleep 5; elapsed=$((elapsed + 5))
  done
  return 1
}

has_cancel() {
  local cid="$1" want="$2"
  get "$PLANS/by-case/$cid/history?limit=10" | python3 -c "
import json,sys
want=sys.argv[1]
sys.exit(0 if any(p.get('status') == 'PLAN_CANCELLED' and p.get('cancelReason') == want for p in json.load(sys.stdin)) else 1)
" "$want"
}

wait_cancel() {
  local cid="$1" want="$2" max="$3"
  local elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    has_cancel "$cid" "$want" && return 0
    sleep 5; elapsed=$((elapsed + 5))
  done
  return 1
}

wait_timeline_new() {
  local uid="$1" want="$2" max="$3" baseline="${4:-0}"
  local elapsed=0 cnt=0 new_cnt=0
  while [ "$elapsed" -lt "$max" ]; do
    cnt=$(get "$PLANS/timeline/$uid?limit=50" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
    new_cnt=$(( cnt - baseline ))
    [ "$new_cnt" -lt 0 ] && new_cnt=0
    echo "   [${elapsed}s] timeline +${new_cnt} (want>=${want})"
    if [ "$new_cnt" -ge "$want" ]; then return 0; fi
    sleep 10; elapsed=$((elapsed + 10))
  done
  return 1
}

wait_timeline_channel() {
  local uid="$1" channel="$2" max="$3"
  local elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    if get "$PLANS/timeline/$uid?limit=50" | python3 -c "
import json,sys
channel=sys.argv[1]
sys.exit(0 if any(row.get('channel') == channel for row in json.load(sys.stdin)) else 1)
" "$channel"; then
      return 0
    fi
    echo "   [${elapsed}s] case=${uid} 尚无 ${channel} timeline"
    sleep 10; elapsed=$((elapsed + 10))
  done
  return 1
}

assert_email_preview_slot() {
  local cid="$1" stage="$2" expected_slot="$3"
  post "$MOCK/send-email?caseId=$cid&dryRun=true" | python3 -c "
import json,sys
stage,expected=sys.argv[1],sys.argv[2]
actual=json.load(sys.stdin)
ok=(actual.get('ok') is True
    and actual.get('dryRun') is True
    and actual.get('stage') == stage
    and actual.get('scriptSlot') == expected)
print('preview:', actual.get('stage'), actual.get('scriptSlot'))
sys.exit(0 if ok else 1)
" "$stage" "$expected_slot"
}

has_stage_channel() {
  local cid="$1" stage="$2" channel="$3"
  HOST="$HOST" CASE_ID="$cid" EXPECTED_STAGE="$stage" EXPECTED_CHANNEL="$channel" python3 <<'PY'
import json, os, sys, urllib.request

host = os.environ["HOST"]
case_id = os.environ["CASE_ID"]
expected_stage = os.environ["EXPECTED_STAGE"]
expected_channel = os.environ["EXPECTED_CHANNEL"]
plans = json.loads(
    urllib.request.urlopen(f"{host}/plans/by-case/{case_id}/history?limit=10").read()
)
stage_plan_ids = {plan["id"] for plan in plans if plan.get("stage") == expected_stage}
timeline = json.loads(
    urllib.request.urlopen(f"{host}/plans/timeline/{case_id}?limit=50").read()
)
records = [
    row for row in timeline
    if row.get("planId") in stage_plan_ids
    and row.get("channel") == expected_channel
    and row.get("providerMsgId")
]
if records:
    print(
        f"case={case_id} stage={expected_stage} "
        f"channel={expected_channel} templateIds={[row.get('templateId') for row in records]} "
        f"providerMsgIds={[row.get('providerMsgId') for row in records]}"
    )
    sys.exit(0)
sys.exit(1)
PY
}

wait_stage_channel() {
  local cid="$1" stage="$2" channel="$3" max="$4"
  local elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    if has_stage_channel "$cid" "$stage" "$channel"; then
      return 0
    fi
    echo "   [${elapsed}s] case=${cid} stage=${stage} 尚无带 providerMsgId 的 ${channel} timeline"
    sleep 10; elapsed=$((elapsed + 10))
  done
  return 1
}

wait_guard_frequency() {
  local cid="$1" max="$2"
  local elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    local pid
    pid=$(get "$PLANS/by-case/$cid/history?limit=1" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['id'] if ps else '')" 2>/dev/null)
    if [ -n "$pid" ] && get "$PLANS/$pid/steps" | python3 -c "
import json,sys
steps=sorted(json.load(sys.stdin), key=lambda step: step.get('stepOrder', 0))
sys.exit(0 if len(steps) >= 2 and steps[0].get('status') != 'SKIPPED' and steps[1].get('status') == 'SKIPPED' else 1)
"; then
      return 0
    fi
    echo "   [${elapsed}s] case=${cid} 第二步尚未进入 FREQUENCY SKIPPED"
    sleep 10; elapsed=$((elapsed + 10))
  done
  return 1
}

wait_plan_status() {
  local cid="$1" want="$2" max="$3"
  local elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    local st
    st=$(get "$PLANS/by-case/$cid/history?limit=3" | python3 -c "
import json,sys
want=sys.argv[1]
for p in json.load(sys.stdin):
    if not p.get('terminal') and p.get('status')==want:
        print(want); sys.exit(0)
    if p.get('status')==want:
        print(want); sys.exit(0)
sys.exit(1)
" "$want" 2>/dev/null || echo "")
    if [ "$st" = "$want" ]; then echo "   plan status=$want @ ${elapsed}s"; return 0; fi
    sleep 5; elapsed=$((elapsed + 5))
  done
  return 1
}

timeline_baseline() {
  get "$PLANS/timeline/$1?limit=50" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0
}

# 轮询 plan 历史直到 PLAN_COMPLETED 数量达标（REBUILD 链）
wait_rebuild_completed() {
  local cid="$1" want="$2" max="$3"
  local elapsed=0
  while [ "$elapsed" -lt "$max" ]; do
    local n
    n=$(get "$PLANS/by-case/$cid/history?limit=10" | python3 -c "
import json,sys
print(sum(1 for p in json.load(sys.stdin) if p.get('status')=='PLAN_COMPLETED'))
" 2>/dev/null || echo 0)
    echo "   [${elapsed}s] PLAN_COMPLETED count=${n} (want>=${want})"
    if [ "$n" -ge "$want" ]; then return 0; fi
    sleep 10
    elapsed=$((elapsed + 10))
  done
  return 1
}

should_run() {
  local id="$1"
  if [ -z "${L4A_ONLY:-}" ]; then return 0; fi
  echo ",$L4A_ONLY," | grep -q ",$id," 
}

# ---- 健康检查 ----------------------------------------------------------------
hdr "0. 健康检查"
if ! curl -s -o /dev/null -w "%{http_code}" "$PLANS/active/by-case/1" | grep -qE '^[23]'; then
  echo "✗ 无法连接 $HOST"; exit 1
fi
pass "App 可达 $HOST"
# L4A_SKIP_RESET=1 保留上一轮现场，用于只复跑收口断言取证（清库会把待查的异常行一起删掉）。
if [ "${L4A_SKIP_RESET:-0}" = "1" ]; then
  echo "   ! L4A_SKIP_RESET=1，跳过清理（仅取证用，逐条用例结果不可信）"
elif post "$MOCK/reset-l4a" | pp; then
  pass "L4a 固定案例运行数据已清理"
else
  echo "✗ 无法清理 L4a 固定案例运行数据"; exit 1
fi

if should_run 1; then
# =============================================================================
# L4a-1 三渠道 legacy-three-step（SMS→PUSH→EMAIL）
# =============================================================================
hdr "L4a-1 三渠道顺序 (case=$CASE_THREE legacyThreeStep=true)"
bl=$(timeline_baseline "$CASE_THREE")
post "$MOCK/ingest?caseId=$CASE_THREE&userId=$CASE_THREE&stage=S1&legacyThreeStep=true" | pp
wait_timeline_new "$CASE_THREE" 3 300 "$bl" && pass "L4a-1 三渠道 timeline≥3" || fail "L4a-1 timeline 未达 3 条"
get "$PLANS/timeline/$CASE_THREE?limit=10" | python3 -c "
import json,sys
rows=json.load(sys.stdin)
chs=set(r.get('channel') for r in rows[:6])
need={'SMS','PUSH','EMAIL'}
if need.issubset(chs):
    print('channels OK:', sorted(chs)); sys.exit(0)
print('missing channels, got', chs); sys.exit(1)
" && pass "L4a-1 SMS+PUSH+EMAIL 均出现" || fail "L4a-1 渠道不全"
sleep 1
fi

if should_run 2; then
# =============================================================================
# L4a-2 PUSH fallback
# =============================================================================
hdr "L4a-2 PUSH 无 token -> SMS fallback (94201)"
bl=$(timeline_baseline 94201)
post "$MOCK/ingest?caseId=94201&userId=94201&stage=S1" | pp
wait_timeline_new 94201 1 180 "$bl" && pass "L4a-2 fallback SMS 触达" || fail "L4a-2 无 timeline"
sleep 1
fi

if should_run 3; then
# =============================================================================
# L4a-3 还款取消 REPAID（94101）
# =============================================================================
hdr "L4a-3 还款取消 REPAID (case=$CASE_REPAY)"
post "$MOCK/reset-l4a" >/dev/null
post "$MOCK/ingest?caseId=$CASE_REPAY&userId=$CASE_REPAY&stage=S1" | pp
wait_active_count "$CASE_REPAY" 1 45 || fail "L4a-3 初始计划未在时限内创建"
post "$MOCK/repayment?userId=$CASE_REPAY&caseId=$CASE_REPAY" | pp
wait_active_count "$CASE_REPAY" 0 30 && pass "L4a-3 活跃计划数=0" || fail "L4a-3 活跃计划未取消"
wait_cancel "$CASE_REPAY" "REPAID" 30 && pass "L4a-3 cancelReason=REPAID" || assert_cancel "$CASE_REPAY" "REPAID" "L4a-3"
sleep 1
fi

if should_run 3b; then
# =============================================================================
# L4a-3b 部分还款只刷余额（94101）
# =============================================================================
# 与 L4a-3 的分水岭：结清取消计划，部分还款只能改快照里的余额。
#
# 不变量只取「部分还款不该碰」的维度：计划身份（同一个 plan 仍在活跃列表里，即没被取消重建）、
# stage、模板、总步数、步骤的 id 与渠道。**不含 status / currentStep / 步骤 status**——
# 计划建好后调度器就在独立推进它，这些字段本来就会自己往前走，拿它们当不变量只会测出调度器在工作。
hdr "L4a-3b 部分还款运行态刷新 (case=$CASE_REPAY)"
post "$MOCK/reset-l4a" >/dev/null
post "$MOCK/ingest?caseId=$CASE_REPAY&userId=$CASE_REPAY&stage=S1" | pp
wait_active_count "$CASE_REPAY" 1 45 || fail "L4a-3b 初始计划未在时限内创建"

plan_identity() {
  get "$PLANS/active/by-case/$CASE_REPAY" | python3 -c "
import json,sys
p=json.load(sys.stdin)
if not p:
    print('NO_ACTIVE_PLAN'); sys.exit(0)
p=p[0]
print('%s|%s|%s|%s' % (p['id'], p.get('stage'), p.get('planTemplateId'), p.get('totalSteps')))
"
}
plan_amount() {
  get "$PLANS/active/by-case/$CASE_REPAY" | python3 -c "
import json,sys
p=json.load(sys.stdin)
if not p: sys.exit(0)
cc=(json.loads(p[0].get('contextSnapshot') or '{}').get('caseContext') or {})
v=cc.get('totalOutstanding')
print('' if v is None else '%.2f' % float(v))
" 2>/dev/null
}
step_shape() {
  get "$PLANS/$1/steps" | python3 -c "
import json,sys
print(','.join('%s:%s' % (s.get('id'), s.get('channelType')) for s in json.load(sys.stdin)))
"
}

before_key="$(plan_identity)"
plan_id="$(echo "$before_key" | cut -d'|' -f1)"
before_amount="$(plan_amount)"
before_steps="$(step_shape "$plan_id")"
echo "   基线 plan=$before_key totalOutstanding=$before_amount steps=$before_steps"

post "$MOCK/balance-updated?caseId=$CASE_REPAY&userId=$CASE_REPAY&dpd=2&overdueAmount=800.00&totalOutstanding=800.00&penaltyAmount=50.00&upcomingAmount=200.00&collectionStatus=IN_COLLECTION" | pp

refreshed=0
for _ in $(seq 1 15); do
  now_amount="$(plan_amount)"
  [ "$now_amount" = "800.00" ] && { refreshed=1; break; }
  sleep 2
done
if [ "$refreshed" = "1" ]; then
  pass "L4a-3b 快照余额已刷新（$before_amount → 800.00）"
else
  fail "L4a-3b 快照余额未刷新（当前 ${now_amount}，期望 800.00）"
fi

after_key="$(plan_identity)"
if [ "$after_key" = "$before_key" ]; then
  pass "L4a-3b 计划身份/stage/模板/总步数均未变（${after_key}）"
else
  fail "L4a-3b 部分还款改动了计划：$before_key → $after_key"
fi

after_steps="$(step_shape "$plan_id")"
if [ "$after_steps" = "$before_steps" ]; then
  pass "L4a-3b 步骤集合与渠道未变"
else
  fail "L4a-3b 步骤被改写：$before_steps → $after_steps"
fi
sleep 1
fi

if should_run 4; then
# =============================================================================
# L4a-4 STAGE_UPGRADE（94101）
# =============================================================================
hdr "L4a-4 阶段升档 STAGE_UPGRADE (case=$CASE_STAGE)"
post "$MOCK/reset-l4a" >/dev/null
post "$MOCK/ingest?caseId=$CASE_STAGE&userId=$CASE_STAGE&stage=S1" | pp
wait_active_count "$CASE_STAGE" 1 45 || fail "L4a-4 初始计划未在时限内创建"
post "$MOCK/stage-changed?caseId=$CASE_STAGE&stage=S2" | pp
wait_cancel "$CASE_STAGE" "STAGE_UPGRADE" 45 && pass "L4a-4 cancelReason=STAGE_UPGRADE" || assert_cancel "$CASE_STAGE" "STAGE_UPGRADE" "L4a-4"
get "$PLANS/by-case/$CASE_STAGE/history?limit=10" | python3 -c "
import json,sys
plans=json.load(sys.stdin)
sys.exit(0 if any(p.get('stage') == 'S2' for p in plans) else 1)
" && pass "L4a-4 已创建 stage=S2 计划" || fail "L4a-4 未找到 stage=S2 新计划"
sleep 1
fi

if should_run 5; then
# =============================================================================
# L4a-5 CEASED（94101）
# =============================================================================
hdr "L4a-5 CASE_CEASED (case=$CASE_CEASE)"
post "$MOCK/reset-l4a" >/dev/null
post "$MOCK/ingest?caseId=$CASE_CEASE&userId=$CASE_CEASE&stage=S1" | pp
wait_active_count "$CASE_CEASE" 1 45 || fail "L4a-5 初始计划未在时限内创建"
post "$MOCK/case-ceased?caseId=$CASE_CEASE" | pp
wait_active_count "$CASE_CEASE" 0 30 && pass "L4a-5 活跃计划数=0" || fail "L4a-5 活跃计划未取消"
wait_cancel "$CASE_CEASE" "CEASED" 30 && pass "L4a-5 cancelReason=CEASED" || assert_cancel "$CASE_CEASE" "CEASED" "L4a-5"
sleep 1
fi

if should_run 6; then
# =============================================================================
# L4a-6 SMS 同步完成（94102）
# =============================================================================
hdr "L4a-6 SMS 同步完成 (case=$CASE_OBS)"
post "$MOCK/ingest?caseId=$CASE_OBS&userId=$CASE_OBS&stage=S1" | pp
wait_plan_status "$CASE_OBS" "PLAN_COMPLETED" 30 && pass "L4a-6 同步完成 PLAN_COMPLETED" || fail "L4a-6 未完成"
pid6=$(get "$PLANS/by-case/$CASE_OBS/history?limit=1" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['id'] if ps else '')" 2>/dev/null)
if [ -n "$pid6" ]; then
  get "$PLANS/$pid6/steps" | python3 -c "
import json,sys
steps=json.load(sys.stdin)
w=bool(steps) and all(s.get('status') == 'COMPLETED' for s in steps)
waiting=any(s.get('status') == 'WAITING' or s.get('observationMinutes',0) != 0 for s in steps)
sys.exit(0 if w and not waiting else 1)
" && pass "L4a-6 步骤均 COMPLETED，未进入 WAITING" || fail "L4a-6 步骤状态或观察期不符"
fi
sleep 1
fi

if should_run 7; then
# =============================================================================
# L4a-7 幂等（92002；不复用 L4a-8 的 S0 Email case）
# =============================================================================
hdr "L4a-7 重复 ingest 幂等 (case=$CASE_IDEM)"
bl=$(timeline_baseline "$CASE_IDEM")
post "$MOCK/ingest?caseId=$CASE_IDEM&userId=$CASE_IDEM&stage=S1" | pp
sleep 3
post "$MOCK/ingest?caseId=$CASE_IDEM&userId=$CASE_IDEM&stage=S1" | pp
sleep 3
new_cnt=$(($(timeline_baseline "$CASE_IDEM") - bl))
[ "$new_cnt" -le 4 ] && pass "L4a-7 重复 ingest timeline +${new_cnt} (<=4 幂等)" || fail "L4a-7 timeline 新增过多=${new_cnt}"
assert_active_count "$CASE_IDEM" 1 "L4a-7"
sleep 1
fi

if should_run 8; then
# =============================================================================
# L4a-8 Email scriptSlot × stage（126 vs Gmail）
# =============================================================================
hdr "L4a-8 scriptSlot x stage + 126 vs Gmail"
for item in "92001:S0:S0_DUE_TODAY_EMAIL" "93101:S1:S1_EMAIL_OVERDUE_NOTICE" "93201:S2:S2_EMAIL_ENTRY"; do
  cid="${item%%:*}"
  remainder="${item#*:}"
  st="${remainder%%:*}"
  expected_slot="${remainder#*:}"
  assert_email_preview_slot "$cid" "$st" "$expected_slot" \
    && pass "L4a-8 case=$cid stage=$st preview scriptSlot=$expected_slot" \
    || fail "L4a-8 case=$cid stage=$st preview scriptSlot 不符"
  post "$MOCK/ingest?caseId=$cid&userId=$cid&stage=$st" | pp
done
post "$MOCK/ingest?caseId=$CASE_GMAIL&userId=$CASE_GMAIL&stage=S0" | pp
wait_stage_channel 92001 S0 SMS 120 \
  && pass "L4a-8 case=92001 stage=S0 SMS 已由渠道受理（S0 模板不含 Email 步骤）" \
  || fail "L4a-8 case=92001 stage=S0 SMS 未在时限内被渠道受理"
for pair in "93101:S1" "93201:S2"; do
  cid="${pair%%:*}"; st="${pair##*:}"
  wait_stage_channel "$cid" "$st" EMAIL 210 \
    && pass "L4a-8 case=$cid stage=$st EMAIL 已由渠道受理" \
    || fail "L4a-8 case=$cid stage=$st EMAIL 未在时限内被渠道受理"
done
get "$PLANS/timeline/$CASE_GMAIL?limit=3" | python3 -c "
import json,sys
rows=json.load(sys.stdin)
print('Gmail rows:', len(rows))
sys.exit(0 if rows else 1)
" && pass "L4a-8 Gmail($CASE_GMAIL) 有 timeline" || fail "L4a-8 Gmail 无触达 (DMARC 风险可人工查收件箱)"
sleep 1
fi

if should_run guard; then
# =============================================================================
# L4a-全-A: Guard NO_PHONE -> SKIPPED (94801)
# =============================================================================
hdr "L4a-全 Guard NO_PHONE -> SKIPPED (case=$CASE_GUARD)"
post "$MOCK/ingest?caseId=$CASE_GUARD&userId=$CASE_GUARD&stage=S1" | pp
sleep 20
pid=$(get "$PLANS/by-case/$CASE_GUARD/history?limit=1" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['id'] if ps else '')" 2>/dev/null)
if [ -n "$pid" ]; then
  get "$PLANS/$pid/steps" | python3 -c "
import json,sys
steps=json.load(sys.stdin)
if steps and steps[0].get('status')=='SKIPPED':
    sys.exit(0)
print('step status:', steps[0].get('status') if steps else None); sys.exit(1)
" && pass "L4a-全 Guard NO_PHONE SKIPPED" || fail "L4a-全 NO_PHONE 步骤非 SKIPPED"
else
  fail "L4a-全 NO_PHONE 无计划"
fi
sleep 1

# =============================================================================
# L4a-全-B: Guard FREQUENCY_LIMIT (94805 两步 SMS)
# =============================================================================
hdr "L4a-全 Guard FREQUENCY -> SKIPPED (case=$CASE_GUARD_FREQ)"
post "$MOCK/ingest?caseId=$CASE_GUARD_FREQ&userId=$CASE_GUARD_FREQ&stage=S1" | pp
wait_guard_frequency "$CASE_GUARD_FREQ" 120 \
  && pass "L4a-全 Guard FREQUENCY 第二步 SKIPPED" \
  || fail "L4a-全 FREQUENCY 第二步未在时限内 SKIPPED"
sleep 1
fi

if should_run rebuild; then
# =============================================================================
# L4a-全-C: REBUILD -> ESCALATE (94804)
# =============================================================================
hdr "L4a-全 REBUILD/ESCALATE (case=$CASE_REBUILD)"
post "$MOCK/ingest?caseId=$CASE_REBUILD&userId=$CASE_REBUILD&stage=S1" | pp
wait_rebuild_completed "$CASE_REBUILD" 2 180 && pass "L4a-全 REBUILD 至少 2 个 PLAN_COMPLETED" || fail "L4a-全 REBUILD 未观测到续建"
get "$PLANS/by-case/$CASE_REBUILD/history?limit=8" | pp
st=$(get "$PLANS/active/by-case/$CASE_REBUILD" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['stage'] if ps else '')" 2>/dev/null)
[ "$st" = "S2" ] && pass "L4a-全 ESCALATE 后活跃计划 stage=S2" || echo "   WARN ESCALATE stage=$st (查日志 [exhausted] ESCALATE)"
fi

# =============================================================================
# 收口不变式：逐条用例全绿也可能留下不收敛的步骤行（2026-08-21 实测：官方 25 项全过，
# 库里仍有 3 行 status=EXECUTING 但 result/completed_at 已写、两个时间列均为 NULL 的步骤）。
# 这两条断言按"跑完之后全局扫一遍"取证，与单条用例的成功判定互补。
# =============================================================================
hdr "L4a-收口 步骤行收敛性"
# 采样全部涉案计划的步骤行，输出稳定排序的 "plan step status result completedAt triggerTime timeoutTime"。
snapshot_steps() {
  for cid in $CASE_THREE $CASE_OBS $CASE_REPAY $CASE_IDEM $CASE_GMAIL \
             $CASE_GUARD $CASE_GUARD_FREQ $CASE_REBUILD 92001 93101 93201; do
    for pid in $(get "$PLANS/by-case/$cid/history?limit=8" \
          | python3 -c "import json,sys;print(' '.join(str(p['id']) for p in json.load(sys.stdin)))" 2>/dev/null); do
      get "$PLANS/$pid/steps" | python3 -c "
import json,sys
plan=sys.argv[1]
for s in json.load(sys.stdin):
    print('%s %s %s %s %s %s %s' % (plan, s.get('id'), s.get('status'), s.get('result'),
          s.get('completedAt'), s.get('triggerTime'), s.get('timeoutTime')))
" "$pid" 2>/dev/null
    done
  done | sort
}

# 收口断言：先等系统静默，再判定。
#
# 必须等静默的原因：步骤在 markExecuting（清空 trigger_time）与终态写入之间，**合法地**处于
# EXECUTING 且 trigger_time / timeout_time 两列皆空。在这个派发窗口内采样，会把健康步骤误判为悬挂
# ——2026-08-21 实测 step 1330 被判 UNREACHABLE，而它在断言后 0.3 秒即收敛为 FAILED。
# 静默判据用「连续两次采样完全一致」而非「距上次写入 N 秒」，因为 /steps 接口不暴露 updated_at。
assert_step_convergence() {
  local grace="$1" settle="${2:-20}" waited=0 prev cur settled=0
  cur=$(snapshot_steps)
  while [ "$waited" -lt "$grace" ]; do
    sleep "$settle"; waited=$((waited + settle))
    prev="$cur"; cur=$(snapshot_steps)
    if [ "$prev" = "$cur" ]; then settled=1; break; fi
  done
  local out
  out=$(echo "$cur" | python3 -c "
import sys
for line in sys.stdin:
    f=line.split()
    if len(f) != 7: continue
    plan, step, st, result, completed, trigger, timeout = f
    none=('None','null','')
    # 非终态却已写完成时刻：状态与完成时刻由同一条 UPDATE 写入，静默后仍矛盾即有第二个写入方。
    if st in ('PENDING','EXECUTING') and completed not in none:
        print('CONTRADICT plan=%s step=%s status=%s completedAt=%s result=%s' % (plan, step, st, completed, result))
    # 两个扫描都摸不到：due 扫 trigger_time、timeout 扫 timeout_time，静默后均空即永久悬挂。
    elif st == 'EXECUTING' and trigger in none and timeout in none:
        print('UNREACHABLE plan=%s step=%s result=%s' % (plan, step, result))
")
  if [ "$settled" -eq 0 ]; then
    fail "L4a-收口 ${grace}s 内步骤行仍在变化，未达静默，判定不可信"
    echo "$out"
    return
  fi
  if [ -z "$out" ]; then
    pass "L4a-收口 静默后无悬挂步骤（非终态未写 completed_at、EXECUTING 至少可被一种扫描拾取）"
  else
    echo "$out"
    fail "L4a-收口 静默后仍有不收敛步骤行（$(echo "$out" | wc -l | tr -d ' ') 行）"
  fi
}
assert_step_convergence 120

# ---- 汇总 --------------------------------------------------------------------
hdr "汇总：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
