#!/usr/bin/env python3
"""513849 接通：步骤、审计、对话。不打印电话/姓名字段。"""
import json
import os
import ssl
import subprocess
import urllib.request
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
    ).decode()


vals = envfile()
print("=== steps ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.plan_id, s.original_trigger_time, s.status, s.result, "
        "s.executed_at, s.completed_at, TIMESTAMPDIFF(SECOND,s.executed_at,s.completed_at) sec "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id=513849 ORDER BY s.id",
    )
)
print("=== plan ===")
print(mysql(vals, "SELECT id, status, stage, cancel_reason FROM t_contact_plan WHERE case_id=513849"))
print("=== audit meta ===")
print(
    mysql(
        vals,
        "SELECT id, step_id, result, disposition, signature_valid, received_at, "
        "LEFT(IFNULL(provider_msg_id,''),40) "
        "FROM t_channel_callback_audit WHERE case_id=513849 ORDER BY id",
    )
)
raw = mysql(
    vals,
    "SELECT canonical_payload FROM t_channel_callback_audit "
    "WHERE case_id=513849 ORDER BY id DESC LIMIT 1",
)
payload = json.loads(raw)
print("keys", sorted(payload.keys()))
ai = payload.get("ai_result") or payload.get("ai") or {}
print("was_ai_connected", payload.get("was_ai_connected"), ai.get("was_ai_connected"))
print("reason", payload.get("reason"))
print("outcome", payload.get("outcome_label") or payload.get("outcome"))
print("summary_empty", not bool((ai.get("summary") or "").strip()))
sess = payload.get("session") or {}
print("session_id", (payload.get("session_id") or sess.get("id") or "")[:40])
print("session_start", sess.get("started_at") or payload.get("started_at"))
print("session_end", sess.get("ended_at") or payload.get("ended_at"))
media = payload.get("media") or {}
scr = media.get("script_url") or ""
print("script_url_set", bool(scr))
print("recording_url_set", bool(media.get("recording_url")))
hist = (
    payload.get("conversation_history")
    or ai.get("conversation_history")
    or sess.get("conversation_history")
    or []
)
print("payload_history_n", len(hist) if isinstance(hist, list) else type(hist))

key = vals.get("CHANNEL_FACADE_API_KEY") or ""
print("api_key", bool(key))
if scr and key:
    req = urllib.request.Request(scr)
    req.add_header("Authorization", "Bearer " + key)
    ctx = ssl._create_unverified_context()
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
            body = resp.read()
            Path("/tmp/answered-513849-script.json").write_bytes(body)
            print("script_http", resp.status, "bytes", len(body))
            try:
                j = json.loads(body)
            except Exception:
                print("script_not_json")
                j = {}
            ch = j.get("conversation_history") or j.get("messages") or []
            print("script_turns", len(ch) if isinstance(ch, list) else 0)
            if isinstance(ch, list):
                for i, turn in enumerate(ch, 1):
                    role = turn.get("role") or turn.get("speaker") or "?"
                    text = turn.get("content") or turn.get("text") or turn.get("message") or ""
                    print("TURN", i, role, text[:240])
    except Exception as e:
        print("script_fetch_error", type(e).__name__, str(e)[:300])
