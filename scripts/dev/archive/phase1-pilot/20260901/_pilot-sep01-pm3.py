#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess

vals = {}
for line in Path("/opt/app/pilot.env").read_text().splitlines():
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
            "-e",
            sql,
        ],
        env=env,
        stderr=subprocess.STDOUT,
    ).decode()


print(
    "health",
    subprocess.run(
        [
            "curl",
            "-s",
            "-o",
            "/dev/null",
            "-w",
            "%{http_code}",
            "http://127.0.0.1:8080/actuator/health",
        ],
        capture_output=True,
        text=True,
    ).stdout,
)
print("=== 1430 now ===")
print(
    q(
        "SELECT s.status, IFNULL(s.result,'') r, COUNT(*) n, "
        "MIN(s.executed_at), MAX(s.executed_at) "
        "FROM t_contact_plan_step s "
        "WHERE s.channel_type='AI_CALL' "
        "AND s.original_trigger_time>='2026-09-01 14:30:00' "
        "AND s.original_trigger_time<'2026-09-01 14:31:00' GROUP BY 1,2"
    )
)
print("executing", q("SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'").strip())
print("=== 531682 ===")
print(q("SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id=531682"))
print(
    q(
        "SELECT id, stage, status, updated_at FROM t_contact_plan "
        "WHERE case_id=531682 ORDER BY id DESC LIMIT 2"
    )
)
print("=== 513849 step 10793 ===")
print(q("SELECT id, status, IFNULL(result,'') r FROM t_contact_plan_step WHERE id=10793"))
