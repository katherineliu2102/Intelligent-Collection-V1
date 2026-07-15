"""Refresh test DB: seed cases, admin config, clean stuck plans."""
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import pymysql
import pymysql.constants.CLIENT as CLIENT

ROOT = Path(__file__).resolve().parents[2]


def load_dotenv():
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
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



def run_sql_file(conn, path: Path):
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("--"):
            continue
        lines.append(line)
    sql = "\n".join(lines)
    with conn.cursor() as cur:
        cur.execute(sql)
        while cur.nextset():
            pass
    conn.commit()
    print(f"OK: {path.name}")


def main():
    load_dotenv()
    host, port, db, user, password = fetch_db_from_nacos()
    conn = pymysql.connect(
        host=host, port=port, user=user, password=password,
        database=db, connect_timeout=20, autocommit=False,
        client_flag=CLIENT.MULTI_STATEMENTS,
    )
    try:
        print("=== 1. 刷新 t_collection 测试案件 ===")
        run_sql_file(conn, ROOT / "db" / "seed-test-cases.sql")

        print("=== 2. 导入 Admin 配置 seed ===")
        run_sql_file(conn, ROOT / "db" / "seed-admin-config.sql")

        print("=== 3. 清理卡住的 STEP_EXECUTING 计划 ===")
        with conn.cursor() as cur:
            cur.execute(
                "SELECT id, case_id, stage FROM t_contact_plan "
                "WHERE status = 'STEP_EXECUTING'"
            )
            stuck = cur.fetchall()
            print(f"  发现 {len(stuck)} 个卡住计划")
            for plan_id, case_id, stage in stuck:
                cur.execute(
                    "UPDATE t_contact_plan SET status='PLAN_CANCELLED', "
                    "cancel_reason='MANUAL_CLEANUP', completed_at=NOW(), "
                    "updated_at=NOW() WHERE id=%s",
                    (plan_id,),
                )
                cur.execute(
                    "UPDATE t_contact_plan_step SET status='SKIPPED', "
                    "result='MANUAL_CLEANUP', completed_at=NOW(), "
                    "updated_at=NOW() WHERE plan_id=%s AND status IN "
                    "('PENDING','EXECUTING','WAITING')",
                    (plan_id,),
                )
                print(f"  已清理 plan_id={plan_id} case={case_id} stage={stage}")
        conn.commit()

        print("=== 4. 验证结果 ===")
        with conn.cursor() as cur:
            cur.execute(
                "SELECT loan_id, overdue_days, id FROM t_collection "
                "WHERE id LIKE 'IC_TEST_%' ORDER BY loan_id"
            )
            print("  t_collection 测试案件:")
            for r in cur.fetchall():
                print(f"    case={r[0]} dpd={r[1]} id={r[2]}")

            for tbl in ("t_contact_plan_template", "t_script_template",
                        "t_strategy_rule", "t_compliance_rule", "t_channel_config"):
                cur.execute(f"SELECT COUNT(*) FROM `{tbl}`")
                print(f"  {tbl}: {cur.fetchone()[0]} rows")

            cur.execute(
                "SELECT COUNT(*) FROM t_contact_plan WHERE status='STEP_EXECUTING'"
            )
            print(f"  STEP_EXECUTING 剩余: {cur.fetchone()[0]}")
    finally:
        conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
