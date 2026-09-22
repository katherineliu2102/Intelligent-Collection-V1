#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Clear channel test-redirect env vars. Do not print values."""
import datetime
import os
import shutil

ENV = "/opt/app/pilot.env"
KEYS = {
    "CHANNEL_FACADE_TEST_CALLEE",
    "CHANNEL_NOTIFICATION_SMS_TEST_RECIPIENT",
    "CHANNEL_SENDGRID_TEST_RECIPIENT",
    "CHANNEL_NOTIFICATION_PUSH_TEST_TOKEN",
}

stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
bak = ENV + ".bak." + stamp + "-before-clear-redirects"
shutil.copy2(ENV, bak)
print("backup", os.path.basename(bak))

out = []
cleared = []
kept_test_mode = False
with open(ENV) as f:
    for line in f:
        raw = line.rstrip("\n")
        stripped = raw.strip()
        if stripped and not stripped.startswith("#") and "=" in stripped:
            k = stripped.split("=", 1)[0].strip()
            if k in KEYS:
                out.append("# CLEARED_FOR_TRUE_CUSTOMER " + k + "=\n")
                cleared.append(k)
                continue
            if k == "CHANNEL_NOTIFICATION_SMS_TEST_MODE":
                kept_test_mode = True
        out.append(line if line.endswith("\n") else line + "\n")

with open(ENV, "w") as f:
    f.writelines(out)

print("cleared", ",".join(sorted(cleared)) or "<none>")
print("sms_test_mode_kept", kept_test_mode)
