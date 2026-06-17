#!/usr/bin/env bash
set -euo pipefail

# 切到仓库根（本脚本位于 deploy/），保证 mvn 反应堆与 compose 相对路径正确
cd "$(dirname "$0")/.."

mvn -pl collection-admin -am clean package -DskipTests
docker compose -f deploy/docker-compose.yml up -d --build --force-recreate
