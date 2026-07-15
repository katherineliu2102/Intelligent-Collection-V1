#!/usr/bin/env bash
# L4b 本地环境变量模板（committed）。用法：
#   cp scripts/test/l4b-env.local.example.sh scripts/test/l4b-env.local.sh
#   # 编辑 l4b-env.local.sh 填真值（该文件已 gitignore，不入仓）
#   source scripts/test/l4b-env.local.sh && <启动 collection-admin>
#
# 仅设置「不入仓」的 GCP 连接三件套；其余联调开关（enabled/case-service/field-map/whitelist）走 Nacos，
# 见 docs/testing/MOCASA催收系统升级_Phase1_L4b环境交接清单.md 的 O4/O5 片段。

# GCP 项目（映射 collection.ingestion.project-id）
export GCP_PUBSUB_PROJECT="fintech-all"

# L4b 阶段：测试订阅（方案 B，见 L4b 交接清单）；上线改回 collection-cases-ai-v1-sub
export GCP_PUBSUB_SUBSCRIPTION="collection-cases-test1-sub"

# 须 true，否则 PubSub Consumer 不启动（application-local.yml 默认 ${INGESTION_ENABLED:false}）
export INGESTION_ENABLED=true

# credentials.json 绝对路径（兼容 bash / zsh source）
if [ -n "${BASH_SOURCE[0]:-}" ]; then
  _L4B_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
elif [ -n "${ZSH_VERSION:-}" ]; then
  _L4B_ROOT="$(cd "$(dirname "${(%):-%x}")/../.." && pwd)"
else
  _L4B_ROOT="$(pwd)"
fi
export GOOGLE_APPLICATION_CREDENTIALS="${GOOGLE_APPLICATION_CREDENTIALS:-${_L4B_ROOT}/credentials.json}"

echo "[l4b-env] GCP_PUBSUB_PROJECT=${GCP_PUBSUB_PROJECT}"
echo "[l4b-env] GCP_PUBSUB_SUBSCRIPTION=${GCP_PUBSUB_SUBSCRIPTION}"
echo "[l4b-env] INGESTION_ENABLED=${INGESTION_ENABLED}"
echo "[l4b-env] GOOGLE_APPLICATION_CREDENTIALS=${GOOGLE_APPLICATION_CREDENTIALS}"
if [ ! -f "${GOOGLE_APPLICATION_CREDENTIALS}" ]; then
  echo "[l4b-env] ⚠️  凭证文件不存在，请向运维索取 credentials.json 放到仓库根目录" >&2
fi
