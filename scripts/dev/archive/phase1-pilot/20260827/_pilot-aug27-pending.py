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
print("=== pending today 08:00/09:15 ===")
print(mysql(vals,
    "SELECT s.id, p.case_id, p.id plan_id, p.status plan_status, s.channel_type, s.status, "
    "s.trigger_time, s.original_trigger_time, s.step_order "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.status='PENDING' "
    "AND DATE(s.original_trigger_time)='2026-08-27' "
    "AND TIME(s.original_trigger_time) IN ('08:00:00','09:15:00') "
    "ORDER BY s.original_trigger_time" % in_list))
print("=== ANSWERED today ===")
print(mysql(vals,
    "SELECT s.id, p.case_id, p.id, s.status, s.result, s.executed_at, s.completed_at "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='AI_CALL' AND s.result='ANSWERED' "
    "AND DATE(COALESCE(s.completed_at,s.executed_at))='2026-08-27'" % in_list))
print("=== skipped 14:30 same plan as ANSWERED ===")
print(mysql(vals,
    "SELECT s.id, s.plan_id, s.status, s.result, s.original_trigger_time "
    "FROM t_contact_plan_step s "
    "WHERE s.plan_id IN ("
    " SELECT s2.plan_id FROM t_contact_plan_step s2 "
    " JOIN t_contact_plan p ON p.id=s2.plan_id "
    " WHERE p.case_id IN (%s) AND s2.result='ANSWERED' AND s2.channel_type='AI_CALL' "
    " AND DATE(COALESCE(s2.completed_at,s2.executed_at))='2026-08-27') "
    "AND s.channel_type='AI_CALL' AND TIME(s.original_trigger_time)='14:30:00' "
    "AND DATE(s.original_trigger_time)='2026-08-27'" % in_list))
print("=== whitelist vs projection ===")
print(mysql(vals,
    "SELECT COUNT(*) whitelist_in_env"))
# count how many whitelist ids exist in t_ai_collection
print(mysql(vals,
    "SELECT COUNT(*) in_projection FROM t_ai_collection WHERE case_id IN (%s)" % in_list))
print(mysql(vals,
    "SELECT COUNT(*) in_projection_id FROM t_ai_collection WHERE id IN (%s)" % in_list))
print("=== 08:00 scan ===")
print(mysql(vals,
    "SELECT DATE_FORMAT(s.executed_at,'%%H:%%i') hh, COUNT(*) FROM t_contact_plan_step s "
    "JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='SMS' AND DATE(s.executed_at)='2026-08-27' "
    "GROUP BY hh" % in_list))
print("=== AI executed_at minute buckets today ===")
print(mysql(vals,
    "SELECT DATE_FORMAT(s.executed_at,'%%H:%%i') hh, s.result, COUNT(*) "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='AI_CALL' AND DATE(s.executed_at)='2026-08-27' "
    "GROUP BY hh, s.result ORDER BY hh" % in_list))
print("=== SMS fail/skip today original 08:00 ===")
print(mysql(vals,
    "SELECT s.id, p.case_id, s.status, s.result, s.executed_at, s.completed_at "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='SMS' "
    "AND DATE(s.original_trigger_time)='2026-08-27' AND TIME(s.original_trigger_time)='08:00:00' "
    "AND s.status<>'COMPLETED' ORDER BY s.status" % in_list))
