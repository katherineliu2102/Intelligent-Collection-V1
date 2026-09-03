#!/usr/bin/env python3
"""Aug 27 slot execution aggregates. No full case-id dump."""
import os
import subprocess
from datetime import datetime, timedelta, timezone

PHT = timezone(timedelta(hours=8))
now = datetime.now(PHT)
today = now.strftime("%Y-%m-%d")


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
print("now_pht", now.strftime("%Y-%m-%d %H:%M:%S"))
print("whitelist", len(ids))

print("\n=== health-ish plans ===")
print(
    mysql(
        vals,
        "SELECT status, COUNT(*) FROM t_contact_plan "
        "WHERE case_id IN (%s) GROUP BY status ORDER BY 1" % in_list,
    ).rstrip()
)

print("\n=== today original_trigger by channel/status (slot identity) ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, TIME(s.original_trigger_time) slot, s.status, "
        "IFNULL(s.result,'NULL'), COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND DATE(s.original_trigger_time)='%s' "
        "GROUP BY 1,2,3,4 ORDER BY 2,1,3" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== today executed_at by hour/channel/status ===")
print(
    mysql(
        vals,
        "SELECT DATE_FORMAT(s.executed_at,'%%H:%%i') hh, s.channel_type, s.status, "
        "IFNULL(s.result,'NULL'), COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND DATE(s.executed_at)='%s' "
        "GROUP BY 1,2,3,4 ORDER BY 1,2" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== lag minutes original_trigger -> executed_at (today slots) ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, TIME(s.original_trigger_time) slot, "
        "ROUND(AVG(TIMESTAMPDIFF(SECOND, s.original_trigger_time, s.executed_at))/60,1) avg_min, "
        "MIN(TIMESTAMPDIFF(SECOND, s.original_trigger_time, s.executed_at)) min_s, "
        "MAX(TIMESTAMPDIFF(SECOND, s.original_trigger_time, s.executed_at)) max_s, "
        "COUNT(*) n "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND DATE(s.original_trigger_time)='%s' "
        "AND s.executed_at IS NOT NULL "
        "GROUP BY 1,2 ORDER BY 2,1" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== hanging EXECUTING ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, COUNT(*), "
        "MIN(s.executed_at), MAX(s.timeout_time) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.status='EXECUTING' "
        "GROUP BY 1" % in_list,
    ).rstrip()
    or "<none>"
)

print("\n=== still PENDING today original slots ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, TIME(s.original_trigger_time), COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.status='PENDING' "
        "AND DATE(s.original_trigger_time)='%s' "
        "GROUP BY 1,2 ORDER BY 2,1" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== timeline today OUT ===")
print(
    mysql(
        vals,
        "SELECT channel, result, COUNT(*) FROM t_contact_timeline "
        "WHERE case_id IN (%s) AND DATE(created_at)='%s' AND direction='OUT' "
        "GROUP BY 1,2 ORDER BY 1,2" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== AI EXECUTING detail (no PII) ===")
print(
    mysql(
        vals,
        "SELECT s.id, s.plan_id, s.status, IFNULL(s.result,'NULL'), s.executed_at, s.timeout_time "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND s.channel_type='AI_CALL' AND s.status='EXECUTING' "
        "ORDER BY s.executed_at" % in_list,
    ).rstrip()
    or "<none>"
)

print("\n=== SKIPPED today ===")
print(
    mysql(
        vals,
        "SELECT s.channel_type, s.result, COUNT(*) "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE p.case_id IN (%s) AND DATE(COALESCE(s.completed_at,s.executed_at))='%s' "
        "AND s.status='SKIPPED' GROUP BY 1,2" % (in_list, today),
    ).rstrip()
    or "<none>"
)

print("\n=== nightly leftover 2181/2177/2461 ===")
print(
    mysql(
        vals,
        "SELECT id, status, result, executed_at, completed_at "
        "FROM t_contact_plan_step WHERE id IN (2181,2177,2461) ORDER BY id",
    ).rstrip()
)
