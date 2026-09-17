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
print("=== t_ai_collection columns (name-like) ===")
print(mysql(vals, "SHOW COLUMNS FROM t_ai_collection"))
print("=== 4 SMS cases profile ===")
print(mysql(vals, """
SELECT c.case_id, c.user_id, c.dpd, c.stage, c.total_outstanding, c.overdue_amount
FROM t_ai_collection c
WHERE c.case_id IN (529878,528136,519965,517047)
"""))
print("=== docker push enqueue around 12:00 ===")
