#!/usr/bin/env python3
"""波次聚合上线前置核对：跑 _pilot-wave-precheck.sql，确认 14:30 那批步骤的槽位可用。"""
import os
import subprocess


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


def main():
    vals = envfile()
    env = os.environ.copy()
    env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
    sql = open("/tmp/wave-precheck.sql").read()
    out = subprocess.run(
        [
            "mysql",
            "-h",
            vals["COLLECTION_DB_HOST"],
            "-P",
            vals.get("COLLECTION_DB_PORT", "3306"),
            "-u",
            vals["COLLECTION_DB_USERNAME"],
            vals["COLLECTION_DB_NAME"],
        ],
        input=sql,
        capture_output=True,
        text=True,
        env=env,
    )
    print(out.stdout)
    if out.stderr:
        print("STDERR:", out.stderr)


if __name__ == "__main__":
    main()
