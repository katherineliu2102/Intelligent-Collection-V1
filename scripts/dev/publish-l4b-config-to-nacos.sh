#!/usr/bin/env bash
# 将 L4b collection.* delta 追加发布到 Nacos Data ID intelligent-collection-local.yml
# 凭证：从项目根 .env 读取 NACOS_*（无需手输账号密码）。
# 用法：
#   ./scripts/dev/publish-l4b-config-to-nacos.sh            # dry-run：拉现网 + 预览合并结果，不发布
#   ./scripts/dev/publish-l4b-config-to-nacos.sh --apply    # 备份现网 → 追加 collection: 块 → 发布
#   ./scripts/dev/publish-l4b-config-to-nacos.sh --apply --force  # 现网已存在 collection: 时仍强制发布
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

APPLY=0; FORCE=0
for a in "$@"; do
  case "$a" in
    --apply) APPLY=1 ;;
    --force) FORCE=1 ;;
  esac
done

[ -f .env ] || { echo "[publish] 缺少 .env（NACOS_* 凭证）" >&2; exit 1; }
# shellcheck disable=SC1091
set -a && source .env && set +a

DATA_ID="intelligent-collection-local.yml"
BLOCK_FILE="deploy/nacos/l4b-collection.publish.yml"
[ -f "$BLOCK_FILE" ] || { echo "[publish] 缺少 $BLOCK_FILE" >&2; exit 1; }

NS="${NACOS_SERVER_ADDR#http://}"; NS="${NS#https://}"; NS="${NS%/nacos}"; NS="${NS%/}"
GROUP="${NACOS_GROUP:?}"; TENANT="${NACOS_NAMESPACE:?}"
USER="${NACOS_USERNAME:?}"; PASS="${NACOS_PASSWORD:?}"

echo "[publish] Nacos=$NS group=$GROUP namespace=$TENANT dataId=$DATA_ID"

current=$(curl -sf -G "http://${NS}/nacos/v1/cs/configs" \
  --data-urlencode "dataId=${DATA_ID}" --data-urlencode "group=${GROUP}" \
  --data-urlencode "tenant=${TENANT}" --data-urlencode "username=${USER}" \
  --data-urlencode "password=${PASS}" 2>/dev/null) || { echo "[publish] 拉取现网失败" >&2; exit 1; }

if [ -z "$current" ]; then echo "[publish] 现网内容为空，请确认 dataId/命名空间" >&2; exit 1; fi

# 去掉块文件的注释头，仅保留 YAML 本体（collection: ...）
block=$(grep -vE '^\s*#' "$BLOCK_FILE" | sed '/^\s*$/d')

if echo "$current" | grep -qE '^collection:'; then
  echo "[publish] ⚠ 现网已存在顶层 collection: 键。" >&2
  if [ "$FORCE" -ne 1 ]; then
    echo "[publish] 为避免重复/冲突，已停止。请手动在 Nacos 控制台合并，或加 --force 追加。" >&2
    exit 2
  fi
  echo "[publish] --force：仍将在末尾追加（可能产生重复键，请自查）。" >&2
fi

merged=$(printf '%s\n%s\n' "$current" "$block")

if [ "$APPLY" -ne 1 ]; then
  echo "===== DRY-RUN：合并后将发布的完整内容如下（未发布）====="
  echo "$merged"
  echo "===== 追加 --apply 执行真正发布 ====="
  exit 0
fi

BAK="deploy/nacos/backup-${DATA_ID}-$(date +%Y%m%d%H%M%S).yml"
printf '%s\n' "$current" > "$BAK"
echo "[publish] 现网已备份到 $BAK"

resp=$(curl -sf -X POST "http://${NS}/nacos/v1/cs/configs" \
  --data-urlencode "dataId=${DATA_ID}" --data-urlencode "group=${GROUP}" \
  --data-urlencode "tenant=${TENANT}" --data-urlencode "type=yaml" \
  --data-urlencode "username=${USER}" --data-urlencode "password=${PASS}" \
  --data-urlencode "content=${merged}") || { echo "[publish] 发布失败" >&2; exit 1; }

if [ "$resp" = "true" ]; then
  echo "[publish] ✅ 发布成功（Nacos 返回 true）。@RefreshScope Bean 将热更新。"
else
  echo "[publish] ⚠ Nacos 返回：$resp" >&2; exit 1
fi
