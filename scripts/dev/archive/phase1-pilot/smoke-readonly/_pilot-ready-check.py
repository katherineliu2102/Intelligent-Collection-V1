#!/usr/bin/env python3
import json
import subprocess
import urllib.request

health = json.load(urllib.request.urlopen("http://127.0.0.1:8080/actuator/health", timeout=5))
print("app", health.get("status"))
c = health.get("components", {})
for k in ("pubSubIngestion", "pubSubSchedule", "redis", "db"):
    x = c.get(k, {})
    details = x.get("details") or {}
    print(k, x.get("status"), details.get("subscription", ""))

env = subprocess.check_output(
    ["docker", "exec", "collection-admin", "printenv"], text=True
)
keys = [
    "COLLECTION_INGESTION_ENABLED",
    "COLLECTION_SCHEDULER_ENABLED",
    "CHANNEL_FACADE_TEST_CALLEE",
    "CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT",
    "CHANNEL_SENDGRID_TEST_RECIPIENT",
    "CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN",
    "CHANNEL_NOTIFICATION_SMS_TEST_MODE",
]
present = {}
for line in env.splitlines():
    if "=" in line:
        k, v = line.split("=", 1)
        present[k] = v
for k in keys:
    if k not in present:
        print("ctr", k, "UNSET")
    elif present[k]:
        print("ctr", k, "SET")
    else:
        print("ctr", k, "EMPTY")
