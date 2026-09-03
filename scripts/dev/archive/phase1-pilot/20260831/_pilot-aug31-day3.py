#!/usr/bin/env python3
"""8/31 daily-roll first tick + S1->S2 plans. No PII."""
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


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout


vals = envfile()
csv_in = ",".join(load_csv())

print("=== daily roll first ===")
print(
    sh(
        "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -E 'DpdStageRollHandler|daily roll completed|stageChanged|full scan' | head -40"
    )
)
print("=== plans created today ===")
print(
    mysql(
        vals,
        "SELECT DATE_FORMAT(created_at,'%%H:%%i') hh, stage, status, COUNT(*) n "
        "FROM t_contact_plan WHERE created_at>='2026-08-31 00:00:00' "
        "GROUP BY 1,2,3 ORDER BY 1,2",
    )
)
print("=== dpd=4 S2 in200: has email today? plan created when? ===")
print(
    mysql(
        vals,
        "SELECT "
        "SUM(s.id IS NOT NULL) has_email, "
        "COUNT(DISTINCT c.case_id) cases "
        "FROM t_ai_collection c "
        "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id "
        "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id AND s.channel_type='EMAIL' "
        "AND s.original_trigger_time>='2026-08-31 00:00:00' "
        "AND s.original_trigger_time<'2026-08-31 23:59:59' "
        "WHERE c.case_id IN (%s) AND c.dpd=4 AND c.stage='S2'" % csv_in,
    )
)
print("=== dpd=4 S2 plan created_at / stage ===")
print(
    mysql(
        vals,
        "SELECT DATE(p.created_at) d, p.stage, COUNT(DISTINCT c.case_id) n "
        "FROM t_ai_collection c "
        "JOIN t_contact_plan p ON p.case_id=c.case_id "
        "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "WHERE c.case_id IN (%s) AND c.dpd=4 AND c.stage='S2' "
        "GROUP BY 1,2" % csv_in,
    )
)
print("=== timeline today OUT ===")
print(
    mysql(
        vals,
        "SELECT channel, direction, COUNT(*) n FROM t_contact_timeline "
        "WHERE created_at>='2026-08-31 00:00:00' GROUP BY 1,2",
    )
)
print("=== 09:15 batch more ===")
print(
    sh(
        "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31 09:15' | "
        "grep -E 'SETNX|wave|cases=|batch' | tail -20"
    )
)
print("=== skipOpen after repay 505901 ===")
print(
    mysql(
        vals,
        "SELECT p.id, p.case_id, p.status, p.cancel_reason, "
        "SUM(s.status='EXECUTING') exec_n, SUM(s.status='PENDING') pend_n, "
        "SUM(s.status='SKIPPED') skip_n "
        "FROM t_contact_plan p JOIN t_contact_plan_step s ON s.plan_id=p.id "
        "WHERE p.case_id IN (505901,530618,531687,531713) "
        "GROUP BY 1,2,3,4 ORDER BY p.case_id, p.id",
    )
)
