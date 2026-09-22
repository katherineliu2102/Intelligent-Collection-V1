#!/usr/bin/env python3
# ⚠ MUTATING — DO NOT cron / DO NOT re-run without change window + backup.
# Archived one-off Phase1 Pilot tool. Prefer scripts/pilot/pilot-env.py or deploy handbook.
# ---
"""订正 hang 住的 step 2477。在 Pilot 上跑，读 /opt/app/pilot.env。"""
import os
import subprocess

SQL_PATH = os.environ.get("FIX_SQL", "/tmp/2026-08-26-fix-hanging-ai-step-2477.sql")


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


def mysql_file(vals, path):
    env = os.environ.copy()
    env["MYSQL_PWD"] = vals["COLLECTION_DB_PASSWORD"]
    with open(path, "rb") as f:
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
            ],
            env=env,
            stdin=f,
            stderr=subprocess.STDOUT,
        ).decode()


def main():
    vals = envfile()
    print(mysql_file(vals, SQL_PATH))


if __name__ == "__main__":
    main()
