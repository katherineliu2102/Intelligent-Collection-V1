#!/usr/bin/env python3
"""从 pilot 计划模板里摘掉 AI_CALL 槽位，生成可评审、可回滚的 SQL。

背景：AI_CALL 的回调依赖尚未就绪的公网入口，硬跑会让每个 AI_CALL 步骤都走满超时收敛成 FAILED，
在 pilot 数据里留下系统性噪声。故先把 AI_CALL 槽位从 dayBlocks 摘掉，回调打通后再用 restore 脚本还原。

不在 MySQL 内用 JSON_TABLE + JSON_ARRAYAGG 改写：后者不保证数组顺序，
虽然 PhtSlotScheduleCalculator 最终按绝对 trigger_time 排序、语义上不依赖顺序，
但生成物无法与原文逐行 diff，评审时看不出改了什么。

用法：
    mysql -N -B ... -e "SELECT template_code, plan_json FROM ..." > current.tsv
    python3 strip-ai-call-slots.py current.tsv out.sql
"""

import json
import sys

TARGET_CHANNEL = "AI_CALL"


def strip(plan_json):
    """返回 (新 plan_json, 摘掉的槽位数, 变空的日块数)。"""
    removed = 0
    emptied = 0
    blocks = []
    for block in plan_json.get("dayBlocks", []):
        kept = [s for s in block.get("slots", []) if s.get("channel") != TARGET_CHANNEL]
        removed += len(block.get("slots", [])) - len(kept)
        if not kept:
            # 整块只剩空槽位就整块丢弃，否则会生成一个不产生任何步骤的空日块。
            emptied += 1
            continue
        new_block = dict(block)
        new_block["slots"] = kept
        blocks.append(new_block)
    result = dict(plan_json)
    result["dayBlocks"] = blocks
    return result, removed, emptied


def sql_literal(text):
    return "'" + text.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    src, dst = sys.argv[1], sys.argv[2]
    statements = []
    summary = []
    with open(src, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line:
                continue
            code, raw = line.split("\t", 1)
            original = json.loads(raw)
            stripped, removed, emptied = strip(original)
            summary.append((code, removed, emptied, len(stripped["dayBlocks"])))
            if removed == 0:
                continue
            payload = json.dumps(stripped, ensure_ascii=False, separators=(",", ":"))
            statements.append(
                "UPDATE t_contact_plan_template\n"
                "   SET plan_json = CAST({} AS JSON),\n"
                "       config_version = @CFG_VER,\n"
                "       version = version + 1,\n"
                "       updated_by = 'pilot-ai-call-strip'\n"
                " WHERE template_code = {} AND tenant_id = @TENANT;".format(
                    sql_literal(payload), sql_literal(code)
                )
            )

    with open(dst, "w", encoding="utf-8") as handle:
        handle.write("-- 由 scripts/pilot/strip-ai-call-slots.py 生成，勿手改。\n")
        handle.write("-- 回滚：scripts/pilot/restore-ai-call-slots.sql\n")
        handle.write("SET @TENANT = 'mocasa-ph';\n")
        handle.write(
            "SELECT COALESCE(MAX(config_version), 0) + 1 INTO @CFG_VER "
            "FROM t_contact_plan_template WHERE tenant_id = @TENANT;\n\n"
        )
        handle.write("\n\n".join(statements))
        handle.write("\n")

    for code, removed, emptied, blocks in summary:
        print("{:<18} 摘除 {:>3} 个 AI_CALL 槽位，丢弃 {} 个空日块，剩余 {} 个日块".format(
            code, removed, emptied, blocks))


if __name__ == "__main__":
    main()
