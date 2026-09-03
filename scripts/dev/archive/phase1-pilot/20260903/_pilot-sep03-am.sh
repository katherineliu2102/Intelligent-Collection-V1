#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-03}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== ai_0915_dist ==="
q "SELECT status, IFNULL(result,'-'), COUNT(*) n FROM t_contact_plan_step WHERE original_trigger_time=CONCAT('$D',' 09:15:00') AND channel_type='AI_CALL' GROUP BY 1,2 ORDER BY n DESC"

echo "=== ai_answered_proj ==="
q "SELECT c.case_id, c.stage, c.dpd, c.overdue_amount, s.plan_id, s.id, s.executed_at
FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id JOIN t_ai_collection c ON c.case_id=p.case_id
WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'"

echo "=== ai_audit_answered ==="
q "SELECT a.case_id, a.result_label, LEFT(IFNULL(a.summary,''),160) FROM t_ai_call_audit a
JOIN t_contact_plan_step s ON s.id=a.step_id
WHERE DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'" 2>/dev/null || echo 'no t_ai_call_audit.step_id join'
q "SHOW COLUMNS FROM t_ai_call_audit" | head -30

echo "=== noplan_in_collection ==="
q "SELECT COUNT(*) FROM t_ai_collection c WHERE c.collection_status='IN_COLLECTION'
AND NOT EXISTS (SELECT 1 FROM t_contact_plan p WHERE p.case_id=c.case_id AND p.status NOT IN ('PLAN_CANCELLED','PLAN_COMPLETED'))"

echo "=== noplan_cancel_reason ==="
q "SELECT IFNULL(p.cancel_reason,'NULL') r, COUNT(*) n
FROM t_ai_collection c
JOIN t_contact_plan p ON p.case_id=c.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=c.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE c.collection_status='IN_COLLECTION'
AND NOT EXISTS (SELECT 1 FROM t_contact_plan x WHERE x.case_id=c.case_id AND x.status NOT IN ('PLAN_CANCELLED','PLAN_COMPLETED'))
GROUP BY 1 ORDER BY n DESC"

echo "=== noplan_sms_gap ==="
q "SELECT COUNT(*) FROM t_ai_collection c WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED')"

echo "=== firm_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND script_slot LIKE '%FIRM%' GROUP BY 1"

echo "=== firm_samples ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id IN (530684,531245,505611,530569)"

echo "=== missing_s0_24 ==="
q "SELECT COUNT(*) FROM t_ai_collection WHERE case_id IN (507188,507250,507265,507272,507279,507300,507350,507363,507373,507376,507399,531802,531811,531812,531816,531831,531833,531841,531845,531847,531848,531852,531858,531866)"

echo "=== yesterday_s1_dpd1_today ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id IN (507262,507333,507347,531822,531860,531867)"

echo "=== hikari_logs ==="
docker logs --since 12h collection-admin 2>&1 | grep -E 'HikariPool|Connection is not available' | grep -v enrolled | tail -15 || true

echo "=== daily_roll ==="
docker logs --since 12h collection-admin 2>&1 | grep 'daily roll completed' | grep '2026-09-03' | tail -5

echo "=== daily_roll_stage ==="
docker logs --since 12h collection-admin 2>&1 | grep 'DpdStageRollHandler' | grep '2026-09-03 03:' | grep -E 'stage|STAGE|settled|CEASED' | tail -20

echo "=== wave_0915 ==="
docker logs --since 6h collection-admin 2>&1 | grep -E 'mocasa-20260903-0915|started batchId|wave mocasa' | grep -v enrolled | tail -15

echo "=== hikari_env ==="
docker exec collection-admin printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE || true

echo "=== whitelist ==="
docker exec collection-admin sh -c 'echo PILOT=$COLLECTION_PILOT_LOAN_IDS; echo SCAN=$COLLECTION_SCAN_CASE_IDS' 2>/dev/null | head -5
