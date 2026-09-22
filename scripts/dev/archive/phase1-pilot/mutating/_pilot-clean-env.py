#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""清掉 pilot.env 里既不是注释也不是 KEY=VALUE 的残行。

pilot-run.sh 会用 shell 解析这个文件，残行会被当成命令执行并让 set -e 直接中止发版。
"""
import shutil
import time

PATH = "/opt/app/pilot.env"

shutil.copy(PATH, PATH + ".bak." + time.strftime("%Y%m%d%H%M%S"))

kept, dropped = [], []
for idx, line in enumerate(open(PATH, encoding="utf-8"), start=1):
    body = line.rstrip("\n").rstrip("\r")
    if body.strip() == "" or body.lstrip().startswith("#") or "=" in body:
        kept.append(body)
    else:
        dropped.append((idx, body))

with open(PATH, "w", encoding="utf-8") as f:
    f.write("\n".join(kept) + "\n")

print("dropped:", dropped if dropped else "none")
print("--- tail ---")
print("\n".join(kept[-4:]))
