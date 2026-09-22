#!/usr/bin/env python3
import os, subprocess, json

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
open("/tmp/sms_request_ids.txt","w").write(mysql(vals,
    "SELECT t.provider_msg_id FROM t_contact_timeline t "
    "JOIN t_contact_plan_step s ON s.id=t.step_id "
    "WHERE t.channel='SMS' AND t.direction='OUT' "
    "AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='08:00:00'"))
open("/tmp/push_user_ids.txt","w").write(mysql(vals,
    "SELECT DISTINCT a.user_id FROM t_ai_collection a "
    "JOIN t_contact_plan p ON p.case_id=a.case_id "
    "JOIN t_contact_plan_step s ON s.plan_id=p.id "
    "WHERE s.channel_type='PUSH' AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='12:00:00'"))
open("/tmp/sms_user_ids.txt","w").write(mysql(vals,
    "SELECT DISTINCT a.user_id FROM t_ai_collection a "
    "JOIN t_contact_plan p ON p.case_id=a.case_id "
    "JOIN t_contact_plan_step s ON s.plan_id=p.id "
    "WHERE s.channel_type='SMS' AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='08:00:00'"))
print("sms req", open("/tmp/sms_request_ids.txt").read().count("\n"))
print("push users", open("/tmp/push_user_ids.txt").read())
print("sms users", open("/tmp/sms_user_ids.txt").read())
