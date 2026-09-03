#!/usr/bin/env python3
"""Pilot: SMS request_ids, S3 template, plan 840, push-test-token."""
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
    print("=== 08:00 SMS request_ids today ===")
    print(
        mysql(
            vals,
            "SELECT t.case_id, t.provider_msg_id, LEFT(t.content_summary, 80) summary "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id=t.step_id "
            "WHERE t.channel='SMS' AND t.direction='OUT' "
            "AND DATE(s.executed_at)=CURDATE() "
            "AND TIME(s.original_trigger_time)='08:00:00' "
            "ORDER BY t.created_at",
        )
    )
    print("=== plan 840 / case 519965 ===")
    print(
        mysql(
            vals,
            "SELECT p.id, p.case_id, p.stage, p.status, p.total_steps, p.current_step, "
            "c.dpd, c.user_id "
            "FROM t_contact_plan p LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
            "WHERE p.id=840 OR p.case_id=519965 ORDER BY p.id DESC LIMIT 8",
        )
    )
    print("=== S3 plan template (DB) ===")
    print(
        mysql(
            vals,
            "SELECT id, stage, template_code, status, "
            "LEFT(CAST(plan_json AS CHAR), 500) preview "
            "FROM t_contact_plan_template WHERE stage='S3'",
        )
    )
    print("=== push-test-token in env (masked) ===")
    tok = vals.get("CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN") or vals.get(
        "CHANNEL_PUSH_TEST_TOKEN"
    )
    print("present" if tok else "absent", "len=", len(tok) if tok else 0)
    keys = sorted(k for k in vals if "PUSH" in k or "SENDGRID" in k or "TEMPLATE" in k)
    print("env keys:", keys)


if __name__ == "__main__":
    main()
