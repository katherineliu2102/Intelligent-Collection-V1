#!/usr/bin/env bash
# =============================================================================
# 重启 App + 等待就绪 + 跑 §L4a 官方测试（一键）
# =============================================================================
# 用法（项目根目录）：
#   ./scripts/test/restart-and-l4a.sh              # 停服 → 编译 → 后台起 → L4a 全量
#   ./scripts/test/restart-and-l4a.sh --no-build   # 跳过 mvn（仅重启 jar）
#   BUILD=0 ./scripts/test/restart-and-l4a.sh      # 同上
#
# 环境：.env 已填 NACOS_*；08:00–21:00 PHT 跑 L4a（静默时段 Guard 会 block）
# 日志：logs/run/admin.log；测试输出 logs/run/l4a.last.log
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

NO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --no-build) NO_BUILD=1 ;;
  esac
done
if [ "${BUILD:-1}" = "0" ]; then NO_BUILD=1; fi

HOST="${HOST:-http://localhost:8888}"
export HOST
# L4a 使用 MockCaseService 合成案件；Nacos 的 L4b real-case 配置不能继承到此运行。
export COLLECTION_CASE_SERVICE="${COLLECTION_CASE_SERVICE:-mock}"
# 扫描隔离：只扫 L4a 固定案件。共享库上多实例会互抢到期步骤（2026-08-21 实测），
# 空名单等于对全库案件发起触达，ScanIsolationGuard 在 local/test 下会拒绝启动。
export COLLECTION_SCAN_CASE_IDS="${COLLECTION_SCAN_CASE_IDS:-92001,92002,93101,93201,94101,94102,94201,94801,94804,94805,94999,95001}"

# 库：默认走本机 MySQL 8.0.46（与远端同版本，结构对齐 25 张表、配置表数据已灌）。
# 不用共享 ai_collection_db 的原因：那上面有外来实例无过滤地抢走全库到期步骤——2026-08-21 实测
# plan 825 三步建好 7 秒即被抢走并扔在地上（trigger_time 清空、timeout_time 未写、无派发），
# 我方 selectDueSteps 全程 Total: 0、一次都没抢到过自己的步骤，L4a 在其上不可能跑通；
# 该账号又只有 ai_collection_db 的权限，无法自建库另开 schema。
# 另：共享库 t_user_extend 有 287 万行真实用户数据、t_ai_collection 为 0 行，
# 即它既有真实数据风险、又提供不了 L4b 需要的真实案件源，留在其上没有收益。
export COLLECTION_DB_URL="${COLLECTION_DB_URL:-jdbc:mysql://127.0.0.1:3306/ai_collection_db?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true}"
export COLLECTION_DB_USER="${COLLECTION_DB_USER:-ai_collection}"
# 口令不入仓：先 export COLLECTION_DB_PASS，或 source 本地未跟踪的 scripts/test/l4b-env.local.sh。
export COLLECTION_DB_PASS="${COLLECTION_DB_PASS:?请先 export COLLECTION_DB_PASS（口令向主架构负责人获取，不写进仓库）}"
# 测试 SSOT §6.1 要求 L4a 在 SPI 硬超时按生产默认启用的前提下执行，不得关闭该开关取得通过。
# 受控远程 MySQL 的查询延迟若真的顶到硬超时，那是须处置的结论，不是应当屏蔽的噪声。
export ENGINE_SPI_TIMEOUT_ENABLED="${ENGINE_SPI_TIMEOUT_ENABLED:-true}"
# L4a 是合成源渠道冒烟，不验真实送达：三个供应商密钥置空后必须回落 Mock，否则 EMAIL/PUSH 直接 FAILED。
# 该键在 application-local.yml 里写死为 false（Level A 口径）且优先级高于 Nacos，只能用环境变量覆盖。
export CHANNEL_FALLBACK_TO_MOCK="${CHANNEL_FALLBACK_TO_MOCK:-true}"
# testSend 端点免签名：base-url 非空时 SMS 会真的打到通知中心并拿到 requestId。L4a 不验真实送达，
# 故置空 base-url 让 SMS 与 EMAIL/PUSH 一样回落 Mock，保证零出站。
export CHANNEL_NOTIFICATION_BASE_URL="${CHANNEL_NOTIFICATION_BASE_URL-}"
# 合规日限缺省是生产口径（每渠道 1 / 合计 3），会让同一 userId 的多步计划自锁；
# 频控用例改由 channel.l4a.guard-frequency-*（仅 94805，限 1）单独覆盖。
export CHANNEL_DAILY_TOTAL_LIMIT="${CHANNEL_DAILY_TOTAL_LIMIT:-200}"
export CHANNEL_DAILY_LIMIT_SMS="${CHANNEL_DAILY_LIMIT_SMS:-50}"
export CHANNEL_DAILY_LIMIT_PUSH="${CHANNEL_DAILY_LIMIT_PUSH:-50}"
export CHANNEL_DAILY_LIMIT_EMAIL="${CHANNEL_DAILY_LIMIT_EMAIL:-50}"
export CHANNEL_DAILY_LIMIT_AI_CALL="${CHANNEL_DAILY_LIMIT_AI_CALL:-50}"

