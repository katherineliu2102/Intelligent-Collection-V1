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

RUN_LOG_DIR="$ROOT/logs/run"
mkdir -p "$RUN_LOG_DIR"
TEST_LOG="$RUN_LOG_DIR/l4a.last.log"
ADMIN_LOG="$RUN_LOG_DIR/admin.log"

echo "========== 1/4 停止旧进程 =========="
"$ROOT/scripts/dev/stop-local.sh"

echo "========== 2/4 启动 App =========="
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
