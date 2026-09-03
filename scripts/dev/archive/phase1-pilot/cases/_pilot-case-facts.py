#!/usr/bin/env python3
"""Print plan/redis facts for smoke; no PII."""
import os
import subprocess

def envfile():
    vals = {}
    with open("/opt/app/pilot.env") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            vals[k] = v.strip().strip('"').strip("'")
    return vals

def mysql(vals, sql):
    host = vals.get("COLLECTION_DB_HOST") or vals.get("SPRING_DATASOURCE_HOST")
    # Prefer explicit collection db keys; fall back parsing URL is skipped.
    port = vals.get("COLLECTION_DB_PORT", "3306")
    user = vals.get("COLLECTION_DB_USERNAME") or vals.get("SPRING_DATASOURCE_USERNAME")
    pwd = vals.get("COLLECTION_DB_PASSWORD") or vals.get("SPRING_DATASOURCE_PASSWORD")
    db = vals.get("COLLECTION_DB_NAME")
    if not all([host, user, pwd, db]):
        raise SystemExit("missing db keys")
    env = os.environ.copy()
    env["MYSQL_PWD"] = pwd
    return subprocess.check_output(
        ["mysql", "-h", host, "-P", str(port), "-u", user, db, "-N", "-e", sql],
        env=env,
        stderr=subprocess.DEVNULL,
    ).decode()

def main():
    vals = envfile()
    print("redis_stream", vals.get("COLLECTION_REDIS_STREAM", "<unset>"))
    print("redis_db", vals.get("COLLECTION_REDIS_DB", "<unset>"))
    print("redis_host_set", "yes" if vals.get("COLLECTION_REDIS_HOST") else "no")
    row = mysql(
        vals,
        "SELECT case_id, stage, dpd, "
        "IF(borrower_name IS NULL OR borrower_name='','0','1') AS has_name, "
        "IF(due_date IS NULL,'0','1') AS has_due "
        "FROM t_ai_collection WHERE case_id=520049",
    ).strip()
    print("case_520049", row.replace("\t", " "))
    print("---plans---")
    print(
        mysql(
            vals,
            "SELECT id, stage, status, total_steps, current_step, created_at "
            "FROM t_contact_plan WHERE case_id=520049 ORDER BY id DESC LIMIT 5",
        ).rstrip()
        or "<none>"
    )
    print("---latest_steps---")
    print(
        mysql(
            vals,
            "SELECT s.step_order, s.channel_type, s.status, s.result, s.trigger_time "
            "FROM t_contact_plan_step s "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE p.case_id=520049 AND p.id=(SELECT MAX(id) FROM t_contact_plan WHERE case_id=520049) "
            "ORDER BY s.step_order",
        ).rstrip()
        or "<none>"
    )

if __name__ == "__main__":
    main()
