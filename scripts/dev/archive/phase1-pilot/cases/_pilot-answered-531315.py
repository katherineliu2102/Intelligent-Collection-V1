#!/usr/bin/env python3
"""531315 / step 12802 接通：审计、summary、对话。不打印电话。"""
import json
import os
import ssl
import subprocess
import urllib.request
from pathlib import Path

CASE = "531315"
STEP = "12802"


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


def show_turns(label, ch):
    print("%s_turns" % label, len(ch) if isinstance(ch, list) else type(ch))
    if not isinstance(ch, list):
        return
    for i, turn in enumerate(ch, 1):
        if not isinstance(turn, dict):
            print("TURN", i, type(turn), str(turn)[:200])
            continue
        role = turn.get("role") or turn.get("speaker") or turn.get("from") or "?"
        text = (
            turn.get("content")
            or turn.get("text")
            or turn.get("message")
            or turn.get("utterance")
            or ""
        )
        print("TURN", i, role, text)


vals = envfile()
print("=== step ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.plan_id, p.stage, p.status, s.status, s.result, "
        "s.executed_at, s.completed_at, "
        "TIMESTAMPDIFF(SECOND,s.executed_at,s.completed_at) sec "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.id=%s" % STEP,
    )
)
print("=== same-plan later AI ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.channel_type, s.status, IFNULL(s.result,''), s.original_trigger_time "
        "FROM t_contact_plan_step s WHERE s.plan_id=("
        "SELECT plan_id FROM t_contact_plan_step WHERE id=%s) "
        "AND s.channel_type='AI_CALL' ORDER BY s.original_trigger_time" % STEP,
    )
)
print("=== audit meta ===")
print(
    mysql(
        vals,
        "SELECT id, step_id, result, disposition, signature_valid, received_at, "
        "LEFT(IFNULL(provider_msg_id,''),40) "
        "FROM t_channel_callback_audit WHERE step_id=%s OR "
        "(case_id=%s AND received_at>='2026-08-31 14:30:00') ORDER BY id" % (STEP, CASE),
    )
)
raw = mysql(
    vals,
    "SELECT canonical_payload FROM t_channel_callback_audit "
    "WHERE step_id=%s ORDER BY id DESC LIMIT 1" % STEP,
)
if not raw.strip():
    print("NO_PAYLOAD")
    raise SystemExit(0)
payload = json.loads(raw)
print("payload_keys", sorted(payload.keys()))
line = payload.get("line_outcome") or {}
print("line_reason", line.get("reason"))
print("was_answered", line.get("was_answered"))
print("was_ai_connected", line.get("was_ai_connected"))
print("final_failure", payload.get("final_failure_reason"))
ai = payload.get("ai_result") or {}
print("ai_result_keys", sorted(ai.keys()) if isinstance(ai, dict) else type(ai))
print("result_label", ai.get("result_label"))
print("summary", ai.get("summary"))
print("promises", ai.get("promises"))
print("session_id", (payload.get("session_id") or "")[:40])
print("batch_id", (payload.get("batch_id") or payload.get("external_batch_id") or "")[:40])
print("event_ts", payload.get("event_timestamp") or payload.get("timestamp"))
media = payload.get("media") or {}
print("media_keys", sorted(media.keys()) if isinstance(media, dict) else type(media))
scr = media.get("script_url") or ""
print("script_url_set", bool(scr))
print("recording_url_set", bool(media.get("recording_url")))
show_turns(
    "payload",
    payload.get("conversation_history")
    or ai.get("conversation_history")
    or payload.get("conversation")
    or [],
)

key = vals.get("CHANNEL_FACADE_API_KEY") or ""
print("api_key", bool(key))
if scr and key:
    req = urllib.request.Request(scr)
    req.add_header("Authorization", "Bearer " + key)
    ctx = ssl._create_unverified_context()
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=30) as resp:
            body = resp.read()
            Path("/tmp/answered-531315-script.json").write_bytes(body)
            print("script_http", resp.status, "bytes", len(body))
            j = json.loads(body)
            print("script_keys", sorted(j.keys()) if isinstance(j, dict) else type(j))
            if isinstance(j, dict):
                print("script_summary", j.get("summary") or j.get("ai_result", {}).get("summary"))
            ch = (
                j.get("conversation_history")
                or j.get("messages")
                or j.get("conversation")
                or j.get("turns")
                or []
            )
            show_turns("script", ch)
    except Exception as e:
        print("script_fetch_error", type(e).__name__, str(e)[:400])
