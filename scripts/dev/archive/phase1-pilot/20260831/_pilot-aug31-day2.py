#!/usr/bin/env python3
"""8/31 follow-up. No PII."""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-31"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"


def envfile():
    vals = {}
    for line in Path("/opt/app/pilot.env").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")
    return vals


def load_csv():
    ids = []
    for line in Path(CSV).read_text().splitlines():
        body = line.strip()
        if body and body.lower() != "loan_id":
            ids.append(body)
    return ids


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
        stderr=subprocess.STDOUT,
    ).decode()


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout


vals = envfile()
csv_in = ",".join(load_csv())

print("=== timeline cols ===")
print(mysql(vals, "SHOW COLUMNS FROM t_contact_timeline"))
print("=== pending 08:00/09:15 cases ===")
print(
    mysql(
        vals,
        "SELECT DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, "
        "s.channel_type, p.case_id, p.id plan_id, s.id step_id, p.status, "
        "p.created_at, s.trigger_time, IFNULL(c.stage,'NULL'), c.dpd "
        "FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
        "WHERE s.status='PENDING' "
        "AND s.original_trigger_time IN ('%s 08:00:00','%s 09:15:00') "
        "ORDER BY slot, p.case_id" % (TODAY, TODAY),
    )
)
print("=== skipped 08:00 SMS cancel_reason ===")
print(
    mysql(
        vals,
        "SELECT IFNULL(p.cancel_reason,'NULL'), COUNT(*) n "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.original_trigger_time='%s 08:00:00' AND s.channel_type='SMS' "
        "AND s.status='SKIPPED' GROUP BY 1" % TODAY,
    )
)
print("=== skipped 08:00 SMS in200 vs not ===")
print(
    mysql(
        vals,
        "SELECT CASE WHEN p.case_id IN (%s) THEN 'in200' ELSE 'not200' END g, "
        "COUNT(*) n, COUNT(DISTINCT p.case_id) cases "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.original_trigger_time='%s 08:00:00' AND s.channel_type='SMS' "
        "AND s.status='SKIPPED' GROUP BY 1" % (csv_in, TODAY),
    )
)
print("=== today SMS delivered in200 vs not ===")
print(
    mysql(
        vals,
        "SELECT CASE WHEN p.case_id IN (%s) THEN 'in200' ELSE 'not200' END g, "
        "COUNT(*) n "
        "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        "WHERE s.executed_at >= '%s 00:00:00' AND s.channel_type='SMS' "
        "AND s.result='DELIVERED' GROUP BY 1" % (csv_in, TODAY),
    )
)
print("=== 34 not200 inbox: have active plan? projection? ===")
print(
    mysql(
        vals,
        "SELECT "
        "SUM(c.case_id IS NOT NULL) has_proj, "
        "SUM(p.id IS NOT NULL) has_any_plan, "
        "SUM(p.id IS NOT NULL AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')) has_active "
        "FROM t_ai_collection_inbox i "
        "LEFT JOIN t_ai_collection c ON c.case_id=i.case_id "
        "LEFT JOIN t_contact_plan p ON p.case_id=i.case_id "
        "WHERE i.created_at >= '%s 00:00:00' AND i.message_type='caseEvent' "
        "AND i.case_id NOT IN (%s)" % (TODAY, csv_in),
    )
)
print("=== 34 not200 latest plan status ===")
print(
    mysql(
        vals,
        "SELECT IFNULL(x.status,'NONE'), COUNT(*) n FROM ("
        "SELECT i.case_id, "
        "(SELECT p.status FROM t_contact_plan p WHERE p.case_id=i.case_id "
        "ORDER BY p.id DESC LIMIT 1) status "
        "FROM t_ai_collection_inbox i "
        "WHERE i.created_at >= '%s 00:00:00' AND i.message_type='caseEvent' "
        "AND i.case_id NOT IN (%s)"
        ") x GROUP BY 1" % (TODAY, csv_in),
    )
)
print("=== repay cases today ===")
print(
    mysql(
        vals,
        "SELECT case_id, COUNT(*) n, MIN(created_at), MAX(created_at) "
        "FROM t_ai_collection_inbox "
        "WHERE message_type='repaymentEvent' "
        "AND created_at >= '%s 00:00:00' GROUP BY 1" % TODAY,
    )
)
print("=== email 4 cases ===")
print(
    mysql(
        vals,
        "SELECT p.case_id, c.dpd, c.stage, s.id, s.status, "
        "DATE_FORMAT(s.original_trigger_time,'%%H:%%i') "
        "FROM t_contact_plan_step s "
        "JOIN t_contact_plan p ON p.id=s.plan_id "
        "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
        "WHERE s.channel_type='EMAIL' "
        "AND s.original_trigger_time >= '%s 00:00:00' "
        "AND s.original_trigger_time < '%s 23:59:59' "
        "ORDER BY p.case_id" % (TODAY, TODAY),
    )
)
print("=== 200 caseEvent vs yesterday 143 lost ===")
print(
    mysql(
        vals,
        "SELECT COUNT(DISTINCT case_id) FROM t_ai_collection_inbox "
        "WHERE message_type='caseEvent' AND created_at>='2026-08-30 00:00:00' "
        "AND created_at<'2026-08-31 00:00:00' AND case_id IN (%s)" % csv_in,
    ).strip()
)
print(
    mysql(
        vals,
        "SELECT case_id FROM t_ai_collection_inbox "
        "WHERE message_type='caseEvent' AND created_at>='2026-08-30 00:00:00' "
        "AND created_at<'2026-08-31 00:00:00' AND case_id IN (%s) "
        "AND case_id NOT IN ("
        "SELECT case_id FROM t_ai_collection_inbox "
        "WHERE message_type='caseEvent' AND created_at>='%s 00:00:00' "
        "AND case_id IN (%s)"
        ") ORDER BY case_id" % (csv_in, TODAY, csv_in),
    )
)
print("=== daily roll ===")
print(
    sh(
        "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -E 'daily roll|DpdStageRoll|scanned=' | grep -E '03:3|03:4|03:5|daily roll completed' | tail -40"
    )
)
print("=== 08:00 / 09:15 ticks ===")
print(
    sh(
        "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -E '08:00|09:15' | grep -E 'scanned=|wave=|batch|SETNX|FacadeBatch' | tail -40"
    )
)
print("=== WHITELIST / consume ===")
print(
    sh(
        "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -E 'WHITELIST|案件不在白名单|消费订阅' | tail -15"
    )
    or "(none)"
)
print("=== ERROR ===")
print(
    sh("docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | grep ' ERROR ' | tail -20")
    or "(none)"
)
print("=== cancel/skipOpen ===")
print(
    sh(
        "docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -E 'skipOpen|skipped .* open|\\[cancel\\]|\\[repayment\\]' | tail -20"
    )
    or "(none)"
)
print("=== deadlock ===")
print(
    sh("docker logs --since 20h collection-admin 2>&1 | grep '2026-08-31' | grep -i deadlock | tail -10")
    or "(none)"
)
