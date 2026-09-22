#!/usr/bin/env python3
"""9/1 14:30 miss + health 503 follow-up. No PII."""
from pathlib import Path
import os
import subprocess

TODAY = "2026-09-01"


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
health = subprocess.run(
    ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8080/actuator/health"],
    capture_output=True,
    text=True,
).stdout
print("health", health)
insp = subprocess.check_output(
    ["docker", "inspect", "-f", "{{.State.Status}} {{.State.StartedAt}} {{.State.Health.Status}}", "collection-admin"],
    text=True,
).strip()
print("container", insp)
print("=== 14:30 AI now ===")
print(
    mysql(
        vals,
        "SELECT s.status, IFNULL(s.result,''), COUNT(*) n, MIN(s.executed_at), MAX(s.executed_at) "
        "FROM t_contact_plan_step s "
        "WHERE s.channel_type='AI_CALL' AND s.original_trigger_time>='%s 14:30:00' "
        "AND s.original_trigger_time<'%s 14:31:00' GROUP BY 1,2" % (TODAY, TODAY),
    )
)
print("=== 531682 ===")
print(
    mysql(
        vals,
        "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id=531682",
    )
)
print(
    mysql(
        vals,
        "SELECT id, stage, status, updated_at FROM t_contact_plan WHERE case_id=531682 ORDER BY id DESC LIMIT 2",
    )
)
print("=== 531315 14:30 ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.status, IFNULL(s.result,'') FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id=531315 AND s.original_trigger_time>='%s 14:30:00' "
        "AND s.original_trigger_time<'%s 14:31:00'" % (TODAY, TODAY),
    )
)
print("=== EXH3 14:30 ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, s.status, IFNULL(s.result,'') FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (520023,531516,531578) AND s.channel_type='AI_CALL' "
        "AND s.original_trigger_time>='%s 14:30:00' "
        "AND s.original_trigger_time<'%s 14:31:00'" % (TODAY, TODAY),
    )
)
print("=== executing now ===")
print(mysql(vals, "SELECT COUNT(*) n FROM t_contact_plan_step WHERE status='EXECUTING'"))
out = subprocess.run(
    "docker logs --since 20m collection-admin 2>&1 | grep -E 'health|Hikari|Connection is not available|wave started|planStepDue|14:30' | grep -v enrolled | tail -40",
    shell=True,
    capture_output=True,
    text=True,
)
print("=== recent logs ===")
print(out.stdout or "(none)")
print("=== health body ===")
hb = subprocess.run(["curl", "-s", "http://127.0.0.1:8080/actuator/health"], capture_output=True, text=True)
print((hb.stdout or "")[:800])
