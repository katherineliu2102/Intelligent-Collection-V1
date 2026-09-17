#!/usr/bin/env python3
"""Find a named whitelist case with no AI_CALL touch today. No PII."""
import os
import subprocess
from datetime import datetime, timedelta, timezone

PHT = timezone(timedelta(hours=8))
today = datetime.now(PHT).strftime("%Y-%m-%d")

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
            "-h", vals["COLLECTION_DB_HOST"],
            "-P", vals.get("COLLECTION_DB_PORT", "3306"),
            "-u", vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-N",
            "-e",
            sql,
        ],
        env=env,
        stderr=subprocess.DEVNULL,
    ).decode()

def main():
    vals = envfile()
    ids = [x.strip() for x in vals.get("COLLECTION_PILOT_LOAN_IDS", "").replace(";", ",").split(",") if x.strip()]
    inlist = ",".join(ids)
    print("whitelist", len(ids), "today_pht", today)
    print("named", mysql(vals, "SELECT COUNT(*) FROM t_ai_collection WHERE case_id IN (%s) AND borrower_name IS NOT NULL AND borrower_name<>''" % inlist).strip())
    print("email_pending", mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id WHERE p.case_id IN (%s) AND s.channel_type='EMAIL' AND s.status='PENDING'" % inlist).strip())
    print("---candidates no AI_CALL timeline today, has name, has pending SMS or PUSH or AI_CALL---")
    print(mysql(vals, """
SELECT c.case_id, c.stage, c.dpd,
  SUM(s.channel_type='SMS' AND s.status='PENDING') sms_p,
  SUM(s.channel_type='PUSH' AND s.status='PENDING') push_p,
  SUM(s.channel_type='EMAIL' AND s.status='PENDING') email_p,
  SUM(s.channel_type='AI_CALL' AND s.status='PENDING') ai_p,
  p.status plan_status
FROM t_ai_collection c
JOIN t_contact_plan p ON p.case_id=c.case_id AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')
LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id
WHERE c.case_id IN (%s)
AND c.borrower_name IS NOT NULL AND c.borrower_name<>''
AND c.case_id NOT IN (
  SELECT DISTINCT case_id FROM t_contact_timeline
  WHERE channel='AI_CALL' AND DATE(CONVERT_TZ(created_at,'+00:00','+08:00'))='%s'
)
GROUP BY c.case_id, c.stage, c.dpd, p.status
HAVING sms_p+push_p+email_p+ai_p>0
ORDER BY c.case_id
LIMIT 8
""" % (inlist, today)).rstrip() or "<none>")

if __name__ == "__main__":
    main()
