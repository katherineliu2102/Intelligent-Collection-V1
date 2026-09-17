#!/usr/bin/env python3
"""Pilot: phones + request ids for today's SMS/PUSH, plus the failed email case."""
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
    print("=== 08:00 SMS timeline today ===")
    print(
        mysql(
            vals,
            "SELECT t.case_id, t.result, t.provider_msg_id, t.created_at, "
            "LEFT(t.content_summary, 40) preview "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id=t.step_id "
            "WHERE t.channel='SMS' AND t.direction='OUT' "
            "AND DATE(s.executed_at)=CURDATE() "
            "AND TIME(s.original_trigger_time)='08:00:00' "
            "ORDER BY t.created_at LIMIT 5",
        )
    )
    print("=== SMS counts ===")
    print(
        mysql(
            vals,
            "SELECT t.result, COUNT(*) n "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id=t.step_id "
            "WHERE t.channel='SMS' AND t.direction='OUT' "
            "AND DATE(s.executed_at)=CURDATE() "
            "AND TIME(s.original_trigger_time)='08:00:00' "
            "GROUP BY 1",
        )
    )
    print("=== 12:00 PUSH timeline today ===")
    print(
        mysql(
            vals,
            "SELECT t.result, COUNT(*) n, MIN(t.created_at) first_at, MAX(t.created_at) last_at "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id=t.step_id "
            "WHERE t.channel='PUSH' AND t.direction='OUT' "
            "AND DATE(s.executed_at)=CURDATE() "
            "AND TIME(s.original_trigger_time)='12:00:00' "
            "GROUP BY 1",
        )
    )
    print("=== email case 529878 ===")
    print(
        mysql(
            vals,
            "SELECT p.id plan_id, p.case_id, p.stage, p.status, "
            "s.id step_id, s.status step_status, s.result, s.executed_at "
            "FROM t_contact_plan p "
            "JOIN t_contact_plan_step s ON s.plan_id=p.id "
            "WHERE p.case_id=529878 AND s.channel_type='EMAIL' "
            "AND DATE(s.original_trigger_time)=CURDATE()",
        )
    )
    print("=== projection 529878 ===")
    print(
        mysql(
            vals,
            "SELECT case_id, user_id, dpd, stage, overdue_amount, email "
            "FROM t_ai_collection WHERE case_id=529878",
        )
    )
    print("=== PLAN_EXHAUSTED 840 ===")
    print(
        mysql(
            vals,
            "SELECT id, case_id, status, current_step, total_steps, cancel_reason "
            "FROM t_contact_plan WHERE id=840",
        )
    )
    print("=== 14:30 result mix ===")
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
    print("=== 12:00 PUSH executed count ===")
    print(
        mysql(
            vals,
            "SELECT s.status, s.result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.channel_type='PUSH' "
            "AND DATE(s.executed_at)=CURDATE() "
            "AND TIME(s.original_trigger_time)='12:00:00' "
            "GROUP BY 1,2",
        )
    )


if __name__ == "__main__":
    main()
