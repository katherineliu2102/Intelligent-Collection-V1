#!/usr/bin/env python3
import os, subprocess

vals = {}
for line in open("/opt/app/pilot.env"):
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    vals[k] = v.strip().strip('"').strip("'")
env = os.environ.copy()
env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
ids = ",".join(
    x.strip()
    for x in vals.get("COLLECTION_SCAN_CASE_IDS", "").replace(";", ",").split(",")
    if x.strip()
)
sql = (
    "SELECT p.case_id, p.id plan_id, s.id step_id, p.stage, s.trigger_time, "
    "c.dpd, c.borrower_email "
    "FROM t_contact_plan_step s "
    "JOIN t_contact_plan p ON p.id=s.plan_id "
    "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
    "WHERE p.case_id IN (%s) AND s.channel_type='EMAIL' AND s.status='PENDING' "
    "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time))='2026-08-28'"
    % ids
)
print(
    subprocess.check_output(
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
)
