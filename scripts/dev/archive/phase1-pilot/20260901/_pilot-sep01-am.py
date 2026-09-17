#!/usr/bin/env python3
"""9/1 上午取数：日切续档、S0、8+3 案、已过槽。不打印 PII。"""
from pathlib import Path
import os
import subprocess

TODAY = "2026-09-01"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
S1_OK8 = "506855,507102,520209,531504,531513,531593,531682,531687"
S1_EXH3 = "520023,531516,531578"
CATCH8 = "506565,519633,531157,531245,531278,531315,531368,531446"
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


def logs(pat, extra="| tail -50"):
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
    print("pilot_list_len", len((vals.get("COLLECTION_PILOT_LOAN_IDS") or "").split(",")) if vals.get("COLLECTION_PILOT_LOAN_IDS") else 0)

    csv_ids = []
    if Path(CSV).exists():
        for line in Path(CSV).read_text().splitlines():
            body = line.strip()
            if body and body.lower() != "loan_id":
                csv_ids.append(body)
    csv_in = ",".join(csv_ids) if csv_ids else "0"
    print("csv", len(csv_ids))

    print("=== projection total / 200 ===")
    print("total", mysql(vals, "SELECT COUNT(*) FROM t_ai_collection").strip())
    print("in200", mysql(vals, "SELECT COUNT(*) FROM t_ai_collection WHERE case_id IN (%s)" % csv_in).strip())
    print("=== projection 200 by status/stage/dpd ===")
    print(
        mysql(
            vals,
            "SELECT collection_status, IFNULL(stage,'NULL'), dpd, COUNT(*) n "
            "FROM t_ai_collection WHERE case_id IN (%s) GROUP BY 1,2,3 ORDER BY 1,2,3" % csv_in,
        )
    )
    print("=== S0 / dpd<=0 ===")
    print(
        mysql(
            vals,
            "SELECT IFNULL(stage,'NULL'), dpd, collection_status, COUNT(*) n "
            "FROM t_ai_collection WHERE stage='S0' OR dpd<=0 GROUP BY 1,2,3",
        )
    )
    print("=== inbox today ===")
    print(
        mysql(
            vals,
            "SELECT message_type, COUNT(*) n, COUNT(DISTINCT case_id) cases, "
            "MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox WHERE created_at>='%s 00:00:00' "
            "AND created_at<'%s 23:59:59' GROUP BY 1" % (TODAY, TODAY),
        )
    )
    print("=== inbox caseEvent in200 / not200 ===")
    print(
        mysql(
            vals,
            "SELECT CASE WHEN case_id IN (%s) THEN 'in200' ELSE 'not200' END g, COUNT(*) n "
            "FROM t_ai_collection_inbox WHERE message_type='caseEvent' "
            "AND created_at>='%s 00:00:00' GROUP BY 1" % (csv_in, TODAY),
        )
    )
    print("=== 200 still missing ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) FROM ("
            "SELECT TRIM(SUBSTRING_INDEX(SUBSTRING_INDEX('%s', ',', n.n), ',', -1)) id "
            "FROM (SELECT 1 n UNION SELECT 2) dummy, "
            "(SELECT @n:=@n+1 n FROM t_ai_collection, (SELECT @n:=0) x LIMIT 250) n "
            "WHERE n.n <= 1+LENGTH('%s')-LENGTH(REPLACE('%s',',',''))"
            ") z LEFT JOIN t_ai_collection c ON c.case_id=z.id WHERE c.case_id IS NULL"
            % (csv_in, csv_in, csv_in),
        )
    )

    print("=== S1_OK8 projection + active/latest plans ===")
    print(
        mysql(
            vals,
            "SELECT c.case_id, c.stage, c.dpd, c.collection_status, "
            "p.id, p.stage pst, p.status, p.created_at "
            "FROM t_ai_collection c "
            "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id "
            "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "WHERE c.case_id IN (%s) ORDER BY c.case_id" % S1_OK8,
        )
    )
    print("=== S1_OK8 all plans created today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id, p.stage, p.status, p.created_at "
            "FROM t_contact_plan p WHERE p.case_id IN (%s) AND p.created_at>='%s 00:00:00' "
            "ORDER BY p.case_id, p.id" % (S1_OK8, TODAY),
        )
    )
    print("=== S1_EXH3 active plans count ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, COUNT(*) n, GROUP_CONCAT(CONCAT(p.id,':',p.stage,':',p.status) "
            "ORDER BY p.id) plans "
            "FROM t_contact_plan p WHERE p.case_id IN (%s) "
            "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "GROUP BY 1" % S1_EXH3,
        )
    )
    print(
        mysql(
            vals,
            "SELECT c.case_id, c.stage, c.dpd, p.id, p.stage, p.status, p.created_at "
            "FROM t_ai_collection c "
            "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id "
            "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "WHERE c.case_id IN (%s) ORDER BY c.case_id, p.id" % S1_EXH3,
        )
    )
    print("=== CATCH8 / OLD2 / 531315 ===")
    print(
        mysql(
            vals,
            "SELECT c.case_id, c.stage, c.dpd, p.id, p.stage, p.status "
            "FROM t_ai_collection c "
            "LEFT JOIN t_contact_plan p ON p.case_id=c.case_id "
            "AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "WHERE c.case_id IN (%s,%s,531315) ORDER BY c.case_id" % (CATCH8, OLD2),
        )
    )
    print("=== 200 active plans ===")
    print(
        mysql(
            vals,
            "SELECT p.status, p.stage, COUNT(*) n FROM t_contact_plan p "
            "WHERE p.case_id IN (%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "GROUP BY 1,2 ORDER BY 1,2" % csv_in,
        )
    )
    print("=== NOT200 active plans ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, p.id, p.stage, p.status FROM t_contact_plan p "
            "WHERE p.case_id NOT IN (%s) AND p.status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED') "
            "ORDER BY p.case_id" % csv_in,
        )
    )
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
            "SELECT s.channel_type, s.status, IFNULL(s.result,''), COUNT(*) n, "
            "MIN(s.executed_at), MAX(s.executed_at) "
            "FROM t_contact_plan_step s "
            "WHERE s.executed_at>='%s 00:00:00' AND s.executed_at<'%s 23:59:59' "
            "GROUP BY 1,2,3" % (TODAY, TODAY),
        )
    )
    print("=== executed NOT in 200 ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.channel_type, s.result "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.executed_at>='%s 00:00:00' AND p.case_id NOT IN (%s) "
            "ORDER BY p.case_id, s.channel_type" % (TODAY, csv_in),
        )
    )
    print("=== 531315 today AI ===")
    print(
        mysql(
            vals,
            "SELECT s.id, s.status, IFNULL(s.result,''), s.original_trigger_time, s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE p.case_id=531315 AND s.channel_type='AI_CALL' "
            "AND s.original_trigger_time>='%s 00:00:00'" % TODAY,
        )
    )
    print("=== S1_OK8 today executed ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.channel_type, s.status, IFNULL(s.result,''), s.executed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE p.case_id IN (%s) AND s.executed_at>='%s 00:00:00' "
            "ORDER BY p.case_id, s.executed_at" % (S1_OK8, TODAY),
        )
    )
    print("=== email today pending/done ===")
    print(
        mysql(
            vals,
            "SELECT DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, s.status, "
            "IFNULL(s.result,''), COUNT(*) n, COUNT(DISTINCT p.case_id) cases "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.channel_type='EMAIL' AND s.original_trigger_time>='%s 00:00:00' "
            "AND s.original_trigger_time<'%s 23:59:59' GROUP BY 1,2,3" % (TODAY, TODAY),
        )
    )
    print(
        mysql(
            vals,
            "SELECT c.dpd, IFNULL(c.stage,'NULL'), COUNT(DISTINCT p.case_id) n "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "LEFT JOIN t_ai_collection c ON c.case_id=p.case_id "
            "WHERE s.channel_type='EMAIL' AND s.original_trigger_time>='%s 00:00:00' "
            "AND s.original_trigger_time<'%s 23:59:59' GROUP BY 1,2" % (TODAY, TODAY),
        )
    )
    print("=== hanging / outbox / dlq ===")
    print("executing", mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'").strip())
    print("outbox_pending", mysql(vals, "SELECT COUNT(*) FROM t_event_outbox WHERE status='PENDING'").strip())
    print("dlq_today", mysql(vals, "SELECT COUNT(*) FROM t_event_dlq WHERE created_at>='%s 00:00:00'" % TODAY).strip())
    print("=== AI answered today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.id, s.result, s.executed_at, s.completed_at "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.channel_type='AI_CALL' AND s.result='ANSWERED' "
            "AND s.executed_at>='%s 00:00:00'" % TODAY,
        )
    )
    print("=== repay today ===")
    print(
        mysql(
            vals,
            "SELECT case_id, COUNT(*) n, MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox WHERE message_type='repaymentEvent' "
            "AND created_at>='%s 00:00:00' GROUP BY 1" % TODAY,
        )
    )
    print("=== daily roll / SETNX / poison date ===")
    print(
        logs(
            "daily roll completed|DpdStageRollHandler|planStepDue scanned|SETNX|started batch|wave mocasa-20260901|非法 dueDate|非法 nextDueDate",
            "| grep '%s' | grep -v enrolled | tail -80" % TODAY,
        )
        or "(none)"
    )
    print("=== ERROR today ===")
    print(logs(" ERROR ", "| grep '%s' | tail -20" % TODAY) or "(none)")
    print("=== WHITELIST / skipOpen ===")
    print(logs("WHITELIST_SKIPPED|案件不在白名单|skipOpen|skipped .* open step", "| grep '%s' | tail -15" % TODAY) or "(none)")


if __name__ == "__main__":
    main()
