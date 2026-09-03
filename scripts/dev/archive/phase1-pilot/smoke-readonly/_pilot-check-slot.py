#!/usr/bin/env python3
"""按触达槽核对当天步骤执行情况。用法：_pilot-check-slot.py 12:00 [PUSH]"""
import os
import subprocess
import sys


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
    ).decode()


def main():
    slot = sys.argv[1] if len(sys.argv) > 1 else "12:00"
    channel = sys.argv[2] if len(sys.argv) > 2 else None
    vals = envfile()

    where = "s.original_trigger_time = CONCAT(CURDATE(),' " + slot + ":00')"
    if channel:
        where += " AND s.channel_type='" + channel + "'"

    print("=== 槽 " + slot + " 步骤状态 ===")
    print(
        mysql(
            vals,
            "SELECT s.channel_type, s.status, s.result, COUNT(*) n, "
            "MIN(s.executed_at) first_exec, MAX(s.executed_at) last_exec "
            "FROM t_contact_plan_step s WHERE " + where + " GROUP BY 1,2,3",
        )
    )

    print("=== 悬挂 EXECUTING（应为 0）===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) hanging FROM t_contact_plan_step s "
            "WHERE " + where + " AND s.status='EXECUTING'",
        )
    )

    print("=== 容器错误日志（最近 2000 行）===")
    out = subprocess.run(
        "docker logs --tail 2000 collection-admin 2>&1 | grep -E ' ERROR ' | tail -15",
        shell=True,
        capture_output=True,
        text=True,
    )
    print(out.stdout or "(无 ERROR)")


if __name__ == "__main__":
    main()
