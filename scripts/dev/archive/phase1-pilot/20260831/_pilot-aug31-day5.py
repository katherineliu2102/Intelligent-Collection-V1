#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess

IDS = "506565,519633,531157,531245,531278,531315,531368,531446"


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


vals = envfile()
print("=== last plan for 8 S2 no-active ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, p.id, p.stage, p.status, p.cancel_reason, "
        "p.created_at, p.updated_at, p.total_steps "
        "FROM t_contact_plan p "
        "JOIN (SELECT case_id, MAX(id) id FROM t_contact_plan "
        "WHERE case_id IN (%s) GROUP BY 1) t ON t.id=p.id "
        "ORDER BY p.case_id" % IDS,
    )
)
print("=== today executed for these 8 ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, s.channel_type, s.status, s.result "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) "
        "AND (s.executed_at>='2026-08-31 00:00:00' "
        "OR s.original_trigger_time>='2026-08-31 00:00:00') "
        "ORDER BY p.case_id, s.original_trigger_time" % IDS,
    )
)
print("=== inbox caseEvent today for 8 ===")
print(
    mysql(
        vals,
        "SELECT case_id, created_at FROM t_ai_collection_inbox "
        "WHERE message_type='caseEvent' AND created_at>='2026-08-31 00:00:00' "
        "AND case_id IN (%s)" % IDS,
    )
)
