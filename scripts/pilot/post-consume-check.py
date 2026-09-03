#!/usr/bin/env python3
"""Post-deploy check: empty lists, no hanging EXECUTING, old circle stopped."""
from pathlib import Path
import os
import subprocess

CSV = "/tmp/e2e200_loan_ids_20260829.csv"


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
            "-N",
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
    ).decode().strip()


ids = []
for line in Path(CSV).read_text().splitlines():
    body = line.strip()
    if body and body.lower() != "loan_id":
        ids.append(body)
csv_in = ",".join(ids)
vals = envfile()
pilot_n = len([x for x in vals.get("COLLECTION_PILOT_LOAN_IDS", "").split(",") if x.strip()])
scan_n = len([x for x in vals.get("COLLECTION_SCAN_CASE_IDS", "").split(",") if x.strip()])
print("pilot_n", pilot_n)
print("scan_n", scan_n)
print("daily_roll_env", vals.get("COLLECTION_DAILY_ROLL_FULL_SCAN_ENABLED", "<unset>"))
print("executing", mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'"))
print(
    "old_circle_active",
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_contact_plan "
        "WHERE status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND case_id NOT IN (%s)" % csv_in,
    ),
)
print(
    "e2e200_active",
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_contact_plan "
        "WHERE status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND case_id IN (%s)" % csv_in,
    ),
)
print(
    "started",
    subprocess.check_output(
        ["docker", "inspect", "-f", "{{.State.StartedAt}} {{.State.Status}}", "collection-admin"]
    )
    .decode()
    .strip(),
)
