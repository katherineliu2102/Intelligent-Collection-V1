#!/usr/bin/env python3
"""Confirm env file and running container both carry the 200-case lists."""
import subprocess

CSV = "/tmp/e2e200_loan_ids_20260829.csv"
KEYS = ("COLLECTION_PILOT_LOAN_IDS", "COLLECTION_SCAN_CASE_IDS")


def parse_ids(raw):
    raw = (raw or "").strip().strip('"').strip("'")
    return [x for x in raw.replace(";", ",").replace(" ", "").split(",") if x]


def load_csv():
    ids = []
    for line in open(CSV, encoding="utf-8"):
        body = line.strip()
        if body and body.lower() != "loan_id":
            ids.append(body)
    return ids


def envfile():
    vals = {}
    for line in open("/opt/app/pilot.env", encoding="utf-8"):
        body = line.strip()
        if not body or body.startswith("#") or "=" not in body:
            continue
        k, v = body.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")
    return vals


def docker_env():
    out = subprocess.check_output(
        ["docker", "exec", "collection-admin", "printenv"],
        text=True,
    )
    vals = {}
    for line in out.splitlines():
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v
    return vals


csv_ids = set(load_csv())
file_vals = envfile()
ctr_vals = docker_env()
print("csv_count", len(csv_ids))
for src, vals in (("file", file_vals), ("container", ctr_vals)):
    for k in KEYS:
        ids = parse_ids(vals.get(k, ""))
        print("%s %s count=%s match_csv=%s sample=%s" % (
            src, k, len(ids), set(ids) == csv_ids, ",".join(ids[:3])
        ))
    a = set(parse_ids(vals.get(KEYS[0], "")))
    b = set(parse_ids(vals.get(KEYS[1], "")))
    print("%s intersection" % src, len(a & b))

insp = subprocess.check_output(
    [
        "docker",
        "inspect",
        "-f",
        "{{.State.Status}} {{.State.StartedAt}} {{.State.Health.Status}}",
        "collection-admin",
    ],
    text=True,
).strip()
print("container", insp)

logs = subprocess.check_output(
    ["docker", "logs", "--tail", "300", "collection-admin"],
    text=True,
    stderr=subprocess.STDOUT,
)
bad = [
    line
    for line in logs.splitlines()
    if "ERROR" in line
    or "loan-id-whitelist" in line
    or "Pilot requires a non-empty" in line
    or "case-id-whitelist" in line
]
print("log_hits", len(bad))
for line in bad[-15:]:
    print(line)
