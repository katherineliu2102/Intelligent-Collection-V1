#!/usr/bin/env python3
"""Aggregate slot volume for whitelist. No case-id dump."""
import os
import subprocess
from datetime import datetime, timedelta, timezone

PHT = timezone(timedelta(hours=8))
now = datetime.now(PHT)
today = now.strftime("%Y-%m-%d")
tomorrow = (now + timedelta(days=1)).strftime("%Y-%m-%d")


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
        [
            "mysql",
            "-h",
            vals["COLLECTION_DB_HOST"],
            "-P",
            vals.get("COLLECTION_DB_PORT", "3306"),
            "-u",
            vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-N",
            "-e",
            sql,
        ],
        env=env,
    ).decode()


vals = envfile()
ids = [
    x.strip()
    for x in vals.get("COLLECTION_SCAN_CASE_IDS", "").replace(";", ",").split(",")
    if x.strip()
]
in_list = ",".join(ids)
print("now_pht", now.strftime("%Y-%m-%d %H:%M"))
print("whitelist", len(ids))

print("\n=== plans ===")
print(
    mysql(
        vals,
        "SELECT status, COUNT(*) FROM t_contact_plan "
        "WHERE case_id IN (%s) AND renewal_pending=0 GROUP BY status ORDER BY 1" % in_list,
    ).rstrip()
    or "<none>"
)

print("\n=== today trigger by channel/status ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, s.status, COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND DATE(COALESCE(s.original_trigger_time,s.trigger_time))='%s' "
        "GROUP BY s.channel_type, s.status ORDER BY 1,2" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== tomorrow trigger PENDING by hour/channel ===")
print(
    mysql(
        vals,
        "SELECT DATE_FORMAT(COALESCE(s.trigger_time,s.original_trigger_time),'%%H:%%i') hh, "
        "s.channel_type, COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.status='PENDING' "
        "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time))='%s' "
        "GROUP BY hh, s.channel_type ORDER BY hh, s.channel_type" % (in_list, tomorrow),
    ).rstrip()
    or "<none>"
)

print("\n=== tomorrow PENDING totals ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, COUNT(*), COUNT(DISTINCT p.case_id) cases "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.status='PENDING' "
        "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time))='%s' "
        "GROUP BY s.channel_type ORDER BY 1" % (in_list, tomorrow),
    ).rstrip()
    or "<none>"
)

print("\n=== today timeline OUT by channel/result ===")
print(
    mysql(
        vals,
        "SELECT channel, result, COUNT(*) FROM t_contact_timeline "
        "WHERE case_id IN (%s) AND DATE(created_at)='%s' AND direction='OUT' "
        "GROUP BY channel, result ORDER BY 1,2" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== dpd snapshot on active plans ===")
print(
    mysql(
        vals,
        "SELECT "
        "CASE "
        " WHEN JSON_UNQUOTE(JSON_EXTRACT(p.context_snapshot,'$.caseContext.dpd')) REGEXP '^[0-9-]+$' "
        " THEN JSON_UNQUOTE(JSON_EXTRACT(p.context_snapshot,'$.caseContext.dpd')) "
        " ELSE 'NA' END dpd, "
        "p.stage, COUNT(*) "
        "FROM t_contact_plan p "
        "WHERE p.case_id IN (%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
        "GROUP BY dpd, p.stage ORDER BY CAST(dpd AS SIGNED), p.stage" % in_list,
    ).rstrip()
    or "<none>"
)

print("\n=== leftover PENDING today (not yet fired) ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, COUNT(*), COUNT(DISTINCT p.case_id) cases "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.status='PENDING' "
        "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time))='%s' "
        "GROUP BY s.channel_type" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== email pending next 2 days ===")
print(
    mysql(
        vals,
        "SELECT DATE(COALESCE(s.trigger_time,s.original_trigger_time)) d, s.status, COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.channel_type='EMAIL' "
        "AND DATE(COALESCE(s.trigger_time,s.original_trigger_time)) IN ('%s','%s') "
        "GROUP BY d, s.status ORDER BY 1,2" % (in_list, today, tomorrow),
    ).rstrip()
    or "<none>"
)

print("\n=== cases with active plan vs whitelist ===")
print(
    mysql(
        vals,
        "SELECT "
        "(SELECT COUNT(DISTINCT case_id) FROM t_contact_plan "
        " WHERE case_id IN (%s) AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')) active_plans, "
        "(SELECT COUNT(*) FROM t_ai_collection WHERE id IN (%s)) ai_rows" % (in_list, in_list),
    ).rstrip()
)
