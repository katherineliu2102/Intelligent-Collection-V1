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
print(mysql(vals, "SELECT id, plan_id, channel_type, status, result, trigger_time, original_trigger_time, executed_at FROM t_contact_plan_step WHERE id IN (2164,2172,2173,2461,2181,2177) ORDER BY executed_at,id"))
print(mysql(vals, "SELECT id, step_id, result, signature_valid FROM t_channel_callback_audit WHERE step_id IN (2461,2181,2177,2477) ORDER BY id DESC LIMIT 8"))
print(mysql(vals, "SELECT id, status, result, executed_at, completed_at, IFNULL(timeout_time,'NULL') timeout FROM t_contact_plan_step WHERE id IN (2461,2181,2177)"))
