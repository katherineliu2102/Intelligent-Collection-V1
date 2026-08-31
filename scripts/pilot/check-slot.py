#!/usr/bin/env python3
"""按触达槽核对当天步骤执行情况 + 悬挂 EXECUTING + 容器 ERROR 日志。

用法：
  ./scripts/pilot/check-slot.py 12:00              # 核对 12:00 槽全部渠道
  ./scripts/pilot/check-slot.py 12:00 PUSH         # 只看 PUSH 渠道
  ./scripts/pilot/check-slot.py --env /path/pilot.env 14:30

默认读 /opt/app/pilot.env（Pilot 容器内路径），可用 --env 或环境变量 PILOT_ENV 覆盖。
"""
import argparse
import os
import subprocess


def envfile(path):
    vals = {}
    with open(path) as f:
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
            "-N",
            "-h", vals["COLLECTION_DB_HOST"],
            "-P", vals.get("COLLECTION_DB_PORT", "3306"),
            "-u", vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
            "-e", sql,
        ],
        env=env,
    ).decode()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("slot", nargs="?", default="12:00", help="触达槽时刻，如 12:00")
    p.add_argument("channel", nargs="?", help="只核对该渠道，如 PUSH / AI_CALL / SMS")
    p.add_argument("--env", default=os.environ.get("PILOT_ENV", "/opt/app/pilot.env"))
    args = p.parse_args()
    vals = envfile(args.env)

    where = "s.original_trigger_time = CONCAT(CURDATE(),' " + args.slot + ":00')"
    if args.channel:
        where += " AND s.channel_type='" + args.channel + "'"

    print("=== 槽 " + args.slot + " 步骤状态 ===")
    print(mysql(
        vals,
        "SELECT s.channel_type, s.status, s.result, COUNT(*) n, "
        "MIN(s.executed_at) first_exec, MAX(s.executed_at) last_exec "
        "FROM t_contact_plan_step s WHERE " + where + " GROUP BY 1,2,3",
    ))

    print("=== 悬挂 EXECUTING（应为 0）===")
    print(mysql(
        vals,
        "SELECT COUNT(*) FROM t_contact_plan_step s "
        "WHERE " + where + " AND s.status='EXECUTING'",
    ))

    print("=== 容器错误日志（最近 2000 行）===")
    out = subprocess.run(
        "docker logs --tail 2000 collection-admin 2>&1 | grep -E ' ERROR ' | tail -15",
        shell=True, capture_output=True, text=True,
    )
    print(out.stdout or "(无 ERROR)")


if __name__ == "__main__":
    main()
