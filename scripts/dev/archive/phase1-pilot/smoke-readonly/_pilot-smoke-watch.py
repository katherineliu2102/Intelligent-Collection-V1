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
        env=env, stderr=subprocess.DEVNULL).decode()

vals = envfile()
print("===steps===")
print(mysql(vals, "SELECT id, step_order, channel_type, status, result, trigger_time, executed_at FROM t_contact_plan_step WHERE id IN (2467,2477,2480,2639) ORDER BY id").rstrip())
print("===timeline_today===")
print(mysql(vals, "SELECT id, channel, result, created_at FROM t_contact_timeline WHERE case_id=489935 AND created_at >= '2026-08-26 00:00:00' ORDER BY id").rstrip() or "<none>")
