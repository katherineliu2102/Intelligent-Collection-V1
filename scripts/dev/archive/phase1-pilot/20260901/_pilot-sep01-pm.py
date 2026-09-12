#!/usr/bin/env python3
"""9/1 afternoon wrap: slots, email, hanging, SETNX. No PII."""
from pathlib import Path
import os
import subprocess

TODAY = "2026-09-01"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
S0_11 = "507209,507262,507296,507323,507333,507347,507379,531817,531822,531860,531867"
S2_NEW = "507102,520209,531504,531513,531593,531682,531687"
EXH3 = "520023,531516,531578"
OLD2 = "513749,526109"


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


def logs(pat, extra="| tail -40"):
    cmd = "docker logs --since 24h collection-admin 2>&1 | grep -E %s %s" % (
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

    csv_ids = []
    if Path(CSV).exists():
        for line in Path(CSV).read_text().splitlines():
            body = line.strip()
            if body and body.lower() != "loan_id":
                csv_ids.append(body)
    csv_in = ",".join(csv_ids) if csv_ids else "0"

    print("=== today steps orig trigger ===")
    print(
        mysql(
            vals,
            "SELECT DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, "
            "s.channel_type, s.status, IFNULL(s.result,'') result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.original_trigger_time>='%s 00:00:00' "
            "AND s.original_trigger_time<'%s 23:59:59' "
            "GROUP BY 1,2,3,4 ORDER BY 1,2,3,4" % (TODAY, TODAY),
        )
    )
    print("=== today executed ===")
    print(
        mysql(
            vals,
            "SELECT DATE_FORMAT(s.executed_at,'%%H:%%i') hhmm, s.channel_type, "
            "s.status, IFNULL(s.result,''), COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.executed_at>='%s 00:00:00' AND s.executed_at<'%s 23:59:59' "
            "GROUP BY 1,2,3,4 ORDER BY 1,2,3,4" % (TODAY, TODAY),
        )
    )
    print("=== email timeline slot/template ===")
    print(
        mysql(
            vals,
            "SELECT c.dpd, IFNULL(c.stage,'NULL'), t.script_slot, t.template_version, COUNT(*) n "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id=t.step_id "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
            "WHERE t.channel='EMAIL' AND t.created_at>='%s 00:00:00' "
            "GROUP BY 1,2,3,4 ORDER BY 1,2" % TODAY,
        )
    )
    print("=== S0 11 email/push ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.channel_type, s.status, IFNULL(s.result,''), s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE p.case_id IN (%s) AND s.executed_at>='%s 00:00:00' "
            "ORDER BY p.case_id, s.executed_at" % (S0_11, TODAY),
        )
    )
    print("=== S2 new7 email ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, c.dpd, s.channel_type, s.status, IFNULL(s.result,''), s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
            "WHERE p.case_id IN (%s) AND s.channel_type='EMAIL' "
            "AND s.original_trigger_time>='%s 00:00:00'" % (S2_NEW, TODAY),
        )
    )
    print("=== 513849 14:30 ===")
    print(
        mysql(
            vals,
            "SELECT s.id, s.status, IFNULL(s.result,''), s.original_trigger_time, s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE p.case_id=513849 AND s.channel_type='AI_CALL' "
            "AND s.original_trigger_time>='%s 00:00:00' "
            "AND s.original_trigger_time<'%s 23:59:59'" % (TODAY, TODAY),
        )
    )
    print("=== hanging / outbox / dlq ===")
    print("executing", mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'").strip())
    print(
        mysql(
            vals,
            "SELECT s.id, p.case_id, s.channel_type, s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.status='EXECUTING'",
        )
    )
    print("outbox", mysql(vals, "SELECT COUNT(*) FROM t_event_outbox WHERE status='PENDING'").strip())
    print("dlq", mysql(vals, "SELECT COUNT(*) FROM t_event_dlq WHERE created_at>='%s 00:00:00'" % TODAY).strip())
    print("=== AI answered today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.id, s.result, s.executed_at, s.completed_at, "
            "TIMESTAMPDIFF(SECOND,s.executed_at,s.completed_at) sec "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.channel_type='AI_CALL' AND s.result='ANSWERED' "
            "AND s.executed_at>='%s 00:00:00'" % TODAY,
        )
    )
    print("=== executed NOT200 ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.channel_type, s.result, s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.executed_at>='%s 00:00:00' AND p.case_id NOT IN (%s) "
            "ORDER BY s.executed_at, p.case_id" % (TODAY, csv_in),
        )
    )
    print("=== EXH3 / OLD2 active ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, COUNT(*) n FROM t_contact_plan p "
            "WHERE p.case_id IN (%s,%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "GROUP BY 1" % (EXH3, OLD2),
        )
    )
    print("=== timeline today ===")
    print(
        mysql(
            vals,
            "SELECT channel, COUNT(*) n FROM t_contact_timeline "
            "WHERE created_at>='%s 00:00:00' GROUP BY 1" % TODAY,
        )
    )
    print("=== SETNX / wave ===")
    print(
        logs(
            "wave started|SETNX|planStepDue scanned|mocasa-20260901",
            "| grep '%s' | grep -v enrolled | grep -E '12:00|14:00|14:30' | tail -30" % TODAY,
        )
        or "(none)"
    )
    print("=== ERROR afternoon ===")
    print(logs(" ERROR ", "| grep '%s' | grep -E '1[2-6]:' | tail -15" % TODAY) or "(none)")
    print("=== skipOpen after 09:38 ===")
    print(logs("skipped .* open step|skipOpen|REPAID", "| grep '%s' | tail -15" % TODAY) or "(none)")


if __name__ == "__main__":
    main()
