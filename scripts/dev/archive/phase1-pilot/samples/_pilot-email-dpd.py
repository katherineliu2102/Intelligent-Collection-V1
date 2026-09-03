#!/usr/bin/env python3
import os
import subprocess
from collections import Counter

vals = {}
with open("/opt/app/pilot.env") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")

ids = [x.strip() for x in vals.get("COLLECTION_PILOT_LOAN_IDS", "").replace(";", ",").split(",") if x.strip()]
inlist = ",".join(ids)
env = os.environ.copy()
env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]

def q(sql):
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
        stderr=subprocess.DEVNULL,
    ).decode()

rows = q(
    "SELECT dpd, stage, COUNT(*) FROM t_ai_collection WHERE case_id IN (%s) GROUP BY dpd, stage ORDER BY dpd"
    % inlist
).strip().splitlines()
print("n", len(ids))
print("dpd_stage_counts")
for row in rows:
    print(row.replace("\t", " "))
milestones = {0: "S0_DUE_TODAY", 1: "S1_OVERDUE", 4: "S2_ENTRY", 31: "S4_ENTRY", 75: "S4_PRE_CLOSE"}
hit = q(
    "SELECT dpd, COUNT(*) FROM t_ai_collection WHERE case_id IN (%s) AND dpd IN (0,1,4,31,75) GROUP BY dpd"
    % inlist
).strip()
print("milestone_hits")
print(hit.replace("\t", " ") if hit else "none")
print("pending_email_on_milestone_dpd")
print(
    q(
        """
SELECT COUNT(*) FROM t_contact_plan_step s
JOIN t_contact_plan p ON p.id=s.plan_id
JOIN t_ai_collection c ON c.case_id=p.case_id
WHERE c.case_id IN (%s)
AND s.channel_type='EMAIL' AND s.status='PENDING'
AND c.dpd IN (0,1,4,31,75)
"""
        % inlist
    ).strip()
)
