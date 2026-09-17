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
sql = (
    "SELECT case_id, borrower_name, dpd, stage, total_outstanding "
    "FROM t_ai_collection "
    "WHERE case_id IN (529878,528136,519965,517047)"
)
def mysql(sql):
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


print(mysql(sql))
print("=== whitelist 10 as case_id ===")
print(
    mysql(
        "SELECT case_id, user_id, dpd, stage, collection_status "
        "FROM t_ai_collection WHERE case_id IN "
        "(468703,474696,504174,529225,529877,504887,530187,528834,528813,489984)"
    )
)
print("=== whitelist 10 as user_id ===")
print(
    mysql(
        "SELECT case_id, user_id, dpd, stage "
        "FROM t_ai_collection WHERE user_id IN "
        "(468703,474696,504174,529225,529877,504887,530187,528834,528813,489984)"
    )
)
