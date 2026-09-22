#!/bin/bash
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
set -euo pipefail
rm -rf /tmp/ic-test_src
mkdir -p /tmp/ic-test_src
tar -xzf /tmp/ic-test_branch.tgz -C /tmp/ic-test_src
cd /tmp/ic-test_src
git init -q
git add -A
git -c user.email=deploy@local -c user.name=deploy commit -q -m init --no-verify
echo "=== unit tests ==="
mvn -pl collection-engine,collection-channel -am test \
  -Dtest=PlanLifecycleManagerTest,EnginePropertiesTest,StepExecutionOrchestratorTest \
  -Dspotless.check.skip=true
echo "=== package admin ==="
mvn -pl collection-admin -am package -DskipTests -Dspotless.check.skip=true
ls -lh collection-admin/target/collection-admin.jar
