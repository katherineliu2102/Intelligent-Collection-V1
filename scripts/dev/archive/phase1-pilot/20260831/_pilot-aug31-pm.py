#!/usr/bin/env python3
"""8/31 afternoon wrap: slots, 8 catch-up cases, email, hanging. No PII."""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-31"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
CATCH8 = "506565,519633,531157,531245,531278,531315,531368,531446"
EMAIL4 = "506516,531187,531221,531489"
S2S3 = "513749,526109"


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


def logs(pat, extra="| tail -50"):
    cmd = "docker logs --since 20h collection-admin 2>&1 | grep -E %s %s" % (
        repr(pat),
        extra,
    )
    out = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return (out.stdout or "").strip()


def main():
    vals = envfile()
    insp = subprocess.check_output(
        ["docker", "inspect", "-f", "{{.State.Status}} {{.State.StartedAt}}", "collection-admin"],
        text=True,
    ).strip()
    health = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8080/actuator/health"],
        capture_output=True,
        text=True,
    ).stdout
    print("=== env ===")
    print("container", insp)
    print("health", health)
    print("lists", len((vals.get("COLLECTION_PILOT_LOAN_IDS") or "").split(",")) if vals.get("COLLECTION_PILOT_LOAN_IDS") else 0)

    print("=== today steps orig trigger ===")
    print(
        mysql(
            vals,
            "SELECT DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, "
            "s.channel_type, s.status, IFNULL(s.result,'') result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.original_trigger_time >= '%s 00:00:00' "
            "AND s.original_trigger_time < '%s 23:59:59' "
            "GROUP BY 1,2,3,4 ORDER BY 1,2,3,4" % (TODAY, TODAY),
        )
    )
    print("=== today executed_at ===")
    print(
        mysql(
            vals,
            "SELECT DATE_FORMAT(s.executed_at,'%%H:%%i') hhmm, s.channel_type, "
            "s.status, IFNULL(s.result,'') result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.executed_at >= '%s 00:00:00' AND s.executed_at < '%s 23:59:59' "
            "GROUP BY 1,2,3,4 ORDER BY 1,2,3,4" % (TODAY, TODAY),
        )
    )
    print("=== catch8 plans+today steps ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id plan_id, p.stage, p.status, "
            "s.channel_type, s.status st, IFNULL(s.result,'') r, "
            "DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, s.executed_at "
            "FROM t_contact_plan p "
            "LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id "
            "AND s.original_trigger_time >= '%s 00:00:00' "
            "AND s.original_trigger_time < '%s 23:59:59' "
            "WHERE p.case_id IN (%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "ORDER BY p.case_id, s.original_trigger_time" % (TODAY, TODAY, CATCH8),
        )
    )
    print("=== catch8 executed today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.channel_type, s.status, IFNULL(s.result,''), s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE p.case_id IN (%s) AND s.executed_at >= '%s 00:00:00' "
            "AND s.executed_at < '%s 23:59:59' ORDER BY p.case_id, s.executed_at"
            % (CATCH8, TODAY, TODAY),
        )
    )
    print("=== S2->S3 extras ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id, p.stage, p.status, COUNT(s.id) steps "
            "FROM t_contact_plan p LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id "
            "WHERE p.case_id IN (%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "GROUP BY 1,2,3,4" % S2S3,
        )
    )
    print("=== email4 ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, c.dpd, c.stage, s.status, IFNULL(s.result,''), s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
            "WHERE p.case_id IN (%s) AND s.channel_type='EMAIL' "
            "AND s.original_trigger_time >= '%s 00:00:00' "
            "AND s.original_trigger_time < '%s 23:59:59'" % (EMAIL4, TODAY, TODAY),
        )
    )
    print("=== email timeline template (no body) ===")
    print(
        mysql(
            vals,
            "SHOW COLUMNS FROM t_contact_timeline LIKE '%%template%%'"
        )
    )
    print("=== hanging EXECUTING ===")
    print(mysql(vals, "SELECT COUNT(*) n FROM t_contact_plan_step WHERE status='EXECUTING'"))
    print(
        mysql(
            vals,
            "SELECT s.id, p.case_id, s.channel_type, p.status, s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.status='EXECUTING'",
        )
    )
    print("=== outbox/dlq ===")
    print("outbox_pending", mysql(vals, "SELECT COUNT(*) FROM t_event_outbox WHERE status='PENDING'").strip())
    print(
        "dlq_today",
        mysql(vals, "SELECT COUNT(*) FROM t_event_dlq WHERE created_at>='%s 00:00:00'" % TODAY).strip(),
    )
    print("=== timeline OUT today ===")
    print(
        mysql(
            vals,
            "SELECT channel_type, COUNT(*) n FROM t_contact_timeline "
            "WHERE created_at>='%s 00:00:00' AND created_at<'%s 23:59:59' GROUP BY 1"
            % (TODAY, TODAY),
        )
    )
    print("=== 531687 ===")
    print(
        mysql(
            vals,
            "SELECT p.id, p.stage, p.status, s.channel_type, s.status, IFNULL(s.result,''), "
            "DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot "
            "FROM t_contact_plan p LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id "
            "AND s.original_trigger_time>='%s 00:00:00' "
            "WHERE p.case_id=531687 AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')"
            % TODAY,
        )
    )
    print("=== inbox after 14:00 (post date-parse deploy) ===")
    print(
        mysql(
            vals,
            "SELECT message_type, COUNT(*) n, MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox WHERE created_at>='%s 14:00:00' "
            "GROUP BY 1" % TODAY,
        )
    )
    print("=== S0 projection count ===")
    print(mysql(vals, "SELECT stage, dpd, COUNT(*) n FROM t_ai_collection WHERE stage='S0' GROUP BY 1,2"))
    print("=== slot ticks ===")
    print(
        logs(
            "planStepDue scanned|batch started|wave=|SETNX|daily roll completed|poison|非法 dueDate|非法 nextDueDate",
            "| grep '%s' | tail -80" % TODAY,
        )
        or "(none)"
    )
    print("=== ERROR today last 20 ===")
    print(logs(" ERROR ", "| grep '%s' | tail -20" % TODAY) or "(none)")


if __name__ == "__main__":
    main()
