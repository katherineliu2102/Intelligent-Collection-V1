#!/usr/bin/env python3
"""S1/S2 drift and repay skipOpen. No PII."""
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


def load_csv():
    ids = []
    for line in Path(CSV).read_text().splitlines():
        body = line.strip()
        if body and body.lower() != "loan_id":
            ids.append(body)
    return ids


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


vals = envfile()
csv_in = ",".join(load_csv())

print("=== proj vs active plan stage (200 IN_COLLECTION) ===")
print(
    mysql(
        vals,
        "SELECT IFNULL(c.stage,'NULL') proj, IFNULL(p.stage,'NONE') plan, "
        "COUNT(*) n "
        "FROM t_ai_collection c "
        "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id "
        "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "WHERE c.case_id IN (%s) AND c.collection_status='IN_COLLECTION' "
        "GROUP BY 1,2 ORDER BY 1,2" % csv_in,
    )
)
print("=== dpd=4 mismatch detail ===")
print(
    mysql(
        vals,
        "SELECT c.case_id, c.stage proj, c.dpd, IFNULL(p.stage,'NONE') plan, "
        "IFNULL(p.status,'NONE'), IFNULL(DATE(p.created_at),'NONE') "
        "FROM t_ai_collection c "
        "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id "
        "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "WHERE c.case_id IN (%s) AND c.dpd=4 "
        "ORDER BY p.stage, c.case_id" % csv_in,
    )
)
print("=== 531687 leftover pending ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.channel_type, s.status, s.trigger_time, s.original_trigger_time "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.id=909 AND s.status IN ('PENDING','EXECUTING')",
    )
)
print("=== 531713 leftover pending on cancelled ===")
print(
    mysql(
        vals,
        "SELECT s.status, COUNT(*) FROM t_contact_plan_step s "
        "WHERE s.plan_id=908 GROUP BY 1",
    )
)
print("=== 505901 skipOpen ok? ===")
print(
    mysql(
        vals,
        "SELECT s.status, COUNT(*) FROM t_contact_plan_step s "
        "WHERE s.plan_id=1017 GROUP BY 1",
    )
)
print("=== scanned vs projection: 184 vs 179 ===")
print(
    mysql(
        vals,
        "SELECT collection_status, COUNT(*) n FROM t_ai_collection GROUP BY 1",
    )
)
