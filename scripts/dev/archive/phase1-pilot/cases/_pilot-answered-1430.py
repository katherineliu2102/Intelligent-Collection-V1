#!/usr/bin/env python3
"""14:30 slot ANSWERED AI call details. No phone/name."""
import json
import os
import ssl
import subprocess
import urllib.request
from pathlib import Path

vals = {}
for line in Path("/opt/app/pilot.env").read_text().splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    k, v = line.split("=", 1)
    vals[k] = v.strip().strip('"').strip("'")
env = os.environ.copy()
env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]


def q(sql):
    return subprocess.check_output(
        [
            "mysql",
            "-N",
            "-h",
            vals["COLLECTION_DB_HOST"],
            "-u",
            vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-e",
            sql,
        ],
        env=env,
    ).decode()


print("=== 1430 ANSWERED step ===")
step = q(
    "SELECT s.id, p.case_id, s.plan_id, s.status, s.result, s.executed_at, s.completed_at, "
    "TIMESTAMPDIFF(SECOND,s.executed_at,s.completed_at) "
    "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
    "WHERE s.channel_type='AI_CALL' AND s.result='ANSWERED' "
    "AND s.original_trigger_time>='2026-09-01 14:30:00' "
    "AND s.original_trigger_time<'2026-09-01 14:31:00'"
)
print(step.strip() or "(none)")
if not step.strip():
    raise SystemExit(0)
parts = step.strip().split("\t")
step_id, case_id = parts[0], parts[1]
plan_id = parts[2]

print("\n=== projection ===")
print(
    q(
        f"SELECT case_id, stage, dpd, collection_status, overdue_amount, upcoming_amount "
        f"FROM t_ai_collection WHERE case_id={case_id}"
    )
)
print("\n=== plan ===")
print(q(f"SELECT id, stage, status, cancel_reason FROM t_contact_plan WHERE id={plan_id}"))
print("\n=== 0915 same day ===")
print(
    q(
        f"SELECT s.id, s.result, s.status, TIMESTAMPDIFF(SECOND,s.executed_at,s.completed_at) "
        f"FROM t_contact_plan_step s WHERE s.plan_id={plan_id} AND s.channel_type='AI_CALL' "
        f"AND s.original_trigger_time='2026-09-01 09:15:00'"
    )
)
print("\n=== audit ===")
print(
    q(
        f"SELECT id, step_id, result, disposition, signature_valid, received_at "
        f"FROM t_channel_callback_audit WHERE step_id={step_id} ORDER BY id"
    )
)
raw = q(
    f"SELECT canonical_payload FROM t_channel_callback_audit "
    f"WHERE step_id={step_id} ORDER BY id DESC LIMIT 1"
)
payload = json.loads(raw)
ai = payload.get("ai_result") or payload.get("ai") or {}
print("\n=== callback payload ===")
print("was_ai_connected", payload.get("was_ai_connected"), ai.get("was_ai_connected"))
print("reason", payload.get("reason"))
print("result_label", payload.get("result_label") or ai.get("result_label"))
summary = (ai.get("summary") or "").strip()
print("summary", summary[:500] if summary else "(empty)")
sess = payload.get("session") or {}
print("session_id", (payload.get("session_id") or sess.get("id") or "")[:48])
print("duration_sec", payload.get("duration_sec") or sess.get("duration_sec"))
media = payload.get("media") or {}
scr = media.get("script_url") or ""
print("script_url_set", bool(scr))
hist = (
    payload.get("conversation_history")
    or ai.get("conversation_history")
    or sess.get("conversation_history")
    or []
)
print("history_n", len(hist) if isinstance(hist, list) else 0)
if isinstance(hist, list):
    for i, u in enumerate(hist[:12]):
        role = u.get("role") or u.get("speaker") or "?"
        text = (u.get("text") or u.get("content") or "").strip()
        print(f"  [{i}] {role}: {text[:200]}")

key = vals.get("CHANNEL_FACADE_API_KEY") or ""
if scr and key:
    req = urllib.request.Request(scr)
    req.add_header("Authorization", "Bearer " + key)
    ctx = ssl._create_unverified_context()
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
            body = json.loads(resp.read().decode())
        turns = body if isinstance(body, list) else body.get("turns") or body.get("conversation") or []
        print("\n=== script_url turns ===", len(turns) if isinstance(turns, list) else type(turns))
        if isinstance(turns, list):
            for i, u in enumerate(turns[:12]):
                role = u.get("role") or u.get("speaker") or "?"
                text = (u.get("text") or u.get("content") or "").strip()
                print(f"  [{i}] {role}: {text[:200]}")
    except Exception as e:
        print("script_fetch_err", str(e)[:120])
    if isinstance(body, str) and body.strip():
        print("script_raw_head", body[:600])

print("\n=== 1430 skip same case? ===")
print(
    q(
        f"SELECT id, status, result FROM t_contact_plan_step "
        f"WHERE plan_id={plan_id} AND channel_type='AI_CALL' "
        f"AND original_trigger_time>='2026-09-01 14:30:00' "
        f"AND original_trigger_time<'2026-09-01 14:31:00'"
    )
)