# 静默时段闸门。21:00–08:00 PHT 内 Guard 会把每一步 defer 到次日 08:00，于是全套用例只剩超时失败，
# 且应用日志里没有任何报错——2026-08-21 实测跑出 FAIL=12，表象全是「未在时限内被渠道受理」，
# 极易误判成产品回归。原先这只是脚本头部的一行提示（纪律），现改为跑前拦下（机制）。
QUIET_START="${CHANNEL_QUIET_HOURS_START:-21:00}"
QUIET_END="${CHANNEL_QUIET_HOURS_END:-08:00}"
now_pht="$(TZ=Asia/Manila date +%H:%M)"
in_quiet="$(python3 -c "
def m(s):
    h, mi = s.split(':')
    return int(h) * 60 + int(mi)
now, a, b = m('$now_pht'), m('$QUIET_START'), m('$QUIET_END')
quiet = (now >= a or now < b) if a > b else (a <= now < b)
print(1 if quiet else 0)
")"
if [ "$in_quiet" = "1" ]; then
  if [ "${L4A_ALLOW_QUIET_HOURS:-0}" != "1" ]; then
    echo "✗ 现在 $now_pht PHT 落在静默时段 ${QUIET_START}-${QUIET_END}，L4a 跑不出有效结论。" >&2
    echo "  等到 ${QUIET_END} PHT 之后再跑；或 L4A_ALLOW_QUIET_HOURS=1 强制放宽窗口" >&2
    echo "  （放宽后这一轮不验静默时段 Guard，其余用例照常）。" >&2
    exit 2
  fi
  export CHANNEL_QUIET_HOURS_START="00:00"
  export CHANNEL_QUIET_HOURS_END="00:00"
  echo "[restart-and-l4a] ⚠ 现在 $now_pht PHT 在静默时段内，已按 L4A_ALLOW_QUIET_HOURS=1 放宽窗口"
  echo "[restart-and-l4a] ⚠ 本轮不构成静默时段 Guard 的证据"
fi

RUN_LOG_DIR="$ROOT/logs/run"
mkdir -p "$RUN_LOG_DIR"
TEST_LOG="$RUN_LOG_DIR/l4a.last.log"
ADMIN_LOG="$RUN_LOG_DIR/admin.log"

echo "========== 1/4 停止旧进程 =========="
"$ROOT/scripts/dev/stop-local.sh"

echo "========== 2/4 启动 App =========="
echo "[restart-and-l4a] COLLECTION_CASE_SERVICE=${COLLECTION_CASE_SERVICE}"
echo "[restart-and-l4a] ENGINE_SPI_TIMEOUT_ENABLED=${ENGINE_SPI_TIMEOUT_ENABLED}"
echo "[restart-and-l4a] CHANNEL_FALLBACK_TO_MOCK=${CHANNEL_FALLBACK_TO_MOCK}"
echo "[restart-and-l4a] CHANNEL_NOTIFICATION_BASE_URL='${CHANNEL_NOTIFICATION_BASE_URL}' (空=SMS 亦回落 Mock)"
echo "[restart-and-l4a] 合规日限 total=${CHANNEL_DAILY_TOTAL_LIMIT} SMS=${CHANNEL_DAILY_LIMIT_SMS} PUSH=${CHANNEL_DAILY_LIMIT_PUSH} EMAIL=${CHANNEL_DAILY_LIMIT_EMAIL}"
if [ "$NO_BUILD" -eq 1 ]; then
  "$ROOT/scripts/dev/start-local.sh" --detach --no-build
else
  "$ROOT/scripts/dev/start-local.sh" --detach
fi

echo "========== 3/4 等待健康 =========="
MAX_WAIT="${MAX_WAIT:-180}" HOST="$HOST" "$ROOT/scripts/dev/wait-health.sh"

echo "========== 4/4 L4a 官方测试 =========="
set +e
"$ROOT/scripts/test/l4a-official-test.sh" 2>&1 | tee "$TEST_LOG"
rc=${PIPESTATUS[0]}
set -e

echo ""
if [ "$rc" -eq 0 ]; then
  echo "✓ restart-and-l4a 完成 — 详见 $TEST_LOG"
else
  echo "✗ L4a 测试有失败项 (exit=$rc) — 详见 $TEST_LOG 与 $ADMIN_LOG" >&2
fi
exit "$rc"
