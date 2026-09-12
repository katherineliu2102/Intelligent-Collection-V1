#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Add Hikari pool size to pilot.env if missing, backup first."""
from pathlib import Path
import shutil
import time

ENV = Path("/opt/app/pilot.env")
KEY = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"
VAL = "25"

bak = str(ENV) + ".bak." + time.strftime("%Y%m%d%H%M%S")
shutil.copy2(ENV, bak)
lines = ENV.read_text().splitlines()
out = []
found = False
for line in lines:
    if line.strip().startswith(KEY + "="):
        out.append(f"{KEY}={VAL}")
        found = True
    else:
        out.append(line)
if not found:
    out.append(f"{KEY}={VAL}")
ENV.write_text("\n".join(out) + "\n")
print("backup", bak)
print("set", f"{KEY}={VAL}")
