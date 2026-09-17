#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Cancel leftover plans not in e2e200, and close EXECUTING steps. No PII."""
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
    ).decode()


ids = []
for line in Path(CSV).read_text().splitlines():
    body = line.strip()
    if body and body.lower() != "loan_id":
        ids.append(body)
csv_in = ",".join(ids)
vals = envfile()

print("=== before hanging ===")
print(mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'"))
print("=== before old-circle active plans ===")
print(
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_contact_plan "
        "WHERE status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND case_id NOT IN (%s)" % csv_in,
    )
)

print(
    mysql(
        vals,
        "UPDATE t_contact_plan_step SET status='SKIPPED', result='SKIPPED', "
        "completed_at=COALESCE(completed_at, NOW()), updated_at=NOW() "
        "WHERE status='EXECUTING'",
    )
)
print(
    mysql(
        vals,
        "UPDATE t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "SET s.status='SKIPPED', s.result='SKIPPED', "
        "s.completed_at=COALESCE(s.completed_at, NOW()), s.updated_at=NOW() "
        "WHERE s.status='PENDING' "
        "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND p.case_id NOT IN (%s)" % csv_in,
    )
)
print(
    mysql(
        vals,
        "UPDATE t_contact_plan SET status='PLAN_CANCELLED', "
        "cancel_reason='MANUAL_CLEANUP', updated_at=NOW() "
        "WHERE status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND case_id NOT IN (%s)" % csv_in,
    )
)

print("=== after hanging ===")
print(mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'"))
print("=== after old-circle active plans ===")
print(
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_contact_plan "
        "WHERE status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND case_id NOT IN (%s)" % csv_in,
    )
)
print("=== e2e200 still active ===")
print(
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_contact_plan "
        "WHERE status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND case_id IN (%s)" % csv_in,
    )
)
