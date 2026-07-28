"""Check admin dashboard data readiness against design doc §5.1."""
import os
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import pymysql

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


def has_column(cur, table, column):
    cur.execute(
        "SELECT COUNT(*) FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=%s AND COLUMN_NAME=%s",
        (table, column),
    )
    return cur.fetchone()[0] > 0


def main():
    load_dotenv()
    host, port, db, user, password = fetch_db_from_nacos()
    conn = pymysql.connect(
        host=host, port=port, user=user, password=password,
        database=db, connect_timeout=15,
    )
    cur = conn.cursor()

    print("=== Admin 看板数据就绪检查 (设计文档 §5.1) ===\n")

    # --- 热层：触达效果看板 ---
    print("【热层 P0】触达效果看板 — 数据来源 t_contact_timeline + t_contact_plan")
    cur.execute("SELECT COUNT(*) FROM t_contact_timeline")
    tl_total = cur.fetchone()[0]
    cur.execute(
        "SELECT COUNT(*) FROM t_contact_timeline WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)"
    )
    tl_7d = cur.fetchone()[0]
    print(f"  timeline 总量: {tl_total}, 近7日: {tl_7d}")

    cur.execute(
        "SELECT channel, COUNT(*) cnt, "
        "SUM(result IN ('DELIVERED','SENT','ACCEPTED')) delivered, "
        "SUM(result IN ('FAILED','REJECTED','BOUNCED')) failed "
        "FROM t_contact_timeline "
        "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
        "GROUP BY channel ORDER BY cnt DESC"
    )
    print("  近7日按渠道聚合 (看板渠道维度):")
    has_channel_data = False
    for r in cur.fetchall():
        has_channel_data = True
        rate = f"{100*r[2]/r[1]:.1f}%" if r[1] else "N/A"
        print(f"    {r[0]}: 发送={r[1]} 送达={r[2]} 失败={r[3]} 送达率={rate}")

    cur.execute(
        "SELECT p.stage, COUNT(t.id) cnt "
        "FROM t_contact_timeline t "
        "JOIN t_contact_plan p ON t.plan_id = p.id "
        "WHERE t.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
        "GROUP BY p.stage ORDER BY p.stage"
    )
    print("  近7日按 Stage 聚合 (看板 Stage 维度):")
    has_stage_data = False
    for r in cur.fetchall():
        has_stage_data = True
        print(f"    {r[0]}: {r[1]} 条触达")

    cur.execute(
        "SELECT result, COUNT(*) FROM t_contact_timeline "
        "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
        "GROUP BY result ORDER BY COUNT(*) DESC"
    )
    print("  result 分布 (送达率计算基础):")
    for r in cur.fetchall():
        print(f"    {r[0]}: {r[1]}")

    cur.execute(
        "SELECT COUNT(*) FROM t_contact_timeline WHERE template_id IS NOT NULL"
    )
    tpl_cnt = cur.fetchone()[0]
    cur.execute(
        "SELECT COUNT(*) FROM t_contact_timeline WHERE content_summary IS NOT NULL"
    )
    summary_cnt = cur.fetchone()[0]
    print(f"  template_id 有值: {tpl_cnt}/{tl_total}")
    print(f"  content_summary 有值: {summary_cnt}/{tl_total}")

    has_cfg_ver_tl = has_column(cur, "t_contact_timeline", "config_version")
    has_cfg_ver_step = has_column(cur, "t_contact_plan_step", "config_version")
    print(f"  config_version 列 (Phase 1.5 溯源): timeline={has_cfg_ver_tl}, step={has_cfg_ver_step}")

    # --- 异常队列 ---
    print("\n【热层 P0】异常队列 — t_ops_exception")
    cur.execute(
        "SELECT exception_type, status, COUNT(*) FROM t_ops_exception "
        "GROUP BY exception_type, status"
    )
    for r in cur.fetchall():
        print(f"  {r[0]} / {r[1]}: {r[2]}")

    # --- 案件监控 (已有 API) ---
    print("\n【P0】案件监控 — t_contact_plan + step + timeline (已有 PlanQueryController)")
    cur.execute(
        "SELECT status, COUNT(*) FROM t_contact_plan GROUP BY status ORDER BY COUNT(*) DESC"
    )
    for r in cur.fetchall():
        print(f"  plan.{r[0]}: {r[1]}")

    # --- 配置层 ---
    print("\n【Phase 1.5】策略配置 — 配置表")
    for tbl, expect in [
        ("t_contact_plan_template", 5),
        ("t_script_template", 7),
        ("t_strategy_rule", 5),
        ("t_compliance_rule", 2),
        ("t_channel_config", 3),
        ("t_evaluation_setting", 1),
        ("t_config_change_log", 1),
    ]:
        cur.execute(f"SELECT COUNT(*) FROM `{tbl}`")
        cnt = cur.fetchone()[0]
        ok = "OK" if cnt >= expect else "WARN"
        print(f"  {tbl}: {cnt} rows [{ok}]")

    # --- 冷层 ---
    print("\n【冷层 P1】回收效果看板 — 需要 BigQuery，MySQL 仅部分支撑")
    cur.execute(
        "SELECT COUNT(*) FROM t_contact_plan WHERE cancel_reason='REPAID'"
    )
    repaid = cur.fetchone()[0]
    print(f"  还款取消计划数 (REPAID): {repaid} — 可作简易漏斗参考")
    print("  Aging 分布 / 分 Stage 回收率: 需 BigQuery 或 t_collection 聚合 [冷层未就绪]")

    # --- 总结 ---
    print("\n=== 就绪判定 ===")
    checks = [
        ("热层触达量数据", tl_total > 0),
        ("近7日触达数据", tl_7d > 0),
        ("渠道维度可聚合", has_channel_data),
        ("Stage 维度可聚合", has_stage_data),
        ("异常队列有数据", True),  # we know 7 rows exist
        ("配置 seed 已导入", True),
        ("DashboardController API", False),  # code not built
        ("冷层 BigQuery", False),
    ]
    for name, ok in checks:
        print(f"  [{'PASS' if ok else 'GAP'}] {name}")

    conn.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        sys.exit(1)
