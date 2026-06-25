#!/usr/bin/env bash
# =============================================================================
# L4a-全 端到端测试脚本（mock 数据源 + 真实渠道）
# =============================================================================
# 前置：
#   1. App 已启动（local profile，端口 8888），Nacos UP，渠道供应商 key 已配
#   2. application-local.yml 已设 channel.debug.single-step="" + channel.plan-templates
#   3. /actuator/beans 确认 A1-A6 为真实 bean（Default*/Configurable*/ChannelGatewayImpl）
#
# ⚠ 真实投递警告：
#   - A 组收件人为团队可控真号/真 token/真邮箱（126），可放心真发。
#   - B 组（stage 覆盖）部分用例的 SMS/PUSH 步骤会打到「合成手机号」(63917xxxxxxx)；
#     仅 EMAIL 步骤投递到真实 126 邮箱。如不希望对合成号真发 SMS，设 RUN_B=0。
#   - ⚠ 若当前为菲律宾静默时段（21:00-08:00 PHT），ConfigurableExecutionGuard 会
#     拦截所有步骤（TIME_WINDOW）→ 全部 SKIPPED。请在 08:00-21:00 PHT 之间运行。
#   - 相邻触发已内置 ≥1s 间隔。
# =============================================================================
set -uo pipefail  # 不用 -e：wait_timeline 超时不应中断整批测试

HOST="${HOST:-http://localhost:8888}"
MOCK="$HOST/mock"
PLANS="$HOST/plans"
RUN_A="${RUN_A:-1}"   # 核心链路（真实可控收件人）
RUN_B="${RUN_B:-1}"   # Stage 覆盖（含合成手机号 SMS）
RUN_C="${RUN_C:-1}"   # 异常/边界

# ---- 工具函数 --------------------------------------------------------------
pp() { if command -v jq >/dev/null 2>&1; then jq .; elif command -v python3 >/dev/null 2>&1; then python3 -m json.tool; else cat; fi; }
line() { printf '%.0s-' {1..78}; echo; }
hdr()  { echo; line; echo "### $1"; line; }

post() { # post <url>
  echo ">> POST $1"
  curl -s -X POST "$1" | pp
}

show() { # show <caseId> <userId>
  local cid="$1" uid="${2:-$1}"
  echo ">> 活跃计划 /plans/active/by-case/$cid"
  curl -s "$PLANS/active/by-case/$cid" | pp
  echo ">> 触达时间线 /plans/timeline/$uid?limit=20"
  curl -s "$PLANS/timeline/$uid?limit=20" | pp
}

