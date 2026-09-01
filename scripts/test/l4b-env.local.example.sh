#!/usr/bin/env bash
# L4b 本地环境变量模板（committed）。用法：
#   cp scripts/test/l4b-env.local.example.sh scripts/test/l4b-env.local.sh
#   # 编辑 l4b-env.local.sh 填真值（该文件已 gitignore，不入仓）
#   source scripts/test/l4b-env.local.sh && <启动 collection-admin>
#
# 配置优先级（2026-08-21 实测）：环境变量 > jar 内 application-local.yml > Nacos
# intelligent-collection-local.yml（后者经 spring.config.import 引入，属被导入文档）。
# 因此 jar 里写成 ${VAR:default} 的键只认环境变量，Nacos 上的同名键是**空转**的；
# 只有 jar 内不存在的键（case-service / loan-id-whitelist）才由 Nacos 决定。

# GCP 项目（映射 collection.ingestion.project-id）
export GCP_PUBSUB_PROJECT="fintech-all"

# 合成源（L4a/L4b 合成消息）用 intelligent-collection-cases-test1-sub；
# 真实源（L4b）用 intelligent-collection-cases-v1-l4b-sub；Pilot/生产改 intelligent-collection-cases-v1-sub。
# 切换订阅改本变量即可 —— Nacos 上的 collection.ingestion.subscription 压不住 jar 里的 ${GCP_PUBSUB_SUBSCRIPTION}。
export GCP_PUBSUB_SUBSCRIPTION="intelligent-collection-cases-test1-sub"

# 须 true，否则 PubSub Consumer 不启动（application-local.yml 默认 ${INGESTION_ENABLED:false}）
export INGESTION_ENABLED=true

# 零真实触达：密钥置空后必须回落 Mock，否则 EMAIL/PUSH 直接 FAILED。
# 该键在 application-local.yml 里写死为 false（Level A 口径），只能用环境变量覆盖。
# 验真实送达时改回 false 并恢复 Nacos 密钥。
export CHANNEL_FALLBACK_TO_MOCK=true

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
echo "[l4b-env] CHANNEL_FALLBACK_TO_MOCK=${CHANNEL_FALLBACK_TO_MOCK}"
echo "[l4b-env] GOOGLE_APPLICATION_CREDENTIALS=${GOOGLE_APPLICATION_CREDENTIALS}"
if [ ! -f "${GOOGLE_APPLICATION_CREDENTIALS}" ]; then
  echo "[l4b-env] ⚠️  凭证文件不存在，请向运维索取 credentials.json 放到仓库根目录" >&2
fi
