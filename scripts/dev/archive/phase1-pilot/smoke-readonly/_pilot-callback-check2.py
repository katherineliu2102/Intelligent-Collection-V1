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
    try:
        return subprocess.check_output(
            ["mysql","-h",vals["COLLECTION_DB_HOST"],"-P",vals.get("COLLECTION_DB_PORT","3306"),
             "-u",vals["COLLECTION_DB_USERNAME"],vals["COLLECTION_DB_NAME"],"-N","-e",sql],
            env=env, stderr=subprocess.STDOUT).decode()
    except subprocess.CalledProcessError as e:
        return "SQL_ERR " + e.output.decode()

vals = envfile()
print("step", mysql(vals, "SELECT id,status,result FROM t_contact_plan_step WHERE id=2477").rstrip())
print("audit", mysql(vals, "SELECT id, step_id, result, signature_valid, received_at, provider_msg_id FROM t_channel_callback_audit WHERE step_id=2477 ORDER BY id DESC LIMIT 3").rstrip())
print("recent_audit", mysql(vals, "SELECT id, step_id, case_id, result, signature_valid, received_at FROM t_channel_callback_audit ORDER BY id DESC LIMIT 3").rstrip())
