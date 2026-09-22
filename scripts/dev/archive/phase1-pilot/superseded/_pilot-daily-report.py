#!/usr/bin/env python3
# deprecated: use scripts/pilot/daily-report.sh
import os, subprocess
D = os.environ.get("PHT_DATE", "2026-09-02")

def q(sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = os.environ["COLLECTION_DB_PASSWORD"]
    return subprocess.check_output([
        "mysql", "-N",
        "-h", os.environ["COLLECTION_DB_HOST"],
        "-P", os.environ.get("COLLECTION_DB_PORT", "3306"),
        "-u", os.environ["COLLECTION_DB_USERNAME"],
        os.environ["COLLECTION_DB_NAME"],
        "-e", sql,
    ]).decode().strip()

def sec(t, sql):
    print(f"\n=== {t} ===")
    print(q(sql) or "(empty)")

print(f"PHT_DATE={D}")
sec("collection_total", "SELECT COUNT(*) FROM t_ai_collection")
sec("inbox_caseEvent", f"SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='CASE_INGESTED' AND DATE(created_at)='{D}'")
sec("projection", "SELECT IFNULL(stage,'null'), dpd, collection_status, COUNT(*) n FROM t_ai_collection GROUP BY 1,2,3 ORDER BY n DESC LIMIT 25")
sec("S0_cases", "SELECT case_id, dpd, collection_status FROM t_ai_collection WHERE stage='S0' ORDER BY case_id")

for slot, ch in [("08:00","SMS"),("08:00","PUSH"),("09:15","AI_CALL"),("12:00","PUSH"),("14:00","EMAIL"),("14:30","AI_CALL")]:
    sec(f"slot_{slot}_{ch}", f"SELECT status, IFNULL(result,'-'), COUNT(*) n, MIN(executed_at), MAX(executed_at) FROM t_contact_plan_step WHERE original_trigger_time=CONCAT('{D}',' {slot}:00') AND channel_type='{ch}' GROUP BY 1,2 ORDER BY n DESC")

sec("timeline_delivered", f"SELECT channel, COUNT(*) n FROM t_contact_timeline WHERE DATE(scheduled_at)='{D}' AND status='DELIVERED' GROUP BY 1")
sec("email_slots", f"SELECT script_slot, COUNT(*) n FROM t_contact_timeline WHERE DATE(scheduled_at)='{D}' AND channel='EMAIL' AND status='DELIVERED' GROUP BY 1")
sec("ai_answered", f"SELECT case_id, plan_id, step_id, executed_at, result FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND DATE(original_trigger_time)='{D}' AND result='ANSWERED'")
sec("ai_executing", f"SELECT COUNT(*) FROM t_contact_plan_step WHERE channel_type='AI_CALL' AND status='EXECUTING' AND DATE(original_trigger_time)='{D}'")
sec("new_plans_today", f"SELECT COUNT(*) FROM t_contact_plan WHERE DATE(created_at)='{D}'")

try:
    sec("ai_audit", f"""SELECT a.case_id, a.result_label, LEFT(IFNULL(a.summary,''),100)
FROM t_ai_call_audit a
JOIN t_contact_plan_step s ON s.step_id=a.step_id
WHERE DATE(s.original_trigger_time)='{D}' AND s.result='ANSWERED'""")
except Exception as e:
    print(f"audit skip: {e}")

sec("repayment_inbox", f"SELECT COUNT(*) FROM t_ai_collection_inbox WHERE event_type='REPAYMENT' AND DATE(created_at)='{D}'")
