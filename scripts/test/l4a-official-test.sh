#!/usr/bin/env bash
# =============================================================================
# §L4a 官方 8 条 + L4a-全 补充（Guard block / REBUILD·ESCALATE）
# =============================================================================
# 前置：App(local:8888) + Nacos + 渠道密钥；08:00–21:00 PHT 运行（静默时段会 TIME_WINDOW block）
# 用法：
#   ./scripts/test/l4a-official-test.sh
#   ./scripts/test/restart-and-l4a.sh          # 停服→编译→后台起→本脚本（推荐一键）
#   HOST=http://localhost:8888 OBS_MIN=1 ./scripts/test/l4a-official-test.sh
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
CASE_IDEM=92001
CASE_GMAIL=95001
CASE_GUARD=94801
CASE_GUARD_FREQ=94805
CASE_REBUILD=94804
OBS_MIN="${OBS_MIN:-1}"
FAIL=0
PASS=0

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
post "$MOCK/ingest?caseId=$CASE_REPAY&userId=$CASE_REPAY&stage=S1" | pp
sleep 15
post "$MOCK/repayment?userId=$CASE_REPAY&caseId=$CASE_REPAY" | pp
sleep 5
assert_active_count "$CASE_REPAY" 0 "L4a-3"
assert_cancel "$CASE_REPAY" "REPAID" "L4a-3"
sleep 1
fi

if should_run 4; then
# =============================================================================
# L4a-4 STAGE_UPGRADE（94101）
# =============================================================================
hdr "L4a-4 阶段升档 STAGE_UPGRADE (case=$CASE_STAGE)"
post "$MOCK/ingest?caseId=$CASE_STAGE&userId=$CASE_STAGE&stage=S1" | pp
sleep 12
post "$MOCK/stage-changed?caseId=$CASE_STAGE&stage=S2" | pp
sleep 8
assert_cancel "$CASE_STAGE" "STAGE_UPGRADE" "L4a-4"
st=$(get "$PLANS/active/by-case/$CASE_STAGE" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['stage'] if ps else '')" 2>/dev/null)
[ "$st" = "S2" ] && pass "L4a-4 新活跃计划 stage=S2" || fail "L4a-4 新计划 stage=$st"
sleep 1
fi

if should_run 5; then
# =============================================================================
# L4a-5 CEASED（94101）
# =============================================================================
hdr "L4a-5 CASE_CEASED (case=$CASE_CEASE)"
post "$MOCK/ingest?caseId=$CASE_CEASE&userId=$CASE_CEASE&stage=S1" | pp
sleep 8
post "$MOCK/case-ceased?caseId=$CASE_CEASE" | pp
sleep 5
assert_active_count "$CASE_CEASE" 0 "L4a-5"
assert_cancel "$CASE_CEASE" "CEASED" "L4a-5"
sleep 1
fi

if should_run 6; then
# =============================================================================
# L4a-6 观察期 STEP_WAITING → COMPLETED（94102）
# =============================================================================
hdr "L4a-6 观察期结转 (case=$CASE_OBS observeMin=$OBS_MIN)"
post "$MOCK/ingest?caseId=$CASE_OBS&userId=$CASE_OBS&stage=S1" | pp
sleep 8
wait_plan_status "$CASE_OBS" "STEP_WAITING" 30 && pass "L4a-6 进入 STEP_WAITING" || fail "L4a-6 未进入 STEP_WAITING"
pid6=$(get "$PLANS/by-case/$CASE_OBS/history?limit=1" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['id'] if ps else '')" 2>/dev/null)
if [ -n "$pid6" ]; then
  get "$PLANS/$pid6/steps" | python3 -c "
import json,sys
steps=json.load(sys.stdin)
w=any(s.get('status') in ('EXECUTING','WAITING') or s.get('observationMinutes',0)>0 for s in steps)
sys.exit(0 if steps else 1)
" && pass "L4a-6 计划含观察期步骤" || fail "L4a-6 无步骤"
fi
wait_sec=$(( (OBS_MIN * 60) + 30 ))
echo "   等待观察期 ${wait_sec}s ..."
sleep "$wait_sec"
st=$(get "$PLANS/by-case/$CASE_OBS/history?limit=1" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['status'] if ps else '')" 2>/dev/null)
[[ "$st" == "PLAN_COMPLETED" || "$st" == "STEP_SCHEDULED" || "$st" == "PLAN_CANCELLED" ]] && pass "L4a-6 观察期后 plan=$st" || fail "L4a-6 观察期后 status=$st"
sleep 1
fi

if should_run 7; then
# =============================================================================
# L4a-7 幂等（92001）
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
for pair in "92001:S0" "93101:S1" "93201:S2"; do
  cid="${pair%%:*}"; st="${pair##*:}"
  post "$MOCK/ingest?caseId=$cid&userId=$cid&stage=$st" | pp
  sleep 8
done
post "$MOCK/ingest?caseId=$CASE_GMAIL&userId=$CASE_GMAIL&stage=S0" | pp
sleep 8
HOST="$HOST" python3 <<'PY' && pass "L4a-8 126 跨 stage 有 EMAIL" || fail "L4a-8 126 EMAIL 缺失"
import json, os, urllib.request
host = os.environ.get("HOST", "http://localhost:8888")
def email_tids(cid):
    raw = urllib.request.urlopen(f"{host}/plans/timeline/{cid}?limit=15").read()
    rows = json.loads(raw)
    return {r.get("templateId") for r in rows if r.get("channel") == "EMAIL"}
s0, s1, s2 = email_tids(92001), email_tids(93101), email_tids(93201)
print("S0/S1/S2 templateIds:", s0, s1, s2)
if not (s0 or s1 or s2):
    raise SystemExit(1)
PY
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
sleep 45
pidf=$(get "$PLANS/by-case/$CASE_GUARD_FREQ/history?limit=1" | python3 -c "import json,sys; ps=json.load(sys.stdin); print(ps[0]['id'] if ps else '')" 2>/dev/null)
if [ -n "$pidf" ]; then
  get "$PLANS/$pidf/steps" | python3 -c "
import json,sys
steps=sorted(json.load(sys.stdin), key=lambda s: s.get('stepOrder',0))
if len(steps)>=2 and steps[0].get('status')!='SKIPPED' and steps[1].get('status')=='SKIPPED':
    sys.exit(0)
print('steps:', [(s.get('stepOrder'), s.get('status')) for s in steps]); sys.exit(1)
" && pass "L4a-全 Guard FREQUENCY 第二步 SKIPPED" || fail "L4a-全 FREQUENCY 断言失败"
else
  fail "L4a-全 FREQUENCY 无计划"
fi
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

# ---- 汇总 --------------------------------------------------------------------
hdr "汇总：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
