#!/usr/bin/env python3
"""14:30 leftover PENDING + 12:00 leftover + ANSWERED check."""
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
    ).decode()


def main():
    vals = envfile()
    print("=== 14:30 PENDING leftover ===")
    print(
        mysql(
            vals,
            "SELECT s.id, s.plan_id, p.case_id, p.status plan_status, p.cancel_reason, "
            "s.status, s.result, s.executed_at "
            "FROM t_contact_plan_step s "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.channel_type='AI_CALL' "
            "AND s.original_trigger_time=CONCAT(CURDATE(),' 14:30:00') "
            "AND s.status='PENDING'",
        )
    )
    print("=== 12:00 PUSH PENDING leftover ===")
    print(
        mysql(
            vals,
            "SELECT s.id, s.plan_id, p.case_id, p.status plan_status, p.cancel_reason, "
            "s.status, s.executed_at "
            "FROM t_contact_plan_step s "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.channel_type='PUSH' "
            "AND s.original_trigger_time=CONCAT(CURDATE(),' 12:00:00') "
            "AND s.status='PENDING'",
        )
    )
    print("=== 14:30 ANSWERED / CONNECT_AND_STOP ===")
    print(
        mysql(
            vals,
            "SELECT s.status, s.result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.channel_type='AI_CALL' "
            "AND s.original_trigger_time=CONCAT(CURDATE(),' 14:30:00') "
            "GROUP BY 1,2",
        )
    )
    print("=== 当日 executed AI result ===")
    print(
        mysql(
            vals,
            "SELECT s.result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.channel_type='AI_CALL' "
            "AND DATE(s.executed_at)=CURDATE() "
            "AND TIME(s.original_trigger_time)='14:30:00' "
            "GROUP BY 1",
        )
    )
    print("=== 14:00 email step ===")
    print(
        mysql(
            vals,
            "SELECT s.id, s.plan_id, p.case_id, s.status, s.result, s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.channel_type='EMAIL' "
            "AND s.original_trigger_time=CONCAT(CURDATE(),' 14:00:00')",
        )
    )


if __name__ == "__main__":
    main()
