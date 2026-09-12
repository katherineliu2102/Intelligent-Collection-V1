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
print("=== S3 dayBlock dpdDays ===")
raw = mysql(vals, "SELECT CAST(plan_json AS CHAR) FROM t_contact_plan_template WHERE stage='S3' AND status='ACTIVE' LIMIT 1")
j = json.loads(raw)
days = [b.get("dpdDay") for b in j.get("dayBlocks", [])]
print("count", len(days), "min", min(days) if days else None, "max", max(days) if days else None)
print("days", days)

print("\n=== S4 dayBlock dpdDays ===")
raw4 = mysql(vals, "SELECT CAST(plan_json AS CHAR) FROM t_contact_plan_template WHERE stage='S4' AND status='ACTIVE' LIMIT 1")
j4 = json.loads(raw4)
days4 = [b.get("dpdDay") for b in j4.get("dayBlocks", [])]
print("count", len(days4), "min", min(days4) if days4 else None, "max", max(days4) if days4 else None)

print("\n=== plan 883 created ===")
print(mysql(vals, "SELECT id, case_id, stage, status, total_steps, created_at FROM t_contact_plan WHERE id IN (840,883)"))

print("\n=== case 519965 projection ===")
print(mysql(vals, "SELECT case_id, user_id, dpd, stage, collection_status, updated_at FROM t_ai_collection WHERE case_id=519965"))

print("\n=== 519965 steps on 840 vs 883 ===")
print(mysql(vals, "SELECT plan_id, COUNT(*) n, SUM(status='PENDING') pending, MIN(trigger_time), MAX(trigger_time) FROM t_contact_plan_step WHERE plan_id IN (840,883) GROUP BY plan_id"))
