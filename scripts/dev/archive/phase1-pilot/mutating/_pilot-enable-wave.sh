#!/usr/bin/env bash
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
# 打开 AI_CALL 波次聚合开关，并清掉此前误写入 pilot.env 的残行。
set -euo pipefail

ENV_FILE=/opt/app/pilot.env

cp "$ENV_FILE" "${ENV_FILE}.bak.$(date +%Y%m%d%H%M%S)"

sed -i '/^n#$/d' "$ENV_FILE"
sed -i '/^CHANNEL_FACADE_BATCH_AGGREGATION_ENABLED=/d' "$ENV_FILE"
sed -i '/^# AI_CALL 波次聚合/d' "$ENV_FILE"

{
  printf '\n'
  printf '# AI_CALL 波次聚合：同一触达槽的到期步骤合成一个 Facade 批次（2026-08-27 上线）。关掉即回到一案一批。\n'
  printf 'CHANNEL_FACADE_BATCH_AGGREGATION_ENABLED=true\n'
} >> "$ENV_FILE"

echo '=== tail ==='
tail -5 "$ENV_FILE"
echo '=== grep ==='
grep -n 'BATCH_AGGREGATION' "$ENV_FILE"
echo '=== 残行检查（应为空）==='
grep -n '^n#' "$ENV_FILE" || true
