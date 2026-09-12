#!/usr/bin/env bash
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
# 重启 Pilot 容器并做基础就绪检查。pilot-run.sh 末尾会跟日志，这里用 timeout 兜住，避免挂死。
set -eu

timeout 240 /opt/app/build/pilot-run.sh > /tmp/pilot-run.log 2>&1 || true
echo '=== pilot-run tail ==='
tail -25 /tmp/pilot-run.log

sleep 25
echo '=== container ==='
docker ps --filter name=collection-admin --format 'table {{.Names}}\t{{.Status}}'

echo '=== health ==='
curl -s -o /dev/null -w 'health:%{http_code}\n' http://127.0.0.1:8080/actuator/health

echo '=== 启动日志：聚合相关 ==='
docker logs --tail 400 collection-admin 2>&1 | grep -iE 'FacadeBatch|batchAggregation|batch-aggregation|PilotReadiness|Tomcat started|ERROR' | tail -30
