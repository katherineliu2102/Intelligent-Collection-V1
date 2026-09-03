#!/bin/bash
set -euo pipefail
ENV="/opt/app/pilot.env"
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }
D=2026-09-02
echo "=== no 1430: dpd>=61 count ==="
q "SELECT COUNT(*) FROM (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time='${D} 09:15:00' AND s.channel_type='AI_CALL' AND s.status='COMPLETED'
) m JOIN t_ai_collection c ON c.case_id=m.case_id WHERE c.dpd>=61
AND m.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time='${D} 14:30:00' AND s.channel_type='AI_CALL')"
echo "=== no 1430 by dpd ==="
q "SELECT c.dpd, COUNT(*) FROM (
  SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time='${D} 09:15:00' AND s.channel_type='AI_CALL' AND s.status='COMPLETED'
) m JOIN t_ai_collection c ON c.case_id=m.case_id WHERE m.case_id NOT IN (
  SELECT DISTINCT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id
  WHERE s.original_trigger_time='${D} 14:30:00' AND s.channel_type='AI_CALL') GROUP BY 1 ORDER BY 1"
