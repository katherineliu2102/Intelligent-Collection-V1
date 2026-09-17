#!/usr/bin/env python3
import json, os, subprocess
from pathlib import Path

def envfile():
    vals = {}
    for line in Path("/opt/app/pilot.env").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")
    return vals

vals = envfile()
env = os.environ.copy()
env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
raw = subprocess.check_output(
    ["mysql","-N","-h",vals["COLLECTION_DB_HOST"],"-P",vals.get("COLLECTION_DB_PORT","3306"),
     "-u",vals["COLLECTION_DB_USERNAME"],vals["COLLECTION_DB_NAME"],"-e",
     "SELECT canonical_payload FROM t_channel_callback_audit WHERE id=303"],
    env=env,
).decode()
p = json.loads(raw)
ai = p.get("ai_result") or {}
print("event", p.get("event"))
print("line_outcome", p.get("line_outcome"))
print("final_failure_reason", p.get("final_failure_reason"))
print("attempt_count", p.get("attempt_count"))
print("ai_result_keys", list(ai.keys()) if isinstance(ai, dict) else type(ai))
for k in ("was_ai_connected","connected","summary","disposition","hangup_reason","duration_seconds"):
    if isinstance(ai, dict) and k in ai:
        v = ai.get(k)
        if k == "summary":
            print(k, "len", len(str(v or "")))
        else:
            print("ai", k, v)
tl = p.get("dial_timeline") or []
print("dial_timeline_n", len(tl) if isinstance(tl, list) else type(tl))
if isinstance(tl, list):
    for ev in tl[:8]:
        if isinstance(ev, dict):
            print("tl", ev.get("event") or ev.get("type"), ev.get("at") or ev.get("timestamp"))
        else:
            print("tl", ev)
cm = p.get("client_metadata") or {}
print("meta_case", cm.get("case_id"), "step", cm.get("step_id"))
