#!/usr/bin/env bash
# Level A 本地冒烟 —— Mock 直连渠道 + 全链路 ingest（见 docs/操作说明_Nacos本地启动.md）
#
# 前置：服务已启动（8888），.env 已填，DB 可连。
# 用法：
#   ./scripts/smoke-level-a.sh direct          # 仅直连 /mock/send-*（快，验文案+通知中心）
#   ./scripts/smoke-level-a.sh ingest          # 全链路 ingest + 等扫描（慢，验引擎+DB）
#   ./scripts/smoke-level-a.sh all             # 两者都跑

set -euo pipefail

BASE="${SMOKE_BASE_URL:-http://localhost:8888}"
MODE="${1:-direct}"

red() { printf '\033[31m%s\033[0m\n' "$*"; }
grn() { printf '\033[32m%s\033[0m\n' "$*"; }
ylw() { printf '\033[33m%s\033[0m\n' "$*"; }

check_ok() {
  local name="$1" body="$2"
  if echo "$body" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'; then
    grn "PASS: $name"
    echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
  else
    red "FAIL: $name"
    echo "$body"
    return 1
  fi
}

check_health() {
  if ! curl -sf --connect-timeout 3 "$BASE/plans/active/by-case/0" >/dev/null 2>&1; then
    red "服务不可达: $BASE — 请先启动 collection-admin（见 docs/操作说明_Nacos本地启动.md §5）"
    exit 1
  fi
  grn "服务可达: $BASE"
}

# ── Phase 1: 直连渠道（不经 plan/DB 写 plan 步骤，但需 CaseService mock 数据）──
run_direct() {
  ylw "=== Phase 1: 直连渠道冒烟（验证 stage 文案 + Notification/SendGrid）==="
  local failed=0

  # SMS：各 stage mock case（94101=S1；用 ingest mock 需不同 caseId — 直连用预设 SMS case）
  for caseId in 94101; do
    body=$(curl -sf -X POST "$BASE/mock/send-sms?caseId=$caseId" || true)
    check_ok "SMS direct caseId=$caseId" "$body" || failed=1
    echo "$body" | grep -q '\[S1\]' && grn "  文案含 [S1] 前缀" || ylw "  提示: 检查 smsBody 是否含 stage 前缀"
  done

  # Email：里程碑 case 92002（S0 DUE TODAY，文档 TC-EMAIL-D0-01）
  body=$(curl -sf -X POST "$BASE/mock/send-email?caseId=92002" || true)
  check_ok "Email direct caseId=92002" "$body" || failed=1

  # Push：94200（有假 token；push-test-token 会覆盖到测试 app）
  body=$(curl -sf -X POST "$BASE/mock/send-push?caseId=94200" || true)
  if echo "$body" | grep -q '"ok"[[:space:]]*:[[:space:]]*true'; then
    grn "PASS: Push direct caseId=94200"
  else
    ylw "WARN: Push 可能因无 NOTIFICATION_APP_KEY 失败（预期行为，fallback-to-mock=false）"
    echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
  fi

  return $failed
}

# ── Phase 2: 全链路 ingest（需 DB + single-step=SMS）──
run_ingest() {
  ylw "=== Phase 2: 全链路 ingest（Mock caseId，验证引擎+timeline）==="
  local failed=0
  local caseId=94101

  body=$(curl -sf -X POST "$BASE/mock/ingest?caseId=$caseId&userId=$caseId&stage=S1" || true)
  check_ok "ingest caseId=$caseId" "$body" || failed=1

  ylw "等待扫描器 15s（collection.scan.interval-ms=5000）..."
  sleep 15

  plan=$(curl -sf "$BASE/plans/active/by-case/$caseId" || true)
  echo "active plan: $plan"
  if echo "$plan" | grep -qE 'PLAN_COMPLETED|STEP_WAITING|STEP_EXECUTING|null|"status"'; then
    grn "PASS: 计划状态已推进（见上方 JSON）"
  else
    red "FAIL: 计划未推进，查日志 MyBatis/DB"
    failed=1
  fi

  timeline=$(curl -sf "$BASE/plans/timeline/$caseId?limit=5" || true)
  echo "timeline: $timeline"
  if echo "$timeline" | grep -qi 'DELIVERED\|SKIPPED\|FAILED'; then
    grn "PASS: timeline 有触达记录"
  else
    ylw "WARN: timeline 暂无记录 — 可能 SMS NOT_CONFIGURED 或扫描未触发"
    failed=1
  fi

  return $failed
}

# ── Phase 3: RealCaseService seed cases（可选，需 case-service=real + seed SQL）──
run_real_hint() {
  ylw "=== Phase 3（可选）: RealCaseService + seed 各 stage ==="
  echo "  1) application-local.yml 或 Nacos 设 collection.case-service=real"
  echo "  2) mysql ... < db/seed-test-cases.sql"
  echo "  3) 批量 ingest:"
  echo "     for id in 99000000 99000001 99000002 99000003 99000004; do"
  echo "       curl -X POST \"$BASE/mock/ingest?caseId=\$id&userId=\$id\""
  echo "     done"
  echo "  4) 99000005 (dpd=95) 应不建 plan（CEASED）"
  echo "     curl \"$BASE/plans/active/by-case/99000005\"  → 无 active plan"
}

main() {
  check_health
  local rc=0
  case "$MODE" in
    direct) run_direct || rc=1 ;;
    ingest) run_ingest || rc=1 ;;
    all)
      run_direct || rc=1
      run_ingest || rc=1
      ;;
    hint) run_real_hint; exit 0 ;;
    *) echo "用法: $0 {direct|ingest|all|hint}"; exit 1 ;;
  esac
  run_real_hint
  if [[ $rc -eq 0 ]]; then
    grn "=== Level A 冒烟完成 ==="
  else
    red "=== 存在失败项，合 main 前请排查 ==="
  fi
  exit $rc
}

main
