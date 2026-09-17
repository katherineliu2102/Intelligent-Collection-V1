#!/usr/bin/env python3
vals = {}
with open("/opt/app/pilot.env") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")
print("scheduler", vals.get("COLLECTION_SCHEDULER_ENABLED"))
print("ingestion", vals.get("COLLECTION_INGESTION_ENABLED", "<unset>"))
print("ai_limit", vals.get("CHANNEL_DAILY_LIMIT_AI_CALL", "<unset>"))
for k in [
    "CHANNEL_FACADE_TEST_CALLEE",
    "NOTIFICATION_PUSH_TEST_TOKEN",
    "CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT",
    "CHANNEL_SENDGRID_TEST_RECIPIENT",
]:
    v = vals.get(k)
    if v is None:
        print(k, "unset")
    elif v == "":
        print(k, "empty")
    else:
        print(k, "set len=%d" % len(v))
ids = vals.get("COLLECTION_PILOT_LOAN_IDS", "")
scan = vals.get("COLLECTION_SCAN_CASE_IDS", "")
print("pilot_ids_count", len([x for x in ids.replace(";", ",").split(",") if x.strip()]))
print("scan_ids_count", len([x for x in scan.replace(";", ",").split(",") if x.strip()]))
print("contains_520049", "520049" in ids.split(",") or "520049" in ids)
