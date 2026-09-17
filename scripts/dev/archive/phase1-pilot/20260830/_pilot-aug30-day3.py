#!/usr/bin/env python3
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-30"


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


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout


vals = envfile()

print("=== EMAIL 14:00 any ===")
print(mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE original_trigger_time='2026-08-30 14:00:00'"))
print(mysql(vals, "SELECT COUNT(*) FROM t_contact_plan_step WHERE channel_type='EMAIL' AND executed_at>='2026-08-30 00:00:00' AND executed_at<'2026-08-31 00:00:00'"))

print("=== timeline today ===")
print(mysql(vals, "SELECT channel, direction, COUNT(*) n FROM t_contact_timeline WHERE created_at>='2026-08-30 00:00:00' AND created_at<'2026-08-31 00:00:00' GROUP BY 1,2"))

print("=== 09:15 / 14:30 AI counts ===")
print(mysql(vals, "SELECT DATE_FORMAT(original_trigger_time,'%H:%i') slot, status, IFNULL(result,''), COUNT(*) FROM t_contact_plan_step WHERE original_trigger_time IN ('2026-08-30 09:15:00','2026-08-30 14:30:00') GROUP BY 1,2,3"))

print("=== 513849 plan status after answer ===")
print(mysql(vals, "SELECT id, status, stage FROM t_contact_plan WHERE case_id=513849"))

print("=== hanging plans ===")
print(mysql(vals, "SELECT id, case_id, status, stage FROM t_contact_plan WHERE case_id IN (531592,520142)"))

print("=== daily roll ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep DpdStageRollHandler | grep '2026-08-30' | tail -40") or "(none)")

print("=== waves ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30' | grep -E 'wave=|mocasa-20260830|FacadeBatch|batchAggregation|SETNX' | tail -50") or "(none)")

print("=== planStepDue ticks ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30' | grep -E 'planStepDue scanned|dailyRoll scanned' | head -20"))
print("--- more ---")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 0[38]:0' | grep -E 'scanned=' | head -20"))
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 09:15' | grep -E 'scanned=|wave=' | head -20"))
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 12:00' | grep -E 'scanned=' | head -10"))
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 14:0' | grep -E 'scanned=' | head -10"))
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30 14:30' | grep -E 'scanned=|wave=' | head -20"))

print("=== ERROR ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30' | grep ' ERROR ' | tail -20") or "(none)")

print("=== WHITELIST_SKIPPED ===")
print(sh("docker logs --since 40h collection-admin 2>&1 | grep '2026-08-30' | grep -c WHITELIST_SKIPPED || true"))
