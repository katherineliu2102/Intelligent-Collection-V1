#!/usr/bin/env python3
"""Who created S2 plans at 14:37; S1 completions today."""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-31"


def envfile():
    vals = {}
    for line in Path("/opt/app/pilot.env").read_text().splitlines():
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


vals = envfile()
print("=== S1 plans updated today ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, p.id, p.stage, p.status, p.updated_at, c.dpd, c.stage cst "
        "FROM t_contact_plan p LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
        "WHERE p.stage='S1' AND p.updated_at>='%s 00:00:00' "
        "ORDER BY p.status, p.case_id" % TODAY,
    )
)
print("=== plans created after 14:30 ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, p.id, p.stage, p.status, p.created_at "
        "FROM t_contact_plan p WHERE p.created_at>='%s 14:30:00' ORDER BY p.id" % TODAY,
    )
)
print("=== 11 morning S1 (dpd=3) current plans ===")
print(
    mysql(
        vals,
        "SELECT c.case_id, c.dpd, c.stage, p.id, p.stage pst, p.status, p.created_at, p.updated_at "
        "FROM t_ai_collection c "
        "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "WHERE c.stage='S1' AND c.dpd=3 ORDER BY c.case_id",
    )
)

cmd = (
    "docker logs --since 3h collection-admin 2>&1 | grep -E "
    "'520023|531516|531578|STAGE_CHANGED|daily roll|Advancement|PLAN_COMPLETED|createPlan|new plan' "
    "| grep -v enrolled | tail -80"
)
out = subprocess.run(cmd, shell=True, capture_output=True, text=True)
print("=== logs 14:30+ related ===")
print(out.stdout or "(none)")
print("=== scanned ticks 14:30+ ===")
out2 = subprocess.run(
    "docker logs --since 3h collection-admin 2>&1 | grep -E 'planStepDue scanned|daily roll|SETNX|started batchId|wave mocasa' | grep -v enrolled | tail -40",
    shell=True,
    capture_output=True,
    text=True,
)
print(out2.stdout or "(none)")
