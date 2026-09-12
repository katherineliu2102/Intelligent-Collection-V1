#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""Pull 3 cases' tomorrow 08:00 SMS + 09:15 AI to tonight. Leave PUSH/email/14:30."""
import os
import subprocess
from datetime import datetime, timedelta, timezone

PHT = timezone(timedelta(hours=8))
now = datetime.now(PHT)

# case, plan, sms_step, ai_step, sms_delay_min, ai_delay_min
CHOSEN = [
    (497382, 856, 2454, 2461, 2, 12),
    (497405, 849, 2168, 2181, 4, 22),
    (503696, 853, 2170, 2177, 6, 32),
]


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
            "-e",
            sql,
        ],
        env=env,
    ).decode()


vals = envfile()
print("now_pht", now.strftime("%Y-%m-%d %H:%M:%S"))
ids = ",".join(str(x[2]) + "," + str(x[3]) for x in CHOSEN)
print("=== before ===")
print(
    mysql(
        vals,
        "SELECT id, plan_id, channel_type, status, trigger_time, original_trigger_time "
        "FROM t_contact_plan_step WHERE id IN (%s) ORDER BY plan_id, id" % ids,
    )
)

parts = []
for case_id, plan_id, sms_id, ai_id, sms_d, ai_d in CHOSEN:
    sms_at = (now + timedelta(minutes=sms_d)).strftime("%Y-%m-%d %H:%M:%S")
    ai_at = (now + timedelta(minutes=ai_d)).strftime("%Y-%m-%d %H:%M:%S")
    print(
        "schedule case=%s plan=%s sms=%s@%s ai=%s@%s"
        % (case_id, plan_id, sms_id, sms_at, ai_id, ai_at)
    )
    parts.append(
        "UPDATE t_contact_plan_step SET trigger_time='%s', updated_at=NOW() "
        "WHERE id=%s AND status='PENDING';" % (sms_at, sms_id)
    )
    parts.append(
        "UPDATE t_contact_plan_step SET trigger_time='%s', updated_at=NOW() "
        "WHERE id=%s AND status='PENDING';" % (ai_at, ai_id)
    )

print(mysql(vals, "\n".join(parts)))
print("=== after ===")
print(
    mysql(
        vals,
        "SELECT id, plan_id, channel_type, status, trigger_time, original_trigger_time "
        "FROM t_contact_plan_step WHERE id IN (%s) ORDER BY trigger_time, id" % ids,
    )
)
# confirm Aug 27 PUSH / later SMS still pending
print("=== untouched same-plan later slots (sample) ===")
plan_ids = ",".join(str(x[1]) for x in CHOSEN)
print(
    mysql(
        vals,
        "SELECT plan_id, channel_type, DATE(COALESCE(trigger_time,original_trigger_time)) d, "
        "TIME(COALESCE(trigger_time,original_trigger_time)) t, status, COUNT(*) "
        "FROM t_contact_plan_step WHERE plan_id IN (%s) AND status='PENDING' "
        "AND DATE(COALESCE(trigger_time,original_trigger_time)) IN ('2026-08-27','2026-08-28') "
        "GROUP BY plan_id, channel_type, d, t, status "
        "ORDER BY plan_id, d, t" % plan_ids,
    )
)
