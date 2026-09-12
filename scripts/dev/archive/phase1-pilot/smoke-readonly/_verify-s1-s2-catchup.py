#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess

LOANS = "506565,519633,531157,531245,531278,531315,531368,531446"
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


def main():
    vals = envfile()
    print("=== 8 cases plans ===")
    print(
        mysql(
            vals,
            "SELECT case_id, id, stage, status, created_at, updated_at "
            "FROM t_contact_plan WHERE case_id IN (%s) ORDER BY case_id, id" % LOANS,
        )
    )
    print("=== 8 cases active + steps today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.stage, p.status, s.channel_type, s.status step_status, "
            "DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, s.original_trigger_time "
            "FROM t_contact_plan p "
            "LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id "
            "AND s.original_trigger_time >= '%s 00:00:00' "
            "AND s.original_trigger_time < '%s 23:59:59' "
            "WHERE p.case_id IN (%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "ORDER BY p.case_id, s.original_trigger_time" % (TODAY, TODAY, LOANS),
        )
    )
    print("=== extra S2->S3 513749 526109 ===")
    print(
        mysql(
            vals,
            "SELECT case_id, id, stage, status, created_at "
            "FROM t_contact_plan WHERE case_id IN (513749,526109) ORDER BY case_id, id",
        )
    )


if __name__ == "__main__":
    main()
