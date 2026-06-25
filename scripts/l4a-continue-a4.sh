#!/usr/bin/env bash
# L4a 续跑：从 A4 开始（A1-A3 已完成，跳过）
set -uo pipefail

HOST="${HOST:-http://localhost:8888}"
MOCK="$HOST/mock"
PLANS="$HOST/plans"
RUN_B="${RUN_B:-1}"
RUN_C="${RUN_C:-1}"

pp() { if command -v jq >/dev/null 2>&1; then jq .; elif command -v python3 >/dev/null 2>&1; then python3 -m json.tool; else cat; fi; }
line() { printf '%.0s-' {1..78}; echo; }
hdr()  { echo; line; echo "### $1"; line; }

post() {
  echo ">> POST $1"
  curl -s -X POST "$1" | pp
}

show() {
  local cid="$1" uid="${2:-$1}"
  echo ">> 活跃计划 /plans/active/by-case/$cid"
  curl -s "$PLANS/active/by-case/$cid" | pp
  echo ">> 触达时间线 /plans/timeline/$uid?limit=20"
  curl -s "$PLANS/timeline/$uid?limit=20" | pp
}

wait_timeline() {
  local uid="$1" want="$2" max="$3" baseline="${4:-0}" elapsed=0 cnt=0
  while [ "$elapsed" -lt "$max" ]; do
    local raw
    raw=$(curl -s "$PLANS/timeline/$uid?limit=50" || echo "[]")
    if command -v python3 >/dev/null 2>&1; then
      cnt=$(echo "$raw" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
    elif command -v jq >/dev/null 2>&1; then
      cnt=$(echo "$raw" | jq 'length' 2>/dev/null || echo 0)
    else
      cnt=$(echo "$raw" | grep -c '"channel"' || echo 0)
    fi
    cnt=$(echo "$cnt" | tr -d ' ')
    local new_cnt=$(( cnt - baseline ))
    [ "$new_cnt" -lt 0 ] && new_cnt=0
    echo "   [$elapsed s] timeline 新增=$new_cnt（总=$cnt，baseline=$baseline，期望新增≥$want）"
    if [ "$new_cnt" -ge "$want" ]; then echo "   ✓ 达到期望新增记录数"; return 0; fi
    sleep 10
    elapsed=$((elapsed + 10))
  done
  echo "   ⚠ 超时（$max s）未达期望新增数"
  return 1
}

timeline_count() {
  local uid="$1" raw cnt
  raw=$(curl -s "$PLANS/timeline/$uid?limit=50" || echo "[]")
  if command -v python3 >/dev/null 2>&1; then
    cnt=$(echo "$raw" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null || echo 0)
  else
    cnt=$(echo "$raw" | grep -c '"channel"' || echo 0)
  fi
  echo "$cnt" | tr -d ' '
}

hdr "0. 续跑健康检查（从 A4 开始）"
code=$(curl -s -o /dev/null -w "%{http_code}" "$PLANS/active/by-case/1" || echo "000")
if [ "$code" = "000" ]; then
  echo "✗ 无法连接 $HOST"
  exit 1
fi
echo "✓ App 可达：$HOST"
START_TS=$(date +%s)

# ---- A4–A7 ----------------------------------------------------------------
hdr "A4  Email 真发里程碑（92001 S0 → S0_DUE_TODAY_EMAIL → 126 邮箱）"
base=$(timeline_count 92001)
post "$MOCK/ingest?caseId=92001&userId=92001&stage=S0"
wait_timeline 92001 1 120 "$base" || true
show 92001

hdr "A5  还款取消（94102）"
sleep 1
base=$(timeline_count 94102)
post "$MOCK/ingest?caseId=94102&userId=94102&stage=S1"
wait_timeline 94102 1 60 "$base" || true
echo ">> 触发还款"
post "$MOCK/repayment?userId=94102&caseId=94102"
sleep 5
show 94102

hdr "A6  阶段变更（94103：S1 → S2）"
sleep 1
post "$MOCK/ingest?caseId=94103&userId=94103&stage=S1"
sleep 15
echo ">> 触发阶段变更 S1→S2"
post "$MOCK/stage-changed?caseId=94103&stage=S2"
sleep 8
show 94103

hdr "A7  CEASED 停催（94100）"
sleep 1
post "$MOCK/ingest?caseId=94100&userId=94100&stage=S1"
sleep 10
post "$MOCK/case-ceased?caseId=94100"
sleep 5
show 94100

# ---- B 组 -----------------------------------------------------------------
if [ "$RUN_B" = "1" ]; then

hdr "B1  S0 计划（90100）"
sleep 1
base=$(timeline_count 90100)
post "$MOCK/ingest?caseId=90100&userId=90100&stage=S0"
wait_timeline 90100 1 60 "$base" || true
show 90100

hdr "B2  S1（已由 A1 覆盖，跳过）"
echo "   参见 A1 timeline（94101）"

hdr "B3  S2 FIRM（90007）"
sleep 1
base=$(timeline_count 90007)
post "$MOCK/ingest?caseId=90007&userId=90007&stage=S2"
wait_timeline 90007 3 300 "$base" || true
show 90007

hdr "B4  S3（93301）"
sleep 1
base=$(timeline_count 93301)
post "$MOCK/ingest?caseId=93301&userId=93301&stage=S3"
wait_timeline 93301 2 240 "$base" || true
show 93301

hdr "B5  S4（93401）"
sleep 1
base=$(timeline_count 93401)
post "$MOCK/ingest?caseId=93401&userId=93401&stage=S4"
wait_timeline 93401 2 180 "$base" || true
show 93401

fi

# ---- C 组 -----------------------------------------------------------------
if [ "$RUN_C" = "1" ]; then

hdr "C1  幂等（94101 重复 ingest）"
sleep 1
post "$MOCK/ingest?caseId=94101&userId=94101&stage=S1"
sleep 2
post "$MOCK/ingest?caseId=94101&userId=94101&stage=S1"
sleep 3
curl -s "$PLANS/active/by-case/94101" | pp

hdr "C2  CEASED 拒建（90091）"
sleep 1
post "$MOCK/ingest?caseId=90091&userId=90091&stage=S4"
sleep 4
curl -s "$PLANS/active/by-case/90091" | pp

hdr "C3  冻结 PreFlight（90008）"
sleep 1
base=$(timeline_count 90008)
post "$MOCK/ingest?caseId=90008&userId=90008&stage=S2"
wait_timeline 90008 0 30 "$base" || true
show 90008

fi

END_TS=$(date +%s)
hdr "续跑结束 —— 耗时 $(( (END_TS - START_TS) / 60 )) 分 $(( (END_TS - START_TS) % 60 )) 秒（A4→C3）"
