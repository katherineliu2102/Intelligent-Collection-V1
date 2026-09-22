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
print("=== t_ai_collection columns ===")
print(mysql(vals, "SHOW COLUMNS FROM t_ai_collection"))
print("=== 529878 row ===")
print(mysql(vals, "SELECT * FROM t_ai_collection WHERE case_id=529878\\G"))
print("=== SMS request ids ===")
print(mysql(vals,
    "SELECT t.provider_msg_id FROM t_contact_timeline t "
    "JOIN t_contact_plan_step s ON s.id=t.step_id "
    "WHERE t.channel='SMS' AND t.direction='OUT' "
    "AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='08:00:00'"))
print("=== PUSH request ids sample ===")
print(mysql(vals,
    "SELECT t.provider_msg_id FROM t_contact_timeline t "
    "JOIN t_contact_plan_step s ON s.id=t.step_id "
    "WHERE t.channel='PUSH' AND t.direction='OUT' "
    "AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='12:00:00' LIMIT 8"))
print("=== plan 840 ===")
print(mysql(vals, "SELECT id, case_id, status, current_step, total_steps FROM t_contact_plan WHERE id=840"))
print("=== decision_log email 3128 ===")
print(mysql(vals, "SELECT step_id, channel, script_slot, LEFT(detail,200) FROM t_decision_log WHERE step_id=3128"))
