#!/usr/bin/env python3
import os
import subprocess

vals = {}
with open("/opt/app/pilot.env") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")

env = os.environ.copy()
env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]

def q(sql):
    return subprocess.check_output(
        [
            "mysql",
            "-h",
            vals["COLLECTION_DB_HOST"],
            "-P",
            vals.get("COLLECTION_DB_PORT", "3306"),
            "-u",
            vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-N",
            "-e",
            sql,
        ],
        env=env,
        stderr=subprocess.DEVNULL,
    ).decode().strip()

print("plan", q("SELECT id, status, current_step FROM t_contact_plan WHERE id=862"))
print("step", q("SELECT id, status, result FROM t_contact_plan_step WHERE id=2477"))
