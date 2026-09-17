#!/usr/bin/env python3
"""Export phones + push tokens for 12:00 PUSH cases; SMS content from timeline."""
import os, subprocess

def envfile():
    vals = {}
    with open("/opt/app/pilot.env") as f:
        for line in f:
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
        ["mysql","-h",vals["COLLECTION_DB_HOST"],"-P",vals.get("COLLECTION_DB_PORT","3306"),
         "-u",vals["COLLECTION_DB_USERNAME"],vals["COLLECTION_DB_NAME"],"-e",sql],
        env=env).decode()

vals = envfile()
print("=== SMS content_summary sample ===")
print(mysql(vals,
    "SELECT t.case_id, t.provider_msg_id, t.content_summary "
    "FROM t_contact_timeline t "
    "JOIN t_contact_plan_step s ON s.id=t.step_id "
    "WHERE t.channel='SMS' AND t.direction='OUT' "
    "AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='08:00:00' "
    "LIMIT 8"))
print("=== 12:00 PUSH: token present? fallback? ===")
print(mysql(vals,
    "SELECT "
    "  SUM(a.push_token IS NULL OR a.push_token='') no_token, "
    "  SUM(a.push_token IS NOT NULL AND a.push_token<>'') has_token, "
    "  COUNT(*) n "
    "FROM t_ai_collection a "
    "JOIN t_contact_plan p ON p.case_id=a.case_id "
    "JOIN t_contact_plan_step s ON s.plan_id=p.id "
    "WHERE s.channel_type='PUSH' AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='12:00:00'"))
print("=== 12:00 PUSH phones (last 10 digits) and token prefix ===")
print(mysql(vals,
    "SELECT a.case_id, a.user_id, RIGHT(a.borrower_phone,10) phone10, "
    "LEFT(a.push_token,12) token_prefix, LENGTH(a.push_token) token_len "
    "FROM t_ai_collection a "
    "JOIN t_contact_plan p ON p.case_id=a.case_id "
    "JOIN t_contact_plan_step s ON s.plan_id=p.id "
    "WHERE s.channel_type='PUSH' AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='12:00:00'"))
print("=== 12:00 PUSH timeline result + content ===")
print(mysql(vals,
    "SELECT t.case_id, t.result, t.provider_msg_id, LEFT(t.content_summary,80) preview "
    "FROM t_contact_timeline t "
    "JOIN t_contact_plan_step s ON s.id=t.step_id "
    "WHERE t.channel='PUSH' AND t.direction='OUT' "
    "AND DATE(s.executed_at)=CURDATE() "
    "AND TIME(s.original_trigger_time)='12:00:00' "
    "LIMIT 8"))
print("=== 12:00 SMS fallback? ===")
print(mysql(vals,
    "SELECT COUNT(*) n FROM t_contact_timeline t "
    "JOIN t_contact_plan_step s ON s.id=t.step_id "
    "WHERE t.channel='SMS' AND DATE(t.created_at)=CURDATE() "
    "AND TIME(t.created_at) BETWEEN '11:59:00' AND '12:05:00'"))
