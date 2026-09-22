#!/usr/bin/env python3
"""14:30 波次首跑观测：日志、步骤状态、timeline 批次号、超时时刻。

判读要点：
- enrolled 行应有 27 条，wave started 应只有 1 条且 cases=27 —— 这就是「一批多案」是否成立。
- timeline 的 distinct provider_msg_id 应为 1（8/27 上午一案一批时是 35）。
- 步骤 timeout_time 应被 flusher 统一改写成同一时刻。
"""
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


def logs(pattern):
    out = subprocess.run(
        "docker logs --tail 4000 collection-admin 2>&1 | grep -E '" + pattern + "'",
        shell=True,
        capture_output=True,
        text=True,
    )
    return out.stdout


def main():
    vals = envfile()

    print("=== 入批日志（每案一行）===")
    enrolled = logs("enrolled into wave")
    print(enrolled or "(无)")
    print("enrolled 行数:", len(enrolled.strip().splitlines()) if enrolled.strip() else 0)

    print("=== 起批日志 ===")
    print(logs(r"\[FacadeBatch\]") or "(无)")

    print("=== 14:30 步骤状态 ===")
    print(
        mysql(
            vals,
            "SELECT s.status, s.result, COUNT(*) n, MIN(s.timeout_time) min_to, "
            "MAX(s.timeout_time) max_to "
            "FROM t_contact_plan_step s "
            "WHERE s.channel_type='AI_CALL' "
            "AND s.original_trigger_time = CONCAT(CURDATE(),' 14:30:00') "
            "GROUP BY s.status, s.result",
        )
    )

    print("=== 14:30 timeline 批次号 ===")
    print(
        mysql(
            vals,
            "SELECT t.provider_msg_id, COUNT(*) n "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id = t.step_id "
            "WHERE t.channel='AI_CALL' "
            "AND s.original_trigger_time = CONCAT(CURDATE(),' 14:30:00') "
            "GROUP BY t.provider_msg_id",
        )
    )

    print("=== 对照：09:15 一案一批的批次号数量 ===")
    print(
        mysql(
            vals,
            "SELECT COUNT(*) steps, COUNT(DISTINCT t.provider_msg_id) distinct_batch "
            "FROM t_contact_timeline t "
            "JOIN t_contact_plan_step s ON s.id = t.step_id "
            "WHERE t.channel='AI_CALL' "
            "AND s.original_trigger_time = CONCAT(CURDATE(),' 09:15:00')",
        )
    )

    print("=== 回调进展 ===")
    print(
        mysql(
            vals,
            "SELECT a.result, COUNT(*) n, MAX(a.received_at) last_at "
            "FROM t_channel_callback_audit a "
            "JOIN t_contact_plan_step s ON s.id = a.step_id "
            "WHERE s.original_trigger_time = CONCAT(CURDATE(),' 14:30:00') "
            "GROUP BY a.result",
        )
    )


if __name__ == "__main__":
    main()
