#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""9/1 afternoon fixes: stop old40 2 cases, export missing projections, check hikari."""
from pathlib import Path
import os
import subprocess

OLD40 = (513749, 526109)
CSV = "/tmp/e2e200_loan_ids_20260829.csv"
D91 = {469165, 468179, 468279, 468491}


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


def main():
    vals = envfile()
    ids = []
    for line in Path(CSV).read_text().splitlines():
        body = line.strip()
        if body and body.lower() != "loan_id":
            ids.append(int(body))
    csv_in = ",".join(str(i) for i in ids)

    print("=== 1. stop old40 two cases ===")
    old_in = ",".join(str(i) for i in OLD40)
    print("before active plans", mysql(vals, f"SELECT case_id,stage,status FROM t_contact_plan WHERE case_id IN ({old_in}) AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')"))
    mysql(
        vals,
        f"UPDATE t_contact_plan_step s JOIN t_contact_plan p ON p.id=s.plan_id "
        f"SET s.status='SKIPPED', s.result='SKIPPED', "
        f"s.completed_at=COALESCE(s.completed_at, NOW()), s.updated_at=NOW() "
        f"WHERE p.case_id IN ({old_in}) AND s.status IN ('PENDING','EXECUTING')",
    )
    mysql(
        vals,
        f"UPDATE t_contact_plan SET status='PLAN_CANCELLED', cancel_reason='MANUAL_CLEANUP', updated_at=NOW() "
        f"WHERE case_id IN ({old_in}) AND status NOT IN ('PLAN_COMPLETED','PLAN_CANCELLED')",
    )
    print("after active plans", mysql(vals, f"SELECT case_id,stage,status,cancel_reason FROM t_contact_plan WHERE case_id IN ({old_in}) ORDER BY id DESC LIMIT 4"))

    print("\n=== 2. missing e2e200 projections ===")
    proj_rows = mysql(vals, f"SELECT case_id FROM t_ai_collection WHERE case_id IN ({csv_in})").strip().splitlines()
    proj = set(int(x.strip()) for x in proj_rows if x.strip())
    missing = sorted(set(ids) - proj)
    print("missing_total", len(missing))
    missing_d91 = [i for i in missing if i in D91]
    missing_other = [i for i in missing if i not in D91]
    print("missing_d91", len(missing_d91), ",".join(str(i) for i in missing_d91))
    print("missing_non_d91", len(missing_other), ",".join(str(i) for i in missing_other))

    print("\n=== 3. S0 bucket missing (from csv buckets file if present) ===")
    s0_csv = Path("/tmp/e2e200_loans_20260829.csv")
    if s0_csv.exists():
        s0_ids = set()
        for line in s0_csv.read_text().splitlines()[1:]:
            parts = line.split(",")
            if len(parts) >= 3 and parts[2].startswith("S0_"):
                s0_ids.add(int(parts[0]))
        s0_missing = sorted(s0_ids - proj)
        print("s0_total_in_csv", len(s0_ids))
        print("s0_in_projection", len(s0_ids & proj))
        print("s0_missing", len(s0_missing))
        print("s0_missing_ids", ",".join(str(i) for i in s0_missing))

    print("\n=== 4. S0 template dayBlocks (dpdDay 0) ===")
    print(
        mysql(
            vals,
            "SELECT stage, JSON_EXTRACT(plan_json, '$.dayBlocks') AS blocks "
            "FROM t_contact_plan_template WHERE stage='S0' LIMIT 1",
        )
    )

    print("\n=== 5. hikari env ===")
    out = subprocess.check_output(["docker", "exec", "collection-admin", "printenv"], text=True)
    for line in sorted(out.splitlines()):
        if any(k in line.upper() for k in ("HIKARI", "POOL", "DATASOURCE")):
            print(line)

    print("\n=== 6. 1430 final ===")
    print(
        mysql(
            vals,
            "SELECT s.status, IFNULL(s.result,'') r, COUNT(*) n "
            "FROM t_contact_plan_step s "
            "WHERE s.channel_type='AI_CALL' "
            "AND s.original_trigger_time>='2026-09-01 14:30:00' "
            "AND s.original_trigger_time<'2026-09-01 14:31:00' GROUP BY 1,2",
        )
    )
    print("executing", mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE status='EXECUTING'").strip())


if __name__ == "__main__":
    main()
