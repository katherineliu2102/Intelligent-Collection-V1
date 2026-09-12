#!/usr/bin/env python3
"""8/31 03:00 S0 25 案：inbox/投影 + poison/nack 日志。无 PII。"""
from pathlib import Path
import os
import subprocess

TODAY = "2026-08-31"
S0_25 = [
    507209, 507262, 531811, 531812, 531813, 531817,
    507296, 507313, 531822, 531827, 531833,
    507323, 507333, 507347, 531847, 531848,
    507363, 507373, 507379, 507398, 531851, 531852, 531858, 531860, 531867,
]
IDS = ",".join(str(i) for i in S0_25)


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

print("=== inbox 当日 caseEvent 总数 ===")
print(
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_ai_collection_inbox "
        "WHERE message_type='caseEvent' "
        "AND created_at>='%s 00:00:00' AND created_at<'%s 23:59:59'" % (TODAY, TODAY),
    )
)

print("=== S0_25 inbox 任意日 ===")
print(
    mysql(
        vals,
        "SELECT case_id, message_type, event_type, publish_status, projection_applied, "
        "DATE_FORMAT(created_at,'%%Y-%%m-%%d %%H:%%i:%%s') "
        "FROM t_ai_collection_inbox WHERE case_id IN (%s) "
        "ORDER BY case_id, created_at" % IDS,
    )
    or "(none)"
)

print("=== S0_25 投影 ===")
print(
    mysql(
        vals,
        "SELECT case_id, dpd, IFNULL(stage,''), collection_status, "
        "overdue_amount, IFNULL(upcoming_amount,''), IFNULL(penalty_amount,''), "
        "IFNULL(product,''), IFNULL(case_version,'') "
        "FROM t_ai_collection WHERE case_id IN (%s) ORDER BY case_id" % IDS,
    )
    or "(none)"
)

print("=== t_event_dlq 当日 ===")
print(
    mysql(
        vals,
        "SELECT COUNT(*) FROM t_event_dlq "
        "WHERE created_at>='%s 00:00:00' AND created_at<'%s 23:59:59'" % (TODAY, TODAY),
    )
)

print("=== 03:00 poison 条数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep -c 'poison message' || true"
    )
)
print("=== 全日 poison 条数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -c 'poison message' || true"
    )
)
print("=== 03:00 nack 条数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep -c 'nack' || true"
    )
)
print("=== 全日 nack 条数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -c 'nack' || true"
    )
)

print("=== 03:00 poison 原文（最多 40）===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep 'poison message' | head -40"
    )
    or "(none)"
)
print("=== 全日 poison 原文（最多 40）===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep 'poison message' | head -40"
    )
    or "(none)"
)
print("=== 03:00 nack 原文（最多 40）===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep 'nack' | head -40"
    )
    or "(none)"
)
print("=== 全日 nack 原文（最多 40）===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep 'nack' | grep -v deadlock | head -40"
    )
    or "(none)"
)

print("=== 03:00 missing financial / 非法 stage / JSON ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep -E 'missing required financial|非法 stage|JSON 解析|缺 eventId|缺 caseId|不支持的 dataType' | head -40"
    )
    or "(none)"
)

print("=== S0_25 出现在 03:00 日志的次数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep -E '%s' | wc -l" % "|".join(str(i) for i in S0_25)
    )
)
print("=== S0_25 全日日志摘录（最多 50，无长 payload）===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31' | "
        "grep -E '%s' | grep -v payload | head -50" % "|".join(str(i) for i in S0_25)
    )
    or "(none)"
)

print("=== 03:00 CASE_INGESTED 条数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep -c 'CASE_INGESTED' || true"
    )
)
print("=== 03:00 投影未产生领域事件 条数 ===")
print(
    sh(
        "docker logs --since 36h collection-admin 2>&1 | grep '2026-08-31 03:0' | "
        "grep -c '投影未产生领域事件' || true"
    )
)

print("=== GCP 订阅 describe（若本机有 gcloud）===")
print(
    sh(
        "gcloud pubsub subscriptions describe intelligent-collection-cases-v1-sub "
        "--project=fintech-all --format='yaml(name,deadLetterPolicy,messageRetentionDuration,"
        "ackDeadlineSeconds,filter)' 2>&1 | head -40"
    )
    or "(gcloud missing)"
)
print("=== GCP DLQ 订阅 list ===")
print(
    sh(
        "gcloud pubsub subscriptions list --project=fintech-all "
        "--filter='name:intelligent-collection-cases' --format='table(name,deadLetterPolicy.deadLetterTopic)' 2>&1"
    )
    or "(gcloud missing)"
)
