#!/bin/bash
set -euo pipefail
D=2026-09-03
ENV=/opt/app/pilot.env
set -a; source "$ENV"; set +a
export MYSQL_PWD="$COLLECTION_DB_PASSWORD"
MY=(mysql -N -h "$COLLECTION_DB_HOST" -P "${COLLECTION_DB_PORT:-3306}" -u "$COLLECTION_DB_USERNAME" "$COLLECTION_DB_NAME")
q() { "${MY[@]}" -e "$1"; }

echo "=== failed_reason ==="
q "SELECT JSON_UNQUOTE(JSON_EXTRACT(canonical_payload,'$.final_failure_reason')) r, COUNT(*) n FROM t_channel_callback_audit WHERE DATE(received_at)='$D' AND result='FAILED' GROUP BY 1"
echo "=== failed_attempt ==="
q "SELECT JSON_UNQUOTE(JSON_EXTRACT(canonical_payload,'$.attempt_count')) a, COUNT(*) n FROM t_channel_callback_audit WHERE DATE(received_at)='$D' AND result='FAILED' GROUP BY 1"
echo "=== sms_content_summary ==="
q "SELECT IFNULL(content_summary,'(null)') s, COUNT(*) n FROM t_contact_timeline WHERE DATE(created_at)='$D' AND channel='SMS' AND result='DELIVERED' GROUP BY 1 LIMIT 8"
echo "=== 99000915_reason ==="
q "SELECT id, status, cancel_reason FROM t_contact_plan WHERE case_id=99000915 ORDER BY id DESC LIMIT 2"
echo "=== s3_dpd30 ==="
q "SELECT case_id, stage, dpd FROM t_ai_collection WHERE stage='S3' AND dpd>=30 ORDER BY dpd DESC, case_id"
echo "=== s2_dpd15 ==="
q "SELECT case_id, stage, dpd FROM t_ai_collection WHERE stage='S2' AND dpd>=15 ORDER BY dpd DESC, case_id"
echo "=== s4_one_ai ==="
q "SELECT c.dpd, COUNT(*) n FROM t_ai_collection c JOIN t_contact_plan p ON p.case_id=c.case_id JOIN t_contact_plan_step s ON s.plan_id=p.id WHERE s.original_trigger_time='2026-09-03 08:00:00' AND s.channel_type='SMS' AND s.result='DELIVERED' AND c.stage='S4' GROUP BY 1 ORDER BY 1"
echo "=== 517881_plan ==="
q "SELECT id, status, cancel_reason, created_at, updated_at FROM t_contact_plan WHERE case_id=517881 ORDER BY id DESC LIMIT 3"
echo "=== whitelist ==="
grep -E '^COLLECTION_PILOT_LOAN_IDS=|^COLLECTION_SCAN_CASE_IDS=' /opt/app/pilot.env | sed 's/=.*/=LEN/' 
python3 - <<'PY'
from pathlib import Path
for line in Path('/opt/app/pilot.env').read_text().splitlines():
    if line.startswith('COLLECTION_PILOT_LOAN_IDS=') or line.startswith('COLLECTION_SCAN_CASE_IDS='):
        k,v=line.split('=',1)
        ids=[x for x in v.split(',') if x.strip()]
        print(k, 'n=', len(ids), 'empty=', v.strip()=='')
PY
