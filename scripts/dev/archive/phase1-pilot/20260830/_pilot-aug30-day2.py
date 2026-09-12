#!/usr/bin/env python3
"""8/30 补取：缺案、悬挂、还款、接通、日切日志。不打印 PII。"""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-30"
CSV = "/tmp/e2e200_loan_ids_20260829.csv"

BUCKETS = {
    "S0_D-3": [507188,507209,507250,507262,507265,507272,531802,531811,531812,531813,531816,531817],
    "S0_D-2": [507279,507296,507300,507313,531822,531827,531831,531833],
    "S0_D-1": [507323,507333,507347,507350,531841,531845,531847,531848],
    "S0_D0": [507363,507373,507376,507379,507398,507399,531851,531852,531858,531860,531866,531867],
    "S1_D+1": [506855,507102,520023,520142,520194,520209,531504,531513,531516,531529,531556,531578,531592,531593,531682,531687,531713,531718,531727,531771],
    "S1_D+2": [506516,506532,506565,506697,519627,519633,519673,531157,531187,531221,531245,531278,531315,531368,531446,531489],
    "S2_D+4": [505611,505644,505772,505819,505901,518937,518947,530512,530536,530556,530558,530569,530572,530599,530618,530684,530713,530731,530770,530771],
    "S2_D+7": [504728,504801,517781,517834,517881,518005,529581,529588,529657,529737,529759,529833],
    "S3_D+16": [500583,513743,513806,513843,513849,513984,514027,525992,525993,526002,526003,526011,526014,526026,526042,526078,526087,526138,526164,526218],
    "S3_D+23": [497410,497428,511238,523486,523517,523553,523555,523556,523578,523581,523645,523704],
    "S4_D+31": [493332,506867,506929,506956,506986,507001,519922,519924,519942,519949,519969,519995,520004,520009,520050,520076,520084,520087,520106,520187],
    "S4_D+61": [478027,493296,506789,506810,506820,506824,506833,506837,506842,506926,506950,507020],
    "S4_D+75": [470966,487034,487063,487157,487187,487237,487480,487509,501308,501311,501346,501358,501398,501707,501878,501992],
    "S4_D+80": [468206,484420,498996,499044],
    "D91_enter": [463016,463054,478720,478895],
    "D91_already": [469165,468179,468279,468491],
}


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


def load_csv():
    ids = []
    for line in Path(CSV).read_text().splitlines():
        body = line.strip()
        if body and body.lower() != "loan_id":
            ids.append(int(body))
    return ids


def main():
    vals = envfile()
    csv_ids = load_csv()
    csv_in = ",".join(str(i) for i in csv_ids)

    present = set(
        int(x)
        for x in mysql(
            vals,
            "SELECT case_id FROM t_ai_collection WHERE case_id IN (%s)" % csv_in,
        ).split()
        if x
    )
    missing = [i for i in csv_ids if i not in present]
    print("=== missing 200 not in projection", len(missing), "===")
    by_bucket = {k: [i for i in v if i not in present] for k, v in BUCKETS.items()}
    for k, v in by_bucket.items():
        in_b = sum(1 for i in BUCKETS[k] if i in present)
        print("%s present=%s missing=%s ids=%s" % (k, in_b, len(v), ",".join(str(x) for x in v) if v else "-"))

    print("=== NULL-stage / settled rows ===")
    print(
        mysql(
            vals,
            "SELECT case_id, collection_status, IFNULL(stage,'NULL'), dpd "
            "FROM t_ai_collection WHERE case_id IN (%s) "
            "AND (stage IS NULL OR collection_status='SETTLED') "
            "ORDER BY collection_status, dpd" % csv_in,
        )
    )

    print("=== hanging EXECUTING ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.id, s.plan_id, s.channel_type, s.original_trigger_time, "
            "s.executed_at, s.timeout_time, s.status "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.status='EXECUTING'",
        )
    )

    print("=== ANSWERED today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.id, s.plan_id, s.original_trigger_time, s.executed_at, s.result "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.executed_at >= '%s 00:00:00' AND s.result='ANSWERED'" % TODAY,
        )
    )
    print("=== SKIPPED today ===")
    print(
        mysql(
            vals,
            "SELECT p.case_id, s.id, s.plan_id, s.original_trigger_time, s.status, s.result "
            "FROM t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
            "WHERE s.original_trigger_time >= '%s 00:00:00' AND s.status='SKIPPED'" % TODAY,
        )
    )

    print("=== repaymentEvent cases today ===")
    print(
        mysql(
            vals,
            "SELECT case_id, COUNT(*) n, MIN(created_at), MAX(created_at) "
            "FROM t_ai_collection_inbox "
            "WHERE created_at >= '%s 00:00:00' AND message_type='repaymentEvent' "
            "GROUP BY 1 ORDER BY n DESC" % TODAY,
        )
    )

    print("=== 14:00 EMAIL steps any ===")
    print(
        mysql(
            vals,
            "SELECT s.status, IFNULL(s.result,''), COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.original_trigger_time='%s 14:00:00' OR "
            "(s.channel_type='EMAIL' AND s.executed_at >= '%s 00:00:00')"
            % (TODAY, TODAY),
        )
    )

    print("=== timeline cols ===")
    print(
        mysql(
            vals,
            "SHOW COLUMNS FROM t_contact_timeline LIKE '%channel%'",
        )
    )
    print(
        mysql(
            vals,
            "SELECT direction, COUNT(*) FROM t_contact_timeline "
            "WHERE created_at >= '%s 00:00:00' GROUP BY 1" % TODAY,
        )
    )

    print("=== plans cancelled/completed cases ===")
    print(
        mysql(
            vals,
            "SELECT p.status, p.stage, COUNT(DISTINCT p.case_id) cases, COUNT(*) plans "
            "FROM t_contact_plan p WHERE p.case_id IN (%s) "
            "AND p.status IN ('PLAN_CANCELLED','PLAN_COMPLETED','PENDING') "
            "GROUP BY 1,2" % csv_in,
        )
    )

    print("=== daily roll ===")
    out = subprocess.run(
        "docker logs --since 40h collection-admin 2>&1 | grep -E 'DpdStageRollHandler|daily roll completed' | grep '%s' | tail -40"
        % TODAY,
        shell=True,
        capture_output=True,
        text=True,
    )
    print(out.stdout or "(none)")
    print("=== waves / facade ===")
    out = subprocess.run(
        "docker logs --since 40h collection-admin 2>&1 | grep '%s' | grep -E 'wave=|mocasa-20260830|FacadeBatch|batchAggregation|SETNX|planStepDue scanned' | tail -80"
        % TODAY,
        shell=True,
        capture_output=True,
        text=True,
    )
    print(out.stdout or "(none)")
    print("=== ERROR ===")
    out = subprocess.run(
        "docker logs --since 40h collection-admin 2>&1 | grep '%s' | grep ' ERROR ' | tail -25"
        % TODAY,
        shell=True,
        capture_output=True,
        text=True,
    )
    print(out.stdout or "(none)")
    print("=== whitelist skip today ===")
    out = subprocess.run(
        "docker logs --since 40h collection-admin 2>&1 | grep '%s' | grep -c WHITELIST_SKIPPED || true"
        % TODAY,
        shell=True,
        capture_output=True,
        text=True,
    )
    print("WHITELIST_SKIPPED_lines", (out.stdout or "").strip())


if __name__ == "__main__":
    main()
