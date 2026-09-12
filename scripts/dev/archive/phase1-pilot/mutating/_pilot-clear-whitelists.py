#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Empty both Pilot loan lists so the app consumes whatever Pub/Sub sends."""
import shutil
import time

SRC = "/opt/app/pilot.env"
KEYS = ("COLLECTION_PILOT_LOAN_IDS", "COLLECTION_SCAN_CASE_IDS")

bak = SRC + ".bak." + time.strftime("%Y%m%d%H%M%S")
shutil.copy(SRC, bak)
lines = open(SRC, encoding="utf-8").read().splitlines(True)
seen = set()
out = []
for line in lines:
    raw = line.strip()
    if not raw or raw.lstrip().startswith("#") or "=" not in raw:
        out.append(line)
        continue
    k = raw.split("=", 1)[0]
    if k in KEYS:
        out.append("%s=\n" % k)
        seen.add(k)
    else:
        out.append(line)
for k in KEYS:
    if k not in seen:
        out.append("%s=\n" % k)
open(SRC, "w", encoding="utf-8").write("".join(out))
print("backup", bak)
print("cleared", ",".join(KEYS))
