#!/usr/bin/env python3
"""8/31 leftover: timeline, email slots, SETNX, 531687, poison. No PII bodies beyond script_slot."""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-31"
EMAIL4 = "506516,531187,531221,531489"
CATCH8 = "506565,519633,531157,531245,531278,531315,531368,531446"


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


def logs(pat, extra="| tail -60"):
    cmd = "docker logs --since 20h collection-admin 2>&1 | grep -E %s %s" % (
        repr(pat),
        extra,
    )
    out = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return (out.stdout or "").strip()


def main():
    vals = envfile()
    print("=== timeline columns channel ===")
    print(mysql(vals, "SHOW COLUMNS FROM t_contact_timeline"))
    print("=== timeline today OUT ===")
    print(
        mysql(
            vals,
            "SELECT channel, direction, COUNT(*) n FROM t_contact_timeline "
            "WHERE created_at>='%s 00:00:00' AND created_at<'%s 23:59:59' "
            "GROUP BY 1,2 ORDER BY 1,2" % (TODAY, TODAY),
        )
    )
    print("=== email 12 script_slot / template / summary (no PII expected in slot) ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, c.dpd, t.script_slot, t.template_id, t.template_version, "
            "LEFT(IFNULL(t.content_summary,''), 80) summary "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id=t.step_id "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
            "WHERE t.channel='EMAIL' AND t.created_at>='%s 00:00:00' "
            "ORDER BY p.case_id" % TODAY,
        )
    )
    print("=== SMS/PUSH distinct script_slot today ===")
    print(
        mysql(
            vals,
            "SELECT channel, script_slot, COUNT(*) n "
            "FROM t_contact_timeline "
            "WHERE created_at>='%s 00:00:00' AND channel IN ('SMS','PUSH') "
            "GROUP BY 1,2 ORDER BY 1,2" % TODAY,
        )
    )
    print("=== SMS/PUSH content_summary sample (5) ===")
    print(
        mysql(
            vals,
            "SELECT channel, script_slot, LEFT(IFNULL(content_summary,''), 120) "
            "FROM t_contact_timeline "
            "WHERE created_at>='%s 00:00:00' AND channel IN ('SMS','PUSH') "
            "GROUP BY 1,2,3 LIMIT 8" % TODAY,
        )
    )
    print("=== 200 S2 PENDING 3 ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id, p.stage, p.status "
            "FROM t_contact_plan p "
            "WHERE p.status='PENDING' AND p.stage='S2'",
        )
    )
    print("=== 531687 ===")
    print(
        mysql(
            vals,
            "SELECT p.id, p.stage, p.status, p.cancel_reason, "
            "s.channel_type, s.status, IFNULL(s.result,''), "
            "DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, s.executed_at "
            "FROM t_contact_plan p LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id "
            "WHERE p.case_id=531687 ORDER BY p.id DESC, s.original_trigger_time LIMIT 40",
        )
    )
    print("=== 513749 526109 latest plans ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id, p.stage, p.status, IFNULL(p.cancel_reason,''), p.created_at "
            "FROM t_contact_plan p WHERE p.case_id IN (513749,526109) "
            "ORDER BY p.case_id, p.id",
        )
    )
    print("=== S0 projection ===")
    print(mysql(vals, "SELECT COUNT(*) n FROM t_ai_collection WHERE stage='S0' OR dpd<0"))
    print(mysql(vals, "SELECT stage, dpd, COUNT(*) n FROM t_ai_collection WHERE dpd<=0 OR stage='S0' GROUP BY 1,2"))
    print("=== inbox after 14:00 ===")
    print(
        mysql(
            vals,
            "SELECT message_type, COUNT(*) n, MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox WHERE created_at>='%s 14:00:00' GROUP BY 1" % TODAY,
        )
    )
    print("=== repay cases today ===")
    print(
        mysql(
            vals,
            "SELECT case_id, COUNT(*) n, MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox WHERE message_type='repaymentEvent' "
            "AND created_at>='%s 00:00:00' GROUP BY 1" % TODAY,
        )
    )
    print("=== 200 S1 remaining active? ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id, p.stage, p.status FROM t_contact_plan p "
            "WHERE p.stage='S1' AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')",
        )
    )
    print("=== SETNX / batch / dailyRoll ===")
    print(
        logs(
            "planStepDue scanned|batch started|wave=|SETNX|daily roll completed|FacadeBatch|started batchId|mocasa-20260831",
            "| grep '%s' | grep -E '08:00|09:15|12:00|14:00|14:30|03:3|11:5' | tail -90" % TODAY,
        )
        or "(none)"
    )
    print("=== poison / dueDate ===")
    print(
        logs(
            "poison|非法 dueDate|非法 nextDueDate|unparseable|DateTimeParse|upcomingAmount",
            "| grep '%s' | tail -40" % TODAY,
        )
        or "(none)"
    )
    print("=== ERROR today last 25 ===")
    print(logs(" ERROR ", "| grep '%s' | tail -25" % TODAY) or "(none)")
    print("=== skipOpen / WHITELIST ===")
    print(logs("skipped .* open step|skipOpen|WHITELIST_SKIPPED|案件不在白名单", "| grep '%s' | tail -15" % TODAY) or "(none)")


if __name__ == "__main__":
    main()
