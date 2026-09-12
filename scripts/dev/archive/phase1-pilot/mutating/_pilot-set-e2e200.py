#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Backup pilot.env and replace both whitelist keys with the e2e200 CSV.

Does not print the full ID list. pilot-run.sh shell-parses this file; keep KEY=v lines.
"""
import shutil
import time

SRC = "/opt/app/pilot.env"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
KEYS = ("COLLECTION_PILOT_LOAN_IDS", "COLLECTION_SCAN_CASE_IDS")


def load_ids(path):
    ids = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            body = line.strip()
            if not body or body.lower() == "loan_id":
                continue
            ids.append(body)
    if len(ids) != 200:
        raise SystemExit("csv count=%s expected 200" % len(ids))
    if len(set(ids)) != 200:
        raise SystemExit("csv has duplicates")
    return ids


def parse_env_ids(raw):
    raw = (raw or "").strip().strip('"').strip("'")
    return [x for x in raw.replace(";", ",").replace(" ", "").split(",") if x]


ids = load_ids(CSV)
joined = ",".join(ids)
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
        out.append("%s=%s\n" % (k, joined))
        seen.add(k)
    else:
        out.append(line)
for k in KEYS:
    if k not in seen:
        out.append("%s=%s\n" % (k, joined))

with open(SRC, "w", encoding="utf-8") as f:
    f.write("".join(out))

vals = {}
for line in open(SRC, encoding="utf-8"):
    body = line.strip()
    if not body or body.startswith("#") or "=" not in body:
        continue
    k, v = body.split("=", 1)
    vals[k] = v.strip().strip('"').strip("'")

pilot = parse_env_ids(vals.get("COLLECTION_PILOT_LOAN_IDS", ""))
scan = parse_env_ids(vals.get("COLLECTION_SCAN_CASE_IDS", ""))
if len(pilot) != 200 or len(scan) != 200:
    raise SystemExit("after write pilot=%s scan=%s" % (len(pilot), len(scan)))
if set(pilot) != set(scan) or set(pilot) != set(ids):
    raise SystemExit("lists do not match csv")

print("backup", bak)
print("pilot_count", len(pilot))
print("scan_count", len(scan))
print("intersection", len(set(pilot) & set(scan)))
print("sample", ",".join(ids[:3]))
