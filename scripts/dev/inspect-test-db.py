"""Inspect ai_collection_db contents for Phase 1 test readiness."""
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[2]


def load_dotenv():
    env_path = ROOT / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip())


def fetch_db_from_nacos():
    params = urllib.parse.urlencode({
        "dataId": "intelligent-collection-local.yml",
        "group": os.environ["NACOS_GROUP"],
        "tenant": os.environ["NACOS_NAMESPACE"],
        "username": os.environ["NACOS_USERNAME"],
        "password": os.environ["NACOS_PASSWORD"],
    })
    url = f"http://{os.environ['NACOS_SERVER_ADDR']}/nacos/v1/cs/configs?{params}"
    yaml_text = urllib.request.urlopen(url, timeout=15).read().decode("utf-8")
    host = port = db = user = password = None
    for line in yaml_text.splitlines():
        s = line.strip()
        if s.startswith("url:") and "jdbc:mysql://" in s:
            part = s.split("url:", 1)[1].strip()
            body = part.split("jdbc:mysql://", 1)[1]
            host_port, db_part = body.split("/", 1)
            host, port = host_port.split(":", 1)
            db = db_part.split("?", 1)[0]
        elif s.startswith("username:"):
            user = s.split(":", 1)[1].strip()
        elif s.startswith("password:"):
            password = s.split(":", 1)[1].strip()
    return host, int(port), db, user, password


