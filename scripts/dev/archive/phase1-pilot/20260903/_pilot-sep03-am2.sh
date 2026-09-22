#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-03}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== timeline_delivered ==="
q "SELECT channel, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND result='DELIVERED' GROUP BY 1"
echo "=== sms_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND result='DELIVERED' GROUP BY 1"
echo "=== ai_answered ==="
q "SELECT p.case_id, s.plan_id, s.id, s.executed_at, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.channel_type='AI_CALL' AND DATE(s.original_trigger_time)='$D' AND s.result='ANSWERED'"
echo "=== ai_executing ==="
q "SELECT COUNT(*) FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND status='EXECUTING' AND DATE(original_trigger_time)='$D'"
echo "=== new_plans_today ==="
q "SELECT COUNT(*) FROM t_contact_plan WHERE DATE(created_at)='$D'"
echo "=== new_plans_sample ==="
q "SELECT case_id, JSON_UNQUOTE(JSON_EXTRACT(context_snapshot,'$.stage')), status, created_at FROM t_contact_plan WHERE DATE(created_at)='$D' ORDER BY created_at"
echo "=== repayment_inbox ==="
q "SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='REPAYMENT' AND DATE(created_at)='$D'"
echo "=== S1_dpd1 ==="
q "SELECT case_id, dpd FROM t_ai_collection WHERE stage='S1' AND dpd=1 ORDER BY case_id"
echo "=== S1_all ==="
q "SELECT case_id, dpd, collection_status FROM t_ai_collection WHERE stage='S1' ORDER BY dpd, case_id"
echo "=== skip_513749_526109 ==="
q "SELECT case_id, collection_status, stage, dpd FROM t_ai_collection WHERE case_id IN (513749,526109)"
echo "=== old40_steps_today ==="
q "SELECT p.case_id, s.channel_type, s.status, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE p.case_id IN (513749,526109) AND DATE(s.original_trigger_time)='$D'"
echo "=== health ==="
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/actuator/health || echo fail
echo "=== yesterday_s1_today ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id IN (507262,507333,507347,531822,531860,531867)"
echo "=== sms_gap_in_collection ==="
q "SELECT COUNT(*) FROM t_ai_collection c WHERE c.collection_status='IN_COLLECTION'
AND c.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='DELIVERED')"
echo "=== noplan_cancel_reason ==="
q "SELECT IFNULL(p.cancel_reason,'NULL') r, COUNT(*) n
FROM t_ai_collection c
JOIN t_contact_plan p ON p.case_id=c.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=c.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE c.collection_status='IN_COLLECTION'
AND NOT EXISTS (SELECT 1 FROM t_contact_plan x WHERE x.case_id=c.case_id AND x.status NOT IN ('PLAN_CANCELLED','PLAN_COMPLETED'))
GROUP BY 1 ORDER BY n DESC"
echo "=== noplan_count ==="
q "SELECT COUNT(*) FROM t_ai_collection c WHERE c.collection_status='IN_COLLECTION'
AND NOT EXISTS (SELECT 1 FROM t_contact_plan p WHERE p.case_id=c.case_id AND p.status NOT IN ('PLAN_CANCELLED','PLAN_COMPLETED'))"
echo "=== firm_slots ==="
q "SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND script_slot LIKE '%FIRM%' GROUP BY 1"
echo "=== firm_samples ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id IN (530684,531245,505611,530569)"
echo "=== missing_s0_24_in_proj ==="
q "SELECT COUNT(*) FROM t_ai_collection WHERE case_id IN (507188,507250,507265,507272,507279,507300,507350,507363,507373,507376,507399,531802,531811,531812,531816,531831,531833,531841,531845,531847,531848,531852,531858,531866)"
echo "=== missing_s0_24_inbox_today ==="
q "SELECT COUNT(DISTINCT case_id) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D' AND case_id IN (507188,507250,507265,507272,507279,507300,507350,507363,507373,507376,507399,531802,531811,531812,531816,531831,531833,531841,531845,531847,531848,531852,531858,531866)"
echo "=== sms_skip_reason_sample ==="
q "SELECT p.case_id, p.status, p.cancel_reason, c.stage, c.dpd FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id JOIN t_ai_collection c ON c.case_id=p.case_id WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='SKIPPED' LIMIT 15"
echo "=== sms_skip_by_plan_status ==="
q "SELECT p.status, IFNULL(p.cancel_reason,'NULL'), COUNT(*) n FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.original_trigger_time=CONCAT('$D',' 08:00:00') AND s.channel_type='SMS' AND s.result='SKIPPED' GROUP BY 1,2"
echo "=== 0915_skip_vs_answered_yesterday ==="
q "SELECT p.case_id, s.result FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE s.original_trigger_time=CONCAT('$D',' 09:15:00') AND s.channel_type='AI_CALL' AND s.result='SKIPPED' AND p.case_id IN (507347,513849,531516,529588,520087)"
echo "=== hikari_env ==="
docker exec collection-admin printenv SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE || true
echo "=== daily_roll ==="
docker logs --since 18h collection-admin 2>&1 | grep 'daily roll completed' | tail -8
echo "=== wave_0915 ==="
docker logs --since 8h collection-admin 2>&1 | grep -E 'mocasa-20260903-0915|started batch' | grep -v enrolled | tail -10
echo "=== hikari_pool ==="
docker logs --since 18h collection-admin 2>&1 | grep -E 'Connection is not available|HikariPool-1 - Timeout' | tail -10 || echo none
echo "=== poison ==="
docker logs --since 18h collection-admin 2>&1 | grep '2026-09-03' | grep -i poison | sed -E 's/.*: //; s/, caseId=.*//' | sort | uniq -c | sort -nr | head -12
echo "=== whitelist_empty ==="
grep -E '^COLLECTION_PILOT_LOAN_IDS=|^COLLECTION_SCAN_CASE_IDS=' /opt/app/pilot.env
