#!/usr/bin/env python3
"""Check FIRM tone usage on Pilot. No PII."""
from pathlib import Path
import os
import subprocess

vals = {}
for line in Path("/opt/app/pilot.env").read_text().splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    vals[k] = v.strip().strip('"').strip("'")
env = os.environ.copy()
env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]


def q(sql):
    return subprocess.check_output(
        [
            "mysql",
            "-N",
            "-h",
            vals["COLLECTION_DB_HOST"],
            "-u",
            vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-e",
            sql,
        ],
        env=env,
    ).decode()


print("=== context_snapshot strategyTone ===")
print(
    q(
        "SELECT "
        "SUM(context_snapshot LIKE '%\"strategyTone\":\"FIRM\"%'), "
        "SUM(context_snapshot LIKE '%\"strategyTone\":\"STANDARD\"%'), "
        "COUNT(*) FROM t_contact_plan"
    )
)
print("=== SMS scriptSlot from resolved_params ===")
print(
    q(
        "SELECT JSON_UNQUOTE(JSON_EXTRACT(resolved_params,'$.scriptSlot')), COUNT(*) "
        "FROM t_contact_plan_step WHERE channel_type='SMS' AND resolved_params IS NOT NULL "
        "GROUP BY 1 ORDER BY 2 DESC LIMIT 15"
    )
)
print("=== FIRM SMS slots ever ===")
print(
    q(
        "SELECT COUNT(*) FROM t_contact_plan_step "
        "WHERE channel_type='SMS' AND resolved_params LIKE '%_SMS_FIRM%'"
    )
)
print("=== S2+ cases projection ===")
print(
    q(
        "SELECT stage, COUNT(*) FROM t_ai_collection "
        "WHERE stage IN ('S2','S3','S4') GROUP BY 1"
    )
)
print("=== plan template tone column ===")
print(q("SELECT stage, tone, COUNT(*) FROM t_contact_plan_template GROUP BY 1,2"))
