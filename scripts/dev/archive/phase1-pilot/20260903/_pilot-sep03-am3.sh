#!/bin/bash
set -euo pipefail
D="${PHT_DATE:-2026-09-03}"
ENV="${PILOT_ENV:-/opt/app/pilot.env}"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== settled_list ==="
q "SELECT case_id, stage, dpd FROM t_ai_collection WHERE collection_status='SETTLED' ORDER BY case_id"

echo "=== yesterday_sms_not_today ==="
q "SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.original_trigger_time='2026-09-02 08:00:00' AND s.channel_type='SMS' AND s.result='DELIVERED'
AND p.case_id NOT IN (
  SELECT DISTINCT p2.case_id FROM t_contact_plan_step s2 JOIN t_contact_plan p2 ON p2.id=s2.plan_id
  WHERE s2.original_trigger_time='2026-09-03 08:00:00' AND s2.channel_type='SMS' AND s2.result='DELIVERED')"

echo "=== those_proj ==="
q "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time='2026-09-02 08:00:00' AND s.channel_type='SMS' AND s.result='DELIVERED'
  AND p.case_id NOT IN (
    SELECT DISTINCT p2.case_id FROM t_contact_plan_step s2 JOIN t_contact_plan p2 ON p2.id=s2.plan_id
    WHERE s2.original_trigger_time='2026-09-03 08:00:00' AND s2.channel_type='SMS' AND s2.result='DELIVERED'))"

echo "=== extra_nodue ==="
q "SELECT n.case_id, n.stage, n.dpd, n.overdue_amount, p.cancel_reason, p.updated_at
FROM t_ai_collection n
JOIN t_contact_plan p ON p.case_id=n.case_id AND p.id=(SELECT p2.id FROM t_contact_plan p2 WHERE p2.case_id=n.case_id ORDER BY p2.created_at DESC LIMIT 1)
WHERE n.collection_status='IN_COLLECTION' AND p.cancel_reason='NO_DUE_BALANCE'
ORDER BY n.case_id"

echo "=== yday_answered_today_ai ==="
q "SELECT p.case_id, s.status, s.result, s.executed_at FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.original_trigger_time='2026-09-03 09:15:00' AND s.channel_type='AI_CALL'
AND p.case_id IN (507347,513849,531516,529588,520087)"

echo "=== 99000915 ==="
q "SELECT case_id, user_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id=99000915"
q "SELECT id, status, created_at FROM t_contact_plan WHERE case_id=99000915 ORDER BY created_at DESC LIMIT 3"

echo "=== email_today_steps ==="
q "SELECT status, IFNULL(result,'-'), COUNT(*) n FROM t_contact_plan_step WHERE original_trigger_time='2026-09-03 14:00:00' AND channel_type='EMAIL' GROUP BY 1,2"

echo "=== email_pending_any ==="
q "SELECT original_trigger_time, status, COUNT(*) FROM t_contact_plan_step WHERE channel_type='EMAIL' AND DATE(original_trigger_time)='$D' GROUP BY 1,2"

echo "=== 1430_skip_reason ==="
q "SELECT p.status, IFNULL(p.cancel_reason,'NULL'), COUNT(*) n FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
WHERE s.original_trigger_time='2026-09-03 14:30:00' AND s.channel_type='AI_CALL' AND s.status='SKIPPED' GROUP BY 1,2"

echo "=== 1430_pending_s4 ==="
q "SELECT c.stage, COUNT(*) n FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id JOIN t_ai_collection c ON c.case_id=p.case_id
WHERE s.original_trigger_time='2026-09-03 14:30:00' AND s.channel_type='AI_CALL' AND s.status='PENDING' GROUP BY 1"

echo "=== sms_body_sample ==="
q "SELECT LEFT(payload,180) FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND result='DELIVERED' LIMIT 2" 2>/dev/null || true
q "SHOW COLUMNS FROM t_contact_timeline" | awk '{print $1}'

echo "=== 530569 ==="
q "SELECT case_id FROM t_ai_collection WHERE case_id=530569"

echo "=== active_plans ==="
q "SELECT COUNT(*) FROM t_contact_plan WHERE status NOT IN ('PLAN_CANCELLED','PLAN_COMPLETED')"

echo "=== inbox_200ring ==="
q "SELECT COUNT(DISTINCT case_id) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D'"
echo "=== inbox_not_in_proj ==="
q "SELECT COUNT(*) FROM (SELECT DISTINCT case_id FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='$D') i LEFT JOIN t_ai_collection c ON c.case_id=i.case_id WHERE c.case_id IS NULL"
echo "=== SENT_NO_RESPONSE ==="
q "SELECT p.case_id, c.stage, c.dpd FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id JOIN t_ai_collection c ON c.case_id=p.case_id WHERE s.original_trigger_time='2026-09-03 09:15:00' AND s.result='SENT_NO_RESPONSE'"
echo "=== FAILED_reason ==="
q "SELECT COUNT(*) FROM t_channel_callback_audit WHERE DATE(received_at)='$D' AND result='FAILED'"
q "SELECT LEFT(canonical_payload,120) FROM t_channel_callback_audit WHERE DATE(received_at)='$D' AND result='FAILED' LIMIT 1"
