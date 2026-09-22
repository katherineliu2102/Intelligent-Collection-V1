#!/usr/bin/env python3
"""Post-deploy checks. No PII."""
import os
import subprocess

REDIRECTS = [
    "CHANNEL_FACADE_TEST_CALLEE",
    "CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT",
    "CHANNEL_SENDGRID_TEST_RECIPIENT",
    "CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN",
]


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
            "-N",
            "-e",
            sql,
        ],
        env=env,
    ).decode().strip()


vals = envfile()
print("health_loopback", subprocess.check_output(
    ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8080/actuator/health"]
).decode())
print("container", subprocess.check_output(
    ["docker", "ps", "--filter", "name=collection-admin", "--format", "{{.Names}} {{.Status}} {{.Image}}"]
).decode().strip())
print("step2477", mysql(vals, "SELECT id,status,result,IFNULL(timeout_time,'NULL') FROM t_contact_plan_step WHERE id=2477"))
print("plan862", mysql(vals, "SELECT id,status,current_step FROM t_contact_plan WHERE id=862"))
for k in REDIRECTS:
    print("redirect", k, "SET" if k in vals and vals[k] else "UNSET")
print("sms_test_mode", vals.get("CHANNEL_NOTIFICATION_SMS_TEST_MODE", "UNSET"))
# confirm new bytecode mentions 30-min default in resolver? skip PII
print("ingestion", vals.get("COLLECTION_INGESTION_ENABLED", "unset->yml-default"))
print("scheduler", vals.get("COLLECTION_SCHEDULER_ENABLED", "UNSET"))
