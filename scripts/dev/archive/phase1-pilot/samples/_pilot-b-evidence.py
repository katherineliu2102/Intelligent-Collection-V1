#!/usr/bin/env python3
import os
import subprocess

STEPS = "2168,2454,2170,2461,2181,2177"


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


vals = envfile()
print("=== steps ===")
print(
    mysql(
        vals,
        "SELECT id, plan_id, channel_type, status, result, executed_at, completed_at, "
        "IFNULL(timeout_time,'NULL') timeout "
        "FROM t_contact_plan_step WHERE id IN (%s) ORDER BY id" % STEPS,
    )
)
print("=== timeline ===")
print(
    mysql(
        vals,
        "SELECT step_id, channel, result, LEFT(IFNULL(provider_msg_id,''),48) mid, "
        "LEFT(IFNULL(provider_callback,''),160) cb, created_at "
        "FROM t_contact_timeline WHERE step_id IN (%s) ORDER BY id" % STEPS,
    )
)
print("=== audit ===")
print(
    mysql(
        vals,
        "SELECT id, step_id, result, signature_valid, "
        "LEFT(IFNULL(failure_reason, IFNULL(reason,'')),80) reason "
        "FROM t_channel_callback_audit WHERE step_id IN (2461,2181,2177) ORDER BY id",
    )
)
