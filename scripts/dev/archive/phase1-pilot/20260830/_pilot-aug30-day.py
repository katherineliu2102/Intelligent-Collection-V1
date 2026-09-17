#!/usr/bin/env python3
"""8/30 自动跑取数。不打印 PII。"""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-30"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
OLD40_SAMPLE = "466438,482686,489935,502131,520049"


def envfile():
    vals = {}
    for line in Path("/opt/app/pilot.env").read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        vals[k] = v.strip().strip('"').strip("'")
    return vals


def parse_ids(raw):
    raw = (raw or "").replace(";", ",").replace(" ", "")
    return [x for x in raw.split(",") if x]


def load_csv():
    ids = []
    p = Path(CSV)
    if not p.exists():
        return []
    for line in p.read_text().splitlines():
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


def logs(pat, extra="| tail -40"):
    cmd = "docker logs --since 40h collection-admin 2>&1 | grep -E %s %s" % (
        repr(pat),
        extra,
    )
    out = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    return (out.stdout or "").strip()


def main():
    vals = envfile()
    csv_ids = load_csv()
    pilot = parse_ids(vals.get("COLLECTION_PILOT_LOAN_IDS", ""))
    scan = parse_ids(vals.get("COLLECTION_SCAN_CASE_IDS", ""))
    print("=== env ===")
    print("pilot", len(pilot), "scan", len(scan), "csv", len(csv_ids))
    print("pilot_eq_csv", set(pilot) == set(csv_ids))
    print("scan_eq_csv", set(scan) == set(csv_ids))
    print("sample", ",".join(pilot[:3]))

    insp = subprocess.check_output(
        [
            "docker",
            "inspect",
            "-f",
            "{{.State.Status}} {{.State.StartedAt}}",
            "collection-admin",
        ],
        text=True,
    ).strip()
    print("container", insp)
    health = subprocess.run(
        [
            "curl",
            "-s",
            "-o",
            "/dev/null",
            "-w",
            "%{http_code}",
            "http://127.0.0.1:8080/actuator/health",
        ],
        capture_output=True,
        text=True,
    ).stdout
    print("health", health)

    csv_in = ",".join(csv_ids) if csv_ids else "0"
    print("=== projection 200 ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) FROM t_ai_collection WHERE case_id IN (%s)" % csv_in,
        ).strip()
    )
    print("=== projection 200 by status/stage/dpd ===")
    print(
        mysql(
            vals,
            "SELECT collection_status, IFNULL(stage,'NULL'), dpd, COUNT(*) n "
            "FROM t_ai_collection WHERE case_id IN (%s) "
            "GROUP BY 1,2,3 ORDER BY 1,2,3" % csv_in,
        )
    )
    print("=== plans 200 ===")
    print(
        mysql(
            vals,
            "SELECT status, stage, COUNT(*) n FROM t_contact_plan "
            "WHERE case_id IN (%s) GROUP BY 1,2 ORDER BY 1,2" % csv_in,
        )
    )
    print("=== cases with any plan 200 ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(DISTINCT case_id) FROM t_contact_plan WHERE case_id IN (%s)"
            % csv_in,
        ).strip()
    )

    print("=== inbox today ===")
    print(
        mysql(
            vals,
            "SELECT message_type, COUNT(*) n, MIN(created_at), MAX(created_at), "
            "COUNT(DISTINCT case_id) cases "
            "FROM t_ai_collection_inbox "
            "WHERE created_at >= '%s 00:00:00' AND created_at < '%s 23:59:59' "
            "GROUP BY 1" % (TODAY, TODAY),
        )
    )
    print("=== inbox today in 200 ===")
    print(
        mysql(
            vals,
            "SELECT message_type, COUNT(*) n, COUNT(DISTINCT case_id) cases, "
            "MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox "
            "WHERE created_at >= '%s 00:00:00' AND created_at < '%s 23:59:59' "
            "AND case_id IN (%s) GROUP BY 1" % (TODAY, TODAY, csv_in),
        )
    )
    print("=== inbox today NOT in 200 ===")
    print(
        mysql(
            vals,
            "SELECT message_type, COUNT(*) n, COUNT(DISTINCT case_id) cases "
            "FROM t_ai_collection_inbox "
            "WHERE created_at >= '%s 00:00:00' AND created_at < '%s 23:59:59' "
            "AND case_id NOT IN (%s) GROUP BY 1" % (TODAY, TODAY, csv_in),
        )
    )

    print("=== t_ai_collection total ===")
    print(mysql(vals, "SELECT COUNT(*) FROM t_ai_collection").strip())

    print("=== today steps by orig trigger / channel / status / result ===")
    print(
        mysql(
            vals,
            "SELECT DATE_FORMAT(s.original_trigger_time,'%%H:%%i') slot, "
            "s.channel_type, s.status, IFNULL(s.result,'') result, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.original_trigger_time >= '%s 00:00:00' "
            "AND s.original_trigger_time < '%s 23:59:59' "
            "AND p.case_id IN (%s) "
            "GROUP BY 1,2,3,4 ORDER BY 1,2,3,4" % (TODAY, TODAY, csv_in),
        )
    )
    print("=== today executed_at by channel (200) ===")
    print(
        mysql(
            vals,
            "SELECT s.channel_type, s.status, IFNULL(s.result,'') result, COUNT(*) n, "
            "MIN(s.executed_at), MAX(s.executed_at) "
            "FROM t_contact_plan_step s "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.executed_at >= '%s 00:00:00' AND s.executed_at < '%s 23:59:59' "
            "AND p.case_id IN (%s) "
            "GROUP BY 1,2,3" % (TODAY, TODAY, csv_in),
        )
    )
    print("=== today executed_at NOT in 200 ===")
    print(
        mysql(
            vals,
            "SELECT s.channel_type, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.executed_at >= '%s 00:00:00' AND s.executed_at < '%s 23:59:59' "
            "AND p.case_id NOT IN (%s) GROUP BY 1" % (TODAY, TODAY, csv_in),
        )
    )
    print("=== hanging EXECUTING ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'",
        ).strip()
    )
    print("=== outbox pending ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) FROM t_event_outbox WHERE status='PENDING'",
        ).strip()
    )
    print("=== dlq today ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) FROM t_event_dlq WHERE created_at >= '%s 00:00:00'"
            % TODAY,
        ).strip()
    )
    print("=== timeline today OUT 200 ===")
    print(
        mysql(
            vals,
            "SELECT channel_type, COUNT(*) n FROM t_contact_timeline "
            "WHERE created_at >= '%s 00:00:00' AND created_at < '%s 23:59:59' "
            "AND case_id IN (%s) GROUP BY 1" % (TODAY, TODAY, csv_in),
        )
    )

    print("=== old40 sample still in projection ===")
    print(
        mysql(
            vals,
            "SELECT case_id, collection_status, IFNULL(stage,'NULL'), dpd "
            "FROM t_ai_collection WHERE case_id IN (%s)" % OLD40_SAMPLE,
        )
    )
    print("=== old40 sample today executed ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.channel_type, COUNT(*) n "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.executed_at >= '%s 00:00:00' AND s.executed_at < '%s 23:59:59' "
            "AND p.case_id IN (%s) GROUP BY 1,2" % (TODAY, TODAY, OLD40_SAMPLE),
        )
    )

    print("=== daily roll logs ===")
    print(logs("DpdStageRollHandler|daily roll completed|dailyRoll", "| grep '%s' | tail -30" % TODAY))
    print("=== slot ticks ===")
    print(
        logs(
            "planStepDue scanned|wave=|batch started|FacadeBatch|SETNX",
            "| grep '%s' | grep -E '08:00|09:15|12:00|14:00|14:30|03:3|03:4|03:5' | tail -60"
            % TODAY,
        )
    )
    print("=== ERROR today ===")
    print(logs(" ERROR ", "| grep '%s' | tail -20" % TODAY) or "(none)")


if __name__ == "__main__":
    main()