def main():
    load_dotenv()
    host, port, db, user, password = fetch_db_from_nacos()
    conn = pymysql.connect(
        host=host, port=port, user=user, password=password,
        database=db, connect_timeout=15,
    )
    cur = conn.cursor()

    print("=== 连接信息 ===")
    print(f"host={host} port={port} database={db} user={user}")
    cur.execute("SELECT DATABASE()")
    print(f"connected_db={cur.fetchone()[0]}")

    print("\n=== 所有表 ===")
    cur.execute("SHOW TABLES")
    tables = [r[0] for r in cur.fetchall()]
    print(f"total_tables={len(tables)}")
    for t in sorted(tables):
        print(f"  {t}")

    required_l4b = [
        "t_contact_plan", "t_contact_plan_step", "t_contact_timeline",
        "t_user_device_token", "t_decision_log",
    ]
    required_admin = [
        "t_contact_plan_template", "t_script_template", "t_strategy_rule",
        "t_compliance_rule", "t_channel_config", "t_config_change_log",
        "t_admin_case_freeze", "t_ops_exception",
    ]
    print("\n=== L4b 必需表检查 ===")
    for t in required_l4b:
        print(f"  {t}: {'OK' if t in tables else 'MISSING'}")

    print("\n=== Admin 后台表检查 ===")
    for t in required_admin:
        print(f"  {t}: {'OK' if t in tables else 'MISSING'}")

    print("\n=== 核心表行数 ===")
    count_tables = [
        "t_contact_plan", "t_contact_plan_step", "t_contact_timeline",
        "t_user_device_token", "t_collection", "t_decision_log",
        "t_email_e2e_registry", "t_contact_plan_template", "t_script_template",
        "t_strategy_rule", "t_compliance_rule", "t_channel_config",
        "t_config_change_log", "t_admin_case_freeze", "t_ops_exception",
    ]
    for t in count_tables:
        if t in tables:
            cur.execute(f"SELECT COUNT(*) FROM `{t}`")
            print(f"  {t}: {cur.fetchone()[0]}")

    if "t_collection" in tables:
        print("\n=== t_collection 测试案件 (9900000x / IC_TEST_%) ===")
        cur.execute(
            "SELECT loan_id, user_id, overdue_days, phone, email, "
            "total_not_paid, colleciton_status, id "
            "FROM t_collection "
            "WHERE id LIKE 'IC_TEST_%' OR loan_id LIKE '9900000%' "
            "ORDER BY loan_id"
        )
        rows = cur.fetchall()
        print(f"rows={len(rows)}")
        for r in rows:
            print(
                f"  loan_id={r[0]} user_id={r[1]} dpd={r[2]} "
                f"phone={r[3]} email={r[4]} outstanding={r[5]} "
                f"status={r[6]} id={r[7]}"
            )

    if "t_contact_plan" in tables:
        print("\n=== t_contact_plan 概览 ===")
        cur.execute(
            "SELECT status, COUNT(*) FROM t_contact_plan "
            "GROUP BY status ORDER BY COUNT(*) DESC"
        )
        for r in cur.fetchall():
            print(f"  status={r[0]} count={r[1]}")
        cur.execute(
            "SELECT case_id, stage, status, cancel_reason, created_at "
            "FROM t_contact_plan ORDER BY id DESC LIMIT 10"
        )
        print("  最近10条:")
        for r in cur.fetchall():
            print(
                f"    case_id={r[0]} stage={r[1]} status={r[2]} "
                f"cancel={r[3]} created={r[4]}"
            )

    if "t_user_device_token" in tables:
        print("\n=== t_user_device_token ===")
        cur.execute(
            "SELECT user_id, LEFT(jpush_token, 20), updated_at "
            "FROM t_user_device_token ORDER BY updated_at DESC LIMIT 10"
        )
        for r in cur.fetchall():
            print(f"  user_id={r[0]} token_prefix={r[1]}... updated={r[2]}")

    if "t_email_e2e_registry" in tables:
        print("\n=== t_email_e2e_registry ===")
        cur.execute(
            "SELECT case_id, script_slot, phase1_active "
            "FROM t_email_e2e_registry ORDER BY case_id"
        )
        for r in cur.fetchall():
            print(f"  case_id={r[0]} slot={r[1]} active={r[2]}")

    print("\n=== 9900000x 计划执行状态 ===")
    cur.execute(
        "SELECT p.case_id, p.stage, p.status, p.cancel_reason, "
        "COUNT(s.id) steps, SUM(s.status='COMPLETED') completed "
        "FROM t_contact_plan p "
        "LEFT JOIN t_contact_plan_step s ON s.plan_id=p.id "
        "WHERE p.case_id BETWEEN 99000000 AND 99000005 "
        "GROUP BY p.id, p.case_id, p.stage, p.status, p.cancel_reason "
        "ORDER BY p.case_id, p.id"
    )
    for r in cur.fetchall():
        print(
            f"  case={r[0]} stage={r[1]} status={r[2]} cancel={r[3]} "
            f"steps={r[4]} completed={r[5]}"
        )

    print("\n=== 9900000x timeline 触达记录 ===")
    cur.execute(
        "SELECT case_id, channel, COUNT(*) cnt "
        "FROM t_contact_timeline "
        "WHERE case_id BETWEEN 99000000 AND 99000005 "
        "GROUP BY case_id, channel ORDER BY case_id, channel"
    )
    for r in cur.fetchall():
        print(f"  case={r[0]} channel={r[1]} count={r[2]}")

    print("\n=== STEP_EXECUTING 卡住计划 ===")
    cur.execute(
        "SELECT p.case_id, p.stage, s.step_order, s.channel_type, "
        "s.status, s.trigger_time "
        "FROM t_contact_plan p JOIN t_contact_plan_step s ON s.plan_id=p.id "
        "WHERE p.status='STEP_EXECUTING' ORDER BY p.case_id, s.step_order"
    )
    for r in cur.fetchall():
        print(
            f"  case={r[0]} stage={r[1]} step={r[2]} channel={r[3]} "
            f"status={r[4]} trigger={r[5]}"
        )

    print("\n=== admin 配置 seed 检查 ===")
    cur.execute(
        "SELECT template_code, stage, status FROM t_contact_plan_template "
        "WHERE tenant_id='mocasa-ph'"
    )
    for r in cur.fetchall():
        print(f"  plan_template: {r[0]} stage={r[1]} status={r[2]}")
    cur.execute(
        "SELECT script_slot, channel, status FROM t_script_template "
        "WHERE tenant_id='mocasa-ph'"
    )
    for r in cur.fetchall():
        print(f"  script: {r[0]} channel={r[1]} status={r[2]}")
    cur.execute("SELECT rule_name, status FROM t_strategy_rule")
    for r in cur.fetchall():
        print(f"  strategy: {r[0]} status={r[1]}")
    cur.execute("SELECT rule_code, status FROM t_compliance_rule")
    for r in cur.fetchall():
        print(f"  compliance: {r[0]} status={r[1]}")
    cur.execute("SELECT channel_type, status FROM t_channel_config")
    for r in cur.fetchall():
        print(f"  channel_config: {r[0]} status={r[1]}")

    conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
