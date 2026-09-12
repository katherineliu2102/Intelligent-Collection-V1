#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Backup pilot.env and shrink smoke window. No PII printed."""
import shutil
from datetime import datetime

SRC = "/opt/app/pilot.env"
BAK = "/opt/app/pilot.env.bak.20260826-test_branch"
CASE = "489935"

updates = {
    "COLLECTION_PILOT_LOAN_IDS": CASE,
    "COLLECTION_SCAN_CASE_IDS": CASE,
    "COLLECTION_INGESTION_ENABLED": "false",
    "CHANNEL_DAILY_TOTAL_LIMIT": "5",
    "CHANNEL_FACADE_TEST_CALLEE": "",
    "CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT": "",
    "CHANNEL_SENDGRID_TEST_RECIPIENT": "",
    "CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN": "",
}

shutil.copy(SRC, BAK)
lines = open(SRC).read().splitlines(True)
seen = set()
out = []
for line in lines:
    raw = line.strip()
    if not raw or raw.startswith("#") or "=" not in raw:
        out.append(line)
        continue
    k = raw.split("=", 1)[0]
    if k in updates:
        out.append("%s=%s\n" % (k, updates[k]))
        seen.add(k)
    else:
        out.append(line)
for k, v in updates.items():
    if k not in seen:
        out.append("%s=%s\n" % (k, v))
open(SRC, "w").write("".join(out))
print("backup", BAK)
print("smoke_case", CASE)
print("ingestion", "false")
print("redirects", "cleared")
print("written_at", datetime.utcnow().isoformat() + "Z")
