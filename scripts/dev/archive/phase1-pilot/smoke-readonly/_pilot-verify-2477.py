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
print(mysql(vals, "SELECT id, attempt_key, result, provider_msg_id FROM t_contact_timeline WHERE step_id=2477"))
print(mysql(vals, "SELECT id,status,result,completed_at,timeout_time FROM t_contact_plan_step WHERE id=2477"))
print(mysql(vals, "SELECT id,status,current_step FROM t_contact_plan WHERE id=862"))
