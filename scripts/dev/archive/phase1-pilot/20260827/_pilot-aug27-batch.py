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
        ["mysql","-h",vals["COLLECTION_DB_HOST"],"-P",vals.get("COLLECTION_DB_PORT","3306"),
         "-u",vals["COLLECTION_DB_USERNAME"],vals["COLLECTION_DB_NAME"],"-e",sql],
        env=env).decode()

vals = envfile()
ids = [x.strip() for x in vals.get("COLLECTION_SCAN_CASE_IDS","").replace(";",",").split(",") if x.strip()]
in_list = ",".join(ids)

print("=== plan 843 14:30 ===")
print(mysql(vals,
    "SELECT s.id, s.plan_id, s.channel_type, s.status, s.result, s.original_trigger_time, s.executed_at "
    "FROM t_contact_plan_step s WHERE s.plan_id=843 AND DATE(s.original_trigger_time)='2026-08-27'"))

print("=== cancelled 855 leftover ===")
print(mysql(vals,
    "SELECT s.channel_type, TIME(s.original_trigger_time) slot, s.status, COUNT(*) "
    "FROM t_contact_plan_step s WHERE s.plan_id=855 AND s.status='PENDING' "
    "GROUP BY 1,2,3 ORDER BY 2"))

print("=== today 09:15 AI timeline provider ids ===")
print(mysql(vals,
    "SELECT COUNT(*) steps, COUNT(DISTINCT provider_msg_id) distinct_batch "
    "FROM t_contact_timeline "
    "WHERE case_id IN (%s) AND channel='AI_CALL' AND DATE(created_at)='2026-08-27' AND direction='OUT'" % in_list))

print("=== today 09:15 executed spread ===")
print(mysql(vals,
    "SELECT MIN(s.executed_at), MAX(s.executed_at), COUNT(*) "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='AI_CALL' AND DATE(s.executed_at)='2026-08-27'" % in_list))

print("=== whitelist vs t_ai_collection ===")
print(mysql(vals,
    "SELECT COUNT(*) by_case_id FROM t_ai_collection WHERE case_id IN (%s)" % in_list))
print(mysql(vals,
    "SELECT COUNT(*) by_id FROM t_ai_collection WHERE id IN (%s)" % in_list))
print(mysql(vals,
    "SELECT COUNT(*) by_loan FROM t_ai_collection WHERE loan_id IN (%s)" % in_list))

print("=== plans without projection (case_id) ===")
print(mysql(vals,
    "SELECT p.status, COUNT(*) FROM t_contact_plan p "
    "LEFT JOIN t_ai_collection a ON a.case_id=p.case_id "
    "WHERE p.case_id IN (%s) AND a.case_id IS NULL GROUP BY p.status" % in_list))

print("=== SMS 08:00 executed vs original completed ===")
print(mysql(vals,
    "SELECT "
    "SUM(DATE(s.executed_at)='2026-08-27') exec_today, "
    "SUM(s.status='COMPLETED' AND DATE(s.original_trigger_time)='2026-08-27') slot_completed "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='SMS' "
    "AND DATE(s.original_trigger_time)='2026-08-27' AND TIME(s.original_trigger_time)='08:00:00'" % in_list))
