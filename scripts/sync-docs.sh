#!/usr/bin/env bash
# 将 Phase 1 四份规格文档从本地「智能催收」工作区同步到本仓库 docs/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEST_DIR="${REPO_ROOT}/docs"

# 默认源目录：与本仓库同级的 智能催收/docs/current
DEFAULT_SOURCE="${REPO_ROOT}/../智能催收/docs/current"
SOURCE_DIR="${SOURCE_DIR:-${DEFAULT_SOURCE}}"

DOCS=(
  "MOCASA催收系统升级_Phase1_产品需求文档_PRD.md"
  "MOCASA催收系统升级_Phase1_架构设计文档.md"
  "MOCASA催收系统升级_Phase1_核心引擎规格.md"
  "MOCASA催收系统升级_Phase1_基础设施交互规范.md"
)

if [[ ! -d "${SOURCE_DIR}" ]]; then
  echo "错误: 源目录不存在: ${SOURCE_DIR}" >&2
  echo "用法: SOURCE_DIR=/path/to/docs/current ${0}" >&2
  exit 1
fi

mkdir -p "${DEST_DIR}"

echo "源目录: ${SOURCE_DIR}"
echo "目标目录: ${DEST_DIR}"
echo "---"

for name in "${DOCS[@]}"; do
  src="${SOURCE_DIR}/${name}"
  if [[ ! -f "${src}" ]]; then
    echo "错误: 缺少源文件: ${src}" >&2
    exit 1
  fi
  cp -f "${src}" "${DEST_DIR}/${name}"
  echo "已同步: ${name}"
done

echo "---"
echo "完成: ${#DOCS[@]} 份文档已复制到 ${DEST_DIR}"
