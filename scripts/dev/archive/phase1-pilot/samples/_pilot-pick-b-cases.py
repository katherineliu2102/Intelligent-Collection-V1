#!/usr/bin/env python3
"""Pick 2-3 cases for tonight SMS+AI pull. Print only chosen rows."""
import os
import subprocess
from datetime import datetime, timedelta, timezone

PHT = timezone(timedelta(hours=8))
now = datetime.now(PHT)
today = now.strftime("%Y-%m-%d")
tomorrow = (now + timedelta(days=1)).strftime("%Y-%m-%d")
EXCLUDE = {489935}  # already used in compressed smoke


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

rows = mysql(
    vals,
    """
SELECT p.case_id, p.id plan_id, p.stage,
  sms.id sms_id, ai.id ai_id,
  IFNULL((SELECT COUNT(*) FROM t_contact_timeline t
          WHERE t.case_id=p.case_id AND t.channel='SMS' AND t.direction='OUT'
            AND DATE(t.created_at)='%s'),0) sms_today,
  IFNULL((SELECT COUNT(*) FROM t_contact_timeline t
          WHERE t.case_id=p.case_id AND t.channel='AI_CALL' AND t.direction='OUT'
            AND DATE(t.created_at)='%s'),0) ai_today
FROM t_contact_plan p
JOIN t_contact_plan_step sms ON sms.plan_id=p.id AND sms.channel_type='SMS'
  AND sms.status='PENDING'
  AND DATE(COALESCE(sms.trigger_time,sms.original_trigger_time))='%s'
  AND TIME(COALESCE(sms.trigger_time,sms.original_trigger_time))='08:00:00'
JOIN t_contact_plan_step ai ON ai.plan_id=p.id AND ai.channel_type='AI_CALL'
  AND ai.status='PENDING'
  AND DATE(COALESCE(ai.trigger_time,ai.original_trigger_time))='%s'
  AND TIME(COALESCE(ai.trigger_time,ai.original_trigger_time))='09:15:00'
WHERE p.case_id IN (%s)
  AND p.status='STEP_SCHEDULED'
HAVING sms_today=0 AND ai_today<=1
ORDER BY ai_today ASC, p.case_id
LIMIT 8
"""
    % (today, today, tomorrow, tomorrow, in_list),
).strip()
print("candidates")
print(rows or "<none>")
