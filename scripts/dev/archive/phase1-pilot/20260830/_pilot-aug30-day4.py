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
print("=== pending 09:15 case_ids ===")
print(
    mysql(
        vals,
        "SELECT p.case_id FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.original_trigger_time='2026-08-30 09:15:00' "
        "AND s.status='PENDING' ORDER BY p.case_id",
    )
)
print("=== 08:00 ticks ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 08:00' | grep scanned="))
print("=== daily roll completed all ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30' | grep 'daily roll completed'"))
print("=== 03:35 ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 03:35' | grep -E 'daily roll|scanned='"))
