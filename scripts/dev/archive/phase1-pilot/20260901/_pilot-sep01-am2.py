#!/usr/bin/env python3
"""9/1 leftovers: missing 200, S0 ids, SETNX, 513849 script. No phones."""
from pathlib import Path
import json
import os
import ssl
import subprocess
import urllib.request

TODAY = "2026-09-01"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
OLD_MISSING = (
    "468179,468279,468491,469165,504801,506532,506697,507188,507209,507250,"
    "507262,507265,507272,507279,507296,507300,507313,507323,507333,507347,"
    "507350,507363,507373,507376,507379,507398,507399,519627,519673,520076,"
    "520194,530569,531529,531556,531718,531727,531771,531802,531811,531812,"
    "531813,531816,531817,531822,531827,531831,531833,531841,531845,531847,"
    "531848,531851,531852,531858,531860,531866,531867"
)


def envfile():
    vals = {}
    for line in Path("/opt/app/pilot.env").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")
    return vals


def mysql(vals, sql):
    env = os.environ.copy()
    env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
    return subprocess.check_output(
        [
            "mysql",
            "-N",
            "-h",
            vals["COLLECTION_DB_HOST"],
            "-P",
            vals.get("COLLECTION_DB_PORT", "3306"),
            "-u",
            vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-e",
            sql,
        ],
        env=env,
        stderr=subprocess.STDOUT,
    ).decode()


vals = envfile()
csv_ids = []
for line in Path(CSV).read_text().splitlines():
    body = line.strip()
    if body and body.lower() != "loan_id":
        csv_ids.append(body)
csv_in = ",".join(csv_ids)
missing_sql = (
    "SELECT x.case_id FROM ("
    + " UNION ALL ".join("SELECT %s AS case_id" % i for i in csv_ids)
    + ") x LEFT JOIN t_ai_collection c ON c.case_id=x.case_id "
    "WHERE c.case_id IS NULL ORDER BY x.case_id"
)
missing = [x for x in mysql(vals, missing_sql).strip().split() if x]
print("=== missing 200 ===")
print("missing_n", len(missing))
print("missing_ids", ",".join(missing))
old = set(OLD_MISSING.split(","))
now = set(missing)
print("still_in_old57", len(now & old))
print("new_missing", ",".join(sorted(now - old)))
print("now_in_proj_were_missing", ",".join(sorted(old - now)))

print("=== S0 cases ===")
print(
    mysql(
        vals,
        "SELECT case_id, dpd, collection_status FROM t_ai_collection "
        "WHERE stage='S0' ORDER BY case_id",
    )
)
print("=== S0 today steps ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, s.channel_type, s.status, IFNULL(s.result,''), "
        "DATE_FORMAT(s.original_trigger_time,'%%H:%%i') "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.stage='S0' AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "AND s.original_trigger_time>='%s 00:00:00' "
        "AND s.original_trigger_time<'%s 23:59:59' "
        "ORDER BY p.case_id, s.original_trigger_time" % (TODAY, TODAY),
    )
)
print("=== 506855 ===")
print(
    mysql(
        vals,
        "SELECT case_id, stage, dpd, collection_status FROM t_ai_collection WHERE case_id=506855",
    )
)
print(
    mysql(
        vals,
        "SELECT id, stage, status, created_at, updated_at FROM t_contact_plan "
        "WHERE case_id=506855 ORDER BY id DESC LIMIT 3",
    )
)
print("=== 513849 plan/step ===")
print(
    mysql(
        vals,
        "SELECT p.id, p.stage, p.status, s.id, s.status, s.result, s.executed_at, s.completed_at, "
        "TIMESTAMPDIFF(SECOND,s.executed_at,s.completed_at) sec "
        "FROM t_contact_plan p JOIN t_contact_plan_step s ON s.plan_id=p.id "
        "WHERE p.case_id=513849 AND s.id=10780",
    )
)
print("=== audit 513849 today ===")
print(
    mysql(
        vals,
        "SELECT id, step_id, result, signature_valid, received_at, LEFT(provider_msg_id,40) "
        "FROM t_channel_callback_audit WHERE case_id=513849 AND received_at>='%s 00:00:00'"
        % TODAY,
    )
)
raw = mysql(
    vals,
    "SELECT canonical_payload FROM t_channel_callback_audit "
    "WHERE step_id=10780 ORDER BY id DESC LIMIT 1",
)
payload = json.loads(raw) if raw.strip() else {}
ai = payload.get("ai_result") or {}
line = payload.get("line_outcome") or {}
media = payload.get("media") or {}
print("line", line.get("reason"), "ai_conn", line.get("was_ai_connected"))
print("summary", ai.get("summary"))
print("result_label", ai.get("result_label"))
print("promises", ai.get("promises"))
print("session", (payload.get("session_id") or "")[:40])
scr = media.get("script_url") or ""
print("script_url_set", bool(scr), "recording_set", bool(media.get("recording_url")))
key = vals.get("CHANNEL_FACADE_API_KEY") or ""
if scr and key:
    req = urllib.request.Request(scr)
    req.add_header("Authorization", "Bearer " + key)
    ctx = ssl._create_unverified_context()
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
            body = resp.read()
            print("script_bytes", len(body))
            j = json.loads(body)
            ch = j.get("conversation_history") or []
            print("script_turns", len(ch))
            for i, turn in enumerate(ch, 1):
                role = turn.get("role") or turn.get("speaker") or "?"
                text = turn.get("content") or turn.get("text") or turn.get("message") or ""
                print("TURN", i, role, text)
    except Exception as e:
        print("script_fetch_error", type(e).__name__, str(e)[:300])

print("=== 09:15 wave (no enroll) ===")
out = subprocess.run(
    "docker logs --since 24h collection-admin 2>&1 | grep '2026-09-01 09:15' | grep -E 'wave mocasa|SETNX|planStepDue scanned|started batch|batchId' | grep -v enrolled | head -20",
    shell=True,
    capture_output=True,
    text=True,
)
print(out.stdout or "(none)")
print("=== S0 inbox vs old missing repay poison ===")
print(
    mysql(
        vals,
        "SELECT case_id, collection_status, IFNULL(stage,'NULL'), dpd "
        "FROM t_ai_collection WHERE case_id IN (%s) ORDER BY case_id" % OLD_MISSING,
    )
)
print("=== email 14:00 pending case dpd ===")
print(
    mysql(
        vals,
        "SELECT c.dpd, c.stage, COUNT(*) n FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
        "WHERE s.channel_type='EMAIL' AND s.status='PENDING' "
        "AND s.original_trigger_time>='%s 14:00:00' "
        "AND s.original_trigger_time<'%s 14:01:00' GROUP BY 1,2" % (TODAY, TODAY),
    )
)
print("=== CONNECT_AND_STOP 513849 later AI today ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.status, IFNULL(s.result,''), s.original_trigger_time "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id=513849 AND s.channel_type='AI_CALL' "
        "AND s.original_trigger_time>='%s 00:00:00' "
        "AND s.original_trigger_time<'%s 23:59:59'" % (TODAY, TODAY),
    )
)
