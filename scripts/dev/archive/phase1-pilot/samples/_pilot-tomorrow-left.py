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
         "-u",vals["COLLECTION_DB_USERNAME"],vals["COLLECTION_DB_NAME"],"-N","-e",sql],
        env=env).decode()

vals = envfile()
ids = [x.strip() for x in vals.get("COLLECTION_SCAN_CASE_IDS","").replace(";",",").split(",") if x.strip()]
in_list = ",".join(ids)
print("aug27_sms_still_pending", mysql(vals,
    "SELECT COUNT(*) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='SMS' AND s.status='PENDING' "
    "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time))='2026-08-27' "
    "AND TIME(COALESCE(s.trigger_time,s.original_trigger_time))='08:00:00'" % in_list).strip())
print("aug27_sms_already_terminal_today", mysql(vals,
    "SELECT COUNT(*) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='SMS' "
    "AND DATE(s.original_trigger_time)='2026-08-27' "
    "AND TIME(s.original_trigger_time)='08:00:00' "
    "AND s.status IN ('COMPLETED','FAILED','SKIPPED') "
    "AND DATE(s.completed_at)='2026-08-26'" % in_list).strip())
print("aug27_pending_by_channel", mysql(vals,
    "SELECT s.channel_type, TIME(COALESCE(s.trigger_time,s.original_trigger_time)), COUNT(*) "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE p.case_id IN (%s) AND s.status='PENDING' "
    "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time))='2026-08-27' "
    "GROUP BY 1,2 ORDER BY 2,1" % in_list).rstrip())
print("health", subprocess.check_output(["curl","-s","-o","/dev/null","-w","%{http_code}","http://127.0.0.1:8080/actuator/health"]).decode())
