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
print("---plan---")
print(mysql(vals, "SELECT id, stage, status, total_steps, current_step FROM t_contact_plan WHERE case_id=489935 AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') ORDER BY id DESC LIMIT 1").rstrip())
print("---pending_by_channel---")
print(mysql(vals, """
SELECT s.channel_type, MIN(s.trigger_time), COUNT(*)
FROM t_contact_plan_step s
JOIN t_contact_plan p ON p.id=s.plan_id
WHERE p.case_id=489935 AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')
AND s.status='PENDING'
GROUP BY s.channel_type
ORDER BY s.channel_type
""").rstrip())
print("---next8---")
print(mysql(vals, """
SELECT s.id, s.step_order, s.channel_type, s.status, s.trigger_time
FROM t_contact_plan_step s
JOIN t_contact_plan p ON p.id=s.plan_id
WHERE p.case_id=489935 AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')
AND s.status='PENDING'
ORDER BY s.trigger_time
LIMIT 8
""").rstrip())
