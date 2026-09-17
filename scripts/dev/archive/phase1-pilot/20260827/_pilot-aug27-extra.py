#!/usr/bin/env python3
import os, subprocess

def envfile():
    vals = {}
    with open("/opt/app/pilot.env") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            vals[k] = v.strip().strip('"').strip("'")
    return vals

def mysql(vals, sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
    return subprocess.check_output(
        ["mysql","-h",vals["COLLECTION_DB_HOST"],"-P","3306",
         "-u",vals["COLLECTION_DB_USERNAME"],vals["COLLECTION_DB_NAME"],"-e",sql],
        env=env).decode()

vals = envfile()
print("=== answered ===")
print(mysql(vals, "SELECT s.id, s.plan_id, s.status, s.result, s.executed_at, s.completed_at, s.original_trigger_time FROM t_contact_plan_step s WHERE s.channel_type='AI_CALL' AND s.result='ANSWERED' AND DATE(s.original_trigger_time)='2026-08-27'"))
print("=== sms not completed 08:00 ===")
print(mysql(vals, "SELECT s.id, s.plan_id, s.status, s.result, s.completed_at FROM t_contact_plan_step s WHERE s.channel_type='SMS' AND DATE(s.original_trigger_time)='2026-08-27' AND TIME(s.original_trigger_time)='08:00:00' AND s.status<>'COMPLETED'"))
print("=== cancel leftover pending ===")
print(mysql(vals, "SELECT p.id, p.case_id, p.status, p.cancel_reason, COUNT(*) pending FROM t_contact_plan p JOIN t_contact_plan_step s ON s.plan_id=p.id WHERE p.status='PLAN_CANCELLED' AND s.status='PENDING' GROUP BY p.id, p.case_id, p.status, p.cancel_reason"))
print("=== 14:30 skipped ===")
print(mysql(vals, "SELECT s.id, s.plan_id, s.status, s.result FROM t_contact_plan_step s WHERE DATE(s.original_trigger_time)='2026-08-27' AND TIME(s.original_trigger_time)='14:30:00' AND s.status='SKIPPED'"))
