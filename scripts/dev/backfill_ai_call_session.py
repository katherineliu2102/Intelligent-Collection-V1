#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""建 t_ai_call_session 表 + 从 t_channel_callback_audit 回填历史会话（一次性运维脚本）。
用法：python scripts/dev/backfill_ai_call_session.py
"""
import datetime
import json
import os
import re
import urllib.parse
import urllib.request

import pymysql

ROOT = r"D:/AI/Intelligent-Collection-V1"

DDL = """
CREATE TABLE IF NOT EXISTS t_ai_call_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(128) NOT NULL,
  batch_id VARCHAR(128) NULL,
  case_id BIGINT NULL,
  plan_id BIGINT NULL,
  step_id BIGINT NULL,
  event VARCHAR(32) NULL,
  was_ringing TINYINT(1) NULL,
  was_answered TINYINT(1) NULL,
  was_ai_connected TINYINT(1) NULL,
  line_reason VARCHAR(32) NULL,
  sip_code VARCHAR(32) NULL,
  final_failure_reason VARCHAR(64) NULL,
  result_label VARCHAR(64) NULL,
  summary TEXT NULL,
  promises_json JSON NULL,
  caller_cli VARCHAR(32) NULL,
  dialed_at DATETIME NULL,
  answered_at DATETIME NULL,
  ended_at DATETIME NULL,
  needs_review TINYINT(1) NULL,
  is_synthetic TINYINT(1) NOT NULL DEFAULT 0,
  stage_snapshot VARCHAR(16) NULL,
  dpd_snapshot INT NULL,
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ai_call_session_id (session_id),
  INDEX idx_ai_call_session_received (received_at),
  INDEX idx_ai_call_session_case (case_id, received_at),
  INDEX idx_ai_call_session_batch (batch_id),
  INDEX idx_ai_call_session_label (result_label, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI Call 会话底座（原生词，看板聚合用）';
"""


def load_env():
    env = {}
    with open(os.path.join(ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip()
    return env


def fetch_jdbc(env):
    q = urllib.parse.urlencode(
        {
            "dataId": "intelligent-collection-local.yml",
            "group": env["NACOS_GROUP"],
            "tenant": env["NACOS_NAMESPACE"],
            "username": env["NACOS_USERNAME"],
            "password": env["NACOS_PASSWORD"],
        }
    )
    url = "http://%s/nacos/v1/cs/configs?%s" % (env["NACOS_SERVER_ADDR"], q)
    yaml = urllib.request.urlopen(url, timeout=15).read().decode("utf-8")
    db_url = re.search(r"url:\s*(jdbc:mysql://\S+)", yaml).group(1)
    db_user = re.search(r"(?m)^\s*username:\s*(\S+)", yaml).group(1)
    db_pwd = re.search(r"(?m)^\s*password:\s*(.+)$", yaml).group(1).strip()
    return db_url, db_user, db_pwd


def txt(node, field):
    if not isinstance(node, dict):
        return None
    v = node.get(field)
    if v is None:
        return None
    return v if isinstance(v, str) else str(v)


def as_int(v):
    if v is None:
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def parse_ts(raw):
    if not raw:
        return None
    head = raw[:19].replace("T", " ")
    try:
        datetime.datetime.strptime(head, "%Y-%m-%d %H:%M:%S")
        return head
    except ValueError:
        return None


def main():
    host = os.environ.get("DB_HOST")
    if host:
        port = int(os.environ.get("DB_PORT", "3306"))
        db = os.environ["DB_NAME"]
        db_user = os.environ["DB_USER"]
        db_pwd = os.environ["DB_PASS"]
    else:
        env = load_env()
        db_url, db_user, db_pwd = fetch_jdbc(env)
        m = re.match(r"jdbc:mysql://([^:/]+):(\d+)/(\w+)", db_url)
        host, port, db = m.group(1), int(m.group(2)), m.group(3)

    conn = pymysql.connect(
        host=host,
        port=port,
        user=db_user,
        password=db_pwd,
        database=db,
        charset="utf8mb4",
        autocommit=False,
    )
    cur = conn.cursor()
    cur.execute(DDL)
    conn.commit()
    print("[1/2] t_ai_call_session 建表完成")

    cur.execute(
        "SELECT id, canonical_payload, received_at FROM t_channel_callback_audit "
        "WHERE canonical_payload IS NOT NULL ORDER BY id"
    )
    rows = cur.fetchall()
    inserted = 0
    skipped = 0
    for audit_id, payload, received_at in rows:
        try:
            root = json.loads(payload)
        except (ValueError, TypeError):
            skipped += 1
            continue
        if not isinstance(root, dict):
            skipped += 1
            continue
        if root.get("event") != "session.completed":
            skipped += 1
            continue
        session_id = root.get("session_id")
        if not session_id:
            skipped += 1
            continue
        line = root.get("line_outcome") or {}
        ai = root.get("ai_result") or {}
        parties = root.get("parties") or {}
        dial = root.get("dial_timeline") or {}
        meta = root.get("client_metadata") or {}
        promises = ai.get("promises")
        promises_json = (
            json.dumps(promises, ensure_ascii=False) if isinstance(promises, list) else None
        )
        cur.execute(
            "INSERT INTO t_ai_call_session (session_id, batch_id, case_id, plan_id, step_id, event, "
            "was_ringing, was_answered, was_ai_connected, line_reason, sip_code, final_failure_reason, "
            "result_label, summary, promises_json, caller_cli, dialed_at, answered_at, ended_at, received_at) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) "
            "ON DUPLICATE KEY UPDATE result_label=VALUES(result_label), summary=VALUES(summary), "
            "line_reason=VALUES(line_reason), sip_code=VALUES(sip_code), "
            "final_failure_reason=VALUES(final_failure_reason)",
            (
                session_id,
                root.get("batch_id") or root.get("external_batch_id"),
                as_int(meta.get("case_id") or root.get("external_case_id")),
                as_int(meta.get("plan_id")),
                as_int(meta.get("step_id")),
                root.get("event"),
                line.get("was_ringing"),
                line.get("was_answered"),
                line.get("was_ai_connected"),
                txt(line, "reason"),
                txt(line, "sip_code"),
                txt(root, "final_failure_reason"),
                txt(ai, "result_label"),
                txt(ai, "summary"),
                promises_json,
                txt(parties, "caller_cli"),
                parse_ts(txt(dial, "dialed_at")),
                parse_ts(txt(dial, "answered_at")),
                parse_ts(txt(dial, "ended_at")),
                received_at,
            ),
        )
        inserted += 1
    conn.commit()

    cur.execute("SELECT COUNT(*) FROM t_ai_call_session")
    total = cur.fetchone()[0]
    print(
        "[2/2] 回填完成：新增/更新 %d 行，跳过 %d 行（非 session.completed 或缺 session_id），"
        "t_ai_call_session 共 %d 行" % (inserted, skipped, total)
    )
    conn.close()


if __name__ == "__main__":
    main()
