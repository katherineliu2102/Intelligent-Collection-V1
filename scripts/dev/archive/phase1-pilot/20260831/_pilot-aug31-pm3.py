#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess


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


def logs(pat, extra="| tail -40"):
    cmd = "docker logs --since 20h collection-admin 2>&1 | grep -E %s %s" % (
        repr(pat),
        extra,
    )
    out = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return (out.stdout or "").strip()


vals = envfile()
print("=== 531687 ===")
print(
    mysql(
        vals,
        "SELECT case_id, collection_status, stage, dpd, overdue_amount, "
        "total_outstanding, upcoming_amount FROM t_ai_collection WHERE case_id=531687",
    )
)
print("=== 531687 today steps ===")
print(
    mysql(
        vals,
        "SELECT p.id, p.stage, p.status, s.channel_type, s.status, IFNULL(s.result,''), "
        "s.original_trigger_time, s.executed_at "
        "FROM t_contact_plan p JOIN t_contact_plan_step s ON s.plan_id=p.id "
        "WHERE p.case_id=531687 AND s.original_trigger_time>='2026-08-31 00:00:00' "
        "ORDER BY s.original_trigger_time",
    )
)
print("=== pending S2 1045-1047 ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, p.id, p.stage, p.status, p.created_at, c.dpd, c.collection_status, c.stage "
        "FROM t_contact_plan p LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
        "WHERE p.id IN (1045,1046,1047)",
    )
)
print(
    mysql(
        vals,
        "SELECT p.case_id, s.channel_type, s.status, IFNULL(s.result,''), "
        "s.original_trigger_time "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.id IN (1045,1046,1047) ORDER BY p.case_id, s.original_trigger_time",
    )
)
print("=== 505901 latest plan ===")
print(
    mysql(
        vals,
        "SELECT p.id, p.stage, p.status, p.updated_at FROM t_contact_plan p "
        "WHERE p.case_id=505901 ORDER BY p.id DESC LIMIT 3",
    )
)
csv = Path("/tmp/e2e200_loan_ids_20260829.csv").read_text().splitlines()
ids = [x.strip() for x in csv if x.strip() and x.strip().lower() != "loan_id"]
csv_in = ",".join(ids)
print("=== Push DELIVERED in200 vs not ===")
print(
    mysql(
        vals,
        "SELECT CASE WHEN p.case_id IN (%s) THEN 'in200' ELSE 'not200' END g, COUNT(*) n "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.channel_type='PUSH' AND s.executed_at>='2026-08-31 00:00:00' "
        "AND s.result='DELIVERED' GROUP BY 1" % csv_in,
    )
)
print("=== Push not200 ids ===")
print(
    mysql(
        vals,
        "SELECT p.case_id FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.channel_type='PUSH' AND s.executed_at>='2026-08-31 00:00:00' "
        "AND s.result='DELIVERED' AND p.case_id NOT IN (%s)" % csv_in,
    )
)
print("=== 14:30 AI not200 ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, s.result FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.channel_type='AI_CALL' AND s.executed_at>='2026-08-31 14:30:00' "
        "AND s.executed_at<'2026-08-31 15:00:00' AND p.case_id NOT IN (%s)" % csv_in,
    )
)
print("=== 531315 duration ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.result, s.executed_at, s.completed_at, "
        "TIMESTAMPDIFF(SECOND, s.executed_at, s.completed_at) sec "
        "FROM t_contact_plan_step s WHERE s.id=12802",
    )
)
print("=== SETNX ticks (no enroll) ===")
print(
    logs(
        "planStepDue scanned|SETNX|started batch|daily roll completed|full scan already",
        "| grep '2026-08-31' | grep -v enrolled | tail -40",
    )
    or "(none)"
)
print("=== dueDate parse poison ===")
print(
    logs(
        "非法 dueDate|非法 nextDueDate|unparseable date|DateTimeParseException",
        "| grep '2026-08-31' | tail -15",
    )
    or "(none)"
)
print("=== poison reason histogram ===")
out = subprocess.run(
    "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | grep 'poison message' | sed -E 's/.*: //; s/, caseId=.*//' | sort | uniq -c | sort -nr | head -15",
    shell=True,
    capture_output=True,
    text=True,
)
print(out.stdout or "(none)")
print("=== ingest after 14:19 caseEvent ===")
print(
    mysql(
        vals,
        "SELECT message_type, COUNT(*) n FROM t_ai_collection_inbox "
        "WHERE created_at>='2026-08-31 14:19:00' GROUP BY 1",
    )
)