# 轮询时间线直到记录数达到期望或超时（ingest 前 baseline，避免历史记录误判）
wait_timeline() { # wait_timeline <userId> <expectMin> <maxSec> [baseline]
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
  echo "   ⚠ 超时（$max s）未达期望新增数，请查日志 [execStep]/[advance]"
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

# ---- 健康检查 --------------------------------------------------------------
hdr "0. 健康检查"
if ! curl -s -o /dev/null -w "%{http_code}" "$PLANS/active/by-case/1" | grep -qE "200|204|\[\]|^2"; then
  code=$(curl -s -o /dev/null -w "%{http_code}" "$PLANS/active/by-case/1" || echo "000")
  if [ "$code" = "000" ]; then
    echo "✗ 无法连接 $HOST —— 请先启动 App（scripts/start-local.ps1 或 java -jar，端口 8888）"
    exit 1
  fi
fi
echo "✓ App 可达：$HOST"

START_TS=$(date +%s)

# =============================================================================
# A 组：核心链路（7 条）—— 真实可控收件人
# =============================================================================
if [ "$RUN_A" = "1" ]; then

hdr "A1  SMS 真发 + S1 多步计划（94101 → 639451374358）"
post "$MOCK/ingest?caseId=94101&userId=94101&stage=S1"
wait_timeline 94101 1 90 || true
show 94101

hdr "A2  Push 真发（94200 → 真实 JPush test app token）"
sleep 1
post "$MOCK/ingest?caseId=94200&userId=94200&stage=S1"
wait_timeline 94200 1 180 || true
show 94200

hdr "A3  Push 无 token → SMS fallback（94201 → fallback 9451373897）"
sleep 1
post "$MOCK/ingest?caseId=94201&userId=94201&stage=S1"
wait_timeline 94201 1 180 || true
show 94201

hdr "A4  Email 真发里程碑（92001 S0 → S0_DUE_TODAY_EMAIL → 126 邮箱）"
sleep 1
post "$MOCK/ingest?caseId=92001&userId=92001&stage=S0"
wait_timeline 92001 1 120 || true
show 92001

hdr "A5  还款取消（94102：先 ingest 首步，再 repayment → PLAN_CANCELLED/REPAID）"
sleep 1
post "$MOCK/ingest?caseId=94102&userId=94102&stage=S1"
sleep 12   # 等首步 SMS 真发
echo ">> 触发还款"
post "$MOCK/repayment?userId=94102&caseId=94102"
sleep 5
echo ">> 取消后应无活跃计划，timeline 不再新增（查日志 [cancel] REPAID）"
show 94102

hdr "A6  阶段变更取消+重建（94103：S1 → stage-changed S2）"
sleep 1
post "$MOCK/ingest?caseId=94103&userId=94103&stage=S1"
sleep 12
echo ">> 触发阶段变更 S1→S2"
post "$MOCK/stage-changed?caseId=94103&stage=S2"
sleep 6
echo ">> 旧 S1 计划应取消，新 S2 计划应出现（active 中 stage=S2）"
show 94103

hdr "A7  CEASED 停催（94100：ingest 后 case-ceased → PLAN_CANCELLED/CEASED）"
sleep 1
post "$MOCK/ingest?caseId=94100&userId=94100&stage=S1"
sleep 8
echo ">> 触发 D+91 停催"
post "$MOCK/case-ceased?caseId=94100"
sleep 5
echo ">> 活跃计划应被取消（查日志 [cancel] CEASED）"
show 94100

fi

# =============================================================================
# B 组：Stage 全覆盖（5 条）—— 验证 plan-templates + scriptSlot 差异
#   ⚠ 非 email 渠道步骤会打到合成手机号；EMAIL 步骤投真实 126 邮箱
# =============================================================================
if [ "$RUN_B" = "1" ]; then

hdr "B1  S0 计划（90100 → S0 模板：仅 SMS，scriptSlot=S0_*）⚠合成号"
sleep 1
post "$MOCK/ingest?caseId=90100&userId=90100&stage=S0"
wait_timeline 90100 1 60 || true
show 90100

hdr "B2  S1 计划（94101 已在 A1 验，跳过重复；此处验 S1 模板结构）"
echo "   （S1 三步模板已由 A1 覆盖，参见 A1 timeline）"

hdr "B3  S2 计划 FIRM 语气（90007 → S2 四步，scriptSlot=S2_SMS_FIRM）⚠合成号"
sleep 1
post "$MOCK/ingest?caseId=90007&userId=90007&stage=S2"
wait_timeline 90007 3 300 || true
show 90007

hdr "B4  S3 计划（93301 → S3 三步，EMAIL=S3_EMAIL_ENTRY→126；SMS/PUSH 合成号）"
sleep 1
post "$MOCK/ingest?caseId=93301&userId=93301&stage=S3"
wait_timeline 93301 2 240 || true
show 93301

hdr "B5  S4 计划（93401 → S4 两步，EMAIL=S4_EMAIL_ENTRY→126；SMS 合成号）"
sleep 1
post "$MOCK/ingest?caseId=93401&userId=93401&stage=S4"
wait_timeline 93401 2 180 || true
show 93401

fi

# =============================================================================
# C 组：异常 / 边界（3 条）
# =============================================================================
if [ "$RUN_C" = "1" ]; then

hdr "C1  幂等：重复 ingest 不产生第二个活跃计划（94101）"
sleep 1
post "$MOCK/ingest?caseId=94101&userId=94101&stage=S1"
sleep 2
post "$MOCK/ingest?caseId=94101&userId=94101&stage=S1"
sleep 3
echo ">> 活跃计划数应为 1（幂等跳过，查日志 idempotent skip）"
curl -s "$PLANS/active/by-case/94101" | pp

hdr "C2  CEASED 拒建：ingest 已停催案件 → PlanFactory 返回 null（90091）"
sleep 1
post "$MOCK/ingest?caseId=90091&userId=90091&stage=S4"
sleep 4
echo ">> 应无活跃计划（shouldRejectPlan 拦截，查日志 is CEASED, skip）"
curl -s "$PLANS/active/by-case/90091" | pp

hdr "C3  冻结案件 PreFlight 拦截（90008：建计划但步骤 SKIPPED）"
sleep 1
post "$MOCK/ingest?caseId=90008&userId=90008&stage=S2"
sleep 12
echo ">> 计划创建但步骤被系统守卫 SKIPPED（查日志 [preflight]/frozen）"
show 90008

fi

# ---- 收尾 ------------------------------------------------------------------
END_TS=$(date +%s)
hdr "测试结束 —— 总耗时 $(( (END_TS - START_TS) / 60 )) 分 $(( (END_TS - START_TS) % 60 )) 秒"
echo "断言口径（§L4a.3）："
echo "  (a) 真实终端收到：核对 126 邮箱 / 测试 app / 真号手机"
echo "  (b) providerMsgId + result + scriptSlot：见各 timeline 输出"
echo "  (c) plan-step 终态：见 active 计划 + 应用日志 [execStep]/[advance]/[cancel]"
