#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Pull four pending steps for 489935 into the next few minutes. No PII."""
import os
import subprocess
from datetime import datetime, timedelta, timezone

PHT = timezone(timedelta(hours=8))
vals = {}
with open("/opt/app/pilot.env") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")

def mysql(sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
    return subprocess.check_output(
        [
            "mysql",
            "-h", vals["COLLECTION_DB_HOST"],
            "-P", vals.get("COLLECTION_DB_PORT", "3306"),
            "-u", vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-N",
            "-e",
            sql,
        ],
        env=env,
        stderr=subprocess.DEVNULL,
    ).decode()

now = datetime.now(PHT).replace(second=0, microsecond=0).replace(tzinfo=None)
jobs = [
    (2467, "SMS", 3),
    (2477, "AI_CALL", 5),
    (2480, "PUSH", 7),
    (2639, "EMAIL", 9),
]
for step_id, channel, plus in jobs:
    when = (now + timedelta(minutes=plus)).strftime("%Y-%m-%d %H:%M:%S")
    n = mysql(
        "UPDATE t_contact_plan_step SET trigger_time='%s', updated_at=NOW() "
        "WHERE id=%d AND status='PENDING'" % (when, step_id)
    )
    print("step", step_id, channel, "->", when)

print("---verify---")
print(
    mysql(
        "SELECT id, step_order, channel_type, status, trigger_time "
        "FROM t_contact_plan_step WHERE id IN (2467,2477,2480,2639) ORDER BY trigger_time"
    ).rstrip()
)
